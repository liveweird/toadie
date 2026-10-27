package ch.nokillswit.auth.sso

/**
 * The bounded set of attempt `state`s a callback has already redeemed, so ONE reusable attempt cookie cannot drive more
 * than one token-endpoint request (`.claude/docs/security.md` "SSO login (OIDC)"). The cookie itself stays stateless; only
 * a state that passed the cookie's signature, expiry and state comparison is ever recorded, so forged cookies cannot grow
 * this set. Genuine cookies minted through `/start` can (one entry per callback, throttled by the `sso` bucket), which
 * only ever costs eviction, never a refusal. An entry lives until its cookie's own expiry, the set holds at most
 * [maxTracked] entries, and when it is full the OLDEST entry is dropped — eviction never refuses a sign-in (it can only
 * let the evicted attempt's cookie be replayed once more inside its ten minutes, which is the pre-existing exposure).
 * Instance-local like the hand-offs: a restart forgets it.
 */
class SsoSeenStates(
    private val maxTracked: Int = MAX_TRACKED,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Insertion-ordered by callback time. Expiry follows each cookie's start time, so the head is only roughly the first to
    // expire: the head-only purge can leave an expired entry behind a live one, which costs at most an early eviction.
    private val seen = LinkedHashMap<String, Long>()

    /** True the first time [state] is presented (it is now recorded until [expiresAt]); false for a repeat. */
    fun firstUse(state: String, expiresAt: Long): Boolean = synchronized(seen) {
        val now = clock()
        val iterator = seen.entries.iterator()
        while (iterator.hasNext() && iterator.next().value <= now) iterator.remove()
        if (state in seen) return false
        if (seen.size >= maxTracked) seen.remove(seen.keys.first())
        seen[state] = expiresAt
        true
    }

    private companion object {
        const val MAX_TRACKED = 10_000
    }
}
