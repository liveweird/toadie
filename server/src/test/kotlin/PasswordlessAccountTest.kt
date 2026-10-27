package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.PasswordResetRequest
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.PasswordUpdateRequest
import ch.nokillswit.users.UserPageResponse
import ch.nokillswit.users.UserResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V48 passwordless accounts: a NULL `password_hash` fails every password path under the timing
 * equalizer (login 401 `no_password`, reset skipped with 202, self-service change 409) while the
 * admin set-password stays the audited break-glass; plus the schema constraints themselves.
 * Rules: `.claude/docs/security.md` "Passwordless accounts (V48)".
 */
class PasswordlessAccountTest {

    @Test
    fun `login with any password for a passwordless account is the same 401 and audits no_password`() = testApplication {
        usePostgresTestcontainer()
        val ordinaryEmail = uniqueEmail("pwl-ordinary")
        TestUsers.seed(email = ordinaryEmail, password = "right-password-1")
        val email = uniqueEmail("pwl-login")
        TestUsers.seedPasswordless(email)
        val client = jsonClient()

        withAuditCapture { audit ->
            val wrongForOrdinary = client.login(ordinaryEmail, "wrong-password-1")
            val attempt = client.login(email, "any-password-123")
            assertEquals(HttpStatusCode.Unauthorized, wrongForOrdinary.status)
            assertEquals(HttpStatusCode.Unauthorized, attempt.status)
            // No enumeration difference: the passwordless 401 reads exactly like a wrong password.
            assertEquals(wrongForOrdinary.bodyAsText(), attempt.bodyAsText())
            assertNotNull(
                audit.awaitEvent {
                    it.message == "login.failure" && it.hasKeyValue("email", email) &&
                        it.hasKeyValue("reason", "no_password")
                },
                "the passwordless failure is audited with reason=no_password",
            )
        }
    }

    @Test
    fun `a passwordless account counts toward the per-account lockout`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("pwl-lock")
        TestUsers.seedPasswordless(email)
        val client = jsonClient()

        repeat(3) { assertEquals(HttpStatusCode.Unauthorized, client.login(email, "guess-$it-password").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.login(email, "guess-again-123").status)
    }

    @Test
    fun `password reset for a passwordless account answers 202 sends nothing and mints no token`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pwl-reset")
        val userId = TestUsers.seedPasswordless(email)
        val mail = LogCapture("ch.nokillswit.mail")
        try {
            withAuditCapture { audit ->
                val response = jsonClient().postJson("/api/v1/password-reset", PasswordResetRequest(email))
                assertEquals(HttpStatusCode.Accepted, response.status)
                val skipped = audit.awaitEvent {
                    it.message == "password_reset.skipped" && it.hasKeyValue("userId", userId.toLong())
                }
                assertNotNull(skipped, "the skip is audited")
                assertTrue(skipped.hasKeyValue("reason", "no_password"))
                assertNull(
                    audit.events.firstOrNull { it.message == "password_reset.link_sent" && it.hasKeyValue("email", email) },
                    "no link is sent",
                )
            }
            assertNull(mail.events.firstOrNull { "To: $email" in it.formattedMessage }, "no email leaves")
            assertTrue(resetTokenHashes(userId).isEmpty(), "no reset token row is minted")
        } finally {
            mail.detach()
        }
    }

    @Test
    fun `self-service password change for a passwordless account is a typed 409`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pwl-self")
        val id = TestUsers.seed(email = email, password = "initial-pass-123", role = UserRole.USER)
        val client = authedClient(email, "initial-pass-123")
        // The session survives the hash going away (auth_version is untouched).
        TestUsers.clearPassword(id)

        val response = client.putJson(
            "/api/v1/users/$id/password",
            PasswordUpdateRequest(password = "brand-new-pass-1", currentPassword = "initial-pass-123"),
        )
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("urn:toadie:password-not-set", response.body<ProblemDetail>().type)
        assertNull(TestUsers.rawPasswordHash(id), "nothing was written")
    }

    @Test
    fun `an admin can set a password on a passwordless account and it then signs in`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("pwl-admin", UserRole.ADMIN)
        val email = uniqueEmail("pwl-break-glass")
        val id = TestUsers.seedPasswordless(email)

        val before = admin.get("/api/v1/users/$id").body<UserResponse>()
        assertTrue(before.sso)
        assertFalse(before.hasPassword)

        val set = admin.putJson("/api/v1/users/$id/password", PasswordUpdateRequest(password = "break-glass-pass-1"))
        assertEquals(HttpStatusCode.NoContent, set.status)
        assertNotNull(TestUsers.rawPasswordHash(id))

        val login = jsonClient().login(email, "break-glass-pass-1")
        assertEquals(HttpStatusCode.OK, login.status)
        assertTrue(login.body<LoginResponse>().hasPassword)
        val after = admin.get("/api/v1/users/$id").body<UserResponse>()
        assertTrue(after.sso, "the link survives the password")
        assertTrue(after.hasPassword)
    }

    @Test
    fun `users list and detail report sso and hasPassword without failing on a NULL hash`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("pwl-list-admin", UserRole.ADMIN)
        val ssoEmail = uniqueEmail("pwl-list-sso")
        val plainEmail = uniqueEmail("pwl-list-plain")
        val ssoId = TestUsers.seedPasswordless(ssoEmail)
        val plainId = TestUsers.seed(email = plainEmail, password = "plain-pass-123", role = UserRole.USER)

        val sso = admin.get("/api/v1/users?email=$ssoEmail").body<UserPageResponse>().items.single()
        assertEquals(ssoId, sso.id)
        assertTrue(sso.sso)
        assertFalse(sso.hasPassword)

        val plain = admin.get("/api/v1/users/$plainId").body<UserResponse>()
        assertFalse(plain.sso)
        assertTrue(plain.hasPassword)
    }

    @Test
    fun `a normal login reports hasPassword true`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("pwl-normal")
        TestUsers.seed(email = email, password = "normal-pass-123")
        val response = jsonClient().login(email, "normal-pass-123")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body<LoginResponse>().hasPassword)
    }

    @Test
    fun `the database refuses a half identity pair and a service account with an identity`() = testApplication {
        usePostgresTestcontainer()
        val id = TestUsers.seedPasswordless(uniqueEmail("pwl-check"))
        val half = runCatching { TestUsers.forceSsoIdentity(id, "https://idp.test/x", null) }.exceptionOrNull()
        assertCauseMentions(half, "ck_users_sso_identity_pair")

        val name = "svc-${UUID.randomUUID()}"
        val serviceId = TestUsers.seedServiceAccount(name = name, email = "$name@toadie.invalid")
        val linked = runCatching { TestUsers.forceSsoIdentity(serviceId, "https://idp.test/x", "sub-1") }.exceptionOrNull()
        assertCauseMentions(linked, "ck_users_service_account_no_sso")
    }

    @Test
    fun `the identity index is full - a soft-deleted holder keeps its link`() = testApplication {
        usePostgresTestcontainer()
        val issuer = "https://idp.test/${UUID.randomUUID()}"
        val subject = UUID.randomUUID().toString()
        val first = TestUsers.seedPasswordless(uniqueEmail("pwl-uq-1"), issuer = issuer, subject = subject)

        val live = runCatching { TestUsers.seedPasswordless(uniqueEmail("pwl-uq-2"), issuer = issuer, subject = subject) }
            .exceptionOrNull()
        assertCauseMentions(live, "uq_users_sso_identity")

        TestUsers.softDelete(first)
        val afterDelete = runCatching {
            TestUsers.seedPasswordless(uniqueEmail("pwl-uq-3"), issuer = issuer, subject = subject)
        }.exceptionOrNull()
        assertCauseMentions(afterDelete, "uq_users_sso_identity")
    }

    private fun assertCauseMentions(thrown: Throwable?, constraint: String) {
        assertNotNull(thrown, "the database should have refused the write ($constraint)")
        assertTrue(
            generateSequence(thrown) { it.cause }.any { it.message?.contains(constraint) == true },
            "expected $constraint in the cause chain, got: $thrown",
        )
    }
}
