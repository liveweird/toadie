package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
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
    fun `catalog file mutations emit audit events`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("catalogaudit")
        val userId = TestUsers.seed(email = email, password = "pw", role = UserRole.USER)
        val capture = LogCapture("ch.nokillswit.audit")
        try {
            val client = authedClient(email, "pw")
            val name = uniqueEntityName("audited")
            val fileId = client.createCatalogFile(componentFile(name)).id
            client.put("/api/v1/catalog-files/$fileId") {
                contentType(ContentType.Application.Json)
                setBody(componentFile(name, title = "Edited"))
            }
            client.delete("/api/v1/catalog-files/$fileId")

            // Ids travel as Long key/values (not message text), so match on the raw value.
            fun hasLong(event: ch.qos.logback.classic.spi.ILoggingEvent, key: String, value: Long) =
                event.keyValuePairs?.any { it.key == key && it.value == value } == true

            for (eventName in listOf("catalog_file.created", "catalog_file.updated", "catalog_file.deleted")) {
                val event = capture.awaitEvent {
                    it.message == eventName && hasLong(it, "catalogFileId", fileId.toLong())
                }
                assertNotNull(event, "expected a $eventName audit event for file $fileId")
                assertTrue(hasLong(event, "byUserId", userId.toLong()))
            }
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
