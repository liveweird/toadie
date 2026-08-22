package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.users.PasswordUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordChangeTest {

    @Test
    fun `self change with the correct current password works and the new password logs in`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("self")
        val userId = TestUsers.seed(email = email, password = "old-password", role = UserRole.USER)
        val client = authedClient(email, "old-password")

        val response = client.put("/api/v1/users/$userId/password") {
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "brand-new-password", currentPassword = "old-password"))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)

        val login = jsonClient().post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, "brand-new-password"))
        }
        assertEquals(HttpStatusCode.OK, login.status)
    }

    @Test
    fun `self change with a wrong or missing current password is 403 and mutates nothing`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("wrongcur")
        val userId = TestUsers.seed(email = email, password = "old-password", role = UserRole.USER)
        val client = authedClient(email, "old-password")

        val wrong = client.put("/api/v1/users/$userId/password") {
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "brand-new-password", currentPassword = "not-it"))
        }
        assertEquals(HttpStatusCode.Forbidden, wrong.status)

        val missing = client.put("/api/v1/users/$userId/password") {
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "brand-new-password"))
        }
        assertEquals(HttpStatusCode.Forbidden, missing.status)

        val oldStillWorks = jsonClient().post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, "old-password"))
        }
        assertEquals(HttpStatusCode.OK, oldStillWorks.status)
    }

    @Test
    fun `an admin resets another user's password without the current one`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("resetter")
        val targetEmail = uniqueEmail("target")
        TestUsers.seed(email = adminEmail, password = "admin-pw", role = UserRole.ADMIN)
        val targetId = TestUsers.seed(email = targetEmail, password = "old-password", role = UserRole.USER)
        val client = authedClient(adminEmail, "admin-pw")

        val response = client.put("/api/v1/users/$targetId/password") {
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "admin-chosen-pw"))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)

        val login = jsonClient().post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(targetEmail, "admin-chosen-pw"))
        }
        assertEquals(HttpStatusCode.OK, login.status)
    }

    @Test
    fun `a regular user may not change somebody else's password`() = testApplication {
        usePostgresTestcontainer()
        val callerEmail = uniqueEmail("caller")
        val otherEmail = uniqueEmail("other")
        TestUsers.seed(email = callerEmail, password = "pw", role = UserRole.USER)
        val otherId = TestUsers.seed(email = otherEmail, password = "pw", role = UserRole.USER)
        val client = authedClient(callerEmail, "pw")

        val response = client.put("/api/v1/users/$otherId/password") {
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "hijacked-pw!"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a too-short new password is 400`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("short")
        val targetId = TestUsers.seed(email = uniqueEmail("shorttarget"), password = "pw", role = UserRole.USER)
        TestUsers.seed(email = adminEmail, password = "admin-pw", role = UserRole.ADMIN)
        val client = authedClient(adminEmail, "admin-pw")

        val response = client.put("/api/v1/users/$targetId/password") {
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "tiny"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `resetting an unknown user is 404`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("notfound")
        TestUsers.seed(email = adminEmail, password = "admin-pw", role = UserRole.ADMIN)
        val client = authedClient(adminEmail, "admin-pw")

        val response = client.put("/api/v1/users/999999999/password") {
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "whatever-works"))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
