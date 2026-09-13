package ch.nokillswit

import ch.nokillswit.auth.PasswordResetThrottle
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `an oversized population is capped at MAX_TRACKED after maintenance runs`() {
        val t = throttle()
        repeat(MAX_TRACKED + 500) { i -> t.tryAcquire("spray-$i@test") }
        assertTrue(
            t.trackedCount <= MAX_TRACKED,
            "expected at most $MAX_TRACKED tracked entries, got ${t.trackedCount}",
        )
    }

    @Test
    fun `a small stale population is reclaimed once the fixed cadence is hit, not only when oversized`() {
        val t = throttle(minIntervalMillis = 60_000)

        // A handful of entries — well under MAX_TRACKED — that will go stale. Checked via
        // trackedCount, not tryAcquire()'s own behavior, since acquiring again after the
        // interval elapses would silently refresh the timestamp rather than proving the
        // size-independent maintenance sweep reclaimed the slot.
        repeat(5) { i -> t.tryAcquire("stale-$i@x") }
        assertEquals(5, t.trackedCount)

        // Advance past the interval so every entry above is stale, then make enough further
        // calls to cross the fixed 256-call cadence boundary (the global call counter keeps
        // advancing across the whole throttle instance, so this loop is guaranteed to cross a
        // multiple of 256 partway through).
        now += 120_000
        repeat(CALLS_PER_PRUNE) { i -> t.tryAcquire("cadence-$i@x") }

        assertEquals(
            CALLS_PER_PRUNE,
            t.trackedCount,
            "the 5 stale entries should have been swept by the cadence-driven prune, " +
                "leaving only the $CALLS_PER_PRUNE fresh cadence-* entries",
        )
    }

    private companion object {
        const val MAX_TRACKED = 10_000
        const val CALLS_PER_PRUNE = 256
    }
}
