package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.sso.SsoClockKey
import ch.nokillswit.auth.sso.SsoExchangeRequest
import ch.nokillswit.auth.sso.SsoHttpKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.setCookie
import io.ktor.server.testing.ApplicationTestBuilder
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// The SSO login harness shared by `SsoLoginTest` and `SsoUnlinkTest`: boot the real app against an in-process [StubIdp] and play
// the browser (no redirect following, the attempt cookie carried by hand). Rules: `.claude/docs/security.md` "SSO login (OIDC)".

internal const val START = "/api/v1/login/sso/start"
internal const val CALLBACK = "/api/v1/login/sso/callback"
internal const val EXCHANGE = "/api/v1/login/sso/exchange"
internal const val COOKIE = "toadie_sso"
internal const val HOST_COOKIE = "__Host-toadie_sso"
internal const val TEST_JWT_SECRET = "sso-test-jwt-secret-0123456789abcdef"
internal const val REDIRECT_URI = "http://localhost:8081/api/v1/login/sso/callback"

internal class Attempt(val cookie: String, val location: String)

internal class SignIn(val attempt: Attempt, val callback: HttpResponse) {
    val location: String get() = callback.headers[HttpHeaders.Location].orEmpty()
    val code: String? get() = location.substringAfter("#code=", "").ifEmpty { null }
    val error: String? get() = location.substringAfter("#sso_error=", "").ifEmpty { null }
}

internal suspend fun ApplicationTestBuilder.ssoApp(
    stub: StubIdp,
    vararg overrides: Pair<String, String>,
    clock: Clock? = null,
    production: Boolean = false,
) {
    configureApp(
        "sso.enabled" to "true",
        "sso.issuer" to stub.issuer,
        "sso.clientId" to stub.clientId,
        "sso.clientSecret" to stub.clientSecret,
        *overrides,
    )
    if (production) serverConfig { developmentMode = false }
    application {
        attributes.put(SsoHttpKey, stub.http())
        if (clock != null) attributes.put(SsoClockKey, clock)
    }
    startApplication()
}

internal fun ApplicationTestBuilder.browser(): HttpClient = createClient {
    followRedirects = false
    toadieTestClientDefaults()
}

internal suspend fun HttpClient.start(): Attempt {
    val response = get(START)
    assertEquals(HttpStatusCode.Found, response.status)
    val cookie = response.setCookie().first { it.name == COOKIE }.value
    return Attempt(cookie, response.headers[HttpHeaders.Location].orEmpty())
}

internal suspend fun HttpClient.callback(cookie: String?, query: String): HttpResponse = get("$CALLBACK?$query") {
    cookie?.let { header(HttpHeaders.Cookie, "$COOKIE=$it") }
}

internal suspend fun HttpClient.exchange(code: String, cookie: String?): HttpResponse = post(EXCHANGE) {
    contentType(ContentType.Application.Json)
    setBody(SsoExchangeRequest(code))
    cookie?.let { header(HttpHeaders.Cookie, "$COOKIE=$it") }
}

/** start -> provider -> callback, with the claims/overrides of [authorize]. */
internal suspend fun HttpClient.signIn(
    stub: StubIdp,
    claims: Map<String, Any?> = emptyMap(),
    authorize: (Attempt) -> String = { stub.authorize(it.location, claims) },
): SignIn {
    val attempt = start()
    return SignIn(attempt, callback(attempt.cookie, authorize(attempt)))
}

internal suspend fun HttpClient.signInAndExchange(stub: StubIdp, claims: Map<String, Any?> = emptyMap()): LoginResponse {
    val result = signIn(stub, claims)
    val code = assertNotNull(result.code, "expected a success redirect, got ${result.location}")
    val response = exchange(code, result.attempt.cookie)
    assertEquals(HttpStatusCode.OK, response.status)
    return response.body()
}

internal suspend fun HttpClient.assertRefused(result: SignIn, error: String) {
    assertEquals(HttpStatusCode.Found, result.callback.status)
    assertEquals("/login#sso_error=$error", result.location)
    assertNull(result.code)
}

internal fun stateOf(attempt: Attempt): String = StubIdp.queryOf(attempt.location).getValue("state")

internal fun failureOf(capture: LogCapture, reason: String) =
    capture.events.firstOrNull { it.message == "login.sso_failure" && it.hasKeyValue("reason", reason) }
