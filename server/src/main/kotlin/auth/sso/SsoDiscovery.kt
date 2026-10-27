package ch.nokillswit.auth.sso

import ch.nokillswit.infra.fetch.OperatorHttp
import io.ktor.util.AttributeKey
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The shared outbound client of the SSO flow. Production wiring builds `OperatorHttp(allowHttp)` lazily
 * (`SsoRoutes.kt`); a test publishes a client that trusts its in-process TLS stub under this key AFTER the
 * application module loaded (the `UrlFetcherKey` idiom). Construction of [OperatorHttp] is confined to
 * `auth/sso/` (`.claude/docs/security.md` "Operator-configured outbound HTTP").
 */
val SsoHttpKey = AttributeKey<OperatorHttp>("SsoHttp")

/**
 * A failure talking to the configured issuer (transport error, non-200 discovery/JWKS, a malformed or
 * mismatching document). Maps to the audit reason `idp_unavailable` and the `failed` redirect; the message
 * never carries a URL, a body or a credential.
 */
class SsoUpstreamException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The three endpoints the flow uses, already validated against the configured scheme rule. */
class SsoEndpoints(val authorizationEndpoint: String, val tokenEndpoint: String, val jwksUri: String)

private const val DISCOVERY_SUFFIX = ".well-known/openid-configuration"
private const val DISCOVERY_TTL_MILLIS = 60 * 60 * 1000L

/**
 * Lazy, cached OIDC discovery (`<issuer>/.well-known/openid-configuration`, one hour; a failure is never cached).
 * The document's `issuer` must equal the configured one exactly, and — outside development — every endpoint
 * must be https. Overlapping first logins share one fetch (the mutex).
 */
class SsoDiscovery(
    private val config: SsoConfig,
    private val http: () -> OperatorHttp,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var cached: SsoEndpoints? = null
    private var fetchedAt = 0L

    suspend fun endpoints(): SsoEndpoints = lock.withLock {
        val current = cached
        if (current != null && clock() - fetchedAt < DISCOVERY_TTL_MILLIS) return current
        fetch().also {
            cached = it
            fetchedAt = clock()
        }
    }

    private suspend fun fetch(): SsoEndpoints {
        val url = config.issuer.let { if (it.endsWith("/")) it else "$it/" } + DISCOVERY_SUFFIX
        val response = http().getJson(url)
        if (response.status != 200) throw SsoUpstreamException("Discovery answered status ${response.status}")
        val document = parseObject(response.body) ?: throw SsoUpstreamException("Discovery document is not a JSON object")
        if (document.string("issuer") != config.issuer) throw SsoUpstreamException("Discovery issuer does not match the configured issuer")
        return SsoEndpoints(
            authorizationEndpoint = endpoint(document, "authorization_endpoint"),
            tokenEndpoint = endpoint(document, "token_endpoint"),
            jwksUri = endpoint(document, "jwks_uri"),
        )
    }

    private fun endpoint(document: JsonObject, name: String): String {
        val value = document.string(name) ?: throw SsoUpstreamException("Discovery has no $name")
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            throw SsoUpstreamException("Discovery $name is not a URL")
        }
        val schemeOk = uri.scheme == "https" || (config.allowHttp && uri.scheme == "http")
        if (!schemeOk || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) {
            throw SsoUpstreamException("Discovery $name is not an acceptable URL")
        }
        return value
    }
}

private val lenientJson = Json { isLenient = false; ignoreUnknownKeys = true }

/** The body as a JSON object, or null when it is not one (never throws, never echoes the body). */
internal fun parseObject(body: String): JsonObject? = try {
    lenientJson.parseToJsonElement(body).jsonObject
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
