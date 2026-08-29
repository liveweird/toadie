package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.LogoutRequest
import ch.nokillswit.auth.RefreshRequest
import ch.nokillswit.auth.hashPassword
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RefreshTest {

    private suspend fun io.ktor.client.HttpClient.login(email: String, password: String): LoginResponse =
        post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, password))
        }.body<LoginResponse>()

    @Test
    fun `a valid refresh token yields a fresh pair`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("refresh")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val response = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))

        assertEquals(HttpStatusCode.OK, response.status)
        val renewed = response.body<LoginResponse>()
        assertTrue(renewed.token.isNotBlank())
        assertNotEquals(session.token, renewed.token, "a fresh access token is minted")
    }

    @Test
    fun `an access token presented as a refresh token is rejected`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("typ")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val response = client.postJson("/api/v1/refresh", RefreshRequest(session.token))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `garbage refresh token returns 401`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().postJson("/api/v1/refresh", RefreshRequest("not-a-jwt"))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a refresh token revoked at logout is rejected`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("revoked")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val logout = client.post("/api/v1/logout") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
            contentType(ContentType.Application.Json)
            setBody(LogoutRequest(refreshToken = session.refreshToken))
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)

        val response = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a password change invalidates refresh tokens minted before it`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("rotated")
        val userId = TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        // JWT iat has second precision and the comparison truncates both sides, so a change in
        // the same wall-clock second as the mint would NOT invalidate — wait out the boundary.
        Thread.sleep(1100)
        TestUsers.service.updatePassword(userId, hashPassword("new-password!", cost = 4))

        val response = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a refresh token without a jti is rejected as malformed`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("nojti")
        val userId = TestUsers.seed(email = email, password = "pw")
        // Correctly signed and typed, but missing the jti — such a token could never be
        // blocklisted, so the refresh path refuses it outright.
        val jtiLess = com.auth0.jwt.JWT.create()
            .withAudience("toadie-api")
            .withIssuer("http://0.0.0.0:8081/")
            .withIssuedAt(java.util.Date())
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000))
            .withClaim("email", email)
            .withClaim("userId", userId.toLong())
            .withClaim("typ", "refresh")
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("secret"))

        val response = jsonClient().postJson("/api/v1/refresh", RefreshRequest(jtiLess))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `refresh for a soft-deleted user is rejected`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("gone")
        val userId = TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        TestUsers.softDelete(userId)

        val response = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
