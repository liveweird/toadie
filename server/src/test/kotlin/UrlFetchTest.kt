package ch.nokillswit

import ch.nokillswit.authz.BadGatewayException
import ch.nokillswit.catalog.BlockedUrlException
import ch.nokillswit.catalog.CatalogUrlFetcher
import ch.nokillswit.catalog.CatalogUrlFetcherKey
import ch.nokillswit.catalog.FetchUrlResponse
import ch.nokillswit.catalog.FetchUrlRequest
import ch.nokillswit.catalog.FETCH_URL_INVALID_DETAIL
import ch.nokillswit.catalog.MAX_FETCH_BYTES
import ch.nokillswit.catalog.isBlockedAddress
import ch.nokillswit.catalog.parseFetchUrl
import ch.nokillswit.catalog.requirePublicHost
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The URL fetch's SSRF posture and response handling. The guards are tested for real; the
 * response-handling logic runs against a plain-HTTP 127.0.0.1 fixture server through the
 * test-only lenient validator (production wiring — the default constructor — keeps the full
 * guard chain, pinned by the route tests below).
 */
class UrlFetchTest {

    // ---- static guard rules -------------------------------------------------------------

    @Test
    fun `parseFetchUrl rejects everything but a clean absolute https URL`() {
        assertFailsWith<BlockedUrlException> { parseFetchUrl("") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("   ") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("https://x.example/" + "a".repeat(2100)) }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("not a url ::") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("http://example.com/catalog-info.yaml") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("ftp://example.com/catalog-info.yaml") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("example.com/catalog-info.yaml") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("https://user:pass@example.com/x.yaml") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("https:///catalog-info.yaml") }

        val uri = parseFetchUrl("  https://example.com:8443/catalog-info.yaml  ")
        assertEquals("example.com", uri.host)
        assertEquals(8443, uri.port)
    }

    @Test
    fun `isBlockedAddress covers every private and special range, and only those`() {
        val blocked = listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1",
            "0.0.0.0", "224.0.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1",
            // The JDK-predicate gaps: CGNAT, IETF protocol assignments, benchmarking, and
            // NAT64 embedding a private IPv4 (a NAT64 gateway would connect to 10.0.0.1).
            "100.64.0.1", "100.127.255.254", "192.0.0.170", "198.18.0.1", "198.19.255.1",
            "64:ff9b::10.0.0.1", "64:ff9b::7f00:1",
        )
        for (literal in blocked) {
            assertTrue(InetAddress.getByName(literal).isBlockedAddress(), "expected blocked: $literal")
        }
        // 64:ff9b:: embedding a PUBLIC IPv4 stays allowed — IPv6-only networks reach the
        // public internet through NAT64, and the embedded target is judged like a native one.
        val public = listOf("1.1.1.1", "140.82.121.3", "2606:4700::1111", "100.128.0.1", "198.20.0.1", "64:ff9b::101:101")
        for (literal in public) {
            assertTrue(!InetAddress.getByName(literal).isBlockedAddress(), "expected public: $literal")
        }
    }

    @Test
    fun `requirePublicHost rejects private literals and unresolvable hosts`() {
        assertFailsWith<BlockedUrlException> { requirePublicHost("127.0.0.1") }
        assertFailsWith<BlockedUrlException> { requirePublicHost("localhost") }
        // .invalid is reserved (RFC 2606) and guaranteed not to resolve.
        assertFailsWith<BlockedUrlException> { requirePublicHost("no-such-host.invalid") }
        val blocked = assertFailsWith<BlockedUrlException> { requirePublicHost("192.168.0.10") }
        assertEquals("192.168.0.10", blocked.host)
    }

    // ---- response handling against the 127.0.0.1 fixture server ------------------------

    private fun fixtureFetcher(timeout: Duration = Duration.ofSeconds(10)) =
        CatalogUrlFetcher(urlValidator = { URI(it) }, timeout = timeout)

    private fun withFixtureServer(
        configure: (HttpServer) -> Unit,
        block: (base: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        configure(server)
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpServer.respond(path: String, status: Int, body: ByteArray, location: String? = null) {
        createContext(path) { exchange ->
            location?.let { exchange.responseHeaders.add("Location", it) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private suspend fun CountDownLatch.awaitOrFail() {
        assertTrue(withContext(Dispatchers.IO) { await(2, TimeUnit.SECONDS) })
    }

    private fun writeUntilDisconnected(
        output: java.io.OutputStream,
        disconnected: CompletableDeferred<Unit>,
    ) {
        try {
            repeat(256) {
                output.write(ByteArray(8_192))
                output.flush()
            }
        } catch (_: IOException) {
            disconnected.complete(Unit)
        }
    }

    @Test
    fun `a 200 returns the body text`() = withFixtureServer(
        configure = { it.respond("/ok", 200, "kind: Component\nmetadata:\n  name: fetched\n".toByteArray()) },
    ) { base ->
        val fetched = runBlocking { fixtureFetcher().fetch("$base/ok") }
        assertTrue(fetched.content.contains("name: fetched"))
    }

    @Test
    fun `validation is inside the fetch deadline and cancellation never starts the request`() {
        val networkCalls = AtomicInteger()
        withFixtureServer(
            configure = { server ->
                server.createContext("/should-not-start") { exchange ->
                    networkCalls.incrementAndGet()
                    exchange.sendResponseHeaders(200, 10)
                    exchange.responseBody.use { it.write("unexpected".toByteArray()) }
                }
            },
        ) { base ->
            val validationStarted = CountDownLatch(2)
            val releaseValidation = CountDownLatch(1)
            val validationFinished = CountDownLatch(2)
            val fetcher = CatalogUrlFetcher(
                urlValidator = { raw ->
                    validationStarted.countDown()
                    // Model native DNS that ignores Thread.interrupt().
                    while (releaseValidation.count > 0) {
                        try {
                            releaseValidation.await(20, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            // Native resolvers may remain blocked despite interruption.
                        }
                    }
                    URI(raw).also { validationFinished.countDown() }
                },
                timeout = Duration.ofMillis(300),
            )

            try {
                val timeout = assertFailsWith<BadGatewayException> {
                    runBlocking { fetcher.fetch("$base/should-not-start") }
                }
                assertEquals("The URL could not be fetched", timeout.message)

                runBlocking {
                    val cancelled = async { fetcher.fetch("$base/should-not-start") }
                    validationStarted.awaitOrFail()
                    cancelled.cancelAndJoin()
                }
            } finally {
                releaseValidation.countDown()
            }
            assertTrue(validationFinished.await(2, TimeUnit.SECONDS))
            assertEquals(0, networkCalls.get())
        }
    }

    @Test
    fun `a parent coroutine timeout remains cancellation`() {
        val validationStarted = CountDownLatch(1)
        val releaseValidation = CountDownLatch(1)
        val fetcher = CatalogUrlFetcher(
            urlValidator = { raw ->
                validationStarted.countDown()
                while (releaseValidation.count > 0) {
                    try {
                        releaseValidation.await(20, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Deliberately model an uninterruptible native resolver.
                    }
                }
                URI(raw)
            },
            timeout = Duration.ofSeconds(5),
        )

        try {
            assertFailsWith<TimeoutCancellationException> {
                runBlocking {
                    withTimeout(100) { fetcher.fetch("http://127.0.0.1/never") }
                }
            }
            assertTrue(validationStarted.await(2, TimeUnit.SECONDS))
        } finally {
            releaseValidation.countDown()
        }
    }

    @Test
    fun `validation saturation is a 502 and cancelling queued work removes it`() {
        val releaseValidation = CountDownLatch(1)
        val validationStarted = CountDownLatch(1)
        val executor = ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
        )
        val fetcher = CatalogUrlFetcher(
            urlValidator = { raw ->
                validationStarted.countDown()
                while (releaseValidation.count > 0) {
                    try {
                        releaseValidation.await(20, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Model native DNS that remains in the sole worker after cancellation.
                    }
                }
                URI(raw)
            },
            timeout = Duration.ofSeconds(5),
            validationExecutor = executor,
        )

        try {
            runBlocking {
                val running = async { fetcher.fetch("http://127.0.0.1/running") }
                validationStarted.awaitOrFail()
                val queued = async { fetcher.fetch("http://127.0.0.1/queued-secret") }
                withContext(Dispatchers.IO) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (executor.queue.size != 1 && System.nanoTime() < deadline) Thread.onSpinWait()
                    assertEquals(1, executor.queue.size)
                }

                val saturated = assertFailsWith<BadGatewayException> {
                    fetcher.fetch("http://127.0.0.1/rejected")
                }
                assertEquals("The URL could not be fetched", saturated.message)

                queued.cancelAndJoin()
                assertTrue(executor.queue.isEmpty())
                running.cancelAndJoin()
            }
        } finally {
            releaseValidation.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `non-200, redirect, oversize, and unreachable all become 502-grade failures`() =
        withFixtureServer(
            configure = { server ->
                server.respond("/missing", 404, "not here".toByteArray())
                server.respond("/moved", 302, ByteArray(0), location = "https://example.com/final")
                server.respond("/huge", 200, ByteArray(MAX_FETCH_BYTES + 1))
            },
        ) { base ->
            runBlocking {
                assertFailsWith<BadGatewayException> { fixtureFetcher().fetch("$base/missing") }
                val redirect = assertFailsWith<BadGatewayException> { fixtureFetcher().fetch("$base/moved") }
                assertTrue(redirect.message!!.contains("redirects"))
                val oversize = assertFailsWith<BadGatewayException> { fixtureFetcher().fetch("$base/huge") }
                assertTrue(oversize.message!!.contains("1 MB"))
                // A connection-refused IOException (nothing listens on the reserved port 1).
                assertFailsWith<BadGatewayException> { fixtureFetcher().fetch("http://127.0.0.1:1/x") }
            }
        }

    @Test
    fun `the body cap accepts the exact limit and rejects the next byte`() =
        withFixtureServer(
            configure = { server ->
                server.respond("/exact", 200, ByteArray(MAX_FETCH_BYTES) { 'x'.code.toByte() })
                server.respond("/over", 200, ByteArray(MAX_FETCH_BYTES + 1) { 'x'.code.toByte() })
            },
        ) { base ->
            runBlocking {
                assertEquals(MAX_FETCH_BYTES, fixtureFetcher().fetch("$base/exact").content.length)
                val failure = assertFailsWith<BadGatewayException> { fixtureFetcher().fetch("$base/over") }
                assertEquals("The file is larger than the 1 MB fetch limit", failure.message)
            }
        }

    @Test
    fun `pre-header and stalled-body timeouts release the caller and the fetcher remains reusable`() {
        val headersStarted = CompletableDeferred<Unit>()
        val releaseHeaders = CountDownLatch(1)
        val headersDisconnected = CompletableDeferred<Unit>()
        val bodyStarted = CompletableDeferred<Unit>()
        val releaseBody = CountDownLatch(1)
        val bodyDisconnected = CompletableDeferred<Unit>()
        withFixtureServer(
            configure = { server ->
                server.createContext("/headers") { exchange ->
                    headersStarted.complete(Unit)
                    releaseHeaders.await()
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { writeUntilDisconnected(it, headersDisconnected) }
                }
                server.createContext("/body") { exchange ->
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        output.write("partial".toByteArray())
                        output.flush()
                        bodyStarted.complete(Unit)
                        releaseBody.await()
                        writeUntilDisconnected(output, bodyDisconnected)
                    }
                }
                server.respond("/ok-after-failure", 200, "ok".toByteArray())
            },
        ) { base ->
            val fetcher = fixtureFetcher(Duration.ofMillis(500))
            try {
                runBlocking {
                    withTimeout(5_000) {
                        supervisorScope {
                            val headers = async { fetcher.fetch("$base/headers") }
                            headersStarted.await()
                            assertFailsWith<BadGatewayException> { headers.await() }
                            releaseHeaders.countDown()
                            headersDisconnected.await()

                            val body = async { fetcher.fetch("$base/body") }
                            bodyStarted.await()
                            assertFailsWith<BadGatewayException> { body.await() }
                            releaseBody.countDown()
                            bodyDisconnected.await()
                            assertEquals("ok", fetcher.fetch("$base/ok-after-failure").content)
                        }
                    }
                }
            } finally {
                releaseHeaders.countDown()
                releaseBody.countDown()
            }
        }
    }

    @Test
    fun `caller cancellation propagates while waiting for headers and body`() {
        val headersStarted = CompletableDeferred<Unit>()
        val releaseHeaders = CountDownLatch(1)
        val headersDisconnected = CompletableDeferred<Unit>()
        val bodyStarted = CompletableDeferred<Unit>()
        val releaseBody = CountDownLatch(1)
        val bodyDisconnected = CompletableDeferred<Unit>()
        withFixtureServer(
            configure = { server ->
                server.createContext("/held-headers") { exchange ->
                    headersStarted.complete(Unit)
                    releaseHeaders.await()
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { writeUntilDisconnected(it, headersDisconnected) }
                }
                server.createContext("/held-body") { exchange ->
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        output.write('x'.code)
                        output.flush()
                        bodyStarted.complete(Unit)
                        releaseBody.await()
                        writeUntilDisconnected(output, bodyDisconnected)
                    }
                }
            },
        ) { base ->
            try {
                runBlocking {
                    val fetcher = fixtureFetcher()
                    val headers = async { fetcher.fetch("$base/held-headers") }
                    withTimeout(2_000) { headersStarted.await() }
                    headers.cancel()
                    assertFailsWith<CancellationException> { headers.await() }
                    releaseHeaders.countDown()
                    withTimeout(2_000) { headersDisconnected.await() }

                    val body = async { fetcher.fetch("$base/held-body") }
                    withTimeout(2_000) { bodyStarted.await() }
                    body.cancel()
                    assertFailsWith<CancellationException> { body.await() }
                    releaseBody.countDown()
                    withTimeout(2_000) { bodyDisconnected.await() }
                }
            } finally {
                releaseHeaders.countDown()
                releaseBody.countDown()
            }
        }
    }

    @Test
    fun `a truncated response body is a 502-grade failure`() =
        withFixtureServer(
            configure = { server ->
                server.createContext("/truncated") { exchange ->
                    exchange.sendResponseHeaders(200, 12)
                    exchange.responseBody.use { it.write("short".toByteArray()) }
                }
            },
        ) { base ->
            val failure = assertFailsWith<BadGatewayException> {
                runBlocking { fixtureFetcher().fetch("$base/truncated") }
            }
            assertEquals("The URL could not be fetched", failure.message)
        }

    // ---- the route, with the REAL guard chain -------------------------------------------

    @Test
    fun `the fetch route answers a uniform 400 for blocked URLs and audits the attempt`() =
        testApplication {
            usePostgresTestcontainer()
            withAuditCapture { capture ->
                val email = uniqueEmail("urlfetch")
                TestUsers.seed(email = email, password = "pw", role = UserRole.USER)
                val client = authedClient(email, "pw")

                for (url in listOf("http://example.com/x.yaml", "https://127.0.0.1/x.yaml")) {
                    val response = client.postJson("$CATALOG_FILES_PATH/fetch", FetchUrlRequest(url = url))
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals(FETCH_URL_INVALID_DETAIL, response.body<ProblemDetail>().detail)
                }

                val event = capture.awaitEvent {
                    it.message == "catalog_file.fetch_blocked" && it.hasKeyValue("host", "127.0.0.1")
                }
                assertNotNull(event)
            }
        }

    @Test
    fun `the fetch route requires authentication`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().postJson("$CATALOG_FILES_PATH/fetch", FetchUrlRequest(url = "https://example.com/catalog-info.yaml"))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `the fetch route returns the fetched text and maps upstream failures and timeout to 502`() {
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        withFixtureServer(
            configure = { server ->
                server.respond("/ok", 200, "kind: Component\nmetadata:\n  name: fetched\n".toByteArray())
                server.respond("/missing", 404, "not here".toByteArray())
                server.createContext("/slow") { exchange ->
                    slowStarted.countDown()
                    releaseSlow.await()
                    exchange.close()
                }
            },
        ) { base ->
            try {
                testApplication {
                    configureApp()
                    // The test seam: a lenient-validator fetcher so the ROUTE can reach the
                    // 127.0.0.1 fixture; production wiring never sets this attribute.
                    application {
                        attributes.put(CatalogUrlFetcherKey, fixtureFetcher(Duration.ofMillis(500)))
                    }
                    startApplication()
                    val client = seededClient("fetchroute")

                    withAuditCapture { capture ->
                        val ok = client.postJson("$CATALOG_FILES_PATH/fetch", FetchUrlRequest(url = "$base/ok"))
                        assertEquals(HttpStatusCode.OK, ok.status)
                        assertTrue(ok.body<FetchUrlResponse>().content.contains("name: fetched"))
                        // A successful outbound fetch leaves its own trail — scheme/host only,
                        // never the full URL (it may embed query-string tokens).
                        val fetched = capture.events.firstOrNull { it.message == "catalog_file.fetched" }
                        assertNotNull(fetched)
                        assertTrue(fetched.hasKeyValue("host", "127.0.0.1"))
                    }

                    val bad = client.postJson("$CATALOG_FILES_PATH/fetch", FetchUrlRequest(url = "$base/missing"))
                    assertEquals(HttpStatusCode.BadGateway, bad.status)
                    assertTrue(bad.body<ProblemDetail>().detail!!.contains("HTTP 404"))

                    val timedOut = client.postJson("$CATALOG_FILES_PATH/fetch", FetchUrlRequest(url = "$base/slow"))
                    assertTrue(slowStarted.await(2, TimeUnit.SECONDS))
                    assertEquals(HttpStatusCode.BadGateway, timedOut.status)
                    assertEquals("The URL could not be fetched", timedOut.body<ProblemDetail>().detail)
                }
            } finally {
                releaseSlow.countDown()
            }
        }
    }
}
