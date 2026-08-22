package ch.nokillswit.auth

import at.favre.lib.crypto.bcrypt.BCrypt

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

internal fun hashPassword(plain: String, cost: Int = 12): String =
    BCrypt.withDefaults().hashToString(cost, plain.toCharArray())

internal fun verifyPassword(plain: String, hash: String): Boolean =
    !exceedsBcryptLimit(plain) && BCrypt.verifyer().verify(plain.toCharArray(), hash).verified
