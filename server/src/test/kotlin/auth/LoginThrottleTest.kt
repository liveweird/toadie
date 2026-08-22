package ch.nokillswit.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure unit tests for the in-memory per-account throttle (no server, no database). */
class LoginThrottleTest {

    @Test
    fun `locks after threshold failures and unlocks when the window elapses`() {
        var now = 1_000L
        val throttle = LoginThrottle(threshold = 3, lockoutMillis = 60_000, clock = { now })
        val email = "a@test"

        assertFalse(throttle.recordFailure(email))
        assertFalse(throttle.recordFailure(email))
        assertTrue(throttle.recordFailure(email), "the third failure trips the lockout")
        assertTrue(throttle.isLocked(email))

        now += 60_001
        assertFalse(throttle.isLocked(email), "an expired lock clears on the way")
    }

    @Test
    fun `a success clears the counter`() {
        val throttle = LoginThrottle(threshold = 3, lockoutMillis = 60_000, clock = { 1_000L })
        val email = "b@test"

        throttle.recordFailure(email)
        throttle.recordFailure(email)
        throttle.recordSuccess(email)
        assertFalse(throttle.recordFailure(email), "the counter restarted after the success")
        assertFalse(throttle.isLocked(email))
    }

    @Test
    fun `keys fold email case and padding into one bucket`() {
        val now = 1_000L
        val throttle = LoginThrottle(threshold = 2, lockoutMillis = 60_000, clock = { now })

        throttle.recordFailure("C@Test")
        assertTrue(throttle.recordFailure("  c@test "), "case/padding variants share the bucket")
        assertTrue(throttle.isLocked("c@TEST"))
    }

    @Test
    fun `unknown accounts throttle exactly like existing ones`() {
        val throttle = LoginThrottle(threshold = 1, lockoutMillis = 60_000, clock = { 0 })
        assertTrue(throttle.recordFailure("ghost@nowhere"))
        assertTrue(throttle.isLocked("ghost@nowhere"))
    }
}
