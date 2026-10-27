package ch.nokillswit

import ch.nokillswit.infra.fetch.OperatorHttp
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/** A clock the test moves by hand (the `SsoClockKey` seam). */
class MovableClock(private var millis: Long = System.currentTimeMillis()) : Clock() {
    fun advanceSeconds(seconds: Long) {
        millis += seconds * 1000
    }

    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(millis)
}

/** One RSA signing key the stub publishes (or merely signs with) under [kid]. */
class StubKey(val kid: String, val pair: KeyPair) {
    val publicKey: RSAPublicKey get() = pair.public as RSAPublicKey
    val privateKey: RSAPrivateKey get() = pair.private as RSAPrivateKey

    companion object {
        private fun generate(bits: Int): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

        // RSA key generation dominates a stub's cost; the pairs are test fixtures, so share them across the whole run.
        private val shared2048 = lazy { listOf(generate(2048), generate(2048), generate(2048)) }
        private val shared1024 = lazy { generate(1024) }

        /** One of three distinct shared 2048-bit pairs published as [kid]. */
        fun rsa2048(kid: String, index: Int = 0) = StubKey(kid, shared2048.value[index])

        /** A 1024-bit key: under the verifier's 2048-bit floor. */
        fun rsa1024(kid: String) = StubKey(kid, shared1024.value)
    }
}

/**
 * An in-process OIDC issuer for the SSO tests: an HTTPS server on 127.0.0.1 (the certificate is self-signed and trusted only
 * through [http], the `SsoHttpKey` test seam) serving discovery, the JWKS and the token endpoint, plus a tiny ID-token minter.
 * [authorize] plays the identity provider's authorization step: it records the PKCE challenge, mints an ID token carrying the
 * `nonce` of the attempt, and hands back the callback query. The token endpoint verifies `client_secret_basic`, the redirect URI,
 * single use of the code and the S256 PKCE verifier, so a mistake in the application's request fails the exchange, not a mock.
 */
class StubIdp(
    val clientId: String = "toadie-test-client",
    val clientSecret: String = "s3cret/with:odd+chars %",
) : AutoCloseable {
    private val certificate = HeldCertificate.Builder().commonName("127.0.0.1").addSubjectAlternativeName("127.0.0.1").build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val handlers = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    private class Pending(val idToken: String, val challenge: String, val redirectUri: String)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** The keys the JWKS publishes; replace to rotate. */
    @Volatile
    var published: List<StubKey> = listOf(StubKey.rsa2048("kid-1"))

    /** Override the discovery document's `issuer` (a mismatch must fail the flow). */
    @Volatile
    var discoveryIssuer: String? = null

    @Volatile
    var discoveryStatus = 200

    /** Replaces (or, for null, removes) members of the discovery document, e.g. an http endpoint. */
    @Volatile
    var discoveryOverrides: Map<String, String?> = emptyMap()

    /** When set, the token endpoint answers this (status, body) regardless of the code. */
    @Volatile
    var tokenOverride: Pair<Int, String>? = null

    val tokenRequests = CopyOnWriteArrayList<TokenRequest>()
    val jwksFetches = AtomicInteger()
    val discoveryFetches = AtomicInteger()

    class TokenRequest(val authorization: String?, val form: Map<String, String>)

    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = handlers
        httpsConfigurator = HttpsConfigurator(serverCertificates.sslContext())
        createContext("$PATH/.well-known/openid-configuration") { discovery(it) }
        createContext("$PATH/v1/keys") { jwks(it) }
        createContext("$PATH/v1/token") { token(it) }
        start()
    }

    private val base = "https://127.0.0.1:${server.address.port}"

    /** The configured issuer (`sso.issuer`): what a real tenant calls `https://<org>.okta.com/oauth2/default`. */
    val issuer = "$base$PATH"

    /** The client for the `SsoHttpKey` seam: public constructor semantics, plus trust in this stub's certificate. */
    fun http(timeout: Duration = Duration.ofSeconds(10)) = OperatorHttp(
        allowHttp = false,
        timeout = timeout,
        executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } },
        customizeClient = { it.sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager) },
    )

    /**
     * The provider's authorization step for the attempt in [authorizeLocation] (the start redirect): mints an ID token with the
     * attempt's nonce plus [claims] (null removes a default claim) and returns the callback query string the provider would
     * redirect back with.
     */
    fun authorize(
        authorizeLocation: String,
        claims: Map<String, Any?> = emptyMap(),
        signWith: StubKey = published.first(),
        kid: String? = signWith.kid,
        nonce: String? = null,
        rawIdToken: String? = null,
    ): String {
        val query = queryOf(authorizeLocation)
        val code = "code-" + UUID.randomUUID()
        val merged = mutableMapOf<String, Any?>(
            "iss" to issuer,
            "aud" to clientId,
            "sub" to "sub-" + UUID.randomUUID(),
            "email" to "person-${UUID.randomUUID()}@sso.test",
            "email_verified" to true,
            "name" to "Sso Person",
            "nonce" to (nonce ?: query.getValue("nonce")),
        )
        merged.putAll(claims)
        pending[code] = Pending(rawIdToken ?: mint(merged, signWith, kid), query.getValue("code_challenge"), query.getValue("redirect_uri"))
        return "code=$code&state=${URLEncoder.encode(query.getValue("state"), Charsets.UTF_8)}"
    }

    /** Signs [claims] (null values dropped; `iat`/`exp` default to now / +1 h) as an RS256 JWT with header [kid]. */
    fun mint(claims: Map<String, Any?>, signWith: StubKey = published.first(), kid: String? = signWith.kid): String {
        val now = System.currentTimeMillis() / 1000
        val all = mutableMapOf<String, Any?>("iat" to now, "exp" to now + 3600)
        all.putAll(claims)
        val builder = JWT.create().apply { if (kid != null) withKeyId(kid) }
        all.forEach { (name, value) ->
            when (value) {
                null -> Unit
                is String -> builder.withClaim(name, value)
                is Boolean -> builder.withClaim(name, value)
                is Long -> builder.withClaim(name, value)
                is Int -> builder.withClaim(name, value.toLong())
                is List<*> -> builder.withArrayClaim(name, value.map { it.toString() }.toTypedArray())
                else -> error("unsupported claim type for $name")
            }
        }
        return builder.sign(Algorithm.RSA256(null, signWith.privateKey))
    }

    private fun discovery(exchange: HttpExchange) {
        discoveryFetches.incrementAndGet()
        if (discoveryStatus != 200) return reply(exchange, discoveryStatus, """{"error":"unavailable"}""")
        val fields = linkedMapOf(
            "issuer" to (discoveryIssuer ?: issuer),
            "authorization_endpoint" to "$issuer/v1/authorize",
            "token_endpoint" to "$issuer/v1/token",
            "jwks_uri" to "$issuer/v1/keys",
        )
        discoveryOverrides.forEach { (name, value) -> if (value == null) fields.remove(name) else fields[name] = value }
        reply(exchange, 200, fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" })
    }

    private fun jwks(exchange: HttpExchange) {
        jwksFetches.incrementAndGet()
        val enc = Base64.getUrlEncoder().withoutPadding()
        val keys = published.joinToString(",") { key ->
            val modulus = key.publicKey.modulus.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            val n = enc.encodeToString(modulus)
            val e = enc.encodeToString(key.publicKey.publicExponent.toByteArray())
            """{"kty":"RSA","alg":"RS256","use":"sig","kid":"${key.kid}","n":"$n","e":"$e"}"""
        }
        reply(exchange, 200, """{"keys":[$keys]}""")
    }

    private fun token(exchange: HttpExchange) {
        val form = exchange.requestBody.readBytes().toString(Charsets.UTF_8).split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }
        val authorization = exchange.requestHeaders.getFirst("Authorization")
        tokenRequests += TokenRequest(authorization, form)
        tokenOverride?.let { (status, body) -> return reply(exchange, status, body) }
        val expected = "Basic " + Base64.getEncoder().encodeToString(
            "${URLEncoder.encode(clientId, Charsets.UTF_8)}:${URLEncoder.encode(clientSecret, Charsets.UTF_8)}".toByteArray(),
        )
        val grant = pending.remove(form["code"] ?: "")
        val verifierChallenge = form["code_verifier"]?.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }
        val valid = authorization == expected && form["grant_type"] == "authorization_code" && grant != null &&
            form["redirect_uri"] == grant.redirectUri && verifierChallenge == grant.challenge
        if (!valid || grant == null) return reply(exchange, 400, """{"error":"invalid_grant"}""")
        reply(exchange, 200, """{"token_type":"Bearer","access_token":"opaque","id_token":"${grant.idToken}"}""")
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
        handlers.shutdownNow()
    }

    companion object {
        const val PATH = "/oauth2/default"

        /** The query parameters of an absolute URL or a `?`-led relative one. */
        fun queryOf(url: String): Map<String, String> = (URI(url).rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }
    }
}
