package ch.nokillswit

import ch.nokillswit.infra.fetch.MAX_FETCH_BYTES
import ch.nokillswit.infra.fetch.OperatorHttp
import ch.nokillswit.infra.fetch.OperatorHttpException
import ch.nokillswit.infra.fetch.OperatorHttpFailure
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * The operator-endpoint client (`infra/fetch/OperatorHttp.kt`): an in-process TLS server on
 * 127.0.0.1 (deliberately a private address — this client has no address rule) whose
 * self-signed certificate is trusted through the `customizeClient` seam. Production wiring (the
 * public constructor) keeps the system trust store.
 */
class OperatorHttpTest {

    private class TlsFixture(configure: (HttpsServer) -> Unit) : AutoCloseable {
        private val certificate = HeldCertificate.Builder().commonName("127.0.0.1").addSubjectAlternativeName("127.0.0.1").build()
        private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        private val handlers = Executors.newCachedThreadPool()
        private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = handlers
            httpsConfigurator = HttpsConfigurator(serverCertificates.sslContext())
            configure(this)
            start()
        }

        val base = "https://127.0.0.1:${server.address.port}"

        fun client(
            timeout: Duration = Duration.ofSeconds(10),
            executor: Executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } },
        ) = OperatorHttp(
            allowHttp = false,
            timeout = timeout,
            executor = executor,
            customizeClient = { it.sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager) },
        )

        override fun close() {
            server.stop(0)
            handlers.shutdownNow()
        }
    }

    private fun HttpsServer.reply(path: String, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        createContext(path) { exchange ->
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun failureOf(block: suspend () -> Unit): OperatorHttpException =
        assertFailsWith<OperatorHttpException> { runBlocking { block() } }

    // ---- request/response handling ---------------------------------------------------------

    @Test
    fun `getJson returns the status and body and sends the JSON accept headers`() {
        val seen = AtomicReference<Map<String, String?>>()
        TlsFixture { server ->
            server.createContext("/.well-known/openid-configuration") { exchange ->
                seen.set(
                    mapOf(
                        "method" to exchange.requestMethod,
                        "accept" to exchange.requestHeaders.getFirst("Accept"),
                        "encoding" to exchange.requestHeaders.getFirst("Accept-Encoding"),
                        "authorization" to exchange.requestHeaders.getFirst("Authorization"),
                    ),
                )
                val body = """{"issuer":"x"}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }.use { fixture ->
            val response = runBlocking { fixture.client().getJson("${fixture.base}/.well-known/openid-configuration") }
            assertEquals(200, response.status)
            assertEquals("""{"issuer":"x"}""", response.body)
            assertEquals("GET", seen.get()["method"])
            assertEquals("application/json", seen.get()["accept"])
            assertEquals("identity", seen.get()["encoding"])
            assertNull(seen.get()["authorization"])
        }
    }

    @Test
    fun `postForm sends an urlencoded body and a form-urlencoded Basic header`() {
        val seen = AtomicReference<Map<String, String?>>()
        TlsFixture { server ->
            server.createContext("/token") { exchange ->
                seen.set(
                    mapOf(
                        "method" to exchange.requestMethod,
                        "contentType" to exchange.requestHeaders.getFirst("Content-Type"),
                        "accept" to exchange.requestHeaders.getFirst("Accept"),
                        "authorization" to exchange.requestHeaders.getFirst("Authorization"),
                        "body" to exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                    ),
                )
                val body = """{"id_token":"t"}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }.use { fixture ->
            val response = runBlocking {
                fixture.client().postForm(
                    "${fixture.base}/token",
                    linkedMapOf("grant_type" to "authorization_code", "code" to "a b&c=d"),
                    basicAuth = "client:id" to "s:3c+r%t x",
                )
            }
            assertEquals(200, response.status)
            assertEquals("POST", seen.get()["method"])
            assertTrue(seen.get()["contentType"]!!.startsWith("application/x-www-form-urlencoded"))
            assertEquals("application/json", seen.get()["accept"])
            assertEquals("grant_type=authorization_code&code=a+b%26c%3Dd", seen.get()["body"])
            val header = seen.get()["authorization"]!!
            assertTrue(header.startsWith("Basic "))
            // RFC 6749 section 2.3.1: each part is form-urlencoded BEFORE the base64 (":" -> %3A, "+" -> %2B,
            // "%" -> %25, space -> "+"), so the colon inside the id and the secret cannot split the pair.
            val decoded = Base64.getDecoder().decode(header.removePrefix("Basic ")).toString(Charsets.UTF_8)
            assertEquals("client%3Aid:s%3A3c%2Br%25t+x", decoded)
        }
    }

    @Test
    fun `postForm without credentials sends no Authorization header`() {
        val authorization = AtomicReference<String?>("unset")
        TlsFixture { server ->
            server.createContext("/token") { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
        }.use { fixture ->
            val response = runBlocking { fixture.client().postForm("${fixture.base}/token", mapOf("a" to "b")) }
            assertEquals(204, response.status)
            assertEquals("", response.body)
            assertNull(authorization.get())
        }
    }

    @Test
    fun `a 400 with a JSON error is a response, not an exception`() {
        TlsFixture { it.reply("/token", 400, """{"error":"invalid_grant"}""") }.use { fixture ->
            val response = runBlocking { fixture.client().postForm("${fixture.base}/token", mapOf("code" to "x")) }
            assertEquals(400, response.status)
            assertEquals("""{"error":"invalid_grant"}""", response.body)
        }
    }

    @Test
    fun `a redirect is returned as its status and never followed`() {
        val finalHits = AtomicInteger()
        TlsFixture { server ->
            server.reply("/moved", 302, "", mapOf("Location" to "/final"))
            server.createContext("/final") { exchange ->
                finalHits.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        }.use { fixture ->
            val response = runBlocking { fixture.client().getJson("${fixture.base}/moved") }
            assertEquals(302, response.status)
            assertEquals(0, finalHits.get())
        }
    }

    @Test
    fun `each call uses an isolated connection`() {
        val remotePorts = mutableSetOf<Int>()
        TlsFixture { server ->
            server.createContext("/connection") { exchange ->
                synchronized(remotePorts) { remotePorts += exchange.remoteAddress.port }
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("ok".toByteArray()) }
            }
        }.use { fixture ->
            val client = fixture.client()
            runBlocking {
                assertEquals("ok", client.getJson("${fixture.base}/connection").body)
                assertEquals("ok", client.getJson("${fixture.base}/connection").body)
            }
        }
        assertEquals(2, remotePorts.size)
    }

    @Test
    fun `the call bypasses the process proxy selector`() {
        val selections = AtomicInteger()
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                selections.incrementAndGet()
                return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1)))
            }

            override fun connectFailed(_uri: URI, _socketAddress: SocketAddress, _failure: IOException) = Unit
        })
        try {
            TlsFixture { it.reply("/direct", 200, "ok") }.use { fixture ->
                assertEquals("ok", runBlocking { fixture.client().getJson("${fixture.base}/direct").body })
            }
            assertEquals(0, selections.get())
        } finally {
            ProxySelector.setDefault(previous)
        }
    }

    // ---- the body cap ----------------------------------------------------------------------

    @Test
    fun `the body cap accepts the exact limit and refuses the next byte, declared or chunked`() {
        TlsFixture { server ->
            server.createContext("/exact") { exchange -> exchange.writeBody(MAX_FETCH_BYTES, chunked = false) }
            server.createContext("/declared") { exchange -> exchange.writeBody(MAX_FETCH_BYTES + 1, chunked = false) }
            server.createContext("/chunked") { exchange -> exchange.writeBody(MAX_FETCH_BYTES + 1, chunked = true) }
        }.use { fixture ->
            val client = fixture.client()
            assertEquals(MAX_FETCH_BYTES, runBlocking { client.getJson("${fixture.base}/exact").body.length })
            assertEquals(
                OperatorHttpFailure.TOO_LARGE,
                failureOf { client.getJson("${fixture.base}/declared") }.kind,
            )
            val chunked = failureOf { client.getJson("${fixture.base}/chunked") }
            assertEquals(OperatorHttpFailure.TOO_LARGE, chunked.kind)
            assertEquals("https", chunked.scheme)
            assertEquals("127.0.0.1", chunked.host)
        }
    }

    private fun HttpExchange.writeBody(size: Int, chunked: Boolean) {
        try {
            // 0 = chunked transfer encoding (unknown length) in the JDK server.
            sendResponseHeaders(200, if (chunked) 0 else size.toLong())
            responseBody.use { it.write(ByteArray(size) { 'x'.code.toByte() }) }
        } catch (_: IOException) {
            // The client aborts the over-limit exchange; a broken pipe here is the expected outcome.
        }
    }

    // ---- the deadline, cancellation, saturation --------------------------------------------

    @Test
    fun `a stalled server hits the deadline as a TIMEOUT`() {
        val release = CountDownLatch(1)
        TlsFixture { server ->
            server.createContext("/slow") { exchange ->
                release.await()
                exchange.close()
            }
        }.use { fixture ->
            try {
                val failure = failureOf { fixture.client(timeout = Duration.ofMillis(300)).getJson("${fixture.base}/slow") }
                assertEquals(OperatorHttpFailure.TIMEOUT, failure.kind)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `an unreachable endpoint is UNAVAILABLE`() {
        // Nothing listens on the reserved port 1.
        val failure = failureOf { OperatorHttp(allowHttp = true).getJson("http://127.0.0.1:1/x") }
        assertEquals(OperatorHttpFailure.UNAVAILABLE, failure.kind)
    }

    @Test
    fun `an untrusted certificate is UNAVAILABLE`() {
        TlsFixture { it.reply("/x", 200, "ok") }.use { fixture ->
            val failure = failureOf { OperatorHttp().getJson("${fixture.base}/x") }
            assertEquals(OperatorHttpFailure.UNAVAILABLE, failure.kind)
        }
    }

    @Test
    fun `caller cancellation propagates unchanged`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        TlsFixture { server ->
            server.createContext("/held") { exchange ->
                started.countDown()
                release.await()
                exchange.close()
            }
        }.use { fixture ->
            try {
                runBlocking {
                    val pending = async { fixture.client().getJson("${fixture.base}/held") }
                    withContext(Dispatchers.IO) { assertTrue(started.await(2, TimeUnit.SECONDS)) }
                    pending.cancelAndJoin()
                    assertFailsWith<CancellationException> { pending.await() }
                }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `a full worker pool and queue is a typed BUSY rejection and cancelling queued work frees it`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
        TlsFixture { server ->
            server.createContext("/held") { exchange ->
                started.countDown()
                release.await()
                exchange.close()
            }
        }.use { fixture ->
            try {
                runBlocking {
                    val client = fixture.client(executor = executor)
                    val running = async { client.getJson("${fixture.base}/held") }
                    withContext(Dispatchers.IO) { assertTrue(started.await(2, TimeUnit.SECONDS)) }
                    val queued = async { client.getJson("${fixture.base}/held") }
                    withContext(Dispatchers.IO) {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (executor.queue.size != 1 && System.nanoTime() < deadline) Thread.onSpinWait()
                        assertEquals(1, executor.queue.size)
                    }
                    val rejected = assertFailsWith<OperatorHttpException> { client.getJson("${fixture.base}/held") }
                    assertEquals(OperatorHttpFailure.BUSY, rejected.kind)
                    queued.cancelAndJoin()
                    assertTrue(executor.queue.isEmpty())
                    running.cancelAndJoin()
                }
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    // ---- the scheme and URL rules ----------------------------------------------------------

    @Test
    fun `plain http is refused unless allowHttp is set, and credentials in the URL are always refused`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/plain") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()
        try {
            val plain = "http://127.0.0.1:${server.address.port}/plain"
            assertEquals(OperatorHttpFailure.INVALID_URL, failureOf { OperatorHttp().getJson(plain) }.kind)
            assertEquals(OperatorHttpFailure.INVALID_URL, failureOf { OperatorHttp(allowHttp = false).getJson(plain) }.kind)
            assertEquals("ok", runBlocking { OperatorHttp(allowHttp = true).getJson(plain).body })

            val userinfo = failureOf { OperatorHttp(allowHttp = true).getJson("http://user:hunter2@127.0.0.1/x") }
            assertEquals(OperatorHttpFailure.INVALID_URL, userinfo.kind)
            assertTrue(!userinfo.message.orEmpty().contains("hunter2"))
            for (bad in listOf("", "   ", "not a url", "ftp://127.0.0.1/x", "127.0.0.1/x", "https:///x", "https://user@127.0.0.1/x")) {
                assertEquals(
                    OperatorHttpFailure.INVALID_URL,
                    failureOf { OperatorHttp(allowHttp = true).getJson(bad) }.kind,
                    "expected refusal: '$bad'",
                )
                assertEquals(
                    OperatorHttpFailure.INVALID_URL,
                    failureOf { OperatorHttp(allowHttp = true).postForm(bad, emptyMap()) }.kind,
                    "expected refusal (post): '$bad'",
                )
            }
        } finally {
            server.stop(0)
        }
    }

    // ---- log and message hygiene -----------------------------------------------------------

    @Test
    fun `failures and logs never carry the path, query, form fields or credentials`() {
        val secrets = listOf("hunter2-secret", "path-segment-xyz", "token=abc123", "code-value-777")
        val capture = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME)
        try {
            TlsFixture { it.reply("/token/path-segment-xyz", 400, """{"error":"invalid_grant"}""") }.use { fixture ->
                val url = "${fixture.base}/token/path-segment-xyz?token=abc123"
                val response = runBlocking {
                    fixture.client().postForm(url, mapOf("code" to "code-value-777"), basicAuth = "id" to "hunter2-secret")
                }
                assertEquals(400, response.status)
                assertEquals("OperatorResponse(status=400)", response.toString())

                val messages = mutableListOf<String>()
                // An unreachable port and an untrusted certificate, each carrying the secrets.
                val fields = mapOf("code" to "code-value-777")
                val credentials = "id" to "hunter2-secret"
                val refusedUrl = "http://127.0.0.1:1/token/path-segment-xyz?token=abc123"
                val failures = listOf(
                    failureOf { OperatorHttp(allowHttp = true).postForm(refusedUrl, fields, credentials) },
                    failureOf { OperatorHttp().postForm(url, fields, credentials) },
                )
                for (failure in failures) {
                    messages += failure.message.orEmpty()
                    messages += failure.toString()
                    assertNull(failure.cause)
                    assertTrue(failure.scheme == "http" || failure.scheme == "https")
                }
                for (event in capture.events) {
                    messages += event.formattedMessage
                    generateSequence(event.throwableProxy) { it.cause }.forEach { messages += it.message.orEmpty() }
                }
                for (secret in secrets) {
                    assertTrue(messages.none { it.contains(secret) }, "leaked: $secret")
                }
            }
        } finally {
            capture.detach()
        }
    }
}
