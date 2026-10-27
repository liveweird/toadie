package ch.nokillswit.auth.sso

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.plugins.isUniqueViolation
import ch.nokillswit.users.SERVICE_ACCOUNT_EMAIL_DOMAIN
import ch.nokillswit.users.SsoLookup
import ch.nokillswit.users.User
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService
import ch.nokillswit.users.MAX_NAME_LENGTH
import io.ktor.server.plugins.BadRequestException

/** How a successful SSO sign-in reached its account — the audit `outcome`. */
enum class SsoOutcome(val wire: String) { EXISTING("existing"), LINKED("linked"), CREATED("created") }

/** Why an otherwise valid sign-in is refused — [reason] is the audit vocabulary, every one ends as `not_allowed`. */
class SsoAccountRefusedException(val reason: String) : RuntimeException("SSO account refused: $reason")

/** The account a verified identity resolved to. */
class SsoResolved(val userId: UInt, val outcome: SsoOutcome)

/**
 * Account resolution for a verified ID token (`.claude/docs/security.md` "SSO login (OIDC)"):
 *
 * 1. (issuer, subject) matches a row, deleted rows included: active = that user; deleted = refused (`account_deleted`).
 * 2. An ACTIVE account has the same canonical email: linked, unless it is ADMIN or has MFA enabled (`link_refused`) or is
 *    already linked to another identity (`subject_conflict`).
 * 3. Otherwise a USER account is created: NULL password, the default language, the default feature rows (the
 *    `UserService.create` path), the `name` claim as its name; the reserved service-account domain is refused.
 *
 * A concurrent first login for the same person hits a unique index (23505) on step 3 (or on a link); the whole
 * resolution then re-runs ONCE, and finds the winner's row. The email stored on an account is never overwritten from a claim.
 */
class SsoAccounts(
    private val lookup: suspend (issuer: String, subject: String, email: String) -> SsoLookup,
    private val creator: suspend (User) -> UInt,
) {
    constructor(users: UserService) : this(users::resolveSsoIdentity, users::create)

    suspend fun resolve(issuer: String, token: VerifiedIdToken): SsoResolved =
        // Only the unique-violation race is retried, exactly once; anything else is a real failure.
        catchingFailures<SsoResolved?>({ resolveOnce(issuer, token) }) { cause ->
            if (!cause.isUniqueViolation()) throw cause
            null
        } ?: resolveOnce(issuer, token)

    private suspend fun resolveOnce(issuer: String, token: VerifiedIdToken): SsoResolved =
        when (val verdict = lookup(issuer, token.subject, token.email)) {
            is SsoLookup.Existing -> SsoResolved(verdict.userId, SsoOutcome.EXISTING)
            is SsoLookup.Linked -> SsoResolved(verdict.userId, SsoOutcome.LINKED)
            SsoLookup.Deleted -> throw SsoAccountRefusedException("account_deleted")
            SsoLookup.LinkRefused -> throw SsoAccountRefusedException("link_refused")
            SsoLookup.SubjectConflict -> throw SsoAccountRefusedException("subject_conflict")
            SsoLookup.Unknown -> SsoResolved(createAccount(issuer, token), SsoOutcome.CREATED)
        }

    private suspend fun createAccount(issuer: String, token: VerifiedIdToken): UInt {
        if (token.email.endsWith("@$SERVICE_ACCOUNT_EMAIL_DOMAIN")) throw SsoAccountRefusedException("domain_not_allowed")
        val user = User(
            name = displayName(token),
            email = token.email,
            passwordHash = null,
            role = UserRole.USER,
            ssoIssuer = issuer,
            ssoSubject = token.subject,
        )
        try {
            return creator(user)
        } catch (_: BadRequestException) {
            // The service re-validates name and email; a claim the grammar rejects is a refusal, not a 400 for a redirect flow.
            throw SsoAccountRefusedException("account_invalid")
        }
    }

    /** The `name` claim, single-line sanitized and cut to the column; the email's local part when absent or unusable. */
    private fun displayName(token: VerifiedIdToken): String {
        val fromClaim = token.name?.let { claim ->
            try {
                sanitizeSingleLine(claim, "Name")
            } catch (_: BadRequestException) {
                ""
            }
        }.orEmpty().take(MAX_NAME_LENGTH).trim()
        return fromClaim.ifEmpty { token.email.substringBefore('@').take(MAX_NAME_LENGTH) }
    }
}
