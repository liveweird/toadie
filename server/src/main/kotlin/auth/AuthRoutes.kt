package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.infra.mail.mailAppUrl
import ch.nokillswit.infra.mail.mailer
import ch.nokillswit.infra.mail.respondMailUnavailable
import ch.nokillswit.plugins.JwtConfig
import ch.nokillswit.plugins.JwtConfigKey
import ch.nokillswit.users.Feature
import ch.nokillswit.users.User
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserServiceKey
import ch.nokillswit.users.canonicalEmail
import ch.nokillswit.users.validateEmail
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

private const val LOGIN_RATE_LIMIT = "login"
private const val REFRESH_RATE_LIMIT = "refresh"
private const val PASSWORD_RESET_RATE_LIMIT = "password-reset"
private const val MFA_RATE_LIMIT = "mfa"

private const val DEFAULT_REFRESH_LIMIT_PER_MINUTE = 30

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class PasswordResetRequest(val email: String)

/**
 * The MFA branch of POST /api/v1/login: credentials verified, second factor pending — no
 * tokens yet. The client sends the emailed 6-digit code with [challengeId] to
 * POST /api/v1/login/mfa to obtain the ordinary [LoginResponse].
 */
@Serializable
data class MfaChallengeResponse(
    // Literal discriminator against LoginResponse in the login 200 oneOf; @EncodeDefault
    // keeps the defaulted value on the wire.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault
    val mfaRequired: Boolean = true,
    val challengeId: String,
    /** Epoch millis when the challenge (and its code) expires. */
    val expiresAt: Long,
)

@Serializable
data class MfaVerifyRequest(val challengeId: String, val code: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String? = null)

@Serializable
data class LoginResponse(
    val token: String,
    val expiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val userId: UInt,
    /** Additional roles of the authenticated user — empty for a regular user. */
    val roles: List<UserRole>,
    /** Per-user feature flags (V12) — the admin-disabled set; empty = full access. */
    val disabledFeatures: List<Feature>,
)

// The refresh rejection detail per audited reason — data beside the handler, not control flow
// in it. Every reason has its explicit entry; the handler's fallback is generic, so a typo in
// a reason string can never masquerade as a specific message.
private val REFRESH_REJECT_MESSAGES = mapOf(
    "invalid_or_expired" to "Invalid or expired refresh token",
    "wrong_token_type" to "Not a refresh token",
    "revoked" to "Refresh token revoked",
    "malformed" to "Malformed refresh token",
    "user_gone" to "User no longer exists",
    "predates_password_change" to "Refresh token predates a password change",
)

private fun JwtConfig.authResponse(
    userId: UInt,
    email: String,
    roles: Set<UserRole>,
    disabledFeatures: Set<Feature>,
): LoginResponse {
    val access = issueAccessToken(userId, email, roles, disabledFeatures)
    val refresh = issueRefreshToken(userId, email, roles, disabledFeatures)
    return LoginResponse(
        token = access.token,
        expiresAt = access.expiresAt,
        refreshToken = refresh.token,
        refreshExpiresAt = refresh.expiresAt,
        userId = userId,
        roles = roles.sortedBy { it.name },
        disabledFeatures = disabledFeatures.sortedBy { it.name },
    )
}

fun Application.configureAuthRoutes() {
    val jwtConfig = attributes[JwtConfigKey]
    val userService = attributes[UserServiceKey]
    val blocklist = attributes[TokenBlocklistServiceKey]

    // Per-account lockout, complementing the per-IP RateLimit below (which rotating hosts
    // sidestep): N consecutive failures for one email → locked for the configured window.
    val loginThrottle = LoginThrottle(
        threshold = environment.config.property("security.lockout.threshold").getString().toInt(),
        lockoutMillis = environment.config.property("security.lockout.durationSeconds").getString().toLong() * 1000,
    )

    // Self-service password reset: one request per submitted email per interval, uniformly
    // whether or not the account exists (the 429 carries no enumeration signal).
    val resetThrottle = PasswordResetThrottle(
        minIntervalMillis = environment.config
            .property("security.passwordReset.minIntervalSeconds").getString().toLong() * 1000,
    )
    val mailer = mailer()
    val mailAppUrl = mailAppUrl()

    // The password-reset work that runs AFTER the uniform 202 (no timing oracle): lookup,
    // generate, send-before-store, and the completion/failure audits. Launched fire-and-forget
    // by the handler. Soft-deleted accounts are unknown here by construction —
    // findWithIdByEmail filters active rows. Recipient language is "en" until a per-user
    // language preference exists (see infra/mail/LocalizedText.kt).
    suspend fun processPasswordReset(app: Application, resetMailer: Mailer, email: String) {
        var delivered = false
        try {
            val record = userService.findWithIdByEmail(email)
            if (record == null) {
                audit("password_reset.unknown_email", "email" to email)
                return
            }
            val (userId, user) = record
            val newPassword = generatePassword()
            // Send FIRST, then store: a delivery failure leaves the old password
            // working; a storage failure after delivery is recoverable by retrying.
            // The two halves audit under distinct events — a store_failed means the
            // recipient now holds an emailed password that does NOT work yet.
            resetMailer.send(
                to = user.email,
                subject = PASSWORD_RESET_EMAIL_SUBJECT.of("en"),
                body = passwordResetEmailBody(user.name, newPassword, mailAppUrl, "en"),
            )
            delivered = true
            userService.updatePassword(userId, hashPassword(newPassword))
            audit("password_reset.completed", "email" to user.email, "userId" to userId.toLong())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            audit(
                if (delivered) "password_reset.store_failed" else "password_reset.send_failed",
                "email" to email,
                "error" to e.message,
            )
            app.log.error("Password reset failed for $email (delivered=$delivered)", e)
        }
    }

    // Email MFA: pending challenges for MFA-enabled accounts mid-login. In-memory,
    // per-instance, like the throttles above.
    val mfaTtlSeconds = environment.config.property("security.mfa.codeTtlSeconds").getString().toLong()
    val mfaChallenges = MfaChallenges(
        ttlMillis = mfaTtlSeconds * 1000,
        maxAttempts = environment.config.property("security.mfa.maxAttempts").getString().toInt(),
    )
    val mfaTtlMinutes = (mfaTtlSeconds + 59) / 60

    // The MFA half of the login handler: correct credentials answered with a challenge
    // instead of tokens. Responds itself (503 fail-closed on a mail-less deployment, else
    // the challenge); the login handler returns right after calling it.
    suspend fun issueMfaChallenge(call: ApplicationCall, userId: UInt, user: User) {
        if (mailer == null) {
            // Fail closed (the password-reset 503 precedent): silently skipping the
            // second factor would downgrade security on a config change. The
            // features PUT still works, so an admin can always flip the flag back.
            audit("login.mfa_unavailable", "email" to user.email, "userId" to userId.toLong())
            call.respondMailUnavailable("multi-factor login")
            return
        }
        val challenge = mfaChallenges.issue(userId)
        audit("login.mfa_challenge", "email" to user.email, "userId" to userId.toLong())
        // Challenge stored BEFORE responding (the user submits the code right away);
        // only the delivery is fire-and-forget, like the password-reset email.
        val app = call.application
        app.launch {
            try {
                mailer.send(
                    to = user.email,
                    subject = MFA_EMAIL_SUBJECT.of("en"),
                    body = mfaEmailBody(user.name, challenge.code, mfaTtlMinutes, "en"),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                audit("login.mfa_send_failed", "email" to user.email, "error" to e.message)
                app.log.error("MFA code email delivery failed for ${user.email}", e)
            }
        }
        call.respond(
            MfaChallengeResponse(
                challengeId = challenge.challengeId,
                expiresAt = challenge.expiresAt,
            ),
        )
    }

    // Verifies signature/issuer/audience/expiry of a presented refresh token. Same secret as the
    // access-token verifier in configureSecurity; the `typ` claim is checked separately below.
    val refreshVerifier = JWT.require(Algorithm.HMAC256(jwtConfig.secret))
        .withAudience(jwtConfig.audience)
        .withIssuer(jwtConfig.issuer)
        .build()

    // Blank follows the mode (see application.yaml): production keeps the 10/min login bucket,
    // development lifts it so a single host driving many logins — the e2e suite — is not
    // throttled. The per-account lockout above is the brute-force defence in both modes.
    val loginLimit = environment.config.propertyOrNull("security.rateLimit.loginPerMinute")
        ?.getString()?.takeIf { it.isNotBlank() }?.toInt()
        ?: if (developmentMode) 1000 else 10

    // The refresh bucket blunts token abuse; blank keeps the default 30/min in both modes
    // (a healthy client refreshes about once per access-token TTL).
    val refreshLimit = environment.config.propertyOrNull("security.rateLimit.refreshPerMinute")
        ?.getString()?.takeIf { it.isNotBlank() }?.toInt()
        ?: DEFAULT_REFRESH_LIMIT_PER_MINUTE

    // The password-reset bucket follows the mode like the login bucket (5/min production,
    // lifted in development — the e2e suite fires several resets from one host in quick
    // succession); the per-EMAIL throttle above is the actual abuse defence in both modes.
    val passwordResetLimit = environment.config.propertyOrNull("security.rateLimit.passwordResetPerMinute")
        ?.getString()?.takeIf { it.isNotBlank() }?.toInt()
        ?: if (developmentMode) 100 else 5

    // Throttle login to blunt password brute-forcing, and refresh to blunt token abuse: a token
    // bucket per client host.
    install(RateLimit) {
        register(RateLimitName(LOGIN_RATE_LIMIT)) {
            rateLimiter(limit = loginLimit, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName(REFRESH_RATE_LIMIT)) {
            rateLimiter(limit = refreshLimit, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName(PASSWORD_RESET_RATE_LIMIT)) {
            rateLimiter(limit = passwordResetLimit, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName(MFA_RATE_LIMIT)) {
            rateLimiter(limit = 10, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }

    routing {
        rateLimit(RateLimitName(LOGIN_RATE_LIMIT)) {
            post("/api/v1/login") {
                val req = call.receive<LoginRequest>()
                // Canonical identity: accounts are stored under the folded email, so the login
                // lookup folds the same way — a padded or case-variant submission matches its
                // account (and keeps sharing one lockout bucket).
                val email = canonicalEmail(req.email)
                if (loginThrottle.isLocked(email)) {
                    audit("login.rejected_locked", "email" to email)
                    // Thrown (not respondProblem) so StatusPages marks the call handled and its
                    // generic 429 status handler cannot replace this specific detail.
                    throw TooManyRequestsException(
                        "Too many failed login attempts for this account — try again later",
                    )
                }
                val record = userService.findWithIdByEmail(email)
                // The unknown-email branch pays a full (discarded) bcrypt verify so its latency
                // matches the wrong-password branch — without it the fast 401 is a timing oracle
                // for account enumeration (the reset path equalizes the same way, via async work).
                val credentialsValid =
                    if (record == null) {
                        verifyPassword(req.password, TIMING_EQUALIZER_HASH)
                        false
                    } else {
                        verifyPassword(req.password, record.second.passwordHash)
                    }
                if (record == null || !credentialsValid) {
                    val tripped = loginThrottle.recordFailure(email)
                    audit(
                        "login.failure",
                        "email" to email,
                        "reason" to if (record == null) "unknown_email" else "wrong_password",
                    )
                    if (tripped) audit("login.lockout", "email" to email)
                    throw UnauthorizedException("Unknown email or wrong password")
                }
                val (userId, user) = record
                loginThrottle.recordSuccess(email)
                // Email MFA (opt-in via the MFA feature flag, read straight off the DB record —
                // no JWT exists yet): correct credentials answer with a challenge, not tokens.
                if (Feature.MFA !in user.disabledFeatures) {
                    issueMfaChallenge(call, userId, user)
                    return@post
                }
                audit("login.success", "email" to user.email, "userId" to userId.toLong())
                call.respond(HttpStatusCode.OK, jwtConfig.authResponse(userId, user.email, user.additionalRoles, user.disabledFeatures))
            }
        }
        rateLimit(RateLimitName(MFA_RATE_LIMIT)) {
            // Second login step for MFA-enabled accounts: exchange the challenge id + the
            // emailed code for the ordinary token pair. Not behind `authenticate` — there is
            // no token yet.
            post("/api/v1/login/mfa") {
                val req = call.receive<MfaVerifyRequest>()
                when (val outcome = mfaChallenges.verify(req.challengeId, req.code)) {
                    is MfaChallenges.Outcome.Failure -> {
                        audit("login.mfa_failure", "reason" to outcome.reason)
                        // Uniform for every failure mode — a guesser learns nothing about
                        // whether the challenge exists, expired, or the code was wrong.
                        throw UnauthorizedException("Invalid or expired sign-in code")
                    }
                    is MfaChallenges.Outcome.Success -> {
                        val userId = outcome.userId
                        // One read (the /refresh precedent): the user must still exist and be
                        // active, and the pair is minted from their current roles/flags.
                        val user = userService.read(userId)
                        if (user == null) {
                            audit("login.mfa_failure", "reason" to "user_gone", "userId" to userId.toLong())
                            throw UnauthorizedException("Invalid or expired sign-in code")
                        }
                        audit("login.mfa_success", "email" to user.email, "userId" to userId.toLong())
                        call.respond(
                            HttpStatusCode.OK,
                            jwtConfig.authResponse(userId, user.email, user.additionalRoles, user.disabledFeatures),
                        )
                    }
                }
            }
        }
        rateLimit(RateLimitName(REFRESH_RATE_LIMIT)) {
            // Not behind `authenticate`: the access token may already be expired here. Pure-sliding —
            // a fresh pair is minted and the old tokens are left to expire on their own (not revoked).
            post("/api/v1/refresh") {
                val req = call.receive<RefreshRequest>()
                fun reject(reason: String, userId: Long? = null): Nothing {
                    audit("refresh.rejected", "reason" to reason, "userId" to userId)
                    throw UnauthorizedException(REFRESH_REJECT_MESSAGES[reason] ?: "Refresh token rejected")
                }

                val decoded = try {
                    refreshVerifier.verify(req.refreshToken)
                } catch (_: JWTVerificationException) {
                    reject("invalid_or_expired")
                }
                if (decoded.getClaim("typ").asString() != TOKEN_TYPE_REFRESH) {
                    reject("wrong_token_type")
                }
                val rawUserId = decoded.getClaim("userId").asLong()
                // A jti-less token could never be blocklisted, so it is malformed by definition
                // (every server-minted token carries one).
                val jti = decoded.id ?: reject("malformed", rawUserId)
                if (blocklist.isRevoked(jti)) {
                    reject("revoked", rawUserId)
                }
                val userId = rawUserId?.toUInt() ?: reject("malformed")
                // One read: confirm the user still exists and isn't soft-deleted, and pick up their
                // current role/email so changes take effect on the next refresh.
                val user = userService.read(userId)
                    ?: reject("user_gone", rawUserId)
                // A password change invalidates all refresh tokens minted before it (tokens
                // without an iat claim predate this scheme and count as minted at epoch 0).
                // JWT iat has SECOND precision, so compare both sides truncated to seconds —
                // otherwise a token minted in the same second as the change is falsely rejected.
                val issuedAtSec = (decoded.issuedAt?.time ?: 0) / 1000
                if (issuedAtSec < user.passwordChangedAt / 1000) {
                    reject("predates_password_change", rawUserId)
                }
                call.respond(HttpStatusCode.OK, jwtConfig.authResponse(userId, user.email, user.additionalRoles, user.disabledFeatures))
            }
        }
        rateLimit(RateLimitName(PASSWORD_RESET_RATE_LIMIT)) {
            // Self-service reset: generate a new password and email it. Always 202 for a
            // well-formed request — existence of the account must not be observable, so the
            // actual work happens asynchronously after the response (uniform latency, no
            // timing oracle: the lookup, bcrypt hash, and SMTP round-trip all take place
            // off the request).
            post("/api/v1/password-reset") {
                // Canonical identity: fold like login, so a case-variant reset request
                // reaches its account (and keeps its one throttle bucket).
                val email = canonicalEmail(call.receive<PasswordResetRequest>().email)
                validateEmail(email)
                if (mailer == null) {
                    // mail.transport=disabled — the deployment cannot send email at all.
                    call.respondMailUnavailable("password reset")
                    return@post
                }
                if (!resetThrottle.tryAcquire(email)) {
                    audit("password_reset.throttled", "email" to email)
                    throw TooManyRequestsException(
                        "Only one password reset per minute per address — try again shortly",
                    )
                }
                audit("password_reset.requested", "email" to email)
                val app = call.application
                app.launch { processPasswordReset(app, mailer, email) }
                call.respond(HttpStatusCode.Accepted)
            }
        }
        authenticate {
            post("/api/v1/logout") {
                val principal = call.principal<JWTPrincipal>()!!
                val jti = principal.payload.id
                val exp = principal.payload.expiresAt?.time ?: System.currentTimeMillis()
                if (jti != null) {
                    blocklist.revoke(jti, exp)
                }
                // Also revoke the refresh token, if the client sent it, so an explicit logout kills it
                // too (rotation leaves superseded tokens alive, but logout is a deliberate revoke).
                // Best-effort by design (logout always answers 204), but the failures are NARROW
                // and logged — a blanket catch would silently skip revocation on unrelated errors
                // and swallow coroutine cancellation.
                val body = try {
                    call.receiveNullable<LogoutRequest>()
                } catch (cause: BadRequestException) {
                    // ContentNegotiation's malformed-JSON wrap.
                    call.application.log.debug("Logout body unparsable — skipping refresh-token revocation", cause)
                    null
                } catch (cause: CannotTransformContentToTypeException) {
                    // A body-less/Content-Type-less POST never enters ContentNegotiation.
                    call.application.log.debug("Logout sent no body — skipping refresh-token revocation", cause)
                    null
                }
                body?.refreshToken?.let { rt ->
                    val decoded = try {
                        refreshVerifier.verify(rt)
                    } catch (cause: JWTVerificationException) {
                        call.application.log.debug("Logout refresh token invalid — nothing to revoke", cause)
                        null
                    }
                    decoded?.id?.let { rjti ->
                        blocklist.revoke(rjti, decoded.expiresAt?.time ?: System.currentTimeMillis())
                    }
                }
                audit(
                    "logout",
                    "userId" to principal.payload.getClaim("userId").asLong(),
                    "email" to principal.payload.getClaim("email").asString(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
