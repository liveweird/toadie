package ch.nokillswit.auth

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `an oversized population is capped at MAX_TRACKED after maintenance runs`() {
        val now = 1_000L
        // threshold=100 so a single failure per unique key never trips the lockout — every
        // entry stays unlocked (lockedUntil = 0) and so is eligible for hard-capacity eviction.
        val throttle = LoginThrottle(threshold = 100, lockoutMillis = 60_000, clock = { now })
        val extra = 500
        repeat(MAX_TRACKED + extra) { i ->
            throttle.recordFailure("spray-$i@test")
        }
        assertTrue(
            throttle.trackedCount <= MAX_TRACKED,
            "expected at most $MAX_TRACKED tracked entries, got ${throttle.trackedCount}",
        )
    }

    @Test
    fun `an actively locked-out entry survives hard-capacity eviction even when oldest`() {
        var now = 1_000L
        // threshold=3 so the target account can be deliberately tripped into lockout, while
        // every filler below only fails ONCE (below threshold) — unlocked, and so eligible for
        // hard-capacity eviction.
        val throttle = LoginThrottle(threshold = 3, lockoutMillis = 60_000, clock = { now })

        // Trip the lockout on one account first, so it is the OLDEST-touched entry (by
        // lastTouched) once the rest are added below.
        throttle.recordFailure("locked@test")
        throttle.recordFailure("locked@test")
        assertTrue(throttle.recordFailure("locked@test"), "the third failure trips the lockout")
        assertTrue(throttle.isLocked("locked@test"))

        // Advance the clock so every filler is touched LATER than the locked account, making it
        // the prime eviction target by recency alone — yet it must be skipped in favor of the
        // newer, unlocked filler entries.
        now += 1
        repeat(MAX_TRACKED + 500) { i ->
            throttle.recordFailure("filler-$i@test")
        }

        assertTrue(throttle.isLocked("locked@test"), "an active lockout must never be evicted to make room")
        assertTrue(throttle.trackedCount <= MAX_TRACKED)
    }

    @Test
    fun `a small stale population is reclaimed once the fixed cadence is hit, not only when oversized`() {
        var now = 1_000L
        val throttle = LoginThrottle(threshold = 1, lockoutMillis = 60_000, clock = { now })

        // A handful of entries — well under MAX_TRACKED — that will go stale. Asserted via
        // trackedCount rather than isLocked(), since isLocked() itself opportunistically expires
        // a single stale lock on access — the point here is that the size-independent MAINTENANCE
        // sweep reclaims them, not that a later read happens to notice they expired.
        repeat(5) { i -> throttle.recordFailure("stale-$i@test") }
        assertEquals(5, throttle.trackedCount)

        // Advance the clock well past the lockout window so every entry above is stale AND
        // unlocked, then make enough further calls to cross the fixed 256-call cadence boundary
        // (the global call counter keeps advancing across the whole throttle instance, so this
        // loop is guaranteed to cross a multiple of 256 partway through).
        now += 120_000
        repeat(CALLS_PER_PRUNE.toInt()) { i -> throttle.recordFailure("cadence-$i@test") }

        assertEquals(
            CALLS_PER_PRUNE.toInt(),
            throttle.trackedCount,
            "the 5 stale entries should have been swept by the cadence-driven prune, " +
                "leaving only the ${CALLS_PER_PRUNE.toInt()} fresh cadence-* entries",
        )
    }

    private companion object {
        const val MAX_TRACKED = 10_000
        const val CALLS_PER_PRUNE = 256L
    }
}
