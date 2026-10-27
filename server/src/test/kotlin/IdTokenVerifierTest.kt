package ch.nokillswit

import ch.nokillswit.auth.sso.IdTokenRejectedException
import ch.nokillswit.auth.sso.IdTokenVerifier
import ch.nokillswit.auth.sso.SsoConfig
import ch.nokillswit.auth.sso.SsoDiscovery
import ch.nokillswit.auth.sso.SsoUpstreamException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * The ID-token verifier and the discovery cache against the in-process TLS stub issuer, with a hand-moved clock: the JWKS cache
 * rules (one fetch, hourly expiry, one refetch per minute for an unknown kid), the JWK defects (not RSA, not for signing, too
 * small, malformed), and discovery's issuer/scheme rules. The end-to-end rejections live in `SsoLoginTest`.
 */
class IdTokenVerifierTest {

    private fun config(stub: StubIdp, allowHttp: Boolean = false, domains: Set<String> = emptySet()) = SsoConfig(
        label = "Okta",
        issuer = stub.issuer,
        clientId = stub.clientId,
        clientSecret = stub.clientSecret,
        redirectUri = "https://toadie.example.com/api/v1/login/sso/callback",
        scopes = "openid email profile",
        allowedEmailDomains = domains,
        allowHttp = allowHttp,
    )

    private class Rig(val stub: StubIdp, val clock: MovableClock, val verifier: IdTokenVerifier, val discovery: SsoDiscovery)

    private fun rig(stub: StubIdp, config: SsoConfig = config(stub), clock: MovableClock = MovableClock()): Rig {
        val http = stub.http()
        val discovery = SsoDiscovery(config, { http }) { clock.millis() }
        return Rig(stub, clock, IdTokenVerifier(config, discovery, { http }, clock), discovery)
    }

    private fun Rig.token(claims: Map<String, Any?> = emptyMap(), key: StubKey = stub.published.first()): String =
        stub.mint(
            mapOf(
                "iat" to clock.millis() / 1000, "exp" to clock.millis() / 1000 + 3600,
                "iss" to stub.issuer, "aud" to stub.clientId, "sub" to "s-1", "email" to "Ada@Example.test",
                "email_verified" to true, "nonce" to "n-1",
            ) + claims,
            key,
        )

    private fun rejection(block: suspend () -> Unit): IdTokenRejectedException =
        assertFailsWith<IdTokenRejectedException> { runBlocking { block() } }

    @Test
    fun `a valid token yields the canonical email and subject, the name is bounded, azp and an audience list are accepted`() = runBlocking {
        StubIdp().use { stub ->
            val rig = rig(stub)
            val verified = rig.verifier.verify(
                rig.token(mapOf("azp" to stub.clientId, "aud" to listOf("other", stub.clientId), "name" to "N".repeat(500))),
                "n-1",
            )
            assertEquals("ada@example.test", verified.email)
            assertEquals("s-1", verified.subject)
            assertEquals(200, verified.name?.length)
            assertNull(rig.verifier.verify(rig.token(), "n-1").name)
        }
    }

    @Test
    fun `the rejection reasons are the audited vocabulary and carry the email only after the signature verified`() = runBlocking<Unit> {
        StubIdp().use { stub ->
            val rig = rig(stub, config(stub, domains = setOf("example.test")))
            val unverified = rejection { rig.verifier.verify(rig.token(mapOf("email_verified" to false)), "n-1") }
            assertEquals("email_unverified", unverified.reason)
            assertEquals("ada@example.test", unverified.email)
            assertEquals("s-1", unverified.subject)
            val domain = rejection { rig.verifier.verify(rig.token(mapOf("email" to "x@other.test")), "n-1") }
            assertEquals("domain_not_allowed", domain.reason)
            val invalid = rejection { rig.verifier.verify(rig.token(), "another-nonce") }
            assertEquals("id_token_invalid", invalid.reason)
            assertNull(invalid.email)
            assertEquals("id_token_invalid", rejection { rig.verifier.verify("x".repeat(20_000), "n-1") }.reason)
            assertEquals("id_token_invalid", rejection { rig.verifier.verify(rig.token(mapOf("email" to "no-at-sign")), "n-1") }.reason)
            assertEquals("id_token_invalid", rejection { rig.verifier.verify(rig.token(mapOf("sub" to "s".repeat(256))), "n-1") }.reason)
            assertEquals("id_token_invalid", rejection { rig.verifier.verify(rig.token(mapOf("azp" to "other")), "n-1") }.reason)
        }
    }

    @Test
    fun `the JWKS is fetched once, expires after an hour, and an unknown kid refetches at most once a minute`() = runBlocking<Unit> {
        StubIdp().use { stub ->
            val rig = rig(stub)
            repeat(3) { rig.verifier.verify(rig.token(), "n-1") }
            assertEquals(1, stub.jwksFetches.get())

            val unknown = StubKey.rsa2048("kid-x", 1)
            rejection { rig.verifier.verify(rig.token(key = unknown), "n-1") }
            // The first unknown kid is the first refetch since the cache was filled a moment ago: inside the minute, none.
            assertEquals(1, stub.jwksFetches.get())
            rig.clock.advanceSeconds(61)
            rejection { rig.verifier.verify(rig.token(key = unknown), "n-1") }
            assertEquals(2, stub.jwksFetches.get())
            rejection { rig.verifier.verify(rig.token(key = unknown), "n-1") }
            assertEquals(2, stub.jwksFetches.get(), "a second unknown kid inside the minute is not another fetch")

            rig.clock.advanceSeconds(3600)
            rig.verifier.verify(rig.token(), "n-1")
            assertEquals(3, stub.jwksFetches.get(), "an hour-old cache is refreshed even for a known kid")
        }
    }

    @Test
    fun `a JWKS entry that is not usable for signing yields a rejection, and the first of two duplicate kids wins`() = runBlocking<Unit> {
        StubIdp().use { stub ->
            val rig = rig(stub)
            val good = StubKey.rsa2048("kid-1")
            // Publish a poisoned duplicate AFTER the real key: the first entry must keep the kid.
            stub.published = listOf(good, StubKey("kid-1", StubKey.rsa2048("x", 1).pair))
            rig.verifier.verify(rig.token(key = good), "n-1")
            assertEquals("id_token_invalid", rejection { rig.verifier.verify(rig.token(key = StubKey.rsa2048("kid-1", 1)), "n-1") }.reason)
        }
    }

    @Test
    fun `a failing JWKS is an upstream failure, not a rejection`() = runBlocking<Unit> {
        StubIdp().use { stub ->
            // jwks_uri pointed at the token endpoint, which answers a non-200: the JWKS fetch fails upstream.
            val rig = rig(stub)
            stub.discoveryOverrides = mapOf("jwks_uri" to "${stub.issuer}/v1/token")
            assertFailsWith<SsoUpstreamException> { runBlocking { rig.verifier.verify(rig.token(), "n-1") } }
        }
    }

    @Test
    fun `discovery requires the exact issuer, https endpoints outside development, and is cached an hour`() = runBlocking<Unit> {
        StubIdp().use { stub ->
            val rig = rig(stub)
            val endpoints = rig.discovery.endpoints()
            rig.discovery.endpoints()
            assertEquals(1, stub.discoveryFetches.get())
            assertEquals("${stub.issuer}/v1/token", endpoints.tokenEndpoint)
            rig.clock.advanceSeconds(3601)
            rig.discovery.endpoints()
            assertEquals(2, stub.discoveryFetches.get())
        }
        StubIdp().use { stub ->
            stub.discoveryIssuer = stub.issuer + "/"
            assertFailsWith<SsoUpstreamException> { runBlocking { rig(stub).discovery.endpoints() } }
        }
        listOf(
            mapOf("token_endpoint" to "http://idp.example.com/token"),
            mapOf("jwks_uri" to "http://idp.example.com/keys"),
            mapOf("authorization_endpoint" to "http://idp.example.com/authorize"),
            mapOf("token_endpoint" to null),
            mapOf("jwks_uri" to "not a url"),
            mapOf("authorization_endpoint" to "https://user:pw@idp.example.com/authorize"),
        ).forEach { overrides ->
            StubIdp().use { stub ->
                stub.discoveryOverrides = overrides
                assertFailsWith<SsoUpstreamException>(overrides.toString()) { runBlocking { rig(stub).discovery.endpoints() } }
            }
        }
        // Development accepts an http endpoint.
        StubIdp().use { stub ->
            stub.discoveryOverrides = mapOf("authorization_endpoint" to "http://localhost:9000/authorize")
            val endpoints = rig(stub, config(stub, allowHttp = true)).discovery.endpoints()
            assertEquals("http://localhost:9000/authorize", endpoints.authorizationEndpoint)
        }
    }
}
