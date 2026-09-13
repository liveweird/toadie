package ch.nokillswit.auth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-account login throttle: after [threshold] consecutive failures for the same submitted
 * email, further attempts are rejected for [lockoutMillis] — regardless of whether the account
 * exists (so it leaks nothing), and independent of the per-IP rate limit (which an attacker can
 * sidestep by rotating hosts).
 *
 * In-memory and per-instance by design: the deployment runs a single replica, and losing the
 * counters on restart only resets the throttle, never grants access. A successful login clears
 * the account's counter.
 */
class LoginThrottle(
    private val threshold: Int,
    private val lockoutMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class State(val failures: Int, val lockedUntil: Long, val lastTouched: Long)

    private val states = ConcurrentHashMap<String, State>()

    private fun key(email: String) = email.trim().lowercase()

    /** True while the account is locked out (expired locks are pruned on the way). */
    fun isLocked(email: String): Boolean {
        val k = key(email)
        val s = states[k] ?: return false
        // One clock read for both branches — re-reading could straddle the expiry instant.
        val now = clock()
        if (s.lockedUntil in 1..now) {
            states.remove(k, s) // lock expired — fresh start
            return false
        }
        return s.lockedUntil > now
    }

    /** Record a failed attempt; returns true when this failure trips the lockout. */
    fun recordFailure(email: String): Boolean {
        val now = clock()
        val next = states.compute(key(email)) { _, cur ->
            val failures = (cur?.failures ?: 0) + 1
            if (failures >= threshold) State(0, now + lockoutMillis, now)
            else State(failures, 0, now)
        }
        maintain() // after the insert, so the map never ends a call above MAX_TRACKED
        return next != null && next.lockedUntil > now
    }

    fun recordSuccess(email: String) {
        states.remove(key(email))
    }

    /** Test-only visibility into the tracked population size (capacity-eviction pins). */
    internal val trackedCount: Int
        get() = states.size

    // Memory bound: an attacker spraying distinct emails must not grow the map without limit.
    // A slowly-growing population that never exceeds MAX_TRACKED in one shot must still be
    // reclaimed, so the stale-entry sweep runs on a FIXED CADENCE (every CALLS_PER_PRUNE calls)
    // regardless of current size, not only once the map is already oversized. If the map is
    // STILL oversized afterwards (a burst, or entries mid-lockout that stale-pruning can't
    // touch), a hard-capacity eviction removes the oldest-touched, non-locked-out entries down
    // to LOW_WATER (not merely to the cap, so the sort runs once per ~1,000 inserts under a
    // spray rather than on every request) — an active lockout is never evicted just to make room.
    private val callCount = AtomicLong(0)

    private fun maintain() {
        // Increment unconditionally on every call so the cadence stays accurate even while
        // the map is oversized (an `||` short-circuit would otherwise skip the count).
        val cadenceHit = callCount.incrementAndGet() % CALLS_PER_PRUNE == 0L
        if (cadenceHit || states.size > MAX_TRACKED) {
            pruneStale()
        }
        if (states.size > MAX_TRACKED) {
            evictOldestUnlocked()
        }
    }

    private fun pruneStale() {
        val now = clock()
        val cutoff = now - lockoutMillis
        states.entries.removeIf { it.value.lastTouched < cutoff && it.value.lockedUntil <= now }
    }

    private fun evictOldestUnlocked() {
        val now = clock()
        val excess = states.size - LOW_WATER
        if (excess <= 0) return
        states.entries
            .filter { it.value.lockedUntil <= now }
            .sortedBy { it.value.lastTouched }
            .take(excess)
            .forEach { states.remove(it.key, it.value) }
    }

    private companion object {
        const val MAX_TRACKED = 10_000
        const val LOW_WATER = 9_000
        const val CALLS_PER_PRUNE = 256L
    }
}
