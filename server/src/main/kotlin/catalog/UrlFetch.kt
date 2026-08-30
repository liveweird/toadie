package ch.nokillswit.catalog

import ch.nokillswit.authz.BadGatewayException
import io.ktor.server.plugins.BadRequestException
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.ktor.util.AttributeKey
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The server side of "import from a URL": fetches a catalog-info.yaml the user points at and
 * returns the raw TEXT (YAML parsing stays a client concern — the standing decision).
 *
 * This endpoint makes the server issue outbound requests on user command, so SSRF is the
 * design center: https only, no userinfo, the resolved host must be a PUBLIC address
 * (loopback/private/link-local/ULA/multicast all refused), redirects are never followed
 * (following one would bypass the address check), and the body read is hard-capped. The
 * resolve-check-then-connect gap (DNS rebinding) is a documented accepted residual risk —
 * see "Outbound URL fetch" in .claude/docs/security.md.
 */
const val MAX_FETCH_URL_LENGTH = 2048
const val MAX_FETCH_BYTES = 1_048_576
private const val FETCH_TIMEOUT_SECONDS = 10L

/** The uniform 400 detail for every guard rejection — never echoes what was probed. */
const val FETCH_URL_INVALID_DETAIL =
    "The URL must be a public https address (no credentials, resolvable, not a private or local host)"

@Serializable
data class FetchUrlRequest(val url: String)

@Serializable
data class FetchUrlResponse(val content: String)

/**
 * A guard rejection. Carries ONLY fields safe to audit — a full URL may embed query-string
 * tokens, and we never log secrets. The route converts it to the uniform 400.
 */
class BlockedUrlException(val scheme: String?, val host: String?) : RuntimeException("Blocked URL")

/** The static SSRF rules: absolute https, no userinfo, a non-blank host, sane length. */
fun parseFetchUrl(raw: String): URI {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_FETCH_URL_LENGTH) throw BlockedUrlException(null, null)
    val uri = try {
        URI(trimmed)
    } catch (_: URISyntaxException) {
        throw BlockedUrlException(null, null)
    }
    if (!uri.isAbsolute || !"https".equals(uri.scheme, ignoreCase = true)) {
        throw BlockedUrlException(uri.scheme, uri.host)
    }
    if (uri.userInfo != null || uri.host.isNullOrBlank()) throw BlockedUrlException(uri.scheme, uri.host)
    return uri
}

/** The dynamic SSRF rule: every address the host resolves to must be public. */
fun requirePublicHost(host: String) {
    val addresses = try {
        InetAddress.getAllByName(host)
    } catch (_: UnknownHostException) {
        throw BlockedUrlException("https", host)
    }
    if (addresses.any { it.isBlockedAddress() }) throw BlockedUrlException("https", host)
}

internal fun InetAddress.isBlockedAddress(): Boolean =
    isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress ||
        isMulticastAddress || isUniqueLocalIpv6() || isSpecialIpv4() || isNat64()

// fc00::/7 — NOT covered by isSiteLocalAddress (that is the deprecated fec0::/10).
private fun InetAddress.isUniqueLocalIpv6(): Boolean {
    val bytes = address
    return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
}

// IPv4 ranges the JDK predicates miss but that are never public internet: 100.64.0.0/10
// (CGNAT — used by cloud VPC/metadata setups), 192.0.0.0/24 (IETF protocol assignments,
// incl. DS-Lite), 198.18.0.0/15 (benchmarking). IPv4-mapped IPv6 arrives here as
// Inet4Address (the JDK folds it), so these cover ::ffff: forms too.
private fun InetAddress.isSpecialIpv4(): Boolean {
    val bytes = address
    if (bytes.size != 4) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    return (b0 == 100 && b1 in 64..127) ||
        (b0 == 192 && b1 == 0 && (bytes[2].toInt() and 0xFF) == 0) ||
        (b0 == 198 && (b1 == 18 || b1 == 19))
}

// 64:ff9b::/96 — the NAT64 well-known prefix; its tail embeds an IPv4 address that a NAT64
// gateway would connect to, so judge the embedded IPv4 the same as a native one.
private fun InetAddress.isNat64(): Boolean {
    val bytes = address
    if (bytes.size != 16) return false
    val prefix = byteArrayOf(0x00, 0x64, 0xFF.toByte(), 0x9B.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
    return bytes.copyOfRange(0, 12).contentEquals(prefix) &&
        InetAddress.getByAddress(bytes.copyOfRange(12, 16)).isBlockedAddress()
}

/** The full guard chain: static rules, then the resolved-address check. */
fun validateFetchUrl(raw: String): URI {
    val uri = parseFetchUrl(raw)
    requirePublicHost(uri.host)
    return uri
}

/** The 400 detail for a rejected source reference on a catalog-file write. */
const val SOURCE_URL_INVALID_DETAIL =
    "sourceUrl must be an absolute https URL without credentials (at most $MAX_FETCH_URL_LENGTH characters)"

/**
 * Sanitizes the optional per-file source reference: trimmed, blank → null, and held to the
 * STATIC fetch guards only (absolute https, no userinfo, sane length) — the DNS/public-host
 * check deliberately runs at fetch time, not at write time (a repo may be temporarily
 * unresolvable without making its files unsaveable). Enforced by route AND service.
 */
fun sanitizedSourceUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    try {
        parseFetchUrl(trimmed)
    } catch (_: BlockedUrlException) {
        throw BadRequestException(SOURCE_URL_INVALID_DETAIL)
    }
    return trimmed
}

/**
 * Test seam: the route resolves its fetcher through this key (falling back to the default
 * full-guard [CatalogUrlFetcher]), so the suite can exercise the ROUTE's 200/502 paths against
 * a 127.0.0.1 fixture server. Production never sets it.
 */
val CatalogUrlFetcherKey = AttributeKey<CatalogUrlFetcher>("CatalogUrlFetcher")

/**
 * The fetcher. [urlValidator] is injectable ONLY so tests can point the response-handling
 * logic at a plain-HTTP 127.0.0.1 fixture server — production wiring always uses
 * [validateFetchUrl] (the default).
 */
class CatalogUrlFetcher(private val urlValidator: (String) -> URI = ::validateFetchUrl) {

    private val client: HttpClient = HttpClient.newBuilder()
        // Load-bearing: following a redirect would re-open the private-address hole.
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
        .build()

    /** Throws [BlockedUrlException] (→ the route's 400) or [BadGatewayException] (→ 502). */
    suspend fun fetch(rawUrl: String): String {
        val uri = urlValidator(rawUrl)
        return withContext(Dispatchers.IO) { execute(uri) }
    }

    private fun execute(uri: URI): String {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
            .header("Accept", "text/yaml, text/plain, */*")
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (_: IOException) {
            throw BadGatewayException("The URL could not be fetched")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BadGatewayException("The URL could not be fetched")
        }
        response.body().use { body ->
            if (response.statusCode() in 300..399) {
                throw BadGatewayException("The URL redirects — use the final URL")
            }
            if (response.statusCode() != 200) {
                throw BadGatewayException("The URL could not be fetched (HTTP ${response.statusCode()})")
            }
            // Bounded read — a BodyHandlers.ofString would buffer an attacker-sized response.
            val bytes = body.readNBytes(MAX_FETCH_BYTES + 1)
            if (bytes.size > MAX_FETCH_BYTES) {
                throw BadGatewayException("The file is larger than the 1 MB fetch limit")
            }
            return String(bytes, Charsets.UTF_8)
        }
    }
}
