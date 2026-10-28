package ch.nokillswit.auth.sso

import ch.nokillswit.auth.MAX_RETAINED_ID_TOKEN_CHARS
import ch.nokillswit.infra.concurrency.BoundedTtlStore
import ch.nokillswit.infra.concurrency.WhenFull
import java.security.SecureRandom
import java.util.Base64

/**
 * In-memory, single-use hand-off between the SSO callback and `POST /login/sso/exchange` (the `MfaChallenges` shape):
 * after the IdP has authenticated the user and the account is resolved, the callback stores `{userId, authVersion,
 * bindHash, ID token}` under an opaque code that travels to the SPA in a URL FRAGMENT. The exchange must present that code AND the
 * attempt cookie whose bind secret hashes to [Handoff.bindHash], so a code planted into someone else's browser
 * (login CSRF through the fragment) is useless. Single-use is the store's atomic remove; the TTL is [ttlMillis]; admission is
 * serialized under one hard ceiling [maxTracked]. Reached only AFTER the IdP authenticated a user, so pre-authentication
 * traffic can never fill it. Instance-local like the throttles: a restart only means signing in again. A policy over
 * [BoundedTtlStore]: this class owns code generation, the expiry verdict and the capacity exception.
 */
class SsoHandoffs(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxTracked: Int = MAX_TRACKED,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class CapacityExceededException : RuntimeException("SSO hand-off capacity is exhausted")

    class Handoff(
        val userId: UInt,
        val authVersion: Long,
        val bindHash: ByteArray,
        val outcome: SsoOutcome,
        val expiresAt: Long,
        /** The raw ID token, carried to the exchange for the session row (provider sign-out); null when over the retention cap. */
        val idToken: String?,
    ) {
        // Never printed: a hand-off holds an identity assertion.
        override fun toString(): String = "Handoff(userId=$userId)"
    }

    private val handoffs = BoundedTtlStore<String, Handoff>(maxTracked, WhenFull.REFUSE, clock, Handoff::expiresAt)

    /**
     * Stores a hand-off ([outcome] feeds the exchange's audit event) and returns its opaque, unguessable code. [idToken] is kept
     * only up to [MAX_RETAINED_ID_TOKEN_CHARS]: a larger one is dropped here, so neither this store nor the session row ever
     * holds it and that login signs out locally only.
     */
    fun issue(userId: UInt, authVersion: Long, bindHash: ByteArray, outcome: SsoOutcome, idToken: String? = null): String =
        handoffs.issue(
            Handoff(
                userId, authVersion, bindHash, outcome, clock() + ttlMillis,
                idToken?.takeIf { it.length <= MAX_RETAINED_ID_TOKEN_CHARS },
            ),
        ) { generateCode() }
            ?: throw CapacityExceededException()

    /**
     * Removes and returns the hand-off for [code], or null when unknown, expired or already consumed. Only the call that
     * actually removes the entry wins, so a concurrent duplicate loses.
     */
    fun consume(code: String): Handoff? = handoffs.remove(code)?.takeIf { it.expiresAt > clock() }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 60_000L
        const val MAX_TRACKED = 10_000
        const val CODE_BYTES = 32
        val secureRandom = SecureRandom()

        /** 256 bits of opaque identity, base64url (fragment- and URL-safe). */
        fun generateCode(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(CODE_BYTES).also(secureRandom::nextBytes))
    }
}
