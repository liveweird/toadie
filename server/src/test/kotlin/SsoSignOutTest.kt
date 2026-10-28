package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.LogoutResponse
import ch.nokillswit.auth.RefreshRequest
import ch.nokillswit.auth.sso.SsoSignOutDeadlineKey
import com.auth0.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Provider sign-out (OIDC RP-initiated logout): `POST /api/v1/logout` for an SSO session answers the provider's end-session
 * location, every other logout stays `204`, and the session is revoked first in every case. Uses the SSO login harness
 * ([SsoTestSupport.kt]) against the in-process [StubIdp]. Rules: `.claude/docs/security.md` "SSO login (OIDC)" (Sign-out).
 */
class SsoSignOutTest {

    private fun jdbc(sql: String, bind: java.sql.PreparedStatement.() -> Unit = {}, read: java.sql.ResultSet.() -> Unit) {
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.bind()
                statement.executeQuery().use { it.read() }
            }
        }
    }

    private fun sessionRows(userId: UInt): Int {
        var count = 0
        jdbc("SELECT count(*) FROM auth_sessions WHERE user_id = ?", { setLong(1, userId.toLong()) }) { next(); count = getInt(1) }
        return count
    }

    private fun storedIdToken(userId: UInt): String? {
        var token: String? = null
        jdbc("SELECT id_token FROM auth_sessions WHERE user_id = ?", { setLong(1, userId.toLong()) }) {
            assertTrue(next(), "the session row exists")
            token = getString(1)
        }
        return token
    }

    private suspend fun HttpClient.logout(session: LoginResponse): HttpResponse =
        post("/api/v1/logout") { header(HttpHeaders.Authorization, "Bearer ${session.token}") }

    private fun exec(sql: String, bind: java.sql.PreparedStatement.() -> Unit) {
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.bind()
                statement.executeUpdate()
            }
        }
    }

    private fun advertise(stub: StubIdp) {
        stub.discoveryOverrides = mapOf("end_session_endpoint" to "${stub.issuer}/v1/logout")
    }

    private fun logoutEventOf(audit: LogCapture, userId: UInt) =
        assertNotNull(audit.events.singleOrNull { it.message == "logout" && it.hasKeyValue("userId", userId.toLong()) })

    private fun assertNoLeak(audit: LogCapture, vararg secrets: String) {
        audit.events.forEach { event ->
            val text = event.formattedMessage + event.keyValuePairs.orEmpty().joinToString { "${it.key}=${it.value}" }
            secrets.forEach { assertFalse(it in text, "audit leaked ${it.take(12)}") }
            assertFalse("id_token_hint" in text)
        }
    }

    @Test
    fun `an SSO logout revokes the session and answers the provider's end-session location with the retained ID token`() = testApplication {
        StubIdp().use { stub ->
            advertise(stub)
            ssoApp(stub)
            val browser = browser()
            val client = jsonClient()
            val session = browser.signInAndExchange(stub, mapOf("email" to uniqueEmail("sso-signout")))
            val retained = assertNotNull(storedIdToken(session.userId), "the sign-in stored the provider's ID token with the session")
            assertEquals(3, retained.split('.').size)
            assertEquals(stub.issuer, JWT.decode(retained).issuer)

            // A refresh keeps the row, so it keeps the token.
            val renewed = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken)).body<LoginResponse>()
            assertEquals(retained, storedIdToken(session.userId))

            withAuditCapture { audit ->
                val response = client.logout(renewed)
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val url = response.body<LogoutResponse>().endSessionUrl
                assertTrue(url.startsWith("${stub.issuer}/v1/logout?"))
                val query = StubIdp.queryOf(url)
                assertEquals(retained, query["id_token_hint"])
                assertEquals("http://localhost:8081/login", query["post_logout_redirect_uri"])
                assertEquals(setOf("id_token_hint", "post_logout_redirect_uri"), query.keys, "no state, nothing else")

                // Revoked: the row (and the token with it) is gone, both tokens of the family are dead.
                assertEquals(0, sessionRows(session.userId))
                assertEquals(HttpStatusCode.Unauthorized, client.logout(session).status)
                assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/refresh", RefreshRequest(renewed.refreshToken)).status)

                val event = logoutEventOf(audit, session.userId)
                assertTrue(event.hasKeyValue("sso", "redirected"))
                assertNoLeak(audit, retained, url)
            }
        }
    }

    @Test
    fun `the post-logout redirect URI is configurable`() = testApplication {
        StubIdp().use { stub ->
            advertise(stub)
            ssoApp(stub, "sso.postLogoutRedirectUri" to "http://localhost:8081/signed-out")
            val session = browser().signInAndExchange(stub)
            val url = jsonClient().logout(session).body<LogoutResponse>().endSessionUrl
            assertEquals("http://localhost:8081/signed-out", StubIdp.queryOf(url)["post_logout_redirect_uri"])
        }
    }

    @Test
    fun `a provider that advertises no end-session endpoint is a plain 204 audited as unsupported, with no warning`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val appLog = LogCapture(application.log.name)
            try {
                val session = browser().signInAndExchange(stub)
                withAuditCapture { audit ->
                    val response = jsonClient().logout(session)
                    assertEquals(HttpStatusCode.NoContent, response.status)
                    assertTrue(logoutEventOf(audit, session.userId).hasKeyValue("sso", "unsupported"))
                    assertEquals(0, sessionRows(session.userId))
                    assertEquals(HttpStatusCode.Unauthorized, jsonClient().logout(session).status)
                }
                assertTrue(
                    appLog.events.none { "end_session_endpoint" in it.formattedMessage },
                    "an absent optional member is not warned about",
                )
            } finally {
                appLog.detach()
            }
        }
    }

    @Test
    fun `an unusable end-session endpoint never fails sign-in or logout, and its one warning names only the field`() = testApplication {
        StubIdp().use { stub ->
            val unusable = "https://user:pw@idp.example.com/secret-logout-path"
            stub.discoveryOverrides = mapOf("end_session_endpoint" to unusable)
            ssoApp(stub)
            val appLog = LogCapture(application.log.name)
            try {
                val session = browser().signInAndExchange(stub)
                withAuditCapture { audit ->
                    assertEquals(HttpStatusCode.NoContent, jsonClient().logout(session).status)
                    assertTrue(logoutEventOf(audit, session.userId).hasKeyValue("sso", "unsupported"))
                    assertEquals(0, sessionRows(session.userId))
                }
                val warnings = appLog.events.filter { "end_session_endpoint" in it.formattedMessage }
                assertEquals(1, warnings.size)
                assertTrue(warnings.all { "secret-logout-path" !in it.formattedMessage && "idp.example.com" !in it.formattedMessage })
            } finally {
                appLog.detach()
            }
        }
    }

    @Test
    fun `an oversized ID token is not retained, so that login signs out locally with no sso audit value`() = testApplication {
        StubIdp().use { stub ->
            advertise(stub)
            ssoApp(stub)
            val session = browser().signInAndExchange(stub, mapOf("name" to "x".repeat(9000)))
            assertNull(storedIdToken(session.userId))
            withAuditCapture { audit ->
                assertEquals(HttpStatusCode.NoContent, jsonClient().logout(session).status)
                assertTrue(logoutEventOf(audit, session.userId).keyValuePairs.none { it.key == "sso" && it.value != null })
            }
            assertEquals(0, sessionRows(session.userId))
        }
    }

    @Test
    fun `a password session logs out as before - 204, no sso audit value`() = testApplication {
        StubIdp().use { stub ->
            advertise(stub)
            ssoApp(stub)
            val email = uniqueEmail("signout-password")
            val id = TestUsers.seed(email = email, password = "pw")
            val client = jsonClient()
            val session = client.login(email, "pw").body<LoginResponse>()
            assertNull(storedIdToken(id))
            withAuditCapture { audit ->
                val response = client.logout(session)
                assertEquals(HttpStatusCode.NoContent, response.status)
                assertTrue(logoutEventOf(audit, id).keyValuePairs.none { it.key == "sso" && it.value != null })
            }
        }
    }

    @Test
    fun `a stored token with SSO switched off signs out locally, audited as unsupported, and the token still leaves with its row`() =
        testApplication {
            usePostgresTestcontainer()
            val email = uniqueEmail("signout-flag-off")
            val id = TestUsers.seed(email = email, password = "pw")
            val client = jsonClient()
            val session = client.login(email, "pw").body<LoginResponse>()
            exec("UPDATE auth_sessions SET id_token = ? WHERE user_id = ?") {
                setString(1, "left.over.token")
                setLong(2, id.toLong())
            }
            withAuditCapture { audit ->
                assertEquals(HttpStatusCode.NoContent, client.logout(session).status)
                assertTrue(logoutEventOf(audit, id).hasKeyValue("sso", "unsupported"))
            }
            assertEquals(0, sessionRows(id))
        }

    @Test
    fun `the session is revoked before discovery is read, and a stale discovery still serves the redirect`() = testApplication {
        StubIdp().use { stub ->
            advertise(stub)
            val clock = MovableClock()
            ssoApp(stub, clock = clock)
            val browser = browser()
            val session = browser.signInAndExchange(stub)
            val second = browser.signInAndExchange(stub)
            val client = jsonClient()
            val rowsDuringDiscovery = AtomicInteger(-1)
            stub.onDiscovery = { rowsDuringDiscovery.set(sessionRows(session.userId)) }
            val fetchesBefore = stub.discoveryFetches.get()

            // Past the one-hour TTL the logout itself needs a fresh discovery document: the row must already be gone then.
            clock.advanceSeconds(2 * 3600)
            val response = client.logout(session)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(fetchesBefore + 1, stub.discoveryFetches.get())
            assertEquals(0, rowsDuringDiscovery.get(), "revocation precedes the provider read")

            // The issuer goes down: the last good document keeps serving (bounded staleness), so the redirect still works.
            stub.onDiscovery = null
            stub.discoveryStatus = 503
            clock.advanceSeconds(2 * 3600)
            val stale = client.logout(second)
            assertEquals(HttpStatusCode.OK, stale.status)
            assertTrue(stale.body<LogoutResponse>().endSessionUrl.startsWith("${stub.issuer}/v1/logout?"))
        }
    }

    @Test
    fun `an unreadable discovery document is a 204 audited as unavailable after revocation, with one leak-free warning`() =
        testApplication {
            StubIdp().use { stub ->
                advertise(stub)
                val clock = MovableClock()
                ssoApp(stub, clock = clock)
                val session = browser().signInAndExchange(stub)
                val retained = assertNotNull(storedIdToken(session.userId))
                val client = jsonClient()
                stub.discoveryStatus = 503
                // Beyond the TTL plus the 24 h staleness bound: no usable copy is left.
                clock.advanceSeconds(26 * 3600)
                val appLog = LogCapture(application.log.name)
                try {
                    withAuditCapture { audit ->
                        val response = client.logout(session)
                        assertEquals(HttpStatusCode.NoContent, response.status)
                        assertTrue(logoutEventOf(audit, session.userId).hasKeyValue("sso", "unavailable"))
                        assertNoLeak(audit)
                    }
                    assertEquals(0, sessionRows(session.userId))
                    assertEquals(HttpStatusCode.Unauthorized, client.logout(session).status)
                    val warnings = appLog.events.filter { "sign-out unavailable" in it.formattedMessage }
                    assertEquals(1, warnings.size)
                    val text = warnings.single().formattedMessage
                    assertTrue("SsoUpstreamException" in text)
                    assertTrue(retained !in text && "127.0.0.1" !in text && "id_token_hint" !in text && "http" !in text, "no token, no URL")
                } finally {
                    appLog.detach()
                }
            }
        }

    @Test
    fun `a hung identity provider ends the provider step at the deadline - a 204 audited as unavailable`() =
        testApplication {
            StubIdp().use { stub ->
                advertise(stub)
                val clock = MovableClock()
                ssoApp(stub, clock = clock)
                application.attributes.put(SsoSignOutDeadlineKey, 300.milliseconds)
                val session = browser().signInAndExchange(stub)
                val retained = assertNotNull(storedIdToken(session.userId))
                val client = jsonClient()
                // The cached document expires, and the refresh never answers until the test lets it.
                clock.advanceSeconds(2 * 3600)
                val release = CountDownLatch(1)
                val answered = AtomicInteger()
                stub.onDiscovery = {
                    release.await(30, TimeUnit.SECONDS)
                    answered.incrementAndGet()
                }
                val appLog = LogCapture(application.log.name)
                try {
                    withAuditCapture { audit ->
                        val response = client.logout(session)
                        // The response came while the provider was still holding the request: the deadline ended it,
                        // not the provider's answer and not OperatorHttp's own 10 s.
                        assertEquals(0, answered.get())
                        assertEquals(HttpStatusCode.NoContent, response.status)
                        assertTrue(logoutEventOf(audit, session.userId).hasKeyValue("sso", "unavailable"))
                        assertNoLeak(audit, retained)
                    }
                    assertEquals(0, sessionRows(session.userId))
                    assertEquals(HttpStatusCode.Unauthorized, client.logout(session).status)
                    val warnings = appLog.events.filter { "sign-out unavailable" in it.formattedMessage }
                    assertEquals(1, warnings.size)
                    val text = warnings.single().formattedMessage
                    assertTrue(retained !in text && "127.0.0.1" !in text && "id_token_hint" !in text && "http" !in text, "no token, no URL")
                } finally {
                    release.countDown()
                    appLog.detach()
                }
            }
        }
}
