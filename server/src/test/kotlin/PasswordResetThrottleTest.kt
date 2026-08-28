package ch.nokillswit

import ch.nokillswit.auth.PasswordResetThrottle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for the per-email password-reset throttle (deterministic via an injected clock). */
class PasswordResetThrottleTest {

    private var now = 1_000_000L
    private fun throttle(minIntervalMillis: Long = 60_000) =
        PasswordResetThrottle(minIntervalMillis) { now }

    @Test
    fun `the first request acquires, an immediate second one does not`() {
        val t = throttle()
        assertTrue(t.tryAcquire("a@x"))
        assertFalse(t.tryAcquire("a@x"))
    }

    @Test
    fun `the slot frees up after the interval`() {
        val t = throttle(minIntervalMillis = 60_000)
        assertTrue(t.tryAcquire("a@x"))
        now += 59_999
        assertFalse(t.tryAcquire("a@x"), "still inside the interval")
        now += 1
        assertTrue(t.tryAcquire("a@x"), "interval elapsed")
    }

    @Test
    fun `a rejected attempt does not extend the wait`() {
        val t = throttle(minIntervalMillis = 60_000)
        assertTrue(t.tryAcquire("a@x"))
        now += 30_000
        assertFalse(t.tryAcquire("a@x"))
        now += 30_000 // 60s after the ORIGINAL acquire, not the rejected retry
        assertTrue(t.tryAcquire("a@x"))
    }

    @Test
    fun `emails are tracked independently and the key is normalized`() {
        val t = throttle()
        assertTrue(t.tryAcquire("a@x"))
        assertTrue(t.tryAcquire("b@x"), "different email is unaffected")
        assertFalse(t.tryAcquire("  A@X  "), "same email spelled differently shares the slot")
    }
}
