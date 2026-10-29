package ch.nokillswit

import ch.nokillswit.auth.MAX_MFA_CHALLENGE_ID_LENGTH
import ch.nokillswit.auth.MAX_MFA_CODE_LENGTH
import ch.nokillswit.auth.MfaChallenges
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Unit tests for the in-memory email-MFA challenge store (deterministic via an injected clock). */
class MfaChallengesTest {

    private var now = 1_000_000L
    private fun store(ttlMillis: Long = 300_000, maxAttempts: Int = 5, maxTracked: Int = 10_000) =
        MfaChallenges(ttlMillis, maxAttempts, maxTracked) { now }

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
        // The route's length bounds are exactly what this store issues (a longer value is refused before the lock).
        assertEquals(MAX_MFA_CHALLENGE_ID_LENGTH, a.challengeId.length)
        assertEquals(MAX_MFA_CODE_LENGTH, a.code.length)
        // Both stay independently verifiable (repeated logins may coexist within the TTL).
        assertIs<MfaChallenges.Outcome.Success>(s.verify(b.challengeId, b.code))
        assertIs<MfaChallenges.Outcome.Success>(s.verify(a.challengeId, a.code))
    }

    @Test
    fun `capacity is a hard live-entry ceiling and expiry reopens admission`() {
        val s = store(ttlMillis = 1_000, maxTracked = 2)
        val stale = listOf(s.issue(1u), s.issue(2u))
        assertFailsWith<MfaChallenges.CapacityExceededException> { s.issue(3u) }

        now += 2_000
        // Admission deterministically prunes expiry before checking capacity.
        val fresh = s.issue(99u)
        stale.forEach {
            assertEquals(
                "unknown_challenge",
                (s.verify(it.challengeId, it.code) as MfaChallenges.Outcome.Failure).reason,
            )
        }
        assertIs<MfaChallenges.Outcome.Success>(s.verify(fresh.challengeId, fresh.code))
    }

    @Test
    fun `concurrent admissions cannot overshoot capacity`() {
        val capacity = 7
        val contenders = 32
        val s = store(maxTracked = capacity)
        val ready = CountDownLatch(contenders)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(contenders)
        try {
            val futures = (1..contenders).map { userId ->
                executor.submit<Result<MfaChallenges.IssuedChallenge>> {
                    ready.countDown()
                    start.await()
                    runCatching { s.issue(userId.toUInt()) }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "all contenders should reach the admission gate")
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(capacity, results.count { it.isSuccess })
            assertEquals(
                contenders - capacity,
                results.count { it.exceptionOrNull() is MfaChallenges.CapacityExceededException },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    /** Runs [contenders] copies of [action] released together by one latch; returns their results in order. */
    private fun <T> race(contenders: Int, action: () -> T): List<T> {
        val ready = CountDownLatch(contenders)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(contenders)
        try {
            val futures = (1..contenders).map {
                executor.submit<T> {
                    ready.countDown()
                    start.await()
                    action()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "all contenders should reach the gate")
            start.countDown()
            return futures.map { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent correct submissions - exactly one wins, the rest find no challenge`() {
        val s = store()
        val issued = s.issue(42u)
        val outcomes = race(32) { s.verify(issued.challengeId, issued.code) }
        assertEquals(1, outcomes.count { it is MfaChallenges.Outcome.Success })
        assertEquals(31, outcomes.count { (it as? MfaChallenges.Outcome.Failure)?.reason == "unknown_challenge" })
    }

    @Test
    fun `concurrent wrong guesses never exceed the attempt cap`() {
        val s = store(maxAttempts = 5)
        val issued = s.issue(42u)
        val wrong = if (issued.code == "000000") "111111" else "000000"
        val reasons = race(32) { (s.verify(issued.challengeId, wrong) as MfaChallenges.Outcome.Failure).reason }
        assertEquals(4, reasons.count { it == "wrong_code" })
        assertEquals(1, reasons.count { it == "too_many_attempts" })
        assertEquals(27, reasons.count { it == "unknown_challenge" })
        // The challenge is gone: even the right code cannot succeed now.
        assertEquals(
            "unknown_challenge",
            (s.verify(issued.challengeId, issued.code) as MfaChallenges.Outcome.Failure).reason,
        )
    }
}
