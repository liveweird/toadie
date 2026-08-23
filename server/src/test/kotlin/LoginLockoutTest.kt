package ch.nokillswit

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The per-account lockout (auth/LoginThrottle.kt wired in AuthRoutes): after `threshold`
 * consecutive failures for one email, further attempts — even with the correct password —
 * answer 429 for the lockout window. Independent of the per-IP rate limit.
 */
class LoginLockoutTest {

    @Test
    fun `threshold consecutive failures lock the account - even the right password answers 429`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("locked")
        TestUsers.seed(email = email, password = "right-pw")
        val client = jsonClient()

        repeat(3) {
            val attempt = client.login(email, "wrong-pw")
            assertEquals(HttpStatusCode.Unauthorized, attempt.status)
        }

        val lockedOut = client.login(email, "right-pw")
        assertEquals(HttpStatusCode.TooManyRequests, lockedOut.status)
        // The lockout's account-specific detail must survive StatusPages' generic 429 handler.
        assertContains(lockedOut.bodyAsText(), "failed login attempts")
    }

    @Test
    fun `a successful login resets the failure counter`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("reset")
        TestUsers.seed(email = email, password = "right-pw")
        val client = jsonClient()

        repeat(2) {
            client.login(email, "wrong-pw")
        }
        val success = client.login(email, "right-pw")
        assertEquals(HttpStatusCode.OK, success.status)

        // The counter restarted: two more failures stay under the threshold of 3.
        repeat(2) {
            val attempt = client.login(email, "wrong-pw")
            assertEquals(HttpStatusCode.Unauthorized, attempt.status)
        }
    }

    @Test
    fun `the lockout bucket is shared across email case variants`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("variant")
        TestUsers.seed(email = email, password = "right-pw")
        val client = jsonClient()

        for (variant in listOf(email, email.uppercase(), " $email ")) {
            client.login(variant, "wrong-pw")
        }
        val lockedOut = client.login(email, "right-pw")
        assertEquals(HttpStatusCode.TooManyRequests, lockedOut.status)
    }
}
