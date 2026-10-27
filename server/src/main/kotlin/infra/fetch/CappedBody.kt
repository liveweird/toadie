package ch.nokillswit.infra.fetch

import okhttp3.Response
import java.io.ByteArrayOutputStream

/**
 * The one capped response-body reader both outbound clients share ([UrlFetcher] and
 * [OperatorHttp]): refuses a declared `Content-Length` over [MAX_FETCH_BYTES], stops streaming the
 * moment the body crosses it, and treats a body shorter or longer than its declared length as a
 * truncated exchange. Only the failures differ per caller, so each passes its own factories; the
 * thrown types are the callers' own and unchanged.
 */
internal fun readCappedBody(
    response: Response,
    tooLarge: () -> Throwable,
    unavailable: () -> Throwable,
): String {
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
    if (declaredLength >= 0 && received.toLong() != declaredLength) throw unavailable()
    return output.toString(Charsets.UTF_8)
}
