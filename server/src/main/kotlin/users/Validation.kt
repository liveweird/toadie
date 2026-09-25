package ch.nokillswit.users

import ch.nokillswit.dictionaries.SUPPORTED_LANGUAGES
import io.ktor.server.plugins.BadRequestException

internal const val MAX_NAME_LENGTH = 50

/** Shared with `auth/AuthRoutes.kt` (login) and `auth/PasswordResetRoutes.kt` (the reset
 *  request): both reject an over-long email with a plain 400 before any throttle/lockout
 *  logic runs, since no stored account can exceed this length anyway. */
const val MAX_EMAIL_LENGTH = 254

/**
 * Service accounts (V41, 2.15.0) live at synthetic `integration-client-<id>@toadie.invalid`
 * addresses — `.invalid` is the RFC 2606 reserved TLD that can never resolve to a real
 * mailbox. Reserving the whole domain for service accounts keeps them collision-free against
 * `uq_users_email_active` without adding grammar rules to the synthetic address itself; a
 * human email ending in this domain is rejected outright by [validateNameAndEmail] (create/
 * update). This is deliberately NOT in [validateEmail]: that function is also called by the
 * public password-reset REQUEST route, which must keep answering its uniform 202 for every
 * address, service accounts included (they simply fall into the ordinary unknown-account
 * branch — `findWithIdByEmail` already excludes them).
 */
internal const val SERVICE_ACCOUNT_EMAIL_DOMAIN = "toadie.invalid"

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
    // The reserved service-account domain (V41) is checked here, not in validateEmail: this
    // function backs user create/update only, while validateEmail is also called by the
    // password-reset request route, which must keep its uniform 202 for every address.
    if (canonicalEmail(email).endsWith("@$SERVICE_ACCOUNT_EMAIL_DOMAIN")) {
        throw BadRequestException("Email domain is reserved for service accounts")
    }
}
