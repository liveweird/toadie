package ch.nokillswit.plugins

import ch.nokillswit.auth.TOKEN_TYPE_ACCESS
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.auth.AuthSessionServiceKey
import ch.nokillswit.auth.sessionUserId
import ch.nokillswit.infra.catchingFailures
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.csrf.*
import io.ktor.util.AttributeKey

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val realm: String,
    val accessExpiresInSeconds: Long,
    val refreshExpiresInSeconds: Long,
)

/**
 * Set on the call when the revocation/session lookup inside `validate` threw: the challenge answers
 * the catch-all's 500 instead of the 401 a missing principal would otherwise produce — an outage
 * must never read as an invalid bearer token (ported from Lettuce/Flow; see "An outage reads as 500, never 401" in
 * `.claude/docs/security.md`).
 */
private val BlocklistFailureKey = AttributeKey<Throwable>("BlocklistFailure")

val JwtConfigKey = AttributeKey<JwtConfig>("JwtConfig")

fun Application.configureSecurity() {
    // Fail closed like the sibling gates (http.exposeOpenApi, http.behindProxy): a missing or
    // blank property means the documented default — OFF — never an accidental install.
    val csrfEnabled = environment.config.propertyOrNull("security.csrf.enabled")?.getString()
        ?.takeIf { it.isNotBlank() }?.toBoolean() ?: false
    if (csrfEnabled) {
        install(CSRF) {
            allowOrigin("http://localhost:8081")
            originMatchesHost()
            checkHeader("X-CSRF-Token")
            // The plugin's default rejection is a text/plain 400 and runs before StatusPages —
            // emit the RFC 7807 body here so every error response stays problem+json.
            onFailure { message ->
                respondProblem(HttpStatusCode.BadRequest, "Cross-site request validation failed: $message")
            }
        }
    }
    val jwtConfig = JwtConfig(
        secret = environment.config.property("jwt.secret").getString(),
        issuer = environment.config.property("jwt.issuer").getString(),
        audience = environment.config.property("jwt.audience").getString(),
        realm = environment.config.property("jwt.realm").getString(),
        accessExpiresInSeconds = environment.config.property("jwt.accessExpiresInSeconds").getString().toLong(),
        refreshExpiresInSeconds = environment.config.property("jwt.refreshExpiresInSeconds").getString().toLong(),
    )
    // Fail closed: a blank secret, the placeholder "secret", or the repo-committed demo key lets
    // anyone forge tokens for any user/role. Allowed (with a loud warning) only in development;
    // rejected at startup in production.
    val burnedSecrets = setOf(
        "secret",
        // Committed to docker-compose.yaml for the local clone-&-run demo — public, thus burned.
        "dev-only-00366d050fa2c920f7efb9a880b8b9c60e693b1797e782a33ddbfb51f88ea9d0",
        // The k8s/templates/secret.yaml template placeholder — applying the template verbatim must not boot.
        "CHANGE-ME-openssl-rand-hex-32",
    )
    if (jwtConfig.secret.isBlank() || jwtConfig.secret in burnedSecrets) {
        val message = "JWT secret is unset or a publicly known value — set a strong, private JWT_SECRET."
        if (developmentMode) log.warn("$message (permitted in development only)")
        else error(message)
    }
    attributes.put(JwtConfigKey, jwtConfig)
    authentication {
        jwt {
            realm = jwtConfig.realm
            verifier(
                JWT.require(Algorithm.HMAC256(jwtConfig.secret))
                    .withAudience(jwtConfig.audience)
                    .withIssuer(jwtConfig.issuer)
                    .build()
            )
            validate { credential ->
                val audOk = credential.payload.audience.contains(jwtConfig.audience)
                // Only access tokens authenticate API calls; a refresh token used as a bearer is rejected.
                val typOk = credential.payload.getClaim("typ").asString() == TOKEN_TYPE_ACCESS
                // Every server-minted token carries a jti; one without it could never be
                // blocklisted, so it is rejected outright rather than skipping the check.
                val jti = credential.payload.id ?: return@validate null
                val userId = credential.payload.sessionUserId()
                val sessionId = credential.payload.getClaim("sid").asString()
                // Structurally invalid tokens (claims, jti, user, session) never reach the database.
                if (!audOk || !typOk || userId == null || sessionId == null) return@validate null
                // A cancelled call (client gone) mid-lookup unwinds — catchingFailures never
                // swallows cancellation.
                val live = catchingFailures({
                    !application.attributes[TokenBlocklistServiceKey].isRevoked(jti) &&
                        application.attributes[AuthSessionServiceKey].isActive(sessionId, userId)
                }) { cause ->
                    // The lookup itself failed (database unreachable, pool acquire timeout) — that
                    // is an outage, not an invalid token. Ktor's JWT provider turns ANY throw out
                    // of validate into a plain challenge, so the cause is stashed on the call for
                    // the challenge below to answer with the catch-all's 500 instead of a 401
                    // (which the SPA reads as session expiry).
                    attributes.put(BlocklistFailureKey, cause)
                    return@validate null
                }
                if (live) JWTPrincipal(credential.payload) else null
            }
            // The challenge runs outside StatusPages, so emit the RFC 7807 body here too.
            challenge { _, _ ->
                val failure = call.attributes.getOrNull(BlocklistFailureKey)
                if (failure != null) {
                    call.respondInternalError(failure)
                } else {
                    call.respondProblem(HttpStatusCode.Unauthorized, "Missing or invalid bearer token")
                }
            }
        }
    }
}
