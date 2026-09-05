package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.LogoutRequest
import ch.nokillswit.auth.RefreshRequest
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.users.UserRole
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRevocationTest {
    private suspend fun HttpClient.readWith(token: String) =
        get(CATALOG_FILES_PATH) { header(HttpHeaders.Authorization, "Bearer $token") }.status

    private suspend fun HttpClient.assertRejected(pair: LoginResponse) {
        assertEquals(HttpStatusCode.Unauthorized, readWith(pair.token))
        assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/refresh", RefreshRequest(pair.refreshToken)).status)
    }

    @Test
    fun `logout without a body revokes all generations but keeps a separate login alive`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("family-logout")
        TestUsers.seed(email, "pw")
        val client = jsonClient()
        val original = client.login(email, "pw").body<LoginResponse>()
        val renewed = client.postJson("/api/v1/refresh", RefreshRequest(original.refreshToken)).body<LoginResponse>()
        val otherDevice = client.login(email, "pw").body<LoginResponse>()
        assertEquals(HttpStatusCode.OK, client.readWith(original.token))
        assertEquals(HttpStatusCode.OK, client.readWith(renewed.token))
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/logout") {
            header(HttpHeaders.Authorization, "Bearer ${renewed.token}")
        }.status)
        client.assertRejected(original)
        client.assertRejected(renewed)
        assertEquals(HttpStatusCode.OK, client.readWith(otherDevice.token))
        assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/refresh", RefreshRequest(otherDevice.refreshToken)).status)
    }

    @Test
    fun `logout cannot revoke an unrelated user's refresh token supplied in the body`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        val email = uniqueEmail("logout-owner")
        val otherEmail = uniqueEmail("logout-unrelated")
        TestUsers.seed(email, "pw")
        TestUsers.seed(otherEmail, "pw")
        val own = client.login(email, "pw").body<LoginResponse>()
        val other = client.login(otherEmail, "pw").body<LoginResponse>()
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/logout") {
            header(HttpHeaders.Authorization, "Bearer ${own.token}")
            contentType(ContentType.Application.Json)
            setBody(LogoutRequest(other.refreshToken))
        }.status)
        client.assertRejected(own)
        assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/refresh", RefreshRequest(other.refreshToken)).status)
    }

    @Test
    fun `password change role removal and deletion reject access and refresh immediately`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("revoke-changes")
        val id = TestUsers.seed(email, "pw")
        val client = jsonClient()
        val first = client.login(email, "pw").body<LoginResponse>()
        val second = client.login(email, "pw").body<LoginResponse>()
        TestUsers.service.updatePassword(id, hashPassword("new-password", cost = 4))
        client.assertRejected(first)
        client.assertRejected(second)
        val afterPassword = client.login(email, "new-password").body<LoginResponse>()
        assertEquals(HttpStatusCode.OK, client.readWith(afterPassword.token))
        assertEquals(UserRole.ADMIN, TestUsers.service.read(id)!!.role)
        TestUsers.service.updateGuarded(id, "Test", email, UserRole.USER)
        client.assertRejected(afterPassword)
        // Restoring a role must not revive a token issued under an older credential epoch.
        TestUsers.service.updateGuarded(id, "Test", email, UserRole.ADMIN)
        client.assertRejected(afterPassword)
        val beforeEmailChange = client.login(email, "new-password").body<LoginResponse>()
        val renamedEmail = uniqueEmail("changed-session-email")
        TestUsers.service.updateGuarded(id, "Test", renamedEmail, UserRole.ADMIN)
        client.assertRejected(beforeEmailChange)
        val beforeDelete = client.login(renamedEmail, "new-password").body<LoginResponse>()
        TestUsers.service.deleteGuarded(id)
        client.assertRejected(beforeDelete)
    }

    @Test
    fun `signed tokens need a stored family and a non-wrapping user identity`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("family-claims")
        val id = TestUsers.seed(email, "pw")
        val client = jsonClient()
        val pair = client.login(email, "pw").body<LoginResponse>()
        val sid = JWT.decode(pair.token).getClaim("sid").asString()
        for ((userId, sessionId) in listOf(
            null to sid,
            -1L to sid,
            (UInt.MAX_VALUE.toLong() + 1) to sid,
            id.toLong() to null,
            id.toLong() to UUID.randomUUID().toString(),
        )) {
            fun token(type: String): String = JWT.create()
                .withAudience("toadie-api").withIssuer("http://0.0.0.0:8081/")
                .withJWTId(UUID.randomUUID().toString()).withExpiresAt(Date(System.currentTimeMillis() + 60_000))
                .withClaim("typ", type).withClaim("userId", userId).withClaim("sid", sessionId)
                .sign(Algorithm.HMAC256("secret"))
            assertEquals(HttpStatusCode.Unauthorized, client.readWith(token("access")))
            assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/refresh", RefreshRequest(token("refresh"))).status)
        }
    }
}
