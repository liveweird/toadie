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
 * in again. Multiple live challenges per account (repeated logins) are accepted up to one
 * global hard ceiling: the short TTL, attempt cap, login rate bucket, and atomic admission
 * bound the guessing and memory surfaces (≤ maxAttempts·10⁻⁶ per admitted challenge).
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

    private val challenges = ConcurrentHashMap<String, Challenge>()
    private val admissionLock = Any()

    fun issue(userId: UInt, authVersion: Long = 0): IssuedChallenge {
        synchronized(admissionLock) {
            pruneExpired()
            if (challenges.size >= maxTracked) throw CapacityExceededException()
            var id: String
            do {
                id = generateChallengeId()
            } while (challenges.containsKey(id))
            val code = generateMfaCode()
            val expiresAt = clock() + ttlMillis
            challenges[id] = Challenge(userId, authVersion, code, expiresAt, attempts = 0)
            return IssuedChallenge(id, code, expiresAt)
        }
    }

    fun verify(challengeId: String, code: String): Outcome = synchronized(admissionLock) {
        val challenge = challenges[challengeId]
            ?: return@synchronized Outcome.Failure("unknown_challenge")
        if (challenge.expiresAt <= clock()) {
            challenges.remove(challengeId, challenge)
            return@synchronized Outcome.Failure("expired")
        }
        if (MessageDigest.isEqual(challenge.code.toByteArray(), code.toByteArray())) {
            // Single-use is a CAS, not a courtesy: only the submission that actually removes
            // the entry wins — a concurrent duplicate with the same correct code loses.
            return@synchronized if (challenges.remove(challengeId, challenge)) {
                Outcome.Success(challenge.userId, challenge.authVersion)
            } else {
                Outcome.Failure("unknown_challenge")
            }
        }
        // The attempt bump is atomic (computeIfPresent) so parallel wrong guesses can never
        // lose an increment and sneak past the cap; reaching the cap drops the entry.
        var capped = false
        challenges.computeIfPresent(challengeId) { _, current ->
            val attempts = current.attempts + 1
            if (attempts >= maxAttempts) {
                capped = true
                null
            } else {
                current.copy(attempts = attempts)
            }
        }
        Outcome.Failure(if (capped) "too_many_attempts" else "wrong_code")
    }

    // Admission is serialized: expiry cleanup, the capacity decision, and insertion form one
    // atomic operation, so concurrent correct-password logins cannot overshoot the live cap.
    private fun pruneExpired() {
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
