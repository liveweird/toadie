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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

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
        )
        for (literal in blocked) {
            assertTrue(InetAddress.getByName(literal).isBlockedAddress(), "expected blocked: $literal")
        }
        val public = listOf("1.1.1.1", "140.82.121.3", "2606:4700::1111")
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

    private fun fixtureFetcher() = CatalogUrlFetcher(urlValidator = { URI(it) })

    private fun withFixtureServer(
        configure: (HttpServer) -> Unit,
        block: (base: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        configure(server)
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpServer.respond(path: String, status: Int, body: ByteArray, location: String? = null) {
        createContext(path) { exchange ->
            location?.let { exchange.responseHeaders.add("Location", it) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    @Test
    fun `a 200 returns the body text`() = withFixtureServer(
        configure = { it.respond("/ok", 200, "kind: Component\nmetadata:\n  name: fetched\n".toByteArray()) },
    ) { base ->
        val content = runBlocking { fixtureFetcher().fetch("$base/ok") }
        assertTrue(content.contains("name: fetched"))
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
                    val response = client.postJson("/api/v1/catalog-files/fetch", FetchUrlRequest(url = url))
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
        val response = jsonClient().postJson("/api/v1/catalog-files/fetch", FetchUrlRequest(url = "https://example.com/catalog-info.yaml"))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `the fetch route returns the fetched text and maps upstream failures to 502`() =
        withFixtureServer(
            configure = { server ->
                server.respond("/ok", 200, "kind: Component\nmetadata:\n  name: fetched\n".toByteArray())
                server.respond("/missing", 404, "not here".toByteArray())
            },
        ) { base ->
            testApplication {
                configureApp()
                // The test seam: a lenient-validator fetcher so the ROUTE can reach the
                // 127.0.0.1 fixture; production wiring never sets this attribute.
                application { attributes.put(CatalogUrlFetcherKey, fixtureFetcher()) }
                startApplication()
                val client = seededClient("fetchroute")

                val ok = client.postJson("/api/v1/catalog-files/fetch", FetchUrlRequest(url = "$base/ok"))
                assertEquals(HttpStatusCode.OK, ok.status)
                assertTrue(ok.body<FetchUrlResponse>().content.contains("name: fetched"))

                val bad = client.postJson("/api/v1/catalog-files/fetch", FetchUrlRequest(url = "$base/missing"))
                assertEquals(HttpStatusCode.BadGateway, bad.status)
                assertTrue(bad.body<ProblemDetail>().detail!!.contains("HTTP 404"))
            }
        }
}
