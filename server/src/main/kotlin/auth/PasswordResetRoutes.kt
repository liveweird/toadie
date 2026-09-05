package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.infra.mail.mailAppUrl
import ch.nokillswit.infra.mail.mailer
import ch.nokillswit.plugins.respondProblem
import ch.nokillswit.users.UserServiceKey
import ch.nokillswit.users.canonicalEmail
import ch.nokillswit.users.validateEmail
import ch.nokillswit.users.validatePassword
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.URI
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

internal const val PASSWORD_RESET_RATE_LIMIT = "password-reset"
internal const val PASSWORD_RESET_CONFIRM_RATE_LIMIT = "password-reset-confirm"
private const val MAX_RESET_URL_LENGTH = 2048
private const val MAX_URL_PORT = 65535
private const val RESET_PATH = "/api/v1/password-reset"
private const val CONFIRM_PATH = "$RESET_PATH/confirm"

@Serializable
data class PasswordResetRequest(val email: String)

@Serializable
data class PasswordResetConfirmRequest(val token: String, val password: String)

/** Only deployment configuration may select a reset origin; never the request Host header. */
internal fun resetAppUrl(value: String?, development: Boolean): String? {
    if (value.isNullOrBlank() || value.length > MAX_RESET_URL_LENGTH) return null
    val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { return null }
    return value.trimEnd('/').takeIf {
        (uri.scheme == "https" || (development && uri.scheme == "http")) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath.orEmpty() in setOf("", "/") && uri.port in -1..MAX_URL_PORT
    }
}

fun Application.configurePasswordResetRoutes() {
    val users = attributes[UserServiceKey]
    val resets = attributes[PasswordResetServiceKey]
    val mailer = mailer()
    val appUrl = resetAppUrl(mailAppUrl(), developmentMode)
    val ttlSeconds = environment.config.property("security.passwordReset.tokenTtlSeconds").getString().toLong()
    val throttle = PasswordResetThrottle(
        minIntervalMillis = environment.config.property("security.passwordReset.minIntervalSeconds").getString().toLong() * 1000,
    )
    // Setup also covers rate-limit/malformed-body failures before the handler runs.
    intercept(ApplicationCallPipeline.Setup) {
        if (call.request.path() == RESET_PATH || call.request.path() == CONFIRM_PATH) {
            call.response.header("Cache-Control", "no-store")
        }
    }
    routing {
        rateLimit(RateLimitName(PASSWORD_RESET_RATE_LIMIT)) {
            post(RESET_PATH) {
                val email = canonicalEmail(call.receive<PasswordResetRequest>().email)
                validateEmail(email)
                if (mailer == null || appUrl == null) {
                    call.respondProblem(HttpStatusCode.ServiceUnavailable, "Password reset email is not configured")
                    return@post
                }
                if (!throttle.tryAcquire(email)) {
                    audit("password_reset.throttled", "email" to email)
                    throw TooManyRequestsException("Password reset requested too recently — try again shortly")
                }
                audit("password_reset.requested", "email" to email)
                val app = call.application
                app.launch { processPasswordReset(app, users, resets, mailer, appUrl, ttlSeconds, email) }
                call.respond(HttpStatusCode.Accepted)
            }
        }
        rateLimit(RateLimitName(PASSWORD_RESET_CONFIRM_RATE_LIMIT)) {
            post(CONFIRM_PATH) {
                val req = call.receive<PasswordResetConfirmRequest>()
                fun reject(): Nothing {
                    audit("password_reset.rejected")
                    throw UnauthorizedException("Reset link is invalid or expired — request a new link")
                }
                if (!resets.isUsable(req.token)) reject()
                validatePassword(req.password)
                val userId = resets.complete(req.token, hashPassword(req.password)) ?: reject()
                audit("password_reset.completed", "userId" to userId.toLong())
                if (mailer != null) {
                    val app = call.application
                    app.launch { notifyPasswordReset(app, users, mailer, userId) }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
