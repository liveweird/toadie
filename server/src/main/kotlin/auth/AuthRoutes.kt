package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.plugins.JwtConfig
import ch.nokillswit.plugins.JwtConfigKey
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserServiceKey
import ch.nokillswit.users.canonicalEmail
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
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

@Serializable
data class LoginRequest(val email: String, val password: String)

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
)

// The refresh rejection detail per audited reason — data beside the handler, not control flow
// in it. Unlisted reasons fall through to the password-change wording (see the handler).
private val REFRESH_REJECT_MESSAGES = mapOf(
    "invalid_or_expired" to "Invalid or expired refresh token",
    "wrong_token_type" to "Not a refresh token",
    "revoked" to "Refresh token revoked",
    "malformed" to "Malformed refresh token",
    "user_gone" to "User no longer exists",
)

private fun JwtConfig.authResponse(userId: UInt, email: String, roles: Set<UserRole>): LoginResponse {
    val access = issueAccessToken(userId, email, roles)
    val refresh = issueRefreshToken(userId, email, roles)
    return LoginResponse(
        token = access.token,
        expiresAt = access.expiresAt,
        refreshToken = refresh.token,
        refreshExpiresAt = refresh.expiresAt,
        userId = userId,
        roles = roles.sortedBy { it.name },
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

    // Throttle login to blunt password brute-forcing, and refresh to blunt token abuse: a token
    // bucket per client host.
    install(RateLimit) {
        register(RateLimitName(LOGIN_RATE_LIMIT)) {
            rateLimiter(limit = loginLimit, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName(REFRESH_RATE_LIMIT)) {
            rateLimiter(limit = 30, refillPeriod = 60.seconds)
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
                if (record == null || !verifyPassword(req.password, record.second.passwordHash)) {
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
                audit("login.success", "email" to user.email, "userId" to userId.toLong())
                call.respond(jwtConfig.authResponse(userId, user.email, user.additionalRoles))
            }
        }
        rateLimit(RateLimitName(REFRESH_RATE_LIMIT)) {
            // Not behind `authenticate`: the access token may already be expired here. Pure-sliding —
            // a fresh pair is minted and the old tokens are left to expire on their own (not revoked).
            post("/api/v1/refresh") {
                val req = call.receive<RefreshRequest>()
                fun reject(reason: String, userId: Long? = null): Nothing {
                    audit("refresh.rejected", "reason" to reason, "userId" to userId)
                    throw UnauthorizedException(
                        REFRESH_REJECT_MESSAGES[reason] ?: "Refresh token predates a password change",
                    )
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
                val jti = decoded.id
                if (jti != null && blocklist.isRevoked(jti)) {
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
                call.respond(jwtConfig.authResponse(userId, user.email, user.additionalRoles))
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
                val body = runCatching { call.receiveNullable<LogoutRequest>() }.getOrNull()
                body?.refreshToken?.let { rt ->
                    runCatching { refreshVerifier.verify(rt) }.getOrNull()?.let { decoded ->
                        val rjti = decoded.id
                        val rexp = decoded.expiresAt?.time ?: System.currentTimeMillis()
                        if (rjti != null) blocklist.revoke(rjti, rexp)
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
