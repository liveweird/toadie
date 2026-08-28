package ch.nokillswit.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * bcrypt hashes at most 72 bytes including a null terminator, so a password may be at most
 * 71 UTF-8 bytes — longer input makes at.favre's strict strategy throw (a 500, and on login
 * an account-enumeration oracle: unknown emails short-circuit to 401 before hashing).
 * Enforced as 400 at the API boundary (see `validatePassword` in users/UserRoutes.kt) and
 * treated as never-matching in [verifyPassword].
 */
const val MAX_PASSWORD_BYTES = 71

internal fun exceedsBcryptLimit(plain: String): Boolean =
    plain.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES

// Both bcrypt entry points hop to Dispatchers.Default: at cost 12 a hash/verify is hundreds
// of milliseconds of pure CPU, which must not occupy a request-dispatcher thread.
internal suspend fun hashPassword(plain: String, cost: Int = 12): String =
    withContext(Dispatchers.Default) { BCrypt.withDefaults().hashToString(cost, plain.toCharArray()) }

internal suspend fun verifyPassword(plain: String, hash: String): Boolean =
    !exceedsBcryptLimit(plain) &&
        withContext(Dispatchers.Default) { BCrypt.verifyer().verify(plain.toCharArray(), hash).verified }

// URL-safe alphabet (no ambiguity-prone symbols) for server-generated passwords.
private const val PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

private val secureRandom = SecureRandom()

/** The server-generated password of the self-service reset: 16 chars × 64-symbol alphabet = 96 bits. */
internal fun generatePassword(length: Int = 16): String =
    buildString(length) {
        repeat(length) { append(PASSWORD_ALPHABET[secureRandom.nextInt(PASSWORD_ALPHABET.length)]) }
    }
