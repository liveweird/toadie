package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import io.ktor.client.call.body
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
        withAuditCapture { capture ->
            jsonClient().postJson("/api/v1/login", LoginRequest(email, "pw"))
            val event = capture.awaitEvent { it.message == "login.success" && it.hasKeyValue("email", email) }
            assertNotNull(event, "expected a login.success audit event for $email")
        }
    }

    @Test
    fun `catalog file mutations emit audit events`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("catalogaudit")
        val userId = TestUsers.seed(email = email, password = "pw", role = UserRole.USER)
        withAuditCapture { capture ->
            val client = authedClient(email, "pw")
            val name = uniqueEntityName("audited")
            val fileId = client.createCatalogFile(componentFile(name)).id
            client.putJson("/api/v1/catalog-files/$fileId", componentFile(name, title = "Edited"))
            client.delete("/api/v1/catalog-files/$fileId")


            for (eventName in listOf("catalog_file.created", "catalog_file.updated", "catalog_file.deleted")) {
                val event = capture.awaitEvent {
                    it.message == eventName && it.hasKeyValue("catalogFileId", fileId.toLong())
                }
                assertNotNull(event, "expected a $eventName audit event for file $fileId")
                assertTrue(event.hasKeyValue("byUserId", userId.toLong()))
            }
        }
    }

    @Test
    fun `user management mutations emit audit events with deltas`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("useraudit")
        val adminId = TestUsers.seed(email = adminEmail, password = "pw-123456789")
        withAuditCapture { capture ->
            val client = authedClient(adminEmail, "pw-123456789")
            val email = uniqueEmail("audited-user")
            val created = client.post("/api/v1/users") {
                contentType(ContentType.Application.Json)
                setBody(
                    ch.nokillswit.users.UserCreateRequest(
                        name = "Audited One",
                        email = email,
                        password = "initial-pass-123",
                    ),
                )
            }.body<ch.nokillswit.users.UserResponse>()
            client.put("/api/v1/users/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    ch.nokillswit.users.UserUpdateRequest(
                        name = "Audited Two",
                        email = email,
                        roles = listOf(ch.nokillswit.users.UserRole.ADMIN),
                    ),
                )
            }
            client.put("/api/v1/users/${created.id}/features") {
                contentType(ContentType.Application.Json)
                setBody(ch.nokillswit.users.UserFeaturesUpdateRequest(listOf(ch.nokillswit.users.Feature.MFA)))
            }
            client.delete("/api/v1/users/${created.id}")


            val createdEvent = capture.awaitEvent {
                it.message == "user.created" && it.hasKeyValue("email", email)
            }
            assertNotNull(createdEvent)
            assertTrue(createdEvent.hasKeyValue("byUserId", adminId.toLong()))
            val updatedEvent = capture.awaitEvent {
                it.message == "user.updated" && it.hasKeyValue("targetUserId", created.id.toLong())
            }
            assertNotNull(updatedEvent)
            assertTrue(updatedEvent.hasKeyValue("nameFrom", "Audited One"))
            assertTrue(updatedEvent.hasKeyValue("nameTo", "Audited Two"))
            val rolesEvent = capture.awaitEvent {
                it.message == "user.roles_changed" && it.hasKeyValue("targetUserId", created.id.toLong())
            }
            assertNotNull(rolesEvent)
            assertTrue(rolesEvent.hasKeyValue("rolesFrom", ""))
            assertTrue(rolesEvent.hasKeyValue("rolesTo", "ADMIN"))
            val featuresEvent = capture.awaitEvent {
                it.message == "user.features_changed" && it.hasKeyValue("targetUserId", created.id.toLong())
            }
            assertNotNull(featuresEvent)
            assertTrue(featuresEvent.hasKeyValue("featuresFrom", ""))
            assertTrue(featuresEvent.hasKeyValue("featuresTo", "MFA"))
            assertNotNull(
                capture.awaitEvent {
                    it.message == "user.deleted" && it.hasKeyValue("targetUserId", created.id.toLong())
                },
            )
        }
    }

    @Test
    fun `failed login emits an audit event carrying the failure reason`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("failaudit")
        TestUsers.seed(email = email, password = "right-pw")
        withAuditCapture { capture ->
            jsonClient().postJson("/api/v1/login", LoginRequest(email, "wrong-pw"))
            val event = capture.awaitEvent { it.message == "login.failure" && it.hasKeyValue("email", email) }
            assertNotNull(event, "expected a login.failure audit event for $email")
            assertTrue(event.hasKeyValue("reason", "wrong_password"))
        }
    }
}
