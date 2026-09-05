package ch.nokillswit.plugins

import ch.nokillswit.auth.TOKEN_TYPE_ACCESS
import ch.nokillswit.auth.TokenBlocklistServiceKey
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
                val jti = credential.payload.id
                val revocable = jti != null && !application.attributes[TokenBlocklistServiceKey].isRevoked(jti)
                if (audOk && typOk && revocable) JWTPrincipal(credential.payload) else null
            }
            // The challenge runs outside StatusPages, so emit the RFC 7807 body here too.
            challenge { _, _ ->
                call.respondProblem(HttpStatusCode.Unauthorized, "Missing or invalid bearer token")
            }
        }
    }
}
