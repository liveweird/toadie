package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.PasswordResetRequest
import ch.nokillswit.auth.PasswordResetConfirmRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.RefreshRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * POST /api/v1/password-reset. The test app uses the dev-default `log` mail transport, so
 * delivered email is captured with a ListAppender on the `ch.nokillswit.mail` logger (the
 * AuditTest pattern); the endpoint's work is asynchronous, so assertions await the audit
 * events that the worker emits as completion barriers.
 */
class PasswordResetTest {

    @Test
    fun `a Polish-language account gets the Polish reset email`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("reset-pl")
        TestUsers.seed(email = email, password = "old-password-123", name = "Reset Polka", language = "pl")
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val response = jsonClient().post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(email))
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "password_reset.link_sent" && it.hasKeyValue("email", email)
                },
                "the reset should complete",
            )
            val message = mail.awaitEvent { "To: $email" in it.formattedMessage }?.formattedMessage
            assertNotNull(message, "the reset email should have been delivered (log transport)")
            // The recipient's stored language (V18) drives the whole email.
            assertTrue("Cześć Reset Polka," in message, "the PL greeting")
            assertTrue("jednorazowego linku" in message, "the PL link instructions")
            assertTrue("Zresetuj hasło Toadie" in message, "the PL subject")
            assertFalse("New password" in message, "no EN leak into the PL body")
        } finally {
            mail.detach()
            auditEvents.detach()
        }
    }

    @Test
    fun `request preserves credentials and confirmation consumes the link and revokes existing sessions`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("reset")
        TestUsers.seed(email = email, password = "old-password-123", name = "Reset Tester")
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            val priorLogin = client.login(email, "old-password-123").body<LoginResponse>()
            val response = client.post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(email))
            }
            assertEquals(HttpStatusCode.Accepted, response.status)

            // Delivery is async, but no credential change happens until confirmation.
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "password_reset.link_sent" && it.hasKeyValue("email", email)
                },
                "the reset should complete",
            )
            val message = mail.awaitEvent { "To: $email" in it.formattedMessage }?.formattedMessage
            assertNotNull(message, "the reset email should have been delivered (log transport)")
            assertTrue("Hi Reset Tester," in message)
            assertTrue("Your password is unchanged" in message)
            assertTrue("Reset your Toadie password" in message)
            val token = Regex("#token=([A-Za-z0-9_-]{43})").find(message)?.groupValues?.get(1)
            assertNotNull(token)
            assertEquals(HttpStatusCode.OK, client.login(email, "old-password-123").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/users/${priorLogin.userId}") {
                bearerAuth(priorLogin.token)
            }.status)
            val confirm = PasswordResetConfirmRequest(token, "chosen-password-123")
            assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/password-reset/confirm",
                confirm.copy(password = "short")).status)
            val confirmed = client.postJson("/api/v1/password-reset/confirm", confirm)
            assertEquals(HttpStatusCode.NoContent, confirmed.status)
            assertEquals("no-store", confirmed.headers["Cache-Control"])
            assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/password-reset/confirm", confirm).status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/users/${priorLogin.userId}") {
                bearerAuth(priorLogin.token)
            }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/refresh",
                RefreshRequest(priorLogin.refreshToken)).status)
            assertEquals(HttpStatusCode.OK, client.login(email, confirm.password).status)

            val oldLogin = client.postJson("/api/v1/login", LoginRequest(email, "old-password-123"))
            assertEquals(HttpStatusCode.Unauthorized, oldLogin.status, "the old password must be dead")
        } finally {
            mail.detach()
            auditEvents.detach()
        }
    }

    @Test
    fun `unknown email answers 202 identically and sends nothing`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("reset-nobody")
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val response = jsonClient().post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(email))
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
            // The async branch signals completion via the audit trail — wait for it, then
            // assert no email went out.
            val audited = auditEvents.awaitEvent {
                it.message == "password_reset.unknown_email" && it.hasKeyValue("email", email)
            }
            assertNotNull(audited, "the unknown-email branch should be audited")
            assertNull(mail.events.firstOrNull { "To: $email" in it.formattedMessage })
        } finally {
            mail.detach()
            auditEvents.detach()
        }
    }

    @Test
    fun `a second request within the interval is 429, uniformly for unknown emails too`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        val email = uniqueEmail("reset-throttle") // does not exist — the throttle must not care
        suspend fun request() = client.post("/api/v1/password-reset") {
            contentType(ContentType.Application.Json)
            setBody(PasswordResetRequest(email))
        }
        assertEquals(HttpStatusCode.Accepted, request().status)
        assertEquals(HttpStatusCode.TooManyRequests, request().status)
    }

    @Test
    fun `malformed emails are 400`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        for (bad in listOf("", "   ", "no-at-sign", "x".repeat(255) + "@test")) {
            val response = client.post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(bad))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "for input '${bad.take(20)}'")
        }
    }

    @Test
    fun `disabled mail transport answers 503`() = testApplication {
        configureApp("mail.transport" to "disabled")
        startApplication()
        val response = jsonClient().post("/api/v1/password-reset") {
            contentType(ContentType.Application.Json)
            setBody(PasswordResetRequest(uniqueEmail("reset-disabled")))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `a delivery failure is audited and leaves the old password working`() = testApplication {
        // Real SMTP transport pointed at a closed port: send() throws AFTER the 202.
        configureApp(
            "mail.transport" to "smtp",
            "mail.smtp.host" to "localhost",
            "mail.smtp.port" to "1",
            "mail.smtp.startTls" to "false",
        )
        startApplication()
        val email = uniqueEmail("reset-sendfail")
        TestUsers.seed(email = email, password = "old-password-123")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val response = jsonClient().post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(email))
            }
            assertEquals(HttpStatusCode.Accepted, response.status, "delivery failure must stay unobservable")
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "password_reset.send_failed" && it.hasKeyValue("email", email)
                },
                "the failed delivery should be audited",
            )
            // Neither requesting nor failing to deliver a link replaces the password.
            val oldLogin = jsonClient().postJson("/api/v1/login", LoginRequest(email, "old-password-123"))
            assertEquals(HttpStatusCode.OK, oldLogin.status, "old password must survive a failed delivery")
        } finally {
            auditEvents.detach()
        }
    }
}
