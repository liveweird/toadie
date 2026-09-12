package ch.nokillswit.catalog

import ch.nokillswit.authz.BadGatewayException
import ch.nokillswit.infra.concurrency.awaitBounded
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The server side of "import from a URL". DNS validation and the complete HTTP exchange run
 * as one bounded operation. The one approved DNS snapshot is also the only address set the
 * transport can use, closing the resolve-check-connect gap without changing TLS hostname/SNI.
 */
const val MAX_FETCH_URL_LENGTH = 2048
const val MAX_FETCH_BYTES = 1_048_576
private const val FETCH_TIMEOUT_SECONDS = 10L
private const val FETCH_WORKERS = 4
private const val FETCH_QUEUE_CAPACITY = 16
private val DEFAULT_FETCH_TIMEOUT = Duration.ofSeconds(FETCH_TIMEOUT_SECONDS)

/**
 * Native DNS may ignore interruption. A cancelled lookup can strand one of these daemon workers,
 * but it cannot proceed to HTTP, and the fixed worker/queue bounds contain that native behavior.
 */
private val URL_FETCH_EXECUTOR: Executor = ThreadPoolExecutor(
    FETCH_WORKERS,
    FETCH_WORKERS,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(FETCH_QUEUE_CAPACITY),
    ThreadFactory { runnable -> Thread(runnable, "catalog-url-fetch").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
)

const val FETCH_URL_INVALID_DETAIL =
    "The URL must be a public https address (no credentials, resolvable, not a private or local host)"

@Serializable
data class FetchUrlRequest(val url: String)

@Serializable
data class FetchUrlResponse(val content: String)

/** Safe audit fields only; the complete URL may contain query-string credentials. */
class BlockedUrlException(val scheme: String?, val host: String?) : RuntimeException("Blocked URL")

data class FetchedContent(val uri: URI, val content: String)

/**
 * The original URI is retained for auditing. [url] is OkHttp's canonical representation, so the
 * exact hostname passed to [Dns] is also the hostname resolved and approved here.
 */
internal data class ValidatedFetchTarget(
    val uri: URI,
    val url: HttpUrl,
    val addresses: List<InetAddress>,
)

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

fun requirePublicHost(host: String) {
    requirePublicAddresses(host, resolveAddresses(host))
}

private fun resolveAddresses(host: String): List<InetAddress> = try {
    InetAddress.getAllByName(host).toList()
} catch (_: UnknownHostException) {
    throw BlockedUrlException("https", host)
}

private fun requirePublicAddresses(host: String, addresses: List<InetAddress>) {
    if (addresses.isEmpty() || addresses.any { it.isBlockedAddress() }) {
        throw BlockedUrlException("https", host)
    }
}

internal fun InetAddress.isBlockedAddress(): Boolean =
    isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress ||
        isMulticastAddress || isUniqueLocalIpv6() || isSpecialIpv4() || isNat64()

private fun InetAddress.isUniqueLocalIpv6(): Boolean {
    val bytes = address
    return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
}

private fun InetAddress.isSpecialIpv4(): Boolean {
    val bytes = address
    if (bytes.size != 4) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    return (b0 == 100 && b1 in 64..127) ||
        (b0 == 192 && b1 == 0 && (bytes[2].toInt() and 0xFF) == 0) ||
        (b0 == 198 && (b1 == 18 || b1 == 19))
}

private fun InetAddress.isNat64(): Boolean {
    val bytes = address
    if (bytes.size != 16) return false
    val prefix = byteArrayOf(0x00, 0x64, 0xFF.toByte(), 0x9B.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
    return bytes.copyOfRange(0, 12).contentEquals(prefix) &&
        InetAddress.getByAddress(bytes.copyOfRange(12, 16)).isBlockedAddress()
}

internal fun resolveFetchTarget(
    raw: String,
    resolver: (String) -> List<InetAddress> = ::resolveAddresses,
): ValidatedFetchTarget {
    val uri = parseFetchUrl(raw)
    val url = raw.trim().toHttpUrlOrNull() ?: throw BlockedUrlException(uri.scheme, uri.host)
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) {
        throw BlockedUrlException(uri.scheme, uri.host)
    }
    val addresses = try {
        resolver(url.host)
    } catch (_: UnknownHostException) {
        throw BlockedUrlException(uri.scheme, uri.host)
    }
    requirePublicAddresses(uri.host, addresses)
    val snapshot = addresses.map { InetAddress.getByAddress(it.address) }.toList()
    return ValidatedFetchTarget(uri = uri, url = url, addresses = snapshot)
}

const val SOURCE_URL_INVALID_DETAIL =
    "sourceUrl must be an absolute https URL without credentials (at most $MAX_FETCH_URL_LENGTH characters)"

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

val CatalogUrlFetcherKey = AttributeKey<CatalogUrlFetcher>("CatalogUrlFetcher")

/** Internal constructor parameters are test seams; production always uses the guarded defaults. */
class CatalogUrlFetcher internal constructor(
    private val targetResolver: (String) -> ValidatedFetchTarget = ::resolveFetchTarget,
    private val timeout: Duration = DEFAULT_FETCH_TIMEOUT,
    private val fetchExecutor: Executor = URL_FETCH_EXECUTOR,
    private val customizeClient: (OkHttpClient.Builder) -> Unit = {},
) {
    constructor() : this(::resolveFetchTarget, DEFAULT_FETCH_TIMEOUT, URL_FETCH_EXECUTOR, {})

    suspend fun fetch(rawUrl: String): FetchedContent {
        try {
            return withTimeoutOrNull(timeout.toMillis()) { executeBounded(rawUrl) }
                ?: throw BadGatewayException("The URL could not be fetched")
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: FetchUnavailableException) {
            throw BadGatewayException("The URL could not be fetched")
        } catch (_: FetchTooLargeException) {
            throw BadGatewayException("The file is larger than the 1 MB fetch limit")
        } catch (cause: FetchRedirectException) {
            throw BadGatewayException("The URL redirects — use the final URL").apply { initCause(cause) }
        } catch (cause: FetchHttpStatusException) {
            throw BadGatewayException("The URL could not be fetched (HTTP ${cause.status})").apply {
                initCause(cause)
            }
        } catch (_: IOException) {
            throw BadGatewayException("The URL could not be fetched")
        }
    }

    /** The cancellation/rejection bridge itself lives in [ch.nokillswit.infra.concurrency.awaitBounded]. */
    private suspend fun executeBounded(rawUrl: String): FetchedContent {
        val operation = FetchOperation(rawUrl, targetResolver, timeout, customizeClient)
        return try {
            fetchExecutor.awaitBounded(onCancel = operation::cancel) { operation.execute() }
        } catch (_: RejectedExecutionException) {
            throw FetchUnavailableException()
        } catch (_: InterruptedException) {
            throw FetchUnavailableException()
        }
    }
}

private class FetchOperation(
    private val rawUrl: String,
    private val targetResolver: (String) -> ValidatedFetchTarget,
    private val timeout: Duration,
    private val customizeClient: (OkHttpClient.Builder) -> Unit,
) {
    private val cancelled = AtomicBoolean(false)
    private val call = AtomicReference<Call?>()

    fun cancel() {
        cancelled.set(true)
        call.get()?.cancel()
    }

    fun execute(): FetchedContent {
        val target = targetResolver(rawUrl)
        failIfCancelled()
        val pool = ConnectionPool(0, 1, TimeUnit.NANOSECONDS)
        val client = buildClient(target, pool)
        try {
            val request = Request.Builder().url(target.url)
                .header("Accept", "text/yaml, text/plain, */*")
                // Keep the byte cap on the upstream representation; never transparently inflate it.
                .header("Accept-Encoding", "identity")
                .get().build()
            val newCall = client.newCall(request)
            if (!call.compareAndSet(null, newCall) || cancelled.get()) {
                newCall.cancel()
                throw InterruptedIOException("fetch cancelled")
            }
            val response = newCall.execute()
            try {
                return FetchedContent(target.uri, readResponse(response))
            } catch (cause: Throwable) {
                // Closing an unread/partially-read body may try to drain it. Abort the socket first.
                newCall.cancel()
                throw cause
            } finally {
                response.close()
            }
        } finally {
            call.getAndSet(null)?.cancel()
            pool.evictAll()
        }
    }

    private fun buildClient(target: ValidatedFetchTarget, pool: ConnectionPool): OkHttpClient {
        val pinnedDns = Dns { hostname ->
            if (hostname != target.url.host) throw UnknownHostException("unexpected fetch hostname")
            target.addresses
        }
        val builder = OkHttpClient.Builder()
            .dns(pinnedDns)
            .proxy(Proxy.NO_PROXY)
            // Socket() itself consults the JVM SOCKS selector; force a direct physical socket too.
            .socketFactory(DirectSocketFactory)
            .connectionPool(pool)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .fastFallback(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .callTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                rejectNonSuccess(response) { chain.call().cancel() }
            }
        customizeClient(builder)
        return builder.build()
    }

    private fun failIfCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("fetch cancelled")
        }
    }
}

private fun rejectNonSuccess(response: Response, cancel: () -> Unit): Response {
    if (response.code == 200) return response
    val code = response.code
    cancel()
    response.close()
    if (code in 300..399) throw FetchRedirectException()
    throw FetchHttpStatusException(code)
}

private fun readResponse(response: Response): String {
    val body = response.body
    val declaredLength = body.contentLength()
    if (declaredLength > MAX_FETCH_BYTES) throw FetchTooLargeException()
    val initialSize = minOf(MAX_FETCH_BYTES, declaredLength.coerceAtLeast(0).toInt())
    val output = ByteArrayOutputStream(initialSize)
    val source = body.source()
    val buffer = ByteArray(8_192)
    var received = 0
    while (true) {
        val count = source.read(buffer, 0, minOf(buffer.size, MAX_FETCH_BYTES - received + 1))
        if (count == -1) break
        received += count
        if (received > MAX_FETCH_BYTES) throw FetchTooLargeException()
        output.write(buffer, 0, count)
    }
    if (declaredLength >= 0 && received.toLong() != declaredLength) throw FetchUnavailableException()
    return output.toString(Charsets.UTF_8)
}

private class FetchUnavailableException : IOException()
private class FetchTooLargeException : IOException()
private class FetchRedirectException : IOException()
private class FetchHttpStatusException(val status: Int) : IOException()

private object DirectSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

    override fun createSocket(host: String, port: Int): Socket = unsupported()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()

    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()

    private fun unsupported(): Socket = throw SocketException("connected socket creation is disabled")
}
