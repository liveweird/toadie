package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.infra.mail.mailAppUrl
import ch.nokillswit.infra.mail.mailer
import ch.nokillswit.plugins.JwtConfig
import ch.nokillswit.plugins.JwtConfigKey
import ch.nokillswit.users.Feature
import ch.nokillswit.users.User
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserServiceKey
import ch.nokillswit.users.canonicalEmail
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.util.UUID
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
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

private const val LOGIN_RATE_LIMIT = "login"
private const val REFRESH_RATE_LIMIT = "refresh"
private const val MFA_RATE_LIMIT = "mfa"

private const val DEFAULT_REFRESH_LIMIT_PER_MINUTE = 30

@Serializable
data class LoginRequest(val email: String, val password: String)

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
    /** The user's stored language (V18) — the SPA applies it to the UI on login/refresh. */
    val language: String,
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
)

private suspend fun JwtConfig.authResponse(
    userId: UInt,
    user: User,
    sessions: AuthSessionService,
    existingSessionId: String? = null,
): LoginResponse {
    val roles = user.additionalRoles
    val sessionId = existingSessionId ?: UUID.randomUUID().toString()
    val access = issueAccessToken(userId, user.email, roles, user.disabledFeatures, sessionId)
    val refresh = issueRefreshToken(userId, user.email, roles, user.disabledFeatures, sessionId)
    val expiresAt = maxOf(access.expiresAt, refresh.expiresAt)
    val active = if (existingSessionId == null) {
        sessions.create(sessionId, userId, user.authVersion, expiresAt)
    } else {
        sessions.renew(sessionId, userId, user.authVersion, expiresAt)
    }
    if (!active) {
        audit("session.rejected", "userId" to userId.toLong())
        throw UnauthorizedException("Session expired or revoked — sign in again")
    }
    return LoginResponse(
        token = access.token,
        expiresAt = access.expiresAt,
        refreshToken = refresh.token,
        refreshExpiresAt = refresh.expiresAt,
        userId = userId,
        roles = roles.sortedBy { it.name },
        disabledFeatures = user.disabledFeatures.sortedBy { it.name },
        // Not a JWT claim: the SPA reads it from this response, and emails read it fresh
        // at send time — nothing needs it inside the token.
        language = user.language,
    )
}

fun Application.configureAuthRoutes() {
    val jwtConfig = attributes[JwtConfigKey]
    val userService = attributes[UserServiceKey]
    val blocklist = attributes[TokenBlocklistServiceKey]
    val sessions = attributes[AuthSessionServiceKey]

    // Per-account lockout, complementing the per-IP RateLimit below (which rotating hosts
    // sidestep): N consecutive failures for one email → locked for the configured window.
    val loginThrottle = LoginThrottle(
        threshold = environment.config.property("security.lockout.threshold").getString().toInt(),
        lockoutMillis = environment.config.property("security.lockout.durationSeconds").getString().toLong() * 1000,
    )

    val mailer = mailer()
    val mailAppUrl = mailAppUrl()

    // Email MFA: pending challenges for MFA-enabled accounts mid-login. In-memory,
    // per-instance, like the throttles above. The issuance worker itself lives beside the
    // email content (auth/MfaEmail.kt — route files stay declarative registrars).
    val mfaTtlSeconds = environment.config.property("security.mfa.codeTtlSeconds").getString().toLong()
    val mfaChallenges = MfaChallenges(
        ttlMillis = mfaTtlSeconds * 1000,
        maxAttempts = environment.config.property("security.mfa.maxAttempts").getString().toInt(),
    )
    val mfaTtlMinutes = (mfaTtlSeconds + 59) / 60

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
    // succession); PasswordResetRoutes' per-email throttle remains identical in both modes.
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
        register(RateLimitName(PASSWORD_RESET_CONFIRM_RATE_LIMIT)) {
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
                    issueMfaChallenge(call, mfaChallenges, mailer, mfaTtlMinutes, userId, user)
                    return@post
                }
                val response = jwtConfig.authResponse(userId, user, sessions)
                audit("login.success", "email" to user.email, "userId" to userId.toLong())
                call.respond(HttpStatusCode.OK, response)
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
                        if (user == null || user.authVersion != outcome.authVersion) {
                            audit("login.mfa_failure", "reason" to "credentials_changed_or_user_gone", "userId" to userId.toLong())
                            throw UnauthorizedException("Invalid or expired sign-in code")
                        }
                        val response = jwtConfig.authResponse(userId, user, sessions)
                        audit("login.mfa_success", "email" to user.email, "userId" to userId.toLong())
                        call.respond(HttpStatusCode.OK, response)
                    }
                }
            }
        }
        rateLimit(RateLimitName(REFRESH_RATE_LIMIT)) {
            // Not behind `authenticate`: the access token may already be expired here. Pure-sliding —
            // a fresh pair stays in the same server-side family; logout revokes ALL its pairs.
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
                val userId = decoded.sessionUserId() ?: reject("malformed")
                val sessionId = decoded.getClaim("sid").asString() ?: reject("malformed", rawUserId)
                // One read: confirm the user still exists and isn't soft-deleted, and pick up their
                // current role/email so changes take effect on the next refresh.
                val user = userService.read(userId)
                    ?: reject("user_gone", rawUserId)
                // Renewal atomically checks the current account epoch and existing family.
                // Unlike second-precision iat, this also rejects same-instant credential changes.
                call.respond(HttpStatusCode.OK, jwtConfig.authResponse(userId, user, sessions, sessionId))
            }
        }
        authenticate {
            post("/api/v1/logout") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.sessionUserId()!!
                val sessionId = principal.payload.getClaim("sid").asString()!!
                sessions.revoke(sessionId, userId)
                val jti = principal.payload.id
                val exp = principal.payload.expiresAt?.time ?: System.currentTimeMillis()
                if (jti != null) {
                    blocklist.revoke(jti, exp)
                }
                // The whole family is already revoked, including superseded refresh tokens.
                // Keep optional per-token blocklisting for compatibility and defense in depth.
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
                    // An optional body must never revoke a different login/device/user.
                    decoded?.takeIf {
                        it.sessionUserId() == userId && it.getClaim("sid").asString() == sessionId
                    }?.id?.let { rjti ->
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
