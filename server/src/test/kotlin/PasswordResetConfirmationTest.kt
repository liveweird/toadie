package ch.nokillswit

import ch.nokillswit.auth.*
import ch.nokillswit.users.Feature
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class PasswordResetConfirmationTest {
    @Test
    fun `missing reset origin fails closed without issuing a grant`() = testApplication {
        configureApp("mail.appUrl" to "")
        val email = uniqueEmail("reset-no-origin")
        val id = TestUsers.seed(email = email, password = "old-password")
        assertEquals(HttpStatusCode.ServiceUnavailable, jsonClient().postJson(
            "/api/v1/password-reset", PasswordResetRequest(email)).status)
        assertTrue(resetTokenHashes(id).isEmpty())
    }

    @Test
    fun `confirmation rejects malformed and unknown grants uniformly and has its own rate limit`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        repeat(10) { index ->
            val token = if (index % 2 == 0) "malformed" else "a".repeat(43)
            assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/password-reset/confirm",
                PasswordResetConfirmRequest(token, "valid-password")).status)
        }
        val limited = client.postJson("/api/v1/password-reset/confirm",
            PasswordResetConfirmRequest("a".repeat(43), "valid-password"))
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("no-store", limited.headers["Cache-Control"])
    }

    @Test
    fun `UTF8 ceiling does not consume a valid grant and completion still works with mail disabled`() = testApplication {
        configureApp("mail.transport" to "disabled")
        val email = uniqueEmail("reset-confirm-disabled")
        val id = TestUsers.seed(email = email, password = "old-password")
        val resets = newPasswordResetService()
        val token = assertNotNull(resets.issue(id, 0))
        val client = jsonClient()
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/password-reset/confirm",
            PasswordResetConfirmRequest(token, "ą".repeat(36))).status)
        assertTrue(resets.isUsable(token))
        assertEquals(HttpStatusCode.NoContent, client.postJson("/api/v1/password-reset/confirm",
            PasswordResetConfirmRequest(token, "chosen-password")).status)
        assertEquals(HttpStatusCode.OK, client.login(email, "chosen-password").status)
    }

    @Test
    fun `notification failure does not undo confirmation and logs no token or password`() = testApplication {
        configureApp("mail.transport" to "smtp", "mail.smtp.host" to "localhost",
            "mail.smtp.port" to "1", "mail.smtp.startTls" to "false")
        val email = uniqueEmail("reset-notify-fail")
        val id = TestUsers.seed(email = email, password = "old-password")
        val token = assertNotNull(newPasswordResetService().issue(id, 0))
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            assertEquals(HttpStatusCode.NoContent, client.postJson("/api/v1/password-reset/confirm",
                PasswordResetConfirmRequest(token, "chosen-password")).status)
            val event = assertNotNull(auditEvents.awaitEvent {
                it.message == "password_reset.notification_failed" && it.hasKeyValue("userId", id.toLong())
            })
            assertFalse(token in event.formattedMessage)
            assertFalse("chosen-password" in event.formattedMessage)
            assertEquals(HttpStatusCode.OK, client.login(email, "chosen-password").status)
        } finally {
            auditEvents.detach()
        }
    }

    @Test
    fun `reset invalidates pending MFA but does not disable MFA for the next login`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("reset-mfa")
        val id = TestUsers.seed(email = email, password = "old-password")
        TestUsers.service.setDisabledFeatures(id, emptySet())
        val mail = LogCapture("ch.nokillswit.mail")
        try {
            val client = jsonClient()
            val pending = client.login(email, "old-password").body<MfaChallengeResponse>()
            val message = assertNotNull(mail.awaitEvent { "To: $email" in it.formattedMessage }).formattedMessage
            val code = assertNotNull(Regex("(?m)^\\d{6}$").find(message)?.value)
            val token = assertNotNull(newPasswordResetService().issue(id, 0))
            assertEquals(HttpStatusCode.NoContent, client.postJson("/api/v1/password-reset/confirm",
                PasswordResetConfirmRequest(token, "chosen-password")).status)
            assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/login/mfa",
                MfaVerifyRequest(pending.challengeId, code)).status)
            assertFalse(Feature.MFA in assertNotNull(TestUsers.service.read(id)).disabledFeatures)
            assertTrue(client.login(email, "chosen-password").body<MfaChallengeResponse>().mfaRequired)
            val notification = assertNotNull(mail.awaitEvent {
                "To: $email" in it.formattedMessage && "Your Toadie password was reset" in it.formattedMessage
            }).formattedMessage
            assertFalse(token in notification)
            assertFalse("chosen-password" in notification)
        } finally {
            mail.detach()
        }
    }
}
