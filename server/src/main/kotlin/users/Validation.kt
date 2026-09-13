package ch.nokillswit.users

import ch.nokillswit.dictionaries.SUPPORTED_LANGUAGES
import io.ktor.server.plugins.BadRequestException

internal const val MAX_NAME_LENGTH = 50

/** Shared with `auth/AuthRoutes.kt` (login) and `auth/PasswordResetRoutes.kt` (the reset
 *  request): both reject an over-long email with a plain 400 before any throttle/lockout
 *  logic runs, since no stored account can exceed this length anyway. */
const val MAX_EMAIL_LENGTH = 254

/** Canonical email identity: trimmed + case-folded. Applied at EVERY entry point — create,
 *  update, login, and the lookup itself — so one mailbox is one account (`ADMIN@x` cannot
 *  create a second account beside `admin@x`, and a padded/case-variant login matches). A pure
 *  fold that never throws: login must stay a uniform 401 on garbage. */
internal fun canonicalEmail(raw: String): String = raw.trim().lowercase()

internal fun validateEmail(email: String) {
    if (email.isBlank()) throw BadRequestException("Email must not be blank")
    if (email.length > MAX_EMAIL_LENGTH) {
        throw BadRequestException("Email must be at most $MAX_EMAIL_LENGTH characters")
    }
    if ('@' !in email) throw BadRequestException("Email must contain '@'")
    if (email.any { it.isISOControl() }) {
        throw BadRequestException("Email must not contain control characters")
    }
}

/** The user language (V18) when provided: must be a supported code. Null = default. */
internal fun validateLanguage(language: String?) {
    if (language != null && language !in SUPPORTED_LANGUAGES) {
        throw BadRequestException("Unsupported language (supported: ${SUPPORTED_LANGUAGES.joinToString()})")
    }
}

/**
 * The wire `roles` array carries only ADDITIONAL roles beyond the implicit [UserRole.USER]
 * baseline (see "Global roles are an additive set" in `.claude/docs/authorization.md`) — [rolesToStored]
 * would silently fold a caller-supplied `USER` entry away, so reject it up front instead of
 * accepting a wire value that can never mean anything.
 */
internal fun validateRoles(roles: List<UserRole>?) {
    if (roles != null && UserRole.USER in roles) {
        throw BadRequestException("roles may name only additional roles")
    }
}

internal fun validateNameAndEmail(name: String, email: String) {
    if (name.isBlank()) throw BadRequestException("Name must not be blank")
    if (name.length > MAX_NAME_LENGTH) {
        throw BadRequestException("Name must be at most $MAX_NAME_LENGTH characters")
    }
    validateEmail(email)
}
