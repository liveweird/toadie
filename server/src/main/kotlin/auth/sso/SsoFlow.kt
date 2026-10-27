package ch.nokillswit.auth.sso

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.fetch.OperatorHttp
import ch.nokillswit.infra.fetch.OperatorHttpException
import ch.nokillswit.users.UserService
import io.ktor.http.URLBuilder
import java.security.MessageDigest

/** The `sso_error` fragment values the SPA understands (`/login#sso_error=<value>`). */
internal const val SSO_ERROR_CANCELLED = "cancelled"
internal const val SSO_ERROR_EXPIRED = "expired"
internal const val SSO_ERROR_FAILED = "failed"
internal const val SSO_ERROR_NOT_ALLOWED = "not_allowed"

/** The relative login location every failed callback ends at; the reason travels in the fragment, never a query. */
internal fun ssoErrorLocation(error: String): String = "/login#sso_error=$error"

/**
 * A sign-in attempt that ends at the error redirect. [reason] is the precise `login.sso_failure` audit reason; [error] is
 * the coarse, user-facing `sso_error` code. [email] and [subject] are set only once the ID token's signature verified.
 */
class SsoFlowException(
    val reason: String,
    val error: String,
    val email: String? = null,
    val subject: String? = null,
    cause: Throwable? = null,
) : RuntimeException("SSO sign-in failed: $reason", cause)

/** A successful callback: the one-time [code] for the fragment redirect (the account is already resolved). */
class SsoCallbackResult(val code: String)

/**
 * The OIDC authorization-code flow, minus HTTP plumbing (`SsoRoutes.kt` owns cookies, redirects and the response audit):
 * start builds the authorization URL, callback validates state, exchanges the code, verifies the ID token, resolves the
 * account and stores the hand-off. Everything the callback can fail with surfaces as one [SsoFlowException].
 */
class SsoFlow(
    private val config: SsoConfig,
    private val http: () -> OperatorHttp,
    private val discovery: SsoDiscovery,
    private val verifier: IdTokenVerifier,
    private val cookies: SsoStateCookie,
    private val handoffs: SsoHandoffs,
    private val accounts: SsoAccounts,
    private val users: UserService,
    private val onFailureLog: (Throwable) -> Unit = {},
) {
    fun newAttempt(): SsoAttempt = cookies.newAttempt()

    fun seal(attempt: SsoAttempt): String = cookies.seal(attempt)

    fun open(cookie: String?): SsoAttempt? = cookies.open(cookie)

    /** The IdP location for [attempt]: code flow, query response mode, PKCE S256, the configured scopes. */
    suspend fun authorizationUrl(attempt: SsoAttempt): String = failuresAsSso {
        URLBuilder(discovery.endpoints().authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("response_mode", "query")
            parameters.append("client_id", config.clientId)
            parameters.append("redirect_uri", config.redirectUri)
            parameters.append("scope", config.scopes)
            parameters.append("state", attempt.state)
            parameters.append("nonce", attempt.nonce)
            parameters.append("code_challenge", pkceChallenge(attempt.verifier))
            parameters.append("code_challenge_method", "S256")
        }.buildString()
    }

    /** Completes one callback. [cookie] is the raw `toadie_sso` value; [code]/[state]/[error] are the three query parameters. */
    suspend fun callback(cookie: String?, code: String?, state: String?, error: String?): SsoCallbackResult = failuresAsSso {
        val attempt = cookies.open(cookie) ?: throw SsoFlowException("state_invalid", SSO_ERROR_EXPIRED)
        if (error != null) {
            throw SsoFlowException("idp_error", if (error == "access_denied") SSO_ERROR_CANCELLED else SSO_ERROR_FAILED)
        }
        if (code.isNullOrEmpty() || state == null || !MessageDigest.isEqual(state.toByteArray(), attempt.state.toByteArray())) {
            throw SsoFlowException("state_invalid", SSO_ERROR_FAILED)
        }
        val token = verifier.verify(exchangeCode(code, attempt), attempt.nonce)
        val resolved = try {
            accounts.resolve(config.issuer, token)
        } catch (refusal: SsoAccountRefusedException) {
            throw SsoFlowException(refusal.reason, SSO_ERROR_NOT_ALLOWED, token.email, token.subject, refusal)
        }
        auditAccount(resolved, token)
        val user = users.read(resolved.userId)
            ?: throw SsoFlowException("account_deleted", SSO_ERROR_NOT_ALLOWED, token.email, token.subject)
        SsoCallbackResult(handoffs.issue(resolved.userId, user.authVersion, bindHash(attempt.bind), resolved.outcome))
    }

    /** The token endpoint exchange (`client_secret_basic` + the PKCE verifier); returns the raw `id_token`. */
    private suspend fun exchangeCode(code: String, attempt: SsoAttempt): String {
        val response = http().postForm(
            discovery.endpoints().tokenEndpoint,
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to config.redirectUri,
                "code_verifier" to attempt.verifier,
            ),
            basicAuth = config.clientId to config.clientSecret,
        )
        val idToken = if (response.status == 200) parseObject(response.body)?.string("id_token") else null
        return idToken ?: throw SsoFlowException("token_exchange_failed", SSO_ERROR_FAILED)
    }

    private fun auditAccount(resolved: SsoResolved, token: VerifiedIdToken) {
        // A just-created account has no acting admin: byUserId is omitted, `via=sso` names the origin (observability.md).
        if (resolved.outcome == SsoOutcome.CREATED) {
            audit("user.created", "newUserId" to resolved.userId.toLong(), "email" to token.email, "via" to "sso")
        }
        if (resolved.outcome == SsoOutcome.LINKED) {
            audit("user.sso_linked", "userId" to resolved.userId.toLong(), "issuer" to config.issuer)
        }
    }

    /** Runs [block], turning every ordinary failure into the [SsoFlowException] that carries its audit reason. */
    private suspend fun <T> failuresAsSso(block: suspend () -> T): T = catchingFailures({ block() }) { cause ->
        throw when (cause) {
            is SsoFlowException -> cause
            is IdTokenRejectedException -> SsoFlowException(
                cause.reason,
                if (cause.reason == "id_token_invalid") SSO_ERROR_FAILED else SSO_ERROR_NOT_ALLOWED,
                cause.email,
                cause.subject,
            )
            is OperatorHttpException, is SsoUpstreamException -> SsoFlowException("idp_unavailable", SSO_ERROR_FAILED)
            is SsoHandoffs.CapacityExceededException -> SsoFlowException("handoff_capacity", SSO_ERROR_FAILED)
            else -> {
                onFailureLog(cause)
                SsoFlowException("internal_error", SSO_ERROR_FAILED)
            }
        }
    }
}
