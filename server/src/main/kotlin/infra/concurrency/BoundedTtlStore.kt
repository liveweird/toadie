package ch.nokillswit.infra.concurrency

/** What a [BoundedTtlStore] does when an admission finds it at capacity after the expired entries are swept. */
enum class WhenFull {
    /** Refuse the admission ([BoundedTtlStore.Admission.FULL] / a null [BoundedTtlStore.issue]); live entries are never displaced. */
    REFUSE,

    /** Drop the OLDEST live entry (admission order) to make room; an admission is never refused for capacity. */
    EVICT_OLDEST,
}

/**
 * The one bounded, expiring, keyed in-memory store: the shape `MfaChallenges`, `SsoHandoffs` and `SsoSeenStates` each
 * hand-rolled — a map under one lock, a hard ceiling [capacity], an expiry sweep at admission, and either refusal or
 * oldest-first eviction at the ceiling ([whenFull]). Each of those three is a thin policy over this class: the policy owns
 * key generation, the expiry verdict on a read, its exception type and its audit reasons; this class owns the map, the lock
 * and the sweep. `.claude/docs/security.md` "Instance-local TTL stores".
 *
 * Every public method is ONE critical section on a single monitor (the `LinkedHashMap` itself), so the sweep, the capacity
 * decision, key generation and insertion are atomic, and [update] / [remove] are serialized against admission: concurrent
 * admissions cannot overshoot [capacity], and a single-use entry is handed to exactly one caller. Expiry is judged by
 * [expiresAt] against one [clock] reading per sweep; [update] and [remove] do NOT sweep, so the policy still sees (and names)
 * an expired entry it is asked about. Eviction order is admission order: a replacement stored through [update] keeps its
 * position.
 *
 * Instance-local by design (the throttles' posture): a restart forgets everything, and the deployment runs a single replica
 * (BACKLOG "Remove instance-local state before allowing multiple app replicas"). This class is the seam a shared backend
 * implements once.
 */
class BoundedTtlStore<K : Any, V : Any>(
    private val capacity: Int,
    private val whenFull: WhenFull,
    private val clock: () -> Long,
    private val expiresAt: (V) -> Long,
) {
    enum class Admission { ADMITTED, DUPLICATE, FULL }

    // Insertion-ordered; also the monitor, so there is exactly one lock and no second map to drift from it.
    private val entries = LinkedHashMap<K, V>()

    /**
     * Stores [value] under the caller-chosen [key]: [Admission.DUPLICATE] when a LIVE entry already holds the key (nothing
     * changes, nothing is evicted), [Admission.FULL] when [WhenFull.REFUSE] and the store is at capacity, otherwise
     * [Admission.ADMITTED] (after evicting the oldest entry under [WhenFull.EVICT_OLDEST]).
     */
    fun admit(key: K, value: V): Admission = synchronized(entries) {
        sweep()
        if (key in entries) return Admission.DUPLICATE
        if (entries.size >= capacity) {
            if (whenFull == WhenFull.REFUSE) return Admission.FULL
            evictEldest()
        }
        entries[key] = value
        Admission.ADMITTED
    }

    /**
     * Stores [value] under a fresh key from [newKey] (regenerated while it collides with a live entry) and returns that key,
     * or null when [WhenFull.REFUSE] and the store is at capacity. The capacity decision precedes key generation.
     */
    fun issue(value: V, newKey: () -> K): K? = synchronized(entries) {
        sweep()
        if (entries.size >= capacity) {
            if (whenFull == WhenFull.REFUSE) return null
            evictEldest()
        }
        var key: K
        do {
            key = newKey()
        } while (key in entries)
        entries[key] = value
        key
    }

    /**
     * Atomic read-modify-write on [key]: [block] gets the current value (null when absent; an EXPIRED value is passed as is,
     * the policy judges expiry) and returns the replacement (null removes the entry) paired with the result. Insertion is
     * admission-only: offering a replacement for an absent key is a programming error.
     */
    fun <R> update(key: K, block: (V?) -> Pair<V?, R>): R = synchronized(entries) {
        val current = entries[key]
        val (replacement, result) = block(current)
        if (replacement == null) {
            entries.remove(key)
        } else {
            check(current != null) { "update cannot insert; use admit or issue" }
            entries[key] = replacement
        }
        result
    }

    /** Removes and returns the value under [key], expired or not: exactly one concurrent caller receives it. */
    fun remove(key: K): V? = synchronized(entries) { entries.remove(key) }

    /** Entry count including not-yet-swept expired entries (a test seam). */
    val size: Int get() = synchronized(entries) { entries.size }

    private fun sweep() {
        val now = clock()
        entries.values.removeIf { expiresAt(it) <= now }
    }

    private fun evictEldest() {
        entries.remove(entries.keys.first())
    }
}
