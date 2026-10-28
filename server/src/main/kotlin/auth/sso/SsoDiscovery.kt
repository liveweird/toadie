package ch.nokillswit.auth.sso

import ch.nokillswit.infra.fetch.OperatorHttp
import io.ktor.util.AttributeKey
import java.net.URI
import java.net.URISyntaxException
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

/**
 * The endpoints the flow uses, already validated against the configured scheme rule. [endSessionEndpoint] is the one OPTIONAL
 * member (OIDC RP-initiated logout): absent, or present but failing the scheme rule, it is null and provider sign-out is
 * simply unavailable — it never fails a login.
 */
class SsoEndpoints(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val jwksUri: String,
    val endSessionEndpoint: String? = null,
)

private const val DISCOVERY_SUFFIX = ".well-known/openid-configuration"
private const val DISCOVERY_TTL_MILLIS = 60 * 60 * 1000L

/**
 * Lazy, cached OIDC discovery (`<issuer>/.well-known/openid-configuration`, one hour; a failure is never cached as good).
 * The document's `issuer` must equal the configured one exactly, and — outside development — every endpoint must be
 * https. The fetch happens outside any lock and overlapping first logins share one (single flight); when a refresh after
 * the hour fails, the last good document keeps being served for up to 24 more hours, retried at most once a minute
 * ([StaleServingCache]).
 */
class SsoDiscovery(
    private val config: SsoConfig,
    private val http: () -> OperatorHttp,
    /** One line per discovery fetch whose optional `end_session_endpoint` was unusable; the message names the field only. */
    private val onWarn: (String) -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
) {
    private val cache = StaleServingCache(DISCOVERY_TTL_MILLIS, clock = clock) { fetch() }

    suspend fun endpoints(): SsoEndpoints = cache.get()

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
            endSessionEndpoint = optionalEndpoint(document, "end_session_endpoint"),
        )
    }

    private fun endpoint(document: JsonObject, name: String): String {
        val value = document.string(name) ?: throw SsoUpstreamException("Discovery has no $name")
        return checkedEndpoint(name, value)
    }

    /** Absent -> null; present but unusable -> null plus one warning (never the value). */
    private fun optionalEndpoint(document: JsonObject, name: String): String? {
        val value = document.string(name) ?: return null
        return try {
            checkedEndpoint(name, value)
        } catch (_: SsoUpstreamException) {
            onWarn("Discovery $name is not an acceptable URL; sign-out through the provider is unavailable")
            null
        }
    }

    private fun checkedEndpoint(name: String, value: String): String {
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

private val strictJson = Json { isLenient = false; ignoreUnknownKeys = true }

/** The body as a JSON object, or null when it is not one (never throws, never echoes the body). */
internal fun parseObject(body: String): JsonObject? = try {
    strictJson.parseToJsonElement(body).jsonObject
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
