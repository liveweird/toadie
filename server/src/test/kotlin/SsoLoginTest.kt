package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.sso.LoginOptionsResponse
import ch.nokillswit.auth.sso.SsoStateCookie
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserResponse
import ch.nokillswit.users.UserRole
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.setCookie
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OIDC sign-in end to end against an in-process TLS stub issuer ([StubIdp]) injected through `SsoHttpKey`: the real routes,
 * real Postgres, the browser played by a client that does NOT follow redirects and carries the attempt cookie by hand.
 * Rules: `.claude/docs/security.md` "SSO login (OIDC)".
 */
class SsoLoginTest {

    // ---- the happy paths ---------------------------------------------------------------------------------------------------

    @Test
    fun `a first sign-in creates a passwordless USER, mints an ordinary session and audits the origin`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val email = uniqueEmail("sso-new")
            val subject = "sub-${UUID.randomUUID()}"
            withAuditCapture { audit ->
                // The start redirect: the code flow with PKCE S256 and the derived redirect URI.
                val attempt = browser.start()
                val query = StubIdp.queryOf(attempt.location)
                assertTrue(attempt.location.startsWith("${stub.issuer}/v1/authorize?"))
                assertEquals("code", query["response_type"])
                assertEquals("query", query["response_mode"])
                assertEquals(stub.clientId, query["client_id"])
                assertEquals(REDIRECT_URI, query["redirect_uri"])
                assertEquals("openid email profile", query["scope"])
                assertEquals("S256", query["code_challenge_method"])
                assertTrue(query.getValue("state").length >= 40 && query.getValue("nonce").length >= 40)

                val claims = mapOf("email" to email.uppercase(), "sub" to subject, "name" to "  Ada Lovelace ")
                val callback = browser.callback(attempt.cookie, stub.authorize(attempt.location, claims))
                assertEquals(HttpStatusCode.Found, callback.status)
                val location = callback.headers[HttpHeaders.Location].orEmpty()
                assertTrue(location.startsWith("/login/sso#code="), location)
                val code = location.substringAfter("#code=")
                // The token endpoint saw client_secret_basic, the redirect URI and the PKCE verifier (it verified all three).
                assertEquals(1, stub.tokenRequests.size)
                assertEquals("authorization_code", stub.tokenRequests.single().form["grant_type"])

                val exchange = browser.exchange(code, attempt.cookie)
                assertEquals(HttpStatusCode.OK, exchange.status)
                val session = exchange.body<LoginResponse>()
                assertFalse(session.hasPassword)
                assertTrue(session.roles.isEmpty())

                val (id, stored) = assertNotNull(TestUsers.service.findWithIdByEmail(email))
                assertEquals(session.userId, id)
                assertEquals("Ada Lovelace", stored.name)
                assertEquals(UserRole.USER, stored.role)
                assertNull(stored.passwordHash)
                assertEquals(stub.issuer, stored.ssoIssuer)
                assertEquals(subject, stored.ssoSubject)

                // An ordinary bearer session: it reads its own account, which reports the SSO link and no password.
                val me = createClient { toadieTestClientDefaults() }.get("/api/v1/users/$id") {
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }.body<UserResponse>()
                assertTrue(me.sso)
                assertFalse(me.hasPassword)

                val created = assertNotNull(audit.events.firstOrNull { it.message == "user.created" && it.hasKeyValue("email", email) })
                assertTrue(created.hasKeyValue("via", "sso"))
                assertTrue(created.hasKeyValue("newUserId", id.toLong()))
                assertNull(created.keyValuePairs.firstOrNull { it.key == "byUserId" })
                val success = assertNotNull(
                    audit.events.firstOrNull { it.message == "login.sso_success" && it.hasKeyValue("userId", id.toLong()) },
                )
                assertTrue(success.hasKeyValue("outcome", "created"))
            }
        }
    }

    @Test
    fun `a returning identity signs in to the same account and a changed email claim never rewrites the stored email`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val email = uniqueEmail("sso-again")
            val subject = "sub-${UUID.randomUUID()}"
            val first = browser.signInAndExchange(stub, mapOf("email" to email, "sub" to subject))
            withAuditCapture { audit ->
                val renamed = mapOf("email" to uniqueEmail("sso-renamed"), "sub" to subject, "name" to "Changed Name")
                val second = browser.signInAndExchange(stub, renamed)
                assertEquals(first.userId, second.userId)
                assertEquals(email, assertNotNull(TestUsers.service.read(second.userId)).email)
                assertEquals("Sso Person", assertNotNull(TestUsers.service.read(second.userId)).name)
                val success = assertNotNull(audit.events.firstOrNull { it.message == "login.sso_success" })
                assertTrue(success.hasKeyValue("outcome", "existing"))
                assertNull(audit.events.firstOrNull { it.message == "user.created" })
            }
        }
    }

    @Test
    fun `an existing password account with the same email is linked on its first SSO sign-in and keeps its password`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val email = uniqueEmail("sso-link")
            val id = TestUsers.seed(email = email, password = "password-1234", role = UserRole.USER, name = "Original Name")
            val subject = "sub-${UUID.randomUUID()}"
            withAuditCapture { audit ->
                // The claim's case differs: identity is the canonical email.
                val session = browser.signInAndExchange(stub, mapOf("email" to email.uppercase(), "sub" to subject, "name" to "IdP Name"))
                assertEquals(id, session.userId)
                assertTrue(session.hasPassword)
                val stored = assertNotNull(TestUsers.service.read(id))
                assertEquals("Original Name", stored.name)
                assertEquals(subject, stored.ssoSubject)
                assertNotNull(stored.passwordHash)
                assertEquals(HttpStatusCode.OK, browser.login(email, "password-1234").status)
                val linked = assertNotNull(audit.events.firstOrNull { it.message == "user.sso_linked" })
                assertTrue(linked.hasKeyValue("userId", id.toLong()) && linked.hasKeyValue("issuerHost", "127.0.0.1"))
                assertNull(linked.keyValuePairs.firstOrNull { it.key == "issuer" }, "the host only, never the full issuer URL")
                assertTrue(assertNotNull(audit.events.firstOrNull { it.message == "login.sso_success" }).hasKeyValue("outcome", "linked"))
            }
        }
    }

    @Test
    fun `an SSO sign-in skips email MFA even for an account an admin later enabled it on`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val claims = mapOf("email" to uniqueEmail("sso-skips-mfa"), "sub" to "sub-${UUID.randomUUID()}")
            val created = browser.signInAndExchange(stub, claims)
            TestUsers.service.setDisabledFeatures(created.userId, emptySet())
            // MFA is now enabled for the account; the identity provider owns the second factor, so the SSO login is unchanged.
            val again = browser.signInAndExchange(stub, claims)
            assertEquals(created.userId, again.userId)
            assertTrue(again.disabledFeatures.isEmpty() && again.token.isNotBlank())
        }
    }

    // ---- refused accounts -------------------------------------------------------------------------------------------------

    @Test
    fun `an ADMIN with the same email is never linked from an IdP-asserted email`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val email = uniqueEmail("sso-admin")
            val id = TestUsers.seed(email = email, password = "password-1234", role = UserRole.ADMIN)
            withAuditCapture { audit ->
                val result = browser.signIn(stub, mapOf("email" to email))
                browser.assertRefused(result, "not_allowed")
                val failure = assertNotNull(failureOf(audit, "link_refused"))
                assertTrue(failure.hasKeyValue("email", email))
                assertNull(assertNotNull(TestUsers.service.read(id)).ssoSubject)
            }
        }
    }

    @Test
    fun `an MFA-enabled account is never linked either, so no configured second factor is bypassed`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val email = uniqueEmail("sso-mfa")
            val id = TestUsers.seed(email = email, password = "password-1234", role = UserRole.USER)
            TestUsers.service.setDisabledFeatures(id, emptySet())
            withAuditCapture { audit ->
                browser.assertRefused(browser.signIn(stub, mapOf("email" to email)), "not_allowed")
                assertNotNull(failureOf(audit, "link_refused"))
                assertNull(assertNotNull(TestUsers.service.read(id)).ssoSubject)
            }
        }
    }

    @Test
    fun `an email already linked to another identity is a subject conflict`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val email = uniqueEmail("sso-conflict")
            val id = TestUsers.seedPasswordless(email, issuer = stub.issuer, subject = "someone-else")
            withAuditCapture { audit ->
                browser.assertRefused(browser.signIn(stub, mapOf("email" to email, "sub" to "me")), "not_allowed")
                assertNotNull(failureOf(audit, "subject_conflict"))
                assertEquals("someone-else", assertNotNull(TestUsers.service.read(id)).ssoSubject)
            }
        }
    }

    @Test
    fun `a soft-deleted account is refused and never recreated`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val email = uniqueEmail("sso-deleted")
            val subject = "sub-${UUID.randomUUID()}"
            val id = TestUsers.seedPasswordless(email, issuer = stub.issuer, subject = subject)
            TestUsers.softDelete(id)
            withAuditCapture { audit ->
                // Same identity, same email: refused, and no second account appears.
                browser.assertRefused(browser.signIn(stub, mapOf("email" to email, "sub" to subject)), "not_allowed")
                assertNotNull(failureOf(audit, "account_deleted"))
                // Same identity with a NEW email claim: still the deleted account's link, still refused.
                browser.assertRefused(browser.signIn(stub, mapOf("email" to uniqueEmail("sso-deleted-2"), "sub" to subject)), "not_allowed")
            }
            assertNull(TestUsers.service.findWithIdByEmail(email))
        }
    }

    @Test
    fun `allowed email domains gate creation and linking`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub, "sso.allowedEmailDomains" to " @Allowed.test , second.test")
            val browser = browser()
            withAuditCapture { audit ->
                browser.assertRefused(browser.signIn(stub, mapOf("email" to "someone@other.test")), "not_allowed")
                assertNotNull(failureOf(audit, "domain_not_allowed"))
                assertNull(TestUsers.service.findWithIdByEmail("someone@other.test"))
            }
            val allowed = "person-${UUID.randomUUID()}@allowed.test"
            assertTrue(browser.signInAndExchange(stub, mapOf("email" to allowed)).userId > 0u)
            // An existing account outside the allowed domains is not linked either.
            val outside = uniqueEmail("sso-outside")
            val id = TestUsers.seed(email = outside, password = "password-1234", role = UserRole.USER)
            browser.assertRefused(browser.signIn(stub, mapOf("email" to outside)), "not_allowed")
            assertNull(assertNotNull(TestUsers.service.read(id)).ssoSubject)
        }
    }

    @Test
    fun `a reserved service-account address never becomes an account`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            withAuditCapture { audit ->
                browser.assertRefused(browser.signIn(stub, mapOf("email" to "integration-client-1@toadie.invalid")), "not_allowed")
                assertNotNull(failureOf(audit, "domain_not_allowed"))
            }
        }
    }

    @Test
    fun `an unverified email is refused whatever form the claim takes`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            withAuditCapture { audit ->
                listOf<Any?>(false, null, "true").forEach { value ->
                    browser.assertRefused(browser.signIn(stub, mapOf("email_verified" to value)), "not_allowed")
                }
                assertEquals(3, audit.events.count { it.message == "login.sso_failure" && it.hasKeyValue("reason", "email_unverified") })
            }
        }
    }

    // ---- ID token checks --------------------------------------------------------------------------------------------------

    @Test
    fun `every ID token defect ends at the failed redirect and creates nothing`() = testApplication {
        StubIdp().use { stub ->
            // One captured clock: the app validates against it and every hand-built claim below derives from it.
            val clock = MovableClock()
            ssoApp(stub, clock = clock)
            val browser = browser()
            val now = clock.instant().epochSecond
            fun hs256(): String = JWT.create().withKeyId("kid-1").withIssuer(stub.issuer).withAudience(stub.clientId).withSubject("s")
                .withClaim("email", "x@sso.test").withClaim("email_verified", true).withIssuedAt(java.util.Date(now * 1000))
                .withExpiresAt(java.util.Date((now + 3_600) * 1000)).sign(Algorithm.HMAC256("secret"))
            val noKid = JWT.create().withIssuer(stub.issuer).withAudience(stub.clientId).withSubject("s").sign(Algorithm.none())
            class Defect(val name: String, val authorize: (Attempt) -> String)
            val defects = listOf(
                Defect("signature by another key under the published kid") {
                    stub.authorize(it.location, signWith = StubKey.rsa2048("kid-1", 1))
                },
                Defect("unknown kid") { stub.authorize(it.location, kid = "kid-unknown") },
                Defect("no kid") { stub.authorize(it.location, kid = null) },
                Defect("wrong audience") { stub.authorize(it.location, mapOf("aud" to "another-client")) },
                Defect("audience list without ours") { stub.authorize(it.location, mapOf("aud" to listOf("a", "b"))) },
                Defect("wrong authorized party") { stub.authorize(it.location, mapOf("azp" to "another-client")) },
                Defect("wrong issuer") { stub.authorize(it.location, mapOf("iss" to "https://evil.test/oauth2/default")) },
                Defect("wrong nonce") { stub.authorize(it.location, nonce = "not-the-nonce") },
                Defect("missing nonce") { stub.authorize(it.location, mapOf("nonce" to null)) },
                Defect("expired") { stub.authorize(it.location, mapOf("exp" to now - 3600, "iat" to now - 7200)) },
                Defect("issued in the future") { stub.authorize(it.location, mapOf("iat" to now + 3600)) },
                Defect("no subject") { stub.authorize(it.location, mapOf("sub" to null)) },
                Defect("no email") { stub.authorize(it.location, mapOf("email" to null)) },
                Defect("HS256 instead of RS256") { stub.authorize(it.location, rawIdToken = hs256()) },
                Defect("alg none") { stub.authorize(it.location, rawIdToken = noKid) },
                Defect("garbage") { stub.authorize(it.location, rawIdToken = "not.a.jwt") },
            )
            withAuditCapture { audit ->
                defects.forEach { defect ->
                    val result = browser.signIn(stub, authorize = defect.authorize)
                    assertEquals("/login#sso_error=failed", result.location, defect.name)
                }
                val rejected = audit.events.count { it.message == "login.sso_failure" && it.hasKeyValue("reason", "id_token_invalid") }
                assertEquals(defects.size, rejected)
                assertNull(audit.events.firstOrNull { it.message == "login.sso_success" || it.message == "user.created" })
            }
        }
    }

    @Test
    fun `a key under 2048 bits is refused`() = testApplication {
        StubIdp().use { stub ->
            val small = StubKey.rsa1024("kid-small")
            stub.published = listOf(small)
            ssoApp(stub)
            val browser = browser()
            withAuditCapture { audit ->
                val result = browser.signIn(stub, authorize = { stub.authorize(it.location, signWith = small) })
                browser.assertRefused(result, "failed")
                assertNotNull(failureOf(audit, "id_token_invalid"))
            }
        }
    }

    @Test
    fun `the JWKS is cached, refetched for an unknown kid at most once a minute, and rotation is picked up`() = testApplication {
        StubIdp().use { stub ->
            val clock = MovableClock()
            ssoApp(stub, clock = clock)
            val browser = browser()
            assertTrue(browser.signInAndExchange(stub).userId > 0u)
            assertTrue(browser.signInAndExchange(stub).userId > 0u)
            assertEquals(1, stub.jwksFetches.get(), "a known kid is served from the cache")

            // The provider rotates: only kid-2 is published now.
            val rotated = StubKey.rsa2048("kid-2", 1)
            stub.published = listOf(rotated)
            val tooSoon = browser.signIn(stub, authorize = { stub.authorize(it.location, signWith = rotated) })
            browser.assertRefused(tooSoon, "failed")
            assertEquals(1, stub.jwksFetches.get(), "an unknown kid inside the minute does not refetch")

            clock.advanceSeconds(61)
            assertTrue(browser.signInAndExchange(stub).userId > 0u)
            assertEquals(2, stub.jwksFetches.get(), "after a minute the unknown kid triggers exactly one refetch")
        }
    }

    // ---- the callback's other failure modes -------------------------------------------------------------------------------

    @Test
    fun `an IdP error for this attempt ends at the redirect, and access_denied reads as cancelled`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            withAuditCapture { audit ->
                val cancelled = browser.start()
                val denied = browser.callback(cancelled.cookie, "error=access_denied&state=${stateOf(cancelled)}")
                assertEquals("/login#sso_error=cancelled", denied.headers[HttpHeaders.Location])
                val failed = browser.start()
                val other = browser.callback(failed.cookie, "error=server_error&error_description=boom&state=${stateOf(failed)}")
                assertEquals("/login#sso_error=failed", other.headers[HttpHeaders.Location])
                assertEquals(2, audit.events.count { it.message == "login.sso_failure" && it.hasKeyValue("reason", "idp_error") })
                // The failure clears the attempt cookie.
                assertTrue(denied.setCookie().any { it.name == COOKIE && it.maxAge == 0 })
            }
        }
    }

    @Test
    fun `a forged IdP error with the wrong state neither ends the attempt nor clears its cookie`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            withAuditCapture { audit ->
                val victim = browser.start()
                // A cross-site page navigates the victim's browser to the callback with an error and a guessed state.
                val forged = browser.callback(victim.cookie, "error=access_denied&state=x")
                assertEquals("/login#sso_error=failed", forged.headers[HttpHeaders.Location])
                assertNotNull(failureOf(audit, "state_invalid"))
                assertNull(failureOf(audit, "idp_error"))
                assertTrue(forged.setCookie().none { it.name == COOKIE }, "a state mismatch leaves the valid cookie alone")
                // No state at all behaves the same.
                assertTrue(browser.callback(victim.cookie, "error=access_denied").setCookie().none { it.name == COOKIE })
                // The victim's in-flight attempt still completes with the same cookie.
                val real = browser.callback(victim.cookie, stub.authorize(victim.location))
                assertTrue(real.headers[HttpHeaders.Location].orEmpty().startsWith("/login/sso#code="))
            }
        }
    }

    @Test
    fun `a callback replayed with the same cookie and state is refused before any token request`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            withAuditCapture { audit ->
                val attempt = browser.start()
                val query = stub.authorize(attempt.location)
                val first = browser.callback(attempt.cookie, query)
                assertTrue(first.headers[HttpHeaders.Location].orEmpty().startsWith("/login/sso#code="))
                assertEquals(1, stub.tokenRequests.size)

                // A second callback with the same cookie + state (a fresh code each time, so the stub's own single-use would not help).
                val replay = browser.callback(attempt.cookie, stub.authorize(attempt.location))
                assertEquals("/login#sso_error=failed", replay.headers[HttpHeaders.Location])
                assertEquals(1, stub.tokenRequests.size, "the replay never reached the token endpoint")
                assertNotNull(failureOf(audit, "state_invalid"))
                // The replay did not clear the cookie the first success is still waiting to exchange with.
                assertTrue(replay.setCookie().none { it.name == COOKIE })
                val code = first.headers[HttpHeaders.Location].orEmpty().substringAfter("#code=")
                assertEquals(HttpStatusCode.OK, browser.exchange(code, attempt.cookie).status)
            }
        }
    }

    @Test
    fun `an unreachable issuer audits the failing host and nothing else about the URL`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            withAuditCapture { audit ->
                stub.stopServing()
                assertEquals("/login#sso_error=failed", browser.get(START).headers[HttpHeaders.Location])
                val failure = assertNotNull(failureOf(audit, "idp_unavailable"))
                assertTrue(failure.hasKeyValue("host", "127.0.0.1"))
                assertTrue(failure.keyValuePairs.none { pair -> pair.value.toString().contains("https://") })
            }
        }
    }

    @Test
    fun `discovery and the JWKS keep serving their last good copy when the issuer goes down after their TTL`() = testApplication {
        StubIdp().use { stub ->
            val clock = MovableClock()
            ssoApp(stub, clock = clock)
            val browser = browser()
            // The tokens must outlive the hand-moved clock.
            val longLived = mapOf<String, Any?>("exp" to clock.instant().epochSecond + 3 * 86400)
            assertTrue(browser.signInAndExchange(stub, longLived).userId > 0u)
            assertEquals(1, stub.discoveryFetches.get())
            assertEquals(1, stub.jwksFetches.get())

            // Both caches are now more than an hour old and the issuer's discovery AND key endpoints fail.
            stub.discoveryStatus = 503
            stub.jwksStatus = 503
            clock.advanceSeconds(3601)
            assertTrue(browser.signIn(stub, longLived).location.startsWith("/login/sso#code="), "the stale copies still sign in")
            assertEquals(2, stub.discoveryFetches.get(), "one refresh was attempted and failed")
            assertEquals(2, stub.jwksFetches.get())
            // Attempts are spaced a minute apart: the next sign-in inside it does not touch the issuer again.
            assertTrue(browser.signIn(stub, longLived).location.startsWith("/login/sso#code="))
            assertEquals(2, stub.discoveryFetches.get())
            assertEquals(2, stub.jwksFetches.get())
            clock.advanceSeconds(61)
            assertTrue(browser.signIn(stub, longLived).location.startsWith("/login/sso#code="))
            assertEquals(3, stub.discoveryFetches.get())
            assertEquals(3, stub.jwksFetches.get())

            // Past the 24 hour staleness bound no copy is usable: the failure surfaces as it always did.
            clock.advanceSeconds(25 * 3600)
            assertEquals("/login#sso_error=failed", browser.get(START).headers[HttpHeaders.Location])
        }
    }

    @Test
    fun `a token endpoint refusal or a body without an ID token ends at the failed redirect`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            withAuditCapture { audit ->
                stub.tokenOverride = 400 to """{"error":"invalid_grant"}"""
                browser.assertRefused(browser.signIn(stub), "failed")
                stub.tokenOverride = 200 to """{"access_token":"opaque"}"""
                browser.assertRefused(browser.signIn(stub), "failed")
                stub.tokenOverride = 200 to "not json"
                browser.assertRefused(browser.signIn(stub), "failed")
                val failures = audit.events.count { it.message == "login.sso_failure" && it.hasKeyValue("reason", "token_exchange_failed") }
                assertEquals(3, failures)
            }
        }
    }

    @Test
    fun `a discovery document naming another issuer, or a failing one, sends start to the login page with an error`() =
        testApplication {
            StubIdp().use { stub ->
                stub.discoveryIssuer = "https://evil.test/oauth2/default"
                ssoApp(stub)
                val browser = browser()
                withAuditCapture { audit ->
                    val start = browser.get(START)
                    assertEquals(HttpStatusCode.Found, start.status)
                    assertEquals("/login#sso_error=failed", start.headers[HttpHeaders.Location])
                    assertNull(start.setCookie().firstOrNull { it.name == COOKIE })
                    assertNotNull(failureOf(audit, "idp_unavailable"))
                }
                stub.discoveryIssuer = null
                stub.discoveryStatus = 503
                val unavailable = browser.get(START)
                assertEquals("/login#sso_error=failed", unavailable.headers[HttpHeaders.Location])
                // A failure is never cached: the issuer recovering is picked up by the next attempt.
                stub.discoveryStatus = 200
                assertEquals(HttpStatusCode.Found, browser.get(START).status)
                assertTrue(browser.get(START).headers[HttpHeaders.Location].orEmpty().startsWith(stub.issuer))
            }
        }

    // ---- flow integrity ---------------------------------------------------------------------------------------------------

    @Test
    fun `a missing, tampered, expired or foreign attempt cookie and a mismatched state all fail closed`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub, "jwt.secret" to TEST_JWT_SECRET)
            val browser = browser()
            val secret = TEST_JWT_SECRET
            withAuditCapture { audit ->
                val attempt = browser.start()
                val query = stub.authorize(attempt.location)

                browser.assertRefused(SignIn(attempt, browser.callback(null, query)), "expired")

                val (payload, signature) = attempt.cookie.split('.')
                val flipped = (if (payload.last() == 'A') 'B' else 'A')
                val tamperedPayload = payload.dropLast(1) + flipped
                browser.assertRefused(SignIn(attempt, browser.callback("$tamperedPayload.$signature", query)), "expired")
                browser.assertRefused(SignIn(attempt, browser.callback("$payload.${signature.reversed()}", query)), "expired")
                browser.assertRefused(SignIn(attempt, browser.callback("garbage", query)), "expired")

                // A cookie sealed with another secret (a forgery) and one that expired are both unusable.
                val foreign = SsoStateCookie("another-secret").run { seal(newAttempt()) }
                browser.assertRefused(SignIn(attempt, browser.callback(foreign, query)), "expired")
                val past = SsoStateCookie(secret, Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC))
                val expired = past.seal(past.newAttempt())
                browser.assertRefused(SignIn(attempt, browser.callback(expired, query)), "expired")

                // The state of ANOTHER attempt does not complete this one.
                val other = browser.start()
                val otherQuery = stub.authorize(other.location)
                browser.assertRefused(SignIn(attempt, browser.callback(attempt.cookie, otherQuery)), "failed")
                browser.assertRefused(SignIn(attempt, browser.callback(attempt.cookie, "code=whatever")), "failed")

                assertTrue(audit.events.count { it.message == "login.sso_failure" && it.hasKeyValue("reason", "state_invalid") } >= 8)
                assertTrue(stub.tokenRequests.isEmpty(), "no code is ever exchanged before the state checks pass")
            }
        }
    }

    @Test
    fun `a hand-off code is single-use, bound to the browser that started the attempt, and short-lived`() = testApplication {
        StubIdp().use { stub ->
            val clock = MovableClock()
            ssoApp(stub, clock = clock)
            val browser = browser()
            withAuditCapture { audit ->
                // Replay: the second exchange of the same code is the uniform 401.
                val first = browser.signIn(stub)
                val code = assertNotNull(first.code)
                val ok = browser.exchange(code, first.attempt.cookie)
                assertEquals(HttpStatusCode.OK, ok.status)
                assertTrue(ok.setCookie().any { it.name == COOKIE && it.maxAge == 0 }, "the exchange clears the attempt cookie")
                val replay = browser.exchange(code, first.attempt.cookie)
                assertEquals(HttpStatusCode.Unauthorized, replay.status)
                assertEquals("Invalid or expired sign-in code", replay.body<ProblemDetail>().detail)

                // A planted code (login CSRF through the fragment): valid code, the VICTIM's own cookie.
                val attackers = browser.signIn(stub)
                val victims = browser.start()
                val planted = browser.exchange(assertNotNull(attackers.code), victims.cookie)
                assertEquals(HttpStatusCode.Unauthorized, planted.status)
                // The code is burned by the attempt, even for its rightful owner.
                assertEquals(HttpStatusCode.Unauthorized, browser.exchange(assertNotNull(attackers.code), attackers.attempt.cookie).status)

                // No cookie at all, and an unknown code.
                val noCookie = browser.signIn(stub)
                assertEquals(HttpStatusCode.Unauthorized, browser.exchange(assertNotNull(noCookie.code), null).status)
                assertEquals(HttpStatusCode.Unauthorized, browser.exchange("unknown-code", noCookie.attempt.cookie).status)

                // The hand-off lives 60 seconds.
                val slow = browser.signIn(stub)
                clock.advanceSeconds(61)
                assertEquals(HttpStatusCode.Unauthorized, browser.exchange(assertNotNull(slow.code), slow.attempt.cookie).status)

                assertTrue(audit.events.count { it.message == "login.sso_failure" && it.hasKeyValue("reason", "handoff_invalid") } >= 6)
            }
        }
    }

    @Test
    fun `an account changed between the callback and the exchange cannot redeem the hand-off`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val email = uniqueEmail("sso-moved")
            val id = TestUsers.seed(email = email, password = "password-1234", role = UserRole.USER)
            val claims = mapOf("email" to email, "sub" to "sub-${UUID.randomUUID()}")
            val result = browser.signIn(stub, claims)
            val code = assertNotNull(result.code)
            // A password change advances the credential epoch, so the pending hand-off no longer matches.
            TestUsers.service.updatePassword(id, ch.nokillswit.auth.hashPassword("another-password-9", cost = 4))
            assertEquals(HttpStatusCode.Unauthorized, browser.exchange(code, result.attempt.cookie).status)

            val second = browser.signIn(stub, claims)
            TestUsers.softDelete(id)
            assertEquals(HttpStatusCode.Unauthorized, browser.exchange(assertNotNull(second.code), second.attempt.cookie).status)
        }
    }

    @Test
    fun `an exchange code longer than any issued one is 400 before reaching the hand-off store`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            withAuditCapture { audit ->
                val signIn = browser.signIn(stub)
                val code = assertNotNull(signIn.code)
                assertEquals(HttpStatusCode.BadRequest, browser.exchange("a".repeat(44), signIn.attempt.cookie).status)
                assertTrue(audit.events.none { it.message == "login.sso_failure" }, "a malformed code is no sign-in failure")
                // The 400 touched neither the attempt cookie nor the hand-off: the real code still redeems.
                assertEquals(HttpStatusCode.OK, browser.exchange(code, signIn.attempt.cookie).status)
            }
        }
    }

    @Test
    fun `the development attempt cookie is plain-named, Path slash, HttpOnly Lax, and every SSO answer is no-store`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val start = browser.get(START)
            val raw = start.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$COOKIE=") }
            assertTrue("HttpOnly" in raw, raw)
            assertTrue("SameSite=Lax" in raw, raw)
            assertTrue(Regex("Path=/(;|$)").containsMatchIn(raw), raw)
            assertFalse("Domain" in raw, raw)
            assertTrue("Max-Age=600" in raw, raw)
            assertFalse("Secure" in raw, "development mode serves http")
            assertEquals("no-store", start.headers[HttpHeaders.CacheControl])
            assertEquals("no-store", browser.callback(null, "state=x").headers[HttpHeaders.CacheControl])
            assertEquals("no-store", browser.exchange("x", null).headers[HttpHeaders.CacheControl])
            assertEquals("no-store", browser.get("/api/v1/login/options").headers[HttpHeaders.CacheControl])
        }
    }

    @Test
    fun `in production the attempt cookie is a Secure host-prefixed Path slash cookie without a Domain, set and cleared alike`() =
        testApplication {
            StubIdp().use { stub ->
                withSeedRestored {
                    ssoApp(
                        stub,
                        "sso.redirectUri" to "https://toadie.example.com/api/v1/login/sso/callback",
                        "http.behindProxy" to "true",
                        "bootstrap.adminInitialPassword" to "rotated-${UUID.randomUUID()}",
                        "jwt.secret" to "strong-${UUID.randomUUID()}",
                        "mail.transport" to "disabled",
                        "mail.appUrl" to "https://toadie.example.com",
                        production = true,
                    )
                    val browser = browser()
                    // The proxy contract: X-Forwarded-Proto marks the request as already over TLS (no HTTPS redirect).
                    val start = browser.get(START) { header("X-Forwarded-Proto", "https") }
                    assertEquals(HttpStatusCode.Found, start.status)
                    val cookies = start.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                    assertTrue(cookies.none { it.startsWith("$COOKIE=") }, "no plain-named cookie")
                    val raw = cookies.single { it.startsWith("$HOST_COOKIE=") }
                    assertTrue("Secure" in raw && "HttpOnly" in raw && "SameSite=Lax" in raw && "Max-Age=600" in raw, raw)
                    assertTrue(Regex("Path=/(;|$)").containsMatchIn(raw), raw)
                    assertFalse("Domain" in raw, raw)

                    // The callback reads the host-prefixed cookie; a failed one clears it with the SAME attributes.
                    val value = start.setCookie().first { it.name == HOST_COOKIE }.value
                    val location = start.headers[HttpHeaders.Location].orEmpty()
                    val forwarded = { request: io.ktor.client.request.HttpRequestBuilder ->
                        request.header("X-Forwarded-Proto", "https")
                        request.header(HttpHeaders.Cookie, "$HOST_COOKIE=$value")
                    }
                    val ok = browser.get("$CALLBACK?${stub.authorize(location)}") { forwarded(this) }
                    assertTrue(ok.headers[HttpHeaders.Location].orEmpty().startsWith("/login/sso#code="), ok.headers[HttpHeaders.Location])
                    val failed = browser.get("$CALLBACK?code=c&state=x") { header("X-Forwarded-Proto", "https") }
                    assertEquals("/login#sso_error=expired", failed.headers[HttpHeaders.Location])
                    val cleared = failed.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$HOST_COOKIE=") }
                    assertTrue("Secure" in cleared && Regex("Path=/(;|$)").containsMatchIn(cleared) && "Max-Age=0" in cleared, cleared)
                    assertFalse("Domain" in cleared, cleared)
                }
            }
        }

    @Test
    fun `options names the provider label when SSO is on`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub, "sso.label" to "Acme ID")
            val options = browser().get("/api/v1/login/options").body<LoginOptionsResponse>()
            assertEquals("Acme ID", options.sso?.label)
        }
    }

    @Test
    fun `a throttled callback still ends at the failed redirect, never a JSON page`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val flood = createClient { followRedirects = false }
            // Development lifts the bucket to 1000/min; the 1001st request from one host is rejected by the plugin.
            repeat(1000) {
                val response = flood.get("$CALLBACK?state=x")
                assertEquals(HttpStatusCode.Found, response.status)
            }
            val throttled = browser().get("$CALLBACK?state=x")
            assertEquals(HttpStatusCode.Found, throttled.status)
            assertEquals("/login#sso_error=failed", throttled.headers[HttpHeaders.Location])
            assertEquals("no-store", throttled.headers[HttpHeaders.CacheControl])
            // The other SSO routes answer the generic problem body.
            assertEquals(HttpStatusCode.TooManyRequests, browser().exchange("x", null).status)
        }
    }

    // ---- account resolution units (the unique-index race) -----------------------------------------------------------------

    @Test
    fun `a concurrent first sign-in hitting the unique email index re-runs the resolution once and finds the winner`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("sso-race")
        val issuer = "https://idp.test/${UUID.randomUUID()}"
        val subject = "sub-${UUID.randomUUID()}"
        val inserts = AtomicLong()
        var winner: UInt? = null
        val accounts = ch.nokillswit.auth.sso.SsoAccounts(
            TestUsers.service::resolveSsoIdentity,
        ) { user ->
            inserts.incrementAndGet()
            // The same person's other tab commits first, between our lookup and our insert.
            winner = TestUsers.seedPasswordless(email, issuer = issuer, subject = subject)
            TestUsers.service.create(user)
        }
        val resolved = accounts.resolve(issuer, ch.nokillswit.auth.sso.VerifiedIdToken(subject, email, "Name"))
        assertEquals(winner, resolved.userId)
        assertEquals(ch.nokillswit.auth.sso.SsoOutcome.EXISTING, resolved.outcome)
        assertEquals(1, inserts.get(), "the retry finds the winner instead of inserting again")
    }

    @Test
    fun `a failure that is not a unique violation is not retried, and a persistent violation surfaces after one retry`() = testApplication {
        usePostgresTestcontainer()
        val token = ch.nokillswit.auth.sso.VerifiedIdToken("sub-1", uniqueEmail("sso-fail"), null)
        val boom = ch.nokillswit.auth.sso.SsoAccounts(TestUsers.service::resolveSsoIdentity) { error("database is on fire") }
        val failure = runCatching { boom.resolve("https://idp.test/x", token) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)

        val email = uniqueEmail("sso-dup")
        TestUsers.seed(email = email, password = "password-1234", role = UserRole.USER)
        val calls = AtomicLong()
        // An always-colliding creator behind a lookup that always says Unknown: the second attempt's violation propagates.
        val stuck = ch.nokillswit.auth.sso.SsoAccounts({ _, _, _ -> ch.nokillswit.users.SsoLookup.Unknown }) { user ->
            calls.incrementAndGet()
            TestUsers.service.create(user)
        }
        val duplicate = runCatching { stuck.resolve("https://idp.test/x", ch.nokillswit.auth.sso.VerifiedIdToken("sub-2", email, null)) }
        assertTrue(duplicate.isFailure)
        assertEquals(2, calls.get())
    }
}
