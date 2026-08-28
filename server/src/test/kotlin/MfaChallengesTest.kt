package ch.nokillswit

import ch.nokillswit.auth.MfaChallenges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Unit tests for the in-memory email-MFA challenge store (deterministic via an injected clock). */
class MfaChallengesTest {

    private var now = 1_000_000L
    private fun store(ttlMillis: Long = 300_000, maxAttempts: Int = 5) =
        MfaChallenges(ttlMillis, maxAttempts) { now }

    @Test
    fun `a correct code succeeds exactly once - the challenge is single-use`() {
        val s = store()
        val issued = s.issue(42u)
        assertEquals(6, issued.code.length)
        assertTrue(issued.code.all { it.isDigit() })
        assertEquals(now + 300_000, issued.expiresAt)

        val outcome = s.verify(issued.challengeId, issued.code)
        assertIs<MfaChallenges.Outcome.Success>(outcome)
        assertEquals(42u, outcome.userId)

        // Replay of the consumed challenge is indistinguishable from an unknown one.
        val replay = s.verify(issued.challengeId, issued.code)
        assertIs<MfaChallenges.Outcome.Failure>(replay)
        assertEquals("unknown_challenge", replay.reason)
    }

    @Test
    fun `an expired challenge fails and is dropped`() {
        val s = store(ttlMillis = 60_000)
        val issued = s.issue(7u)
        now += 60_000
        val outcome = s.verify(issued.challengeId, issued.code)
        assertIs<MfaChallenges.Outcome.Failure>(outcome)
        assertEquals("expired", outcome.reason)
        // The drop is permanent — a later attempt sees unknown, not expired.
        assertEquals(
            "unknown_challenge",
            (s.verify(issued.challengeId, issued.code) as MfaChallenges.Outcome.Failure).reason,
        )
    }

    @Test
    fun `wrong codes count toward the attempt cap and exhausting it kills the challenge`() {
        val s = store(maxAttempts = 3)
        val issued = s.issue(7u)
        assertEquals("wrong_code", (s.verify(issued.challengeId, "x") as MfaChallenges.Outcome.Failure).reason)
        assertEquals("wrong_code", (s.verify(issued.challengeId, "x") as MfaChallenges.Outcome.Failure).reason)
        assertEquals(
            "too_many_attempts",
            (s.verify(issued.challengeId, "x") as MfaChallenges.Outcome.Failure).reason,
        )
        // Even the correct code no longer works — the challenge is gone.
        assertEquals(
            "unknown_challenge",
            (s.verify(issued.challengeId, issued.code) as MfaChallenges.Outcome.Failure).reason,
        )
    }

    @Test
    fun `an unknown challenge id fails uniformly`() {
        val s = store()
        val outcome = s.verify("no-such-challenge", "123456")
        assertIs<MfaChallenges.Outcome.Failure>(outcome)
        assertEquals("unknown_challenge", outcome.reason)
    }

    @Test
    fun `challenge ids are unique and opaque`() {
        val s = store()
        val a = s.issue(1u)
        val b = s.issue(1u)
        assertNotEquals(a.challengeId, b.challengeId)
        assertEquals(32, a.challengeId.length)
        // Both stay independently verifiable (repeated logins may coexist within the TTL).
        assertIs<MfaChallenges.Outcome.Success>(s.verify(b.challengeId, b.code))
        assertIs<MfaChallenges.Outcome.Success>(s.verify(a.challengeId, a.code))
    }

    @Test
    fun `the store prunes expired entries once oversized instead of growing without bound`() {
        val s = store(ttlMillis = 1_000)
        val stale = (1..10_001).map { s.issue(it.toUInt()) }
        now += 2_000
        // The next issue triggers the prune; every stale entry is now unknown.
        val fresh = s.issue(99u)
        assertEquals(
            "unknown_challenge",
            (s.verify(stale.first().challengeId, stale.first().code) as MfaChallenges.Outcome.Failure).reason,
        )
        assertIs<MfaChallenges.Outcome.Success>(s.verify(fresh.challengeId, fresh.code))
    }
}
