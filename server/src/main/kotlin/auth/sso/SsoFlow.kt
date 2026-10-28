package ch.nokillswit.auth.sso

import ch.nokillswit.audit.audit
import ch.nokillswit.auth.ProviderSignOutResult
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.fetch.OperatorHttp
import ch.nokillswit.infra.fetch.OperatorHttpException
import io.ktor.http.URLBuilder
import java.net.URI
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * [userId] is the soft-deleted account of an `account_deleted` refusal (the row an admin releases).
 * [host] is the unreachable issuer-side host of an `idp_unavailable` failure (never a URL). [clearCookie] is false when the
 * request did not prove it owns the attempt (a state mismatch or replay): such a request must leave the browser's valid
 * attempt cookie alone, or a forged cross-site callback would kill the victim's in-flight sign-in.
 */
class SsoFlowException(
    val reason: String,
    val error: String,
    val email: String? = null,
    val subject: String? = null,
    cause: Throwable? = null,
    val host: String? = null,
    val clearCookie: Boolean = true,
    val userId: UInt? = null,
) : RuntimeException("SSO sign-in failed: $reason", cause)

/**
 * The most a logout waits for the provider step (discovery read). A hung IdP must not hold the sign-out for
 * [OperatorHttp]'s own 10 s; past this the logout is local only (`sso=unavailable`).
 */
internal val SIGN_OUT_DEADLINE: Duration = 3.seconds

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
    private val seenStates: SsoSeenStates,
    private val handoffs: SsoHandoffs,
    private val accounts: SsoAccounts,
    private val onFailureLog: (Throwable) -> Unit = {},
    /** The provider step's deadline, read per logout (a test publishes a short one after the module loaded). */
    private val signOutDeadline: () -> Duration = { SIGN_OUT_DEADLINE },
    /**
     * Called once per logout whose provider step went wrong, with a safe cause description (class name, failure kind, or
     * `deadline`): [servedFromCache] is true when a hung refresh was answered from the still-servable cached discovery
     * document (the logout redirects anyway), false for [ProviderSignOutResult.Unavailable].
     */
    private val onSignOutProblem: (cause: String, servedFromCache: Boolean) -> Unit = { _, _ -> },
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

    /** Completes one callback. [cookie] is the raw attempt-cookie value; [code]/[state]/[error] are the three query parameters. */
    suspend fun callback(cookie: String?, code: String?, state: String?, error: String?): SsoCallbackResult = failuresAsSso {
        val attempt = cookies.open(cookie) ?: throw SsoFlowException("state_invalid", SSO_ERROR_EXPIRED)
        // The state comes BEFORE the IdP's error: a forged cross-site `?error=...&state=x` proves nothing about this attempt
        // and must neither end it nor clear its cookie.
        if (state == null || !MessageDigest.isEqual(state.toByteArray(), attempt.state.toByteArray())) {
            throw SsoFlowException("state_invalid", SSO_ERROR_FAILED, clearCookie = false)
        }
        if (error != null) {
            throw SsoFlowException("idp_error", if (error == "access_denied") SSO_ERROR_CANCELLED else SSO_ERROR_FAILED)
        }
        if (code.isNullOrEmpty()) throw SsoFlowException("state_invalid", SSO_ERROR_FAILED, clearCookie = false)
        // Single use: one cookie + state pair reaches the token endpoint once (a replayed callback is refused before any request).
        if (!seenStates.firstUse(attempt.state, attempt.exp)) {
            throw SsoFlowException("state_invalid", SSO_ERROR_FAILED, clearCookie = false)
        }
        val rawIdToken = exchangeCode(code, attempt)
        val token = verifier.verify(rawIdToken, attempt.nonce)
        val resolved = try {
            accounts.resolve(config.issuer, token)
        } catch (refusal: SsoAccountRefusedException) {
            throw SsoFlowException(refusal.reason, SSO_ERROR_NOT_ALLOWED, token.email, token.subject, refusal, userId = refusal.userId)
        }
        auditAccount(resolved, token)
        // The version comes from the resolving transaction itself: a second read could see a later unlink's bump
        // and hand a session to an unlinked row.
        SsoCallbackResult(
            handoffs.issue(resolved.userId, resolved.authVersion, bindHash(attempt.bind), resolved.outcome, rawIdToken),
        )
    }

    /**
     * The provider's end-session location for [idToken] (OIDC RP-initiated logout): `id_token_hint` plus our
     * `post_logout_redirect_uri`, no `state`. Never throws for a provider problem — an unreadable or too slow discovery
     * is [ProviderSignOutResult.Unavailable] — and never logs the token or the URL. It runs under [NonCancellable] and
     * a [signOutDeadline]: the session is already revoked, so a client that hung up must not skip the caller's `logout`
     * audit, and a hung IdP must not hold the logout. A hung refresh behaves like a refused one: while the last good
     * discovery document is still servable its end-session endpoint is used ([onSignOutProblem] logs that).
     */
    suspend fun endSessionUrl(idToken: String): ProviderSignOutResult = withContext(NonCancellable) {
        withTimeoutOrNull(signOutDeadline()) {
            catchingFailures(
                { providerSignOut(discovery.endpoints(), idToken) },
                { cause -> unavailable(describe(cause)) },
            )
        } ?: deadlineFallback(idToken)
    }

    private fun deadlineFallback(idToken: String): ProviderSignOutResult {
        val cached = discovery.cachedEndpoints() ?: return unavailable("deadline")
        onSignOutProblem("deadline", true)
        return providerSignOut(cached, idToken)
    }

    private fun providerSignOut(endpoints: SsoEndpoints, idToken: String): ProviderSignOutResult {
        val endpoint = endpoints.endSessionEndpoint ?: return ProviderSignOutResult.Unsupported
        return ProviderSignOutResult.Redirect(
            URLBuilder(endpoint).apply {
                parameters.append("id_token_hint", idToken)
                parameters.append("post_logout_redirect_uri", config.postLogoutRedirectUri)
            }.buildString(),
        )
    }

    private fun unavailable(description: String): ProviderSignOutResult {
        onSignOutProblem(description, false)
        return ProviderSignOutResult.Unavailable
    }

    // Class name (and the OperatorHttp failure kind): never the message, which a parser or driver may fill with data.
    private fun describe(cause: Exception): String =
        (cause as? OperatorHttpException)?.let { "${it.javaClass.simpleName}/${it.kind}" } ?: cause.javaClass.simpleName

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
            audit("user.sso_linked", "userId" to resolved.userId.toLong(), "issuerHost" to URI(config.issuer).host)
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
            is OperatorHttpException -> SsoFlowException("idp_unavailable", SSO_ERROR_FAILED, host = cause.host)
            is SsoUpstreamException -> SsoFlowException("idp_unavailable", SSO_ERROR_FAILED)
            is SsoHandoffs.CapacityExceededException -> SsoFlowException("handoff_capacity", SSO_ERROR_FAILED)
            else -> {
                onFailureLog(cause)
                SsoFlowException("internal_error", SSO_ERROR_FAILED)
            }
        }
    }
}
