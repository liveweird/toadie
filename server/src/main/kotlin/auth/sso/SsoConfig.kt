package ch.nokillswit.auth.sso

import ch.nokillswit.auth.resetAppUrl
import io.ktor.server.config.ApplicationConfig
import java.net.URI
import java.net.URISyntaxException

internal const val SSO_PATH_PREFIX = "/api/v1/login/sso"
internal const val SSO_START_PATH = "$SSO_PATH_PREFIX/start"
internal const val SSO_CALLBACK_PATH = "$SSO_PATH_PREFIX/callback"
internal const val SSO_EXCHANGE_PATH = "$SSO_PATH_PREFIX/exchange"
internal const val LOGIN_OPTIONS_PATH = "/api/v1/login/options"

/** The SPA's sign-in page: where the provider returns the browser after its session ended. */
private const val POST_LOGOUT_PATH = "/login"
private const val DEFAULT_LABEL = "Okta"
private const val DEFAULT_SCOPES = "openid email profile"
private const val MAX_LABEL_LENGTH = 50
private const val MAX_CONFIG_URL_LENGTH = 2048

/** `users.sso_issuer` is VARCHAR(512) (V48): a longer issuer could never be stored on an account. */
private const val MAX_ISSUER_LENGTH = 512
private const val MAX_URL_PORT = 65535

// RFC 6749 section 3.3: a scope token is one or more printable ASCII characters other than space, '"' and '\'.
private val SCOPE_TOKEN = Regex("""[\x21\x23-\x5B\x5D-\x7E]+""")
private val EMAIL_DOMAIN = Regex("""[a-z0-9]([a-z0-9.-]*[a-z0-9])?""")

/** Thrown at module start when the SSO configuration is incomplete or unsafe — the app refuses to boot. */
class SsoConfigException(message: String) : IllegalStateException(message)

/**
 * The validated `sso:` configuration (`application.yaml`). Only ever built when `sso.enabled=true`;
 * with the flag off nothing here is read. [clientSecret] is deliberately absent from [toString].
 */
class SsoConfig internal constructor(
    val label: String,
    /** Exactly as configured (whitespace trimmed): the discovered and the token `iss` must equal it byte for byte. */
    val issuer: String,
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    /**
     * Where the provider sends the browser after ending its session (`post_logout_redirect_uri`): the origin of [redirectUri] plus
     * `/login`, or the validated `sso.postLogoutRedirectUri`. Must be registered at the provider as a sign-out redirect URI.
     */
    val postLogoutRedirectUri: String,
    val scopes: String,
    val allowedEmailDomains: Set<String>,
    /** Development mode only: lets the issuer, the redirect URI and the discovered endpoints be plain http. */
    val allowHttp: Boolean,
) {
    /** True when the canonical (lower-cased) [email] belongs to an admitted domain; blank list = any. */
    fun isEmailAllowed(email: String): Boolean =
        allowedEmailDomains.isEmpty() || email.substringAfterLast('@', "") in allowedEmailDomains

    /** Boot-time WARN lines [configureSso] logs; empty when nothing needs the operator's attention. */
    fun bootWarnings(): List<String> = buildList {
        if (allowedEmailDomains.isEmpty()) {
            add(
                "sso.allowedEmailDomains is blank: any identity the provider authenticates may create an account; " +
                    "restrict who can sign in with the provider's own app assignment (SSO_ALLOWED_EMAIL_DOMAINS narrows it here)",
            )
        }
    }

    /** Label and issuer HOST only: no secret, and never a full URL in a log line. */
    override fun toString(): String = "SsoConfig(label=$label, issuerHost=${URI(issuer).host})"

    companion object {
        /**
         * Reads `sso.*`; null when the flag is off. Every violation throws [SsoConfigException] naming the
         * property, never its value (the client secret above all). [mailAppUrl] is `mail.appUrl`, the
         * fallback origin for the redirect URI.
         */
        fun fromConfig(config: ApplicationConfig, development: Boolean, mailAppUrl: String?): SsoConfig? {
            fun read(key: String): String = config.propertyOrNull("sso.$key")?.getString().orEmpty()
            if (!read("enabled").trim().toBoolean()) return null

            val label = read("label").trim().ifEmpty { DEFAULT_LABEL }
            if (label.length > MAX_LABEL_LENGTH || label.any { it.isISOControl() }) {
                throw SsoConfigException("sso.label must be at most $MAX_LABEL_LENGTH characters without control characters")
            }
            val issuer = read("issuer").trim()
            val clientId = read("clientId").trim()
            val clientSecret = read("clientSecret").trim()
            if (issuer.isEmpty()) throw SsoConfigException("sso.issuer is required when SSO is enabled (SSO_ISSUER)")
            if (clientId.isEmpty()) throw SsoConfigException("sso.clientId is required when SSO is enabled (SSO_CLIENT_ID)")
            if (clientSecret.isBlank()) {
                throw SsoConfigException("sso.clientSecret is required when SSO is enabled (SSO_CLIENT_SECRET)")
            }
            requireUrl("sso.issuer", issuer, development)
            if (issuer.length > MAX_ISSUER_LENGTH) throw SsoConfigException("sso.issuer must be at most $MAX_ISSUER_LENGTH characters")

            val redirectUri = redirectUriOf(read("redirectUri").trim(), mailAppUrl, development)
            val postLogoutRedirectUri = read("postLogoutRedirectUri").trim().ifEmpty { null }
                ?.also { requireUrl("sso.postLogoutRedirectUri", it, development) }
                ?: (originOf(redirectUri) + POST_LOGOUT_PATH)
            val scopes = read("scopes").trim().ifEmpty { DEFAULT_SCOPES }.split(Regex("\\s+"))
            if ("openid" !in scopes || scopes.any { !SCOPE_TOKEN.matches(it) }) {
                throw SsoConfigException("sso.scopes must be space-separated scope tokens including openid")
            }
            return SsoConfig(
                label = label,
                issuer = issuer,
                clientId = clientId,
                clientSecret = clientSecret,
                redirectUri = redirectUri,
                postLogoutRedirectUri = postLogoutRedirectUri,
                scopes = scopes.joinToString(" "),
                allowedEmailDomains = allowedDomainsOf(read("allowedEmailDomains")),
                allowHttp = development,
            )
        }

        private fun redirectUriOf(configured: String, mailAppUrl: String?, development: Boolean): String {
            if (configured.isNotEmpty()) {
                requireUrl("sso.redirectUri", configured, development)
                // EQUAL, not a suffix: the attempt cookie is `Path=/` and the callback's Location is root-relative, so a
                // sub-path deployment cannot work (the same no-sub-path rule as `resetAppUrl`).
                if (URI(configured).rawPath.orEmpty() != SSO_CALLBACK_PATH) {
                    throw SsoConfigException("sso.redirectUri must have exactly the path $SSO_CALLBACK_PATH")
                }
                return configured
            }
            // The same origin rule as the reset/MFA email links (https; http in development): never the Host header.
            val origin = resetAppUrl(mailAppUrl, development)
                ?: throw SsoConfigException(
                    "sso.redirectUri (SSO_REDIRECT_URI) is required when SSO is enabled and MAIL_APP_URL is not a usable origin",
                )
            return origin + SSO_CALLBACK_PATH
        }

        /** `scheme://authority` of an already validated URL (it has a host and no userinfo). */
        private fun originOf(url: String): String = URI(url).let { "${it.scheme}://${it.rawAuthority}" }

        private fun allowedDomainsOf(raw: String): Set<String> =
            raw.split(',').map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }
                .onEach {
                    if (!EMAIL_DOMAIN.matches(it)) throw SsoConfigException("sso.allowedEmailDomains has an invalid domain entry")
                }.toSet()

        /** https always; http only in development; no userinfo, query or fragment (an issuer or callback is a bare location). */
        private fun requireUrl(property: String, value: String, development: Boolean) {
            val uri = try {
                URI(value)
            } catch (_: URISyntaxException) {
                throw SsoConfigException("$property is not a valid URL")
            }
            val schemeOk = uri.scheme == "https" || (development && uri.scheme == "http")
            val shapeOk = !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null &&
                uri.rawFragment == null && uri.port in -1..MAX_URL_PORT && value.length <= MAX_CONFIG_URL_LENGTH
            if (!schemeOk) {
                throw SsoConfigException("$property must be an https URL" + if (development) " (http is allowed in development)" else "")
            }
            if (!shapeOk) throw SsoConfigException("$property must be an absolute URL without credentials, query or fragment")
        }
    }
}
