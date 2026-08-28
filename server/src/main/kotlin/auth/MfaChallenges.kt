package ch.nokillswit.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store of pending email-MFA challenges (Lettuce's, ported): a login with correct
 * credentials by an MFA-enabled user mints a challenge — an opaque id handed to the client
 * plus a 6-digit code emailed to the account — and the pair must come back to
 * POST /api/v1/login/mfa within [ttlMillis] and [maxAttempts] guesses. A challenge is
 * single-use: consumed on success, dropped on expiry or when the attempt cap is exceeded.
 *
 * In-memory and per-instance by design (the LoginThrottle posture): the deployment runs a
 * single replica, and a restart only invalidates pending challenges — the user simply signs
 * in again. Multiple live challenges per account (repeated logins) are accepted: the short
 * TTL, the attempt cap, and the login rate bucket bound the guessing surface
 * (≤ maxAttempts·10⁻⁶ per challenge).
 */
class MfaChallenges(
    private val ttlMillis: Long,
    private val maxAttempts: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class IssuedChallenge(val challengeId: String, val code: String, val expiresAt: Long)

    sealed interface Outcome {
        data class Success(val userId: UInt) : Outcome

        /** [reason] feeds the audit trail only — the HTTP answer stays a uniform 401. */
        data class Failure(val reason: String) : Outcome
    }

    private data class Challenge(
        val userId: UInt,
        val code: String,
        val expiresAt: Long,
        val attempts: Int,
    )

    private val challenges = ConcurrentHashMap<String, Challenge>()

    fun issue(userId: UInt): IssuedChallenge {
        pruneIfOversized()
        val id = generateChallengeId()
        val code = generateMfaCode()
        val expiresAt = clock() + ttlMillis
        challenges[id] = Challenge(userId, code, expiresAt, attempts = 0)
        return IssuedChallenge(id, code, expiresAt)
    }

    fun verify(challengeId: String, code: String): Outcome {
        val challenge = challenges[challengeId]
            ?: return Outcome.Failure("unknown_challenge")
        if (challenge.expiresAt <= clock()) {
            challenges.remove(challengeId, challenge)
            return Outcome.Failure("expired")
        }
        if (MessageDigest.isEqual(challenge.code.toByteArray(), code.toByteArray())) {
            challenges.remove(challengeId, challenge)
            return Outcome.Success(challenge.userId)
        }
        val attempts = challenge.attempts + 1
        return if (attempts >= maxAttempts) {
            challenges.remove(challengeId, challenge)
            Outcome.Failure("too_many_attempts")
        } else {
            challenges.replace(challengeId, challenge, challenge.copy(attempts = attempts))
            Outcome.Failure("wrong_code")
        }
    }

    // Memory bound: unauthenticated logins mint challenges, so the map must not grow without
    // limit. Cheap opportunistic prune of expired entries once it gets large.
    private fun pruneIfOversized() {
        if (challenges.size <= MAX_TRACKED) return
        val now = clock()
        challenges.entries.removeIf { it.value.expiresAt <= now }
    }

    private companion object {
        const val MAX_TRACKED = 10_000
        val secureRandom = SecureRandom()

        /** 128 bits of opaque, unguessable challenge identity. */
        fun generateChallengeId(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
