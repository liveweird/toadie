package ch.nokillswit.infra.fetch

import ch.nokillswit.infra.concurrency.awaitBounded
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val OPERATOR_TIMEOUT_SECONDS = 10L
internal const val OPERATOR_WORKERS = 2
internal const val OPERATOR_QUEUE_CAPACITY = 8
private val DEFAULT_OPERATOR_TIMEOUT = Duration.ofSeconds(OPERATOR_TIMEOUT_SECONDS)

private val OPERATOR_HTTP_EXECUTOR: Executor = ThreadPoolExecutor(
    OPERATOR_WORKERS,
    OPERATOR_WORKERS,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(OPERATOR_QUEUE_CAPACITY),
    ThreadFactory { runnable -> Thread(runnable, "operator-http").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
)

/** Why an operator call produced no [OperatorResponse]. */
enum class OperatorHttpFailure {
    /** Not an https URL (http only with `allowHttp`), or it carries userinfo or no host. */
    INVALID_URL,

    /** The 10-second deadline (or a connect/read timeout inside it) expired. */
    TIMEOUT,

    /** Connect, TLS, I/O or truncated-body failure. */
    UNAVAILABLE,

    /** The body exceeded [MAX_FETCH_BYTES], by `Content-Length` or while streaming. */
    TOO_LARGE,

    /** The bounded worker pool and its queue were full. */
    BUSY,
}

/** Safe audit fields only ([scheme] and [host]; null when the URL did not parse); no URL path or query. */
class OperatorHttpException(val kind: OperatorHttpFailure, val scheme: String?, val host: String?) :
    RuntimeException("Operator HTTP request failed: ${kind.name}")

/**
 * A response of any status. [body] may hold tokens, so `toString` deliberately omits it; this is
 * not a data class for the same reason.
 */
class OperatorResponse(val status: Int, val body: String) {
    override fun toString(): String = "OperatorResponse(status=$status)"
}

/**
 * An HTTP client for OPERATOR-CONFIGURED endpoints: the SSO issuer's discovery, JWKS and token
 * endpoints (`auth/sso/`). The address is deployment configuration, like the SMTP host or the
 * PostgreSQL URL, so it may legitimately sit on a private network (the e2e stub IdP lives on a
 * Docker network). It is therefore NOT [UrlFetcher]: that guard is for URLs a user types into the
 * app (GET-only, 200-only, public addresses only, a pool shared with imports), and widening its
 * address rule would reopen the SSRF hole it exists to close. This class has no address rule and
 * must NEVER be handed a URL taken from a request — only values read from configuration or from
 * the configured issuer's own discovery document. Construction is confined to `auth/sso/`
 * (`.claude/docs/security.md` "Operator-configured outbound HTTP").
 *
 * Everything else is [UrlFetcher]'s hardening: no redirects, no proxy (the physical socket is
 * direct too), no cookie jar, no retries, a fresh zero-idle connection pool per call, the
 * [MAX_FETCH_BYTES] cap on the declared and the streamed body, one 10-second deadline over the
 * whole exchange, and a bounded daemon pool of its own (2 workers, 8 queued) so a stalled IdP
 * can never starve the user-URL fetches or the reverse. Caller cancellation propagates unchanged.
 *
 * Failures are one typed [OperatorHttpException]; any HTTP status is a normal [OperatorResponse]
 * (the token endpoint answers a failed exchange as a 400 carrying a JSON error the caller maps).
 * Nothing is logged and no audit event is emitted here — the SSO caller audits. No failure
 * message, cause or `toString` carries a path, query, form field, credential or response body.
 *
 * Internal constructor parameters are test seams; production uses the bounded defaults.
 */
class OperatorHttp internal constructor(
    private val allowHttp: Boolean,
    private val timeout: Duration,
    private val executor: Executor,
    private val customizeClient: (OkHttpClient.Builder) -> Unit = {},
) {
    /** [allowHttp] is passed by the SSO wiring in development mode only; production is https-only. */
    constructor(allowHttp: Boolean = false) : this(allowHttp, DEFAULT_OPERATOR_TIMEOUT, OPERATOR_HTTP_EXECUTOR, {})

    suspend fun getJson(url: String): OperatorResponse {
        val target = parseTarget(url)
        return execute(target, baseRequest(target).get().build())
    }

    /**
     * POSTs [fields] as `application/x-www-form-urlencoded`. [basicAuth] (`client_secret_basic`) is
     * sent as `Basic base64(urlencode(user):urlencode(password))` — RFC 6749 section 2.3.1 requires
     * BOTH parts form-urlencoded before the base64, which matters for secrets containing `:`, `+`,
     * `%` or spaces.
     */
    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        basicAuth: Pair<String, String>? = null,
    ): OperatorResponse {
        val target = parseTarget(url)
        val form = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        val request = baseRequest(target).apply {
            if (basicAuth != null) header("Authorization", basicAuthorization(basicAuth.first, basicAuth.second))
        }.post(form).build()
        return execute(target, request)
    }

    private fun parseTarget(raw: String): HttpUrl {
        val trimmed = raw.trim()
        // Two parsers on purpose (as `resolveFetchTarget`): java.net.URI is strict about the authority
        // (OkHttp reads `https:///x` as host "x"), OkHttp is the one whose canonical form is requested.
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            null
        }
        val url = trimmed.toHttpUrlOrNull()
        val authorityClean = uri != null && uri.userInfo == null && !uri.host.isNullOrBlank()
        val schemeAllowed = url != null && (url.isHttps || (allowHttp && url.scheme == "http"))
        val credentialFree = url != null && url.username.isEmpty() && url.password.isEmpty()
        if (url == null || !authorityClean || !schemeAllowed || !credentialFree) {
            throw OperatorHttpException(OperatorHttpFailure.INVALID_URL, url?.scheme, url?.host)
        }
        return url
    }

    private fun baseRequest(target: HttpUrl): Request.Builder = Request.Builder().url(target)
        .header("Accept", "application/json")
        // Keep the byte cap on the upstream representation; never transparently inflate it.
        .header("Accept-Encoding", "identity")

    private fun basicAuthorization(user: String, password: String): String {
        val credentials = "${formEncode(user)}:${formEncode(password)}"
        return "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
    }

    private fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun execute(target: HttpUrl, request: Request): OperatorResponse {
        fun failure(kind: OperatorHttpFailure) = OperatorHttpException(kind, target.scheme, target.host)
        try {
            return withTimeoutOrNull(timeout.toMillis()) { executeBounded(target, request) }
                ?: throw failure(OperatorHttpFailure.TIMEOUT)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: OperatorHttpException) {
            throw cause
        } catch (_: RejectedExecutionException) {
            throw failure(OperatorHttpFailure.BUSY)
        } catch (_: InterruptedIOException) {
            // SocketTimeoutException (connect/read) and OkHttp's call timeout are both this.
            throw failure(OperatorHttpFailure.TIMEOUT)
        } catch (_: IOException) {
            throw failure(OperatorHttpFailure.UNAVAILABLE)
        } catch (_: InterruptedException) {
            throw failure(OperatorHttpFailure.UNAVAILABLE)
        }
    }

    /** The cancellation/rejection bridge itself lives in [ch.nokillswit.infra.concurrency.awaitBounded]. */
    private suspend fun executeBounded(target: HttpUrl, request: Request): OperatorResponse {
        val operation = OperatorCall(target, request, timeout, customizeClient)
        return executor.awaitBounded(onCancel = operation::cancel) { operation.execute() }
    }
}

private class OperatorCall(
    private val target: HttpUrl,
    private val request: Request,
    private val timeout: Duration,
    private val customizeClient: (OkHttpClient.Builder) -> Unit,
) {
    private val cancelled = AtomicBoolean(false)
    private val call = AtomicReference<Call?>()

    fun cancel() {
        cancelled.set(true)
        call.get()?.cancel()
    }

    fun execute(): OperatorResponse {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("call cancelled")
        val pool = ConnectionPool(0, 1, TimeUnit.NANOSECONDS)
        try {
            val newCall = buildClient(pool).newCall(request)
            if (!call.compareAndSet(null, newCall) || cancelled.get()) {
                newCall.cancel()
                throw InterruptedIOException("call cancelled")
            }
            val response = newCall.execute()
            var completed = false
            try {
                val result = OperatorResponse(response.code, readBody(response))
                completed = true
                return result
            } finally {
                // Closing an unread/partially-read body may try to drain it. Abort the socket first.
                if (!completed) newCall.cancel()
                response.close()
            }
        } finally {
            call.getAndSet(null)?.cancel()
            pool.evictAll()
        }
    }

    private fun buildClient(pool: ConnectionPool): OkHttpClient {
        val builder = OkHttpClient.Builder()
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
        customizeClient(builder)
        return builder.build()
    }

    private fun readBody(response: Response): String {
        val body = response.body
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_FETCH_BYTES) throw tooLarge()
        val initialSize = minOf(MAX_FETCH_BYTES, declaredLength.coerceAtLeast(0).toInt())
        val output = ByteArrayOutputStream(initialSize)
        val source = body.source()
        val buffer = ByteArray(8_192)
        var received = 0
        while (true) {
            val count = source.read(buffer, 0, minOf(buffer.size, MAX_FETCH_BYTES - received + 1))
            if (count == -1) break
            received += count
            if (received > MAX_FETCH_BYTES) throw tooLarge()
            output.write(buffer, 0, count)
        }
        if (declaredLength >= 0 && received.toLong() != declaredLength) {
            throw OperatorHttpException(OperatorHttpFailure.UNAVAILABLE, target.scheme, target.host)
        }
        return output.toString(Charsets.UTF_8)
    }

    private fun tooLarge() = OperatorHttpException(OperatorHttpFailure.TOO_LARGE, target.scheme, target.host)
}
