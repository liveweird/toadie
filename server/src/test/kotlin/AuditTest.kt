package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The audit trail (audit/Audit.kt): structured SLF4J events on the dedicated
 * `ch.nokillswit.audit` logger, fields as key/values (not message text).
 */
class AuditTest {

    @Test
    fun `successful login emits an audit event with the email as a key-value`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("audited")
        TestUsers.seed(email = email, password = "pw")
        val capture = LogCapture("ch.nokillswit.audit")
        try {
            jsonClient().post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(email, "pw"))
            }
            val event = capture.awaitEvent { it.message == "login.success" && it.hasKeyValue("email", email) }
            assertNotNull(event, "expected a login.success audit event for $email")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `failed login emits an audit event carrying the failure reason`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("failaudit")
        TestUsers.seed(email = email, password = "right-pw")
        val capture = LogCapture("ch.nokillswit.audit")
        try {
            jsonClient().post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(email, "wrong-pw"))
            }
            val event = capture.awaitEvent { it.message == "login.failure" && it.hasKeyValue("email", email) }
            assertNotNull(event, "expected a login.failure audit event for $email")
            assertTrue(event.hasKeyValue("reason", "wrong_password"))
        } finally {
            capture.detach()
        }
    }
}
