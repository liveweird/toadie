package ch.nokillswit.users

import io.ktor.server.plugins.BadRequestException

/** Column limits (see Users table / migrations) enforced up-front so oversized or blank
 *  payloads are a clean 400 instead of a DB-level 500. */
internal const val MAX_NAME_LENGTH = 50
internal const val MAX_EMAIL_LENGTH = 254

/** Canonical email identity: trimmed + case-folded. Applied at EVERY entry point — create,
 *  login, and the lookup itself — so one mailbox is one account (`ADMIN@x` cannot create a
 *  second account beside `admin@x`, and a padded/case-variant login matches). A pure fold
 *  that never throws: login must stay a uniform 401 on garbage. */
internal fun canonicalEmail(raw: String): String = raw.trim().lowercase()

/** The email acceptance rule — callers pass the [canonicalEmail]-folded value. Deliberately
 *  loose (presence of '@' + length) — real validation is delivery. */
internal fun validateEmail(email: String) {
    if (email.isBlank()) throw BadRequestException("Email must not be blank")
    if (email.length > MAX_EMAIL_LENGTH) throw BadRequestException("Email must be at most $MAX_EMAIL_LENGTH characters")
    if ('@' !in email) throw BadRequestException("Email must contain '@'")
    if (email.any { it.isISOControl() }) throw BadRequestException("Email must not contain control characters")
}

internal fun validateNameAndEmail(name: String, email: String) {
    if (name.isBlank()) throw BadRequestException("Name must not be blank")
    if (name.length > MAX_NAME_LENGTH) throw BadRequestException("Name must be at most $MAX_NAME_LENGTH characters")
    validateEmail(email)
}
