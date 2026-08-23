package ch.nokillswit

import ch.nokillswit.users.PasswordUpdateRequest
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every authenticated endpoint answers a uniform 401 to callers without a valid bearer. */
class AnonymousAccessTest {

    @Test
    fun `password change without a token is 401`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().putJson("/api/v1/users/1/password", PasswordUpdateRequest(password = "whatever-works"))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a forged token signed with the wrong secret is 401`() = testApplication {
        usePostgresTestcontainer()
        val forged = com.auth0.jwt.JWT.create()
            .withAudience("toadie-api")
            .withIssuer("http://0.0.0.0:8081/")
            .withClaim("email", "attacker@test")
            .withClaim("userId", 1L)
            .withArrayClaim("roles", arrayOf("ADMIN"))
            .withClaim("typ", "access")
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000))
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("not-the-server-secret"))

        val response = jsonClient().put("/api/v1/users/1/password") {
            header(HttpHeaders.Authorization, "Bearer $forged")
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "whatever-works"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
