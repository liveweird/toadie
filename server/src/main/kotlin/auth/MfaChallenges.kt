package ch.nokillswit.auth

import ch.nokillswit.infra.concurrency.BoundedTtlStore
import ch.nokillswit.infra.concurrency.WhenFull
import java.security.MessageDigest
import java.security.SecureRandom

private const val CHALLENGE_ID_BYTES = 16

/** Every challenge id [MfaChallenges] issues is exactly this long (hex): `POST /login/mfa` refuses a longer one with a 400. */
internal const val MAX_MFA_CHALLENGE_ID_LENGTH = CHALLENGE_ID_BYTES * 2

/**
 * In-memory store of pending email-MFA challenges (Lettuce's, ported): a login with correct
 * credentials by an MFA-enabled user mints a challenge — an opaque id handed to the client
 * plus a 6-digit code emailed to the account — and the pair must come back to
 * POST /api/v1/login/mfa within [ttlMillis] and [maxAttempts] guesses. A challenge is
 * single-use: consumed on success, dropped on expiry or when the attempt cap is exceeded.
 *
 * In-memory and per-instance by design (the LoginThrottle posture): the deployment runs a
 * single replica, and a restart only invalidates pending challenges — the user simply signs
 * in again. Multiple live challenges per account (repeated logins) are accepted up to one
 * global hard ceiling: the short TTL, attempt cap, login rate bucket, and atomic admission
 * bound the guessing and memory surfaces (≤ maxAttempts·10⁻⁶ per admitted challenge).
 * A policy over [BoundedTtlStore]: this class owns id and code generation, the expiry and
 * attempt verdicts and the audit reasons; the store owns the map, the lock and the sweep.
 */
class MfaChallenges(
    private val ttlMillis: Long,
    private val maxAttempts: Int,
    private val maxTracked: Int = MAX_TRACKED,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class CapacityExceededException : RuntimeException("MFA challenge capacity is exhausted")

    data class IssuedChallenge(val challengeId: String, val code: String, val expiresAt: Long)

    sealed interface Outcome {
        data class Success(val userId: UInt, val authVersion: Long = 0) : Outcome

        /** [reason] feeds the audit trail only — the HTTP answer stays a uniform 401. */
        data class Failure(val reason: String) : Outcome
    }

    private data class Challenge(
        val userId: UInt,
        val authVersion: Long,
        val code: String,
        val expiresAt: Long,
        val attempts: Int,
    )

    private val challenges = BoundedTtlStore<String, Challenge>(maxTracked, WhenFull.REFUSE, clock, Challenge::expiresAt)

    fun issue(userId: UInt, authVersion: Long = 0): IssuedChallenge {
        val challenge = Challenge(userId, authVersion, generateMfaCode(), clock() + ttlMillis, attempts = 0)
        val id = challenges.issue(challenge) { generateChallengeId() } ?: throw CapacityExceededException()
        return IssuedChallenge(id, challenge.code, challenge.expiresAt)
    }

    // One atomic read-modify-write: single-use (a correct code removes the entry), the attempt bump (parallel wrong
    // guesses can never lose an increment and sneak past the cap) and the expiry verdict all happen under the store's lock.
    fun verify(challengeId: String, code: String): Outcome = challenges.update<Outcome>(challengeId) { c ->
        when {
            c == null -> null to Outcome.Failure("unknown_challenge")
            c.expiresAt <= clock() -> null to Outcome.Failure("expired")
            MessageDigest.isEqual(c.code.toByteArray(), code.toByteArray()) ->
                null to Outcome.Success(c.userId, c.authVersion)
            c.attempts + 1 >= maxAttempts -> null to Outcome.Failure("too_many_attempts")
            else -> c.copy(attempts = c.attempts + 1) to Outcome.Failure("wrong_code")
        }
    }

    private companion object {
        const val MAX_TRACKED = 10_000
        val secureRandom = SecureRandom()

        /** 128 bits of opaque, unguessable challenge identity. */
        fun generateChallengeId(): String {
            val bytes = ByteArray(CHALLENGE_ID_BYTES)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
