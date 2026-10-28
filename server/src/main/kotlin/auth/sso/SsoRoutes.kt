package ch.nokillswit.auth.sso

import ch.nokillswit.audit.audit
import ch.nokillswit.auth.AuthSessionServiceKey
import ch.nokillswit.auth.SSO_RATE_LIMIT
import ch.nokillswit.auth.authResponse
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.infra.fetch.OperatorHttp
import ch.nokillswit.infra.mail.mailAppUrl
import ch.nokillswit.plugins.JwtConfigKey
import ch.nokillswit.users.UserServiceKey
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.util.date.GMTDate
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId

/** Test seam for the flow's clock (cookie expiry, JWKS cache age, ID-token `exp`); production never sets it. */
val SsoClockKey = AttributeKey<Clock>("SsoClock")

private const val COOKIE_MAX_AGE_SECONDS = 10 * 60
private val NO_STORE_PATHS = setOf(LOGIN_OPTIONS_PATH, SSO_START_PATH, SSO_CALLBACK_PATH, SSO_EXCHANGE_PATH)

/** Delegates to the clock a test published (read per call, after the module loaded); the system UTC clock otherwise. */
private class SeamClock(private val application: Application) : Clock() {
    private fun delegate(): Clock = application.attributes.getOrNull(SsoClockKey) ?: systemUTC()

    override fun getZone(): ZoneId = delegate().zone

    override fun withZone(zone: ZoneId): Clock = delegate().withZone(zone)

    override fun instant() = delegate().instant()
}

/**
 * OIDC sign-in (`.claude/docs/security.md` "SSO login (OIDC)"), registered after `configureAuthRoutes` (which owns the
 * `sso` rate-limit bucket). `GET /api/v1/login/options` is ALWAYS registered — the sign-in page asks it whether to show
 * the SSO button, so a flag-off deployment must answer `{"sso":null}` rather than 404. The three flow routes exist only
 * with `sso.enabled=true`; with it, a missing or unsafe configuration refuses to start (a [SsoConfigException]).
 */
fun Application.configureSso() {
    val config = SsoConfig.fromConfig(environment.config, developmentMode, mailAppUrl())
    // Setup also covers a rate-limit rejection before any handler runs (the password-reset idiom): none of these
    // responses may ever be cached — they carry codes, redirects and the sign-in options.
    intercept(ApplicationCallPipeline.Setup) {
        if (call.request.path() in NO_STORE_PATHS) call.response.header("Cache-Control", "no-store")
    }
    val options = LoginOptionsResponse(config?.let { SsoOption(it.label) })
    // Outside the `sso` bucket on purpose: a constant answer costs nothing, and many people signing in behind one NAT
    // must never lose the SSO button to a 429 (the `/healthz` posture).
    routing {
        get(LOGIN_OPTIONS_PATH) { call.respond(HttpStatusCode.OK, options) }
    }
    if (config == null) {
        log.info("SSO login disabled (sso.enabled=false)")
        return
    }
    log.info("SSO login enabled ({})", config)
    config.bootWarnings().forEach { log.warn(it) }

    val clock = SeamClock(this)
    // Resolved on first use, after a test had its chance to publish a client that trusts its stub (the UrlFetcherKey idiom).
    val http by lazy { attributes.getOrNull(SsoHttpKey) ?: OperatorHttp(allowHttp = developmentMode) }
    val httpProvider: () -> OperatorHttp = { http }
    val users = attributes[UserServiceKey]
    val sessions = attributes[AuthSessionServiceKey]
    val jwtConfig = attributes[JwtConfigKey]
    val discovery = SsoDiscovery(config, httpProvider) { clock.millis() }
    val handoffs = SsoHandoffs(clock = { clock.millis() })
    val flow = SsoFlow(
        config = config,
        http = httpProvider,
        discovery = discovery,
        verifier = IdTokenVerifier(config, discovery, httpProvider, clock),
        cookies = SsoStateCookie(jwtConfig.secret, clock),
        seenStates = SsoSeenStates(clock = { clock.millis() }),
        handoffs = handoffs,
        accounts = SsoAccounts(users),
        // The class name only (the observability.md rule): a database or driver message can quote data.
        onFailureLog = { cause -> log.warn("SSO callback failed unexpectedly ({})", cause.javaClass.name) },
    )
    val secureCookie = !developmentMode
    // Production: `__Host-` + Secure + Path=/ + no Domain, so a sibling subdomain cannot toss its own cookie over ours.
    // Development serves plain http, which cannot carry a `__Host-` cookie. Set and clear share these attributes exactly.
    val cookieName = ssoCookieName(production = secureCookie)

    // Lax: the callback is a cross-site top-level navigation from the IdP, which Strict would not carry the cookie on.
    fun attemptCookie(value: String, maxAge: Int, expires: GMTDate? = null) = Cookie(
        name = cookieName,
        value = value,
        maxAge = maxAge,
        expires = expires,
        path = "/",
        secure = secureCookie,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Lax"),
    )

    fun ApplicationCall.setAttemptCookie(sealed: String) = response.cookies.append(attemptCookie(sealed, COOKIE_MAX_AGE_SECONDS))

    fun ApplicationCall.clearAttemptCookie() = response.cookies.append(attemptCookie("", 0, GMTDate.START))

    routing {
        rateLimit(RateLimitName(SSO_RATE_LIMIT)) {
            get(SSO_START_PATH) {
                val attempt = flow.newAttempt()
                try {
                    val location = flow.authorizationUrl(attempt)
                    call.setAttemptCookie(flow.seal(attempt))
                    call.respondRedirect(location)
                } catch (failure: SsoFlowException) {
                    audit("login.sso_failure", "reason" to failure.reason, "host" to failure.host)
                    call.respondRedirect(ssoErrorLocation(failure.error))
                }
            }
            get(SSO_CALLBACK_PATH) {
                val query = call.request.queryParameters
                try {
                    val result = flow.callback(call.request.cookies[cookieName], query["code"], query["state"], query["error"])
                    // The cookie stays: the exchange must present its bind secret again (a planted code is then useless).
                    call.respondRedirect("/login/sso#code=${result.code}")
                } catch (failure: SsoFlowException) {
                    // Only ids the signature vouches for, never a token, code, state or claim beyond email and subject.
                    audit(
                        "login.sso_failure",
                        "reason" to failure.reason,
                        "email" to failure.email,
                        "sub" to failure.subject,
                        "host" to failure.host,
                        "userId" to failure.userId?.toLong(),
                    )
                    // A request that did not prove it owns the attempt (state mismatch, replay) leaves the cookie alone.
                    if (failure.clearCookie) call.clearAttemptCookie()
                    call.respondRedirect(ssoErrorLocation(failure.error))
                }
            }
            post(SSO_EXCHANGE_PATH) {
                val req = call.receive<SsoExchangeRequest>()
                fun reject(): Nothing {
                    audit("login.sso_failure", "reason" to "handoff_invalid")
                    // Uniform for every cause: unknown, expired, replayed, wrong browser, account changed.
                    throw UnauthorizedException("Invalid or expired sign-in code")
                }
                val attempt = flow.open(call.request.cookies[cookieName]) ?: reject()
                val handoff = handoffs.consume(req.code) ?: reject()
                if (!MessageDigest.isEqual(handoff.bindHash, bindHash(attempt.bind))) reject()
                call.clearAttemptCookie()
                // The /login/mfa verify shape: still there, still active, and no credential/identity change since the callback.
                val user = users.read(handoff.userId)
                if (user == null || user.authVersion != handoff.authVersion) reject()
                val response = jwtConfig.authResponse(handoff.userId, user, sessions)
                audit("login.sso_success", "userId" to handoff.userId.toLong(), "outcome" to handoff.outcome.wire)
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
