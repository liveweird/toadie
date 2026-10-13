package ch.nokillswit

import ch.nokillswit.auth.AuthSessionService
import ch.nokillswit.auth.AuthSessionServiceKey
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.plugins.ProblemDetail
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JWT `validate` block's revocation and session lookups (`plugins/Security.kt`) run database
 * queries on every authenticated request. A query that THROWS — database unreachable, pool acquire
 * timeout — is an outage, not a bad token: it must answer the catch-all's 500, never the 401 that
 * the SPA reads as session expiry (ported from Lettuce and Flow; see "An outage reads as 500, never 401" in
 * `.claude/docs/security.md`). The app's services are swapped for ones pointed at an unreachable
 * database, a deterministic fast failure without a real outage.
 */
class BlocklistOutageTest {

    private fun unreachableDatabase() = R2dbcDatabase.connect(
        url = "r2dbc:postgresql://localhost:1/nonexistent",
        user = "nobody",
        password = "nobody",
    )

    private fun brokenBlocklist() = TokenBlocklistService(unreachableDatabase())

    private fun brokenSessions() = AuthSessionService(unreachableDatabase())

    private suspend fun io.ktor.client.HttpClient.readWith(token: String) =
        get(CATALOG_FILES_PATH) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun loginPair(client: io.ktor.client.HttpClient, prefix: String): LoginResponse {
        // Login mints tokens without consulting the blocklist, so a real pair is available.
        val email = uniqueEmail(prefix)
        TestUsers.seed(email = email, password = "pw")
        val login = client.login(email, "pw")
        assertEquals(HttpStatusCode.OK, login.status)
        return login.body<LoginResponse>()
    }

    @Test
    fun `a failing blocklist lookup answers 500, not 401`() = testApplication {
        configureApp()
        application { attributes.put(TokenBlocklistServiceKey, brokenBlocklist()) }
        startApplication()
        val client = jsonClient()
        val token = loginPair(client, "blocklist-outage").token

        val response = client.readWith(token)
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val problem = response.body<ProblemDetail>()
        assertEquals(HttpStatusCode.InternalServerError.value, problem.status)
        assertEquals("An unexpected error occurred", problem.detail)
    }

    @Test
    fun `the outage is logged once at ERROR with its cause and never with the bearer text`() = testApplication {
        configureApp()
        application { attributes.put(TokenBlocklistServiceKey, brokenBlocklist()) }
        startApplication()
        val client = jsonClient()
        val token = loginPair(client, "outage-log").token
        // The root logger: the Application logger's name differs between the test engine and EngineMain.
        val capture = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME)
        try {
            assertEquals(HttpStatusCode.InternalServerError, client.readWith(token).status)
            val lines = capture.events.filter {
                it.level == ch.qos.logback.classic.Level.ERROR &&
                    it.formattedMessage.startsWith("Unhandled exception while processing")
            }
            assertEquals(1, lines.size, "exactly one ERROR line for the outage")
            val line = lines.single()
            assertTrue(line.throwableProxy != null, "the cause is attached")
            assertTrue(!line.formattedMessage.contains(token), "no bearer text in the message")
            val traceText = generateSequence(line.throwableProxy) { it.cause }.joinToString(" ") { it.message.orEmpty() }
            assertTrue(!traceText.contains(token), "no bearer text in the cause chain")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `a failing session lookup answers 500, not 401`() = testApplication {
        configureApp()
        application { attributes.put(AuthSessionServiceKey, brokenSessions()) }
        startApplication()
        val client = jsonClient()
        val token = loginPair(client, "session-outage").token

        val response = client.readWith(token)
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("An unexpected error occurred", response.body<ProblemDetail>().detail)
    }

    @Test
    fun `a refresh token used as bearer stays 401 during an outage`() = testApplication {
        // The typ check runs before the lookups, so a structurally wrong token is rejected as
        // such even while the database is unreachable.
        configureApp()
        application {
            attributes.put(TokenBlocklistServiceKey, brokenBlocklist())
            attributes.put(AuthSessionServiceKey, brokenSessions())
        }
        startApplication()
        val client = jsonClient()
        val refreshToken = loginPair(client, "outage-refresh").refreshToken

        assertEquals(HttpStatusCode.Unauthorized, client.readWith(refreshToken).status)
    }

    @Test
    fun `a jti-less token never reaches the lookup and stays 401 during an outage`() = testApplication {
        // Structurally valid (right audience/issuer/typ) but with NO jti — every server-minted
        // token carries one; a token without it could never be blocklisted, so plugins/Security.kt
        // rejects it outright BEFORE any database lookup.
        configureApp()
        application {
            attributes.put(TokenBlocklistServiceKey, brokenBlocklist())
            attributes.put(AuthSessionServiceKey, brokenSessions())
        }
        startApplication()

        val jtiLess = JWT.create()
            .withAudience("toadie-api").withIssuer("http://0.0.0.0:8081/")
            .withClaim("typ", "access").withClaim("userId", 1L).withClaim("sid", UUID.randomUUID().toString())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256("secret"))

        assertEquals(HttpStatusCode.Unauthorized, jsonClient().readWith(jtiLess).status)
    }

    @Test
    fun `a malformed token never reaches the lookup and stays 401 during an outage`() = testApplication {
        configureApp()
        application {
            attributes.put(TokenBlocklistServiceKey, brokenBlocklist())
            attributes.put(AuthSessionServiceKey, brokenSessions())
        }
        startApplication()

        val response = jsonClient().readWith("not-a-jwt")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Missing or invalid bearer token", response.body<ProblemDetail>().detail)
    }
}
