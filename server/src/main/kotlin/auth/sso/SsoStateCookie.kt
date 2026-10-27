package ch.nokillswit.auth.sso

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The development cookie name (plain http cannot carry a `__Host-` cookie). */
internal const val SSO_COOKIE_NAME = "toadie_sso"

/** The production name: the `__Host-` prefix makes a browser refuse it unless `Secure`, `Path=/` and without a `Domain`. */
internal const val SSO_HOST_COOKIE_NAME = "__Host-$SSO_COOKIE_NAME"

internal fun ssoCookieName(production: Boolean): String = if (production) SSO_HOST_COOKIE_NAME else SSO_COOKIE_NAME

/** Domain-separation label for the cookie key: HMAC(jwt.secret, label) — the raw JWT secret never signs anything else. */
private const val STATE_KEY_LABEL = "toadie-sso-state"
private const val STATE_TTL_MILLIS = 10 * 60 * 1000L
private const val MAX_COOKIE_LENGTH = 2048
private const val RANDOM_BYTES = 32

/** What one sign-in attempt carries across the IdP round trip; lives only in the signed cookie. */
@Serializable
class SsoAttempt(
    /** Sent to the IdP and echoed on the callback — binds the callback to this browser's attempt. */
    val state: String,
    /** Sent to the IdP and echoed inside the ID token. */
    val nonce: String,
    /** The PKCE verifier; only its S256 challenge ever leaves the server before the token exchange. */
    val verifier: String,
    /** Secret that never leaves the cookie; the handoff stores its SHA-256 and the exchange must present it again. */
    val bind: String,
    /** Epoch millis after which the attempt is dead. */
    val exp: Long,
)

/**
 * The stateless attempt cookie (`toadie_sso`; `__Host-toadie_sso` in production):
 * `base64url(json).base64url(HMAC-SHA256)`. No server-side attempt store
 * exists to exhaust (a store would be admitted pre-authentication). The signing key is derived from the JWT secret with
 * a fixed label, so rotating the secret kills in-flight attempts together with every session. Verification is
 * constant-time; a malformed, forged, oversized or expired value simply opens as null.
 */
class SsoStateCookie(jwtSecret: String, private val clock: Clock = Clock.systemUTC()) {
    private val key = SecretKeySpec(hmac(jwtSecret.toByteArray(Charsets.UTF_8), STATE_KEY_LABEL.toByteArray(Charsets.UTF_8)), "HmacSHA256")

    /** A fresh attempt: random state, nonce, PKCE verifier and bind, valid for ten minutes. */
    fun newAttempt(): SsoAttempt = SsoAttempt(
        state = randomToken(),
        nonce = randomToken(),
        verifier = randomToken(),
        bind = randomToken(),
        exp = clock.millis() + STATE_TTL_MILLIS,
    )

    fun seal(attempt: SsoAttempt): String {
        val payload = encoder.encodeToString(json.encodeToString(SsoAttempt.serializer(), attempt).toByteArray(Charsets.UTF_8))
        return payload + "." + encoder.encodeToString(sign(payload))
    }

    /** The attempt, or null for anything not issued by [seal] under this key, or already expired. */
    fun open(value: String?): SsoAttempt? {
        if (value.isNullOrEmpty() || value.length > MAX_COOKIE_LENGTH) return null
        val parts = value.split('.')
        if (parts.size != 2 || !signatureMatches(parts[0], parts[1])) return null
        val attempt = try {
            json.decodeFromString(SsoAttempt.serializer(), String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8))
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return attempt.takeIf { it.exp > clock.millis() }
    }

    /** Constant-time comparison of the presented signature with the one computed over [payload]. */
    private fun signatureMatches(payload: String, presented: String): Boolean {
        val signature = try {
            Base64.getUrlDecoder().decode(presented)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return MessageDigest.isEqual(signature, sign(payload))
    }

    private fun sign(payload: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(payload.toByteArray(Charsets.US_ASCII))
    }

    private companion object {
        val random = SecureRandom()
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val json = Json { ignoreUnknownKeys = false }

        fun randomToken(): String = encoder.encodeToString(ByteArray(RANDOM_BYTES).also(random::nextBytes))

        fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
            doFinal(data)
        }
    }
}

/** The S256 PKCE challenge of [verifier]: base64url(SHA-256(verifier)) without padding (RFC 7636 section 4.2). */
internal fun pkceChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/** SHA-256 of the cookie's [bind] secret — what a handoff stores and the exchange compares against. */
internal fun bindHash(bind: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(bind.toByteArray(Charsets.UTF_8))
