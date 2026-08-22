package ch.nokillswit.auth

import java.util.concurrent.ConcurrentHashMap

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
        if (s.lockedUntil in 1..clock()) {
            states.remove(k, s) // lock expired — fresh start
            return false
        }
        return s.lockedUntil > clock()
    }

    /** Record a failed attempt; returns true when this failure trips the lockout. */
    fun recordFailure(email: String): Boolean {
        pruneIfOversized()
        val now = clock()
        val next = states.compute(key(email)) { _, cur ->
            val failures = (cur?.failures ?: 0) + 1
            if (failures >= threshold) State(0, now + lockoutMillis, now)
            else State(failures, 0, now)
        }
        return next != null && next.lockedUntil > now
    }

    fun recordSuccess(email: String) {
        states.remove(key(email))
    }

    // Memory bound: an attacker spraying distinct emails must not grow the map without limit.
    // Cheap opportunistic prune of stale entries once the map gets large.
    private fun pruneIfOversized() {
        if (states.size <= MAX_TRACKED) return
        val cutoff = clock() - lockoutMillis
        states.entries.removeIf { it.value.lastTouched < cutoff && it.value.lockedUntil <= clock() }
    }

    private companion object {
        const val MAX_TRACKED = 10_000
    }
}
