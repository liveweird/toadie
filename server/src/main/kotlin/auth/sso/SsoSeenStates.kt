package ch.nokillswit.auth.sso

import ch.nokillswit.infra.concurrency.BoundedTtlStore
import ch.nokillswit.infra.concurrency.BoundedTtlStore.Admission
import ch.nokillswit.infra.concurrency.WhenFull

/**
 * The bounded set of attempt `state`s a callback has already redeemed, so ONE reusable attempt cookie cannot drive more
 * than one token-endpoint request (`.claude/docs/security.md` "SSO login (OIDC)"). The cookie itself stays stateless; only
 * a state that passed the cookie's signature, expiry and state comparison is ever recorded, so forged cookies cannot grow
 * this set. Genuine cookies minted through `/start` can (one entry per callback, throttled by the `sso` bucket), which
 * only ever costs eviction, never a refusal. An entry lives until its cookie's own expiry, the set holds at most
 * [maxTracked] entries, and when it is full the OLDEST entry is dropped — eviction never refuses a sign-in (it can only
 * let the evicted attempt's cookie be replayed once more inside its ten minutes, which is the pre-existing exposure).
 * Instance-local like the hand-offs: a restart forgets it. A policy over [BoundedTtlStore] (`EVICT_OLDEST`).
 */
class SsoSeenStates(
    private val maxTracked: Int = MAX_TRACKED,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // The value is the entry's own expiry instant. Every admission sweeps ALL expired entries (not just the head), so the
    // oldest-first eviction only ever drops a live entry when the set is genuinely full of live ones.
    private val seen = BoundedTtlStore<String, Long>(maxTracked, WhenFull.EVICT_OLDEST, clock) { it }

    /** True the first time [state] is presented (it is now recorded until [expiresAt]); false for a repeat. */
    fun firstUse(state: String, expiresAt: Long): Boolean = seen.admit(state, expiresAt) == Admission.ADMITTED

    private companion object {
        const val MAX_TRACKED = 10_000
    }
}
