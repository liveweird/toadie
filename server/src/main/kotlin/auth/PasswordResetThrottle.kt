package ch.nokillswit.auth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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

    /** Test-only visibility into the tracked population size (capacity-eviction pins). */
    internal val trackedCount: Int
        get() = lastRequestAt.size

    /** Atomically claims a slot for this email; false while the previous one is still fresh. */
    fun tryAcquire(email: String): Boolean {
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
        maintain() // after the insert, so the map never ends a call above MAX_TRACKED
        return acquired
    }

    // Memory bound: spraying distinct emails must not grow the map without limit (the
    // LoginThrottle maintenance shape). The stale-entry sweep runs on a FIXED CADENCE (every
    // CALLS_PER_PRUNE calls) regardless of current size, not only once already oversized; if
    // still oversized afterwards, a hard-capacity eviction removes the oldest timestamps down
    // to LOW_WATER (not merely to the cap, so the sort runs once per ~1,000 inserts under a
    // spray rather than on every request; there is no lockout concept here — every entry is eligible).
    private val callCount = AtomicLong(0)

    private fun maintain() {
        val cadenceHit = callCount.incrementAndGet() % CALLS_PER_PRUNE == 0L
        if (cadenceHit || lastRequestAt.size > MAX_TRACKED) {
            pruneStale()
        }
        if (lastRequestAt.size > MAX_TRACKED) {
            evictOldest()
        }
    }

    private fun pruneStale() {
        val cutoff = clock() - minIntervalMillis
        lastRequestAt.entries.removeIf { it.value < cutoff }
    }

    private fun evictOldest() {
        val excess = lastRequestAt.size - LOW_WATER
        if (excess <= 0) return
        lastRequestAt.entries
            .sortedBy { it.value }
            .take(excess)
            .forEach { lastRequestAt.remove(it.key, it.value) }
    }

    private companion object {
        const val MAX_TRACKED = 10_000
        const val LOW_WATER = 9_000
        const val CALLS_PER_PRUNE = 256L
    }
}
