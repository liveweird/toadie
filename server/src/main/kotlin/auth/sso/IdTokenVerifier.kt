package ch.nokillswit.auth.sso

import ch.nokillswit.infra.fetch.OperatorHttp
import ch.nokillswit.users.canonicalEmail
import ch.nokillswit.users.validateEmail
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.server.plugins.BadRequestException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** What a validated ID token proves: a stable subject and a verified, canonical (lower-cased) email. */
class VerifiedIdToken(val subject: String, val email: String, val name: String?)

/**
 * The ID token cannot be accepted. [reason] is the audit vocabulary (`id_token_invalid`, `email_unverified`,
 * `domain_not_allowed`); the message never carries a claim or a token.
 */
class IdTokenRejectedException(
    val reason: String,
    /** The token's canonical email / subject, set only when the signature already verified (for the audit trail). */
    val email: String? = null,
    val subject: String? = null,
) : RuntimeException("ID token rejected: $reason")

private const val MIN_RSA_BITS = 2048
private const val JWKS_TTL_MILLIS = 60 * 60 * 1000L
private const val CLOCK_LEEWAY_SECONDS = 60L
private const val MAX_ID_TOKEN_LENGTH = 16 * 1024
private const val MAX_NAME_CLAIM = 200

/**
 * Validates OIDC ID tokens from the configured issuer (`.claude/docs/security.md` "SSO login (OIDC)").
 *
 * The header is decoded FIRST and must name `RS256` and a `kid`; the JWK is picked by `kid`, must be an RSA key of at
 * least 2048 bits, is built with the JDK `KeyFactory` and handed to java-jwt's `Algorithm.RSA256` (which also refuses a
 * header whose `alg` differs). The JWKS is cached for an hour; an unknown `kid` triggers at most one refetch per
 * minute, so rotation is picked up without letting a token stream turn into a JWKS stream; the fetch runs outside any
 * lock and, when it fails, the last good key set is served for up to 24 more hours ([StaleServingCache]). Then `iss`,
 * `aud`, `azp`, `exp`/`iat`/`nbf` (60 seconds of leeway), `nonce`, `sub`, `email`, `email_verified == true` and the
 * allowed domain.
 */
class IdTokenVerifier(
    private val config: SsoConfig,
    private val discovery: SsoDiscovery,
    private val http: () -> OperatorHttp,
    private val clock: Clock = Clock.systemUTC(),
) {
    private class Jwks(val keys: Map<String, RSAPublicKey?>)

    private val jwks = StaleServingCache(JWKS_TTL_MILLIS, clock = clock::millis) { fetchJwks() }

    suspend fun verify(idToken: String, expectedNonce: String): VerifiedIdToken {
        if (idToken.length > MAX_ID_TOKEN_LENGTH) throw invalid()
        val decoded = try {
            JWT.decode(idToken)
        } catch (_: JWTDecodeException) {
            throw invalid()
        }
        val kid = decoded.keyId
        if (decoded.algorithm != "RS256" || kid.isNullOrEmpty()) throw invalid()
        val key = keyFor(kid) ?: throw invalid()
        val verified = try {
            val verification = JWT.require(Algorithm.RSA256(key, null))
                .withIssuer(config.issuer)
                .withAudience(config.clientId)
                .withClaimPresence("sub")
                .withClaimPresence("exp")
                .withClaimPresence("iat")
                .acceptLeeway(CLOCK_LEEWAY_SECONDS)
            // JWT.require always hands back the concrete BaseVerification, the only type with the injectable-clock build.
            (verification as JWTVerifier.BaseVerification).build(clock).verify(decoded)
        } catch (_: JWTVerificationException) {
            throw invalid()
        }
        return claimsOf(verified, expectedNonce)
    }

    private fun claimsOf(token: DecodedJWT, expectedNonce: String): VerifiedIdToken {
        val azp = token.getClaim("azp")
        if (!azp.isMissing && !azp.isNull && azp.asString() != config.clientId) throw invalid()
        val nonce = token.getClaim("nonce").asString()
        if (nonce == null || !MessageDigest.isEqual(nonce.toByteArray(), expectedNonce.toByteArray())) throw invalid()
        val subject = token.subject?.takeIf { it.isNotEmpty() && it.length <= MAX_SUBJECT_LENGTH } ?: throw invalid()
        val rawEmail = token.getClaim("email").asString() ?: throw invalid()
        val email = canonicalEmail(rawEmail)
        try {
            validateEmail(email)
        } catch (_: BadRequestException) {
            throw invalid()
        }
        // Strictly the boolean true: a string "true", a missing claim or false is unverified.
        if (token.getClaim("email_verified").asBoolean() != true) throw IdTokenRejectedException("email_unverified", email, subject)
        if (!config.isEmailAllowed(email)) throw IdTokenRejectedException("domain_not_allowed", email, subject)
        val name = token.getClaim("name").asString()?.take(MAX_NAME_CLAIM)
        return VerifiedIdToken(subject, email, name)
    }

    private fun invalid() = IdTokenRejectedException("id_token_invalid")

    /**
     * The key for [kid]: from the cached JWKS, refreshed when an hour old or — at most once a minute — when [kid] is
     * unknown, so rotation is picked up. A failed refresh serves the last good key set (bounded staleness). Null =
     * unusable/unknown.
     */
    private suspend fun keyFor(kid: String): RSAPublicKey? = jwks.get { kid !in it.keys }.keys[kid]

    private suspend fun fetchJwks(): Jwks {
        val response = http().getJson(discovery.endpoints().jwksUri)
        if (response.status != 200) throw SsoUpstreamException("JWKS answered status ${response.status}")
        val document = parseObject(response.body) ?: throw SsoUpstreamException("JWKS is not a JSON object")
        val keys = LinkedHashMap<String, RSAPublicKey?>()
        (document["keys"] as? JsonArray).orEmpty().forEach { element ->
            val jwk = element as? JsonObject ?: return@forEach
            val id = jwk.string("kid") ?: return@forEach
            // First wins; a duplicate kid can never replace a key already accepted.
            if (id !in keys) keys[id] = rsaKeyOf(jwk)
        }
        return Jwks(keys)
    }

    /** Null = present but unusable: not RSA, not for signing, malformed, or under 2048 bits. */
    private fun rsaKeyOf(jwk: JsonObject): RSAPublicKey? {
        if (jwk.string("kty") != "RSA") return null
        val use = jwk.string("use")
        if (use != null && use != "sig") return null
        val modulus = jwk.string("n")?.let(::unsignedBigInteger) ?: return null
        val exponent = jwk.string("e")?.let(::unsignedBigInteger) ?: return null
        if (modulus.bitLength() < MIN_RSA_BITS) return null
        return try {
            KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as? RSAPublicKey
        } catch (_: InvalidKeySpecException) {
            null
        }
    }

    private fun unsignedBigInteger(encoded: String): BigInteger? = try {
        BigInteger(1, Base64.getUrlDecoder().decode(encoded))
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val MAX_SUBJECT_LENGTH = 255
    }
}
