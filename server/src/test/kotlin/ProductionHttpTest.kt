package ch.nokillswit

import io.ktor.server.netty.EngineMain
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Production-mode HTTP posture (plugins/Http.kt + plugins/SecurityHeaders.kt) on the REAL Netty
 * engine, booted the way production boots (EngineMain + application.yaml, `-P:` overrides):
 * plain HTTP is redirected to HTTPS, and the redirect itself carries the security headers (they
 * are appended in the Setup phase, BEFORE the HttpsRedirect plugin commits its 301 — appending
 * afterwards throws on Netty, never on Ktor's test engine, which is why `testApplication` cannot
 * pin this). Behind a proxy (`http.behindProxy`), `X-Forwarded-Proto: https` marks the request as
 * already secure — the contract the k8s probes rely on (k8s/app-deployment.yaml) — and only the
 * canonical `X-Forwarded-Host` may name the redirect target.
 */
class ProductionHttpTest {

    @Test
    fun `production mode redirects plain HTTP with the security headers and honors X-Forwarded-Proto`() {
        val server = EngineMain.createServer(
            arrayOf(
                "-port=0",
                "-P:ktor.development=false",
                "-P:postgres.jdbcUrl=${PostgresTestSupport.jdbcUrl}",
                "-P:postgres.r2dbcUrl=${PostgresTestSupport.r2dbcUrl}",
                "-P:postgres.user=${PostgresTestSupport.user}",
                "-P:postgres.password=${PostgresTestSupport.password}",
                "-P:security.csrf.enabled=false",
                "-P:bootstrap.adminInitialPassword=rotated-${UUID.randomUUID()}",
                "-P:jwt.secret=strong-${UUID.randomUUID()}",
                // The dev-default `log` mail transport is refused in production (see infra/mail).
                "-P:mail.transport=disabled",
                "-P:http.behindProxy=true",
            ),
        )
        val log = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME)
        try {
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            fun get(path: String = "/", vararg headers: String): HttpResponse<String> {
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET()
                if (headers.isNotEmpty()) request.headers(*headers)
                return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
            }

            val redirect = get()
            assertEquals(301, redirect.statusCode())
            val location = assertNotNull(redirect.headers().firstValue("Location").orElse(null))
            assertTrue(location.startsWith("https://"), "redirect target should be https, was $location")
            assertEquals("nosniff", redirect.headers().firstValue("X-Content-Type-Options").orElse(null))
            assertEquals("DENY", redirect.headers().firstValue("X-Frame-Options").orElse(null))
            assertNotNull(
                redirect.headers().firstValue("Content-Security-Policy").orElse(null),
                "the redirect must carry the CSP",
            )

            // A TLS-terminating proxy (or the kubelet probe) marks the request secure: no
            // redirect. Tests set no WEB_STATIC_DIR, so / has no route — a 404 proves the
            // redirect was skipped, and it is hardened like every other response.
            val forwarded = get("/", "X-Forwarded-Proto", "https")
            assertEquals(404, forwarded.statusCode())
            assertEquals("nosniff", forwarded.headers().firstValue("X-Content-Type-Options").orElse(null))
            assertNotNull(
                forwarded.headers().firstValue("Strict-Transport-Security").orElse(null),
                "HSTS rides the secure request",
            )

            // The probes themselves: the health endpoints answer through the same contract.
            assertEquals(200, get("/healthz", "X-Forwarded-Proto", "https").statusCode())
            assertEquals(200, get("/readyz", "X-Forwarded-Proto", "https").statusCode())
            assertEquals(301, get("/healthz").statusCode(), "a probe without the header is redirected")

            // THE hostname question: the redirect target is the name the proxy reported, not the
            // pod's address — and only the canonical X-Forwarded-Host counts (Ktor's default list
            // also honoured X-Forwarded-Server, which nothing in front ever overwrites).
            val named = get("/", "X-Forwarded-Host", "toadie.example.com")
            assertEquals(301, named.statusCode())
            assertEquals("https://toadie.example.com/", named.headers().firstValue("Location").orElse(null))
            val spoofed = get("/", "X-Forwarded-Server", "evil.example")
            assertEquals(301, spoofed.statusCode())
            val spoofedLocation = assertNotNull(spoofed.headers().firstValue("Location").orElse(null))
            assertTrue("evil.example" !in spoofedLocation, "X-Forwarded-Server must not set the host: $spoofedLocation")

            val unhandled = log.events.filter {
                it.level == ch.qos.logback.classic.Level.ERROR && it.message.startsWith("Unhandled exception")
            }
            assertEquals(emptyList(), unhandled.map { it.message }, "no request may log an unhandled exception")
        } finally {
            log.detach()
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
            runBlocking { TestSeedState.restoreSeedAccounts() }
        }
    }
}
