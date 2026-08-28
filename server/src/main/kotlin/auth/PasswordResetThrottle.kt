package ch.nokillswit.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-email throttle for the self-service password reset: at most one request per submitted
 * email per [minIntervalMillis] — uniformly, whether or not the account exists (so the 429
 * carries no enumeration signal). Sibling of [LoginThrottle]: in-memory and per-instance by
 * design (single-replica deployment; a restart only resets the throttle).
 */
class PasswordResetThrottle(
    private val minIntervalMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastRequestAt = ConcurrentHashMap<String, Long>()

    private fun key(email: String) = email.trim().lowercase()

    /** Atomically claims a slot for this email; false while the previous one is still fresh. */
    fun tryAcquire(email: String): Boolean {
        pruneIfOversized()
        val now = clock()
        var acquired = false
        lastRequestAt.compute(key(email)) { _, last ->
            if (last != null && now - last < minIntervalMillis) {
                last // still throttled — keep the original timestamp
            } else {
                acquired = true
                now
            }
        }
        return acquired
    }

    // Memory bound: spraying distinct emails must not grow the map without limit (same
    // opportunistic prune as LoginThrottle).
    private fun pruneIfOversized() {
        if (lastRequestAt.size <= MAX_TRACKED) return
        val cutoff = clock() - minIntervalMillis
        lastRequestAt.entries.removeIf { it.value < cutoff }
    }

    private companion object {
        const val MAX_TRACKED = 10_000
    }
}
