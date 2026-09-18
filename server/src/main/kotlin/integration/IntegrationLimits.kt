package ch.nokillswit.integration

import ch.nokillswit.entities.estimatedHeapBytes
import ch.nokillswit.authz.TooManyRequestsException
import io.ktor.server.plugins.BadRequestException
import java.util.LinkedHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.JsonElement

internal const val MAX_QUERY_SOURCE_BYTES = 64 * 1024
internal const val MAX_INTEGRATION_BODY_BYTES = 256L * 1024
internal const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
internal const val EXECUTION_TIMEOUT_MILLIS = 5_000L
internal const val MAX_QUERY_DEPTH = 15
internal const val MAX_QUERY_COMPLEXITY = 1_000
internal const val MAX_DISTINCT_EXPENSIVE_ROOTS = 8
internal const val MAX_AUDITED_OPERATION_NAME = 200
private const val MAX_CONCURRENT_REQUESTS = 4
private const val CLIENT_REQUESTS_PER_MINUTE = 120
private const val CLIENT_BUCKET_CAPACITY = 1_024
private const val WINDOW_MILLIS = 60_000L
internal const val MAX_RETAINED_GRAPHQL_HEAP_BYTES = 32L * 1024 * 1024

/** Bounded admission before authentication and a bounded, authenticated-client-only rate map.
 * Invalid credentials never become map keys, and saturation never queues a coroutine. */
internal class IntegrationLimits(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    concurrentRequests: Int = MAX_CONCURRENT_REQUESTS,
    private val requestsPerMinute: Int = CLIENT_REQUESTS_PER_MINUTE,
    private val bucketCapacity: Int = CLIENT_BUCKET_CAPACITY,
) {
    private val admission = Semaphore(concurrentRequests)
    private val buckets = LinkedHashMap<UInt, Window>(16, 0.75f, true)

    internal class Lease(private val semaphore: Semaphore) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (!closed) {
                closed = true
                semaphore.release()
            }
        }
    }

    private data class Window(var startedAt: Long, var requests: Int)

    fun tryAcquire(): Lease? = if (admission.tryAcquire()) Lease(admission) else null

    @Synchronized
    fun allow(clientId: UInt): Boolean {
        val now = nowMillis()
        val window = buckets[clientId]
        if (window == null) {
            if (buckets.size >= bucketCapacity) {
                val eldest = buckets.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
            buckets[clientId] = Window(now, 1)
            return true
        }
        if (now - window.startedAt >= WINDOW_MILLIS || now < window.startedAt) {
            window.startedAt = now
            window.requests = 1
            return true
        }
        if (window.requests >= requestsPerMinute) return false
        window.requests += 1
        return true
    }

    @Synchronized
    internal fun bucketCount(): Int = buckets.size
}

internal fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

/** Process-wide budget for result objects retained after a domain service releases its own read
 * reservation. Each GraphQL request holds one reservation until its response is encoded. */
internal class IntegrationRetainedLedger(private val capacity: Long = MAX_RETAINED_GRAPHQL_HEAP_BYTES) {
    private val inFlight = AtomicLong(0)

    fun open(): Reservation = Reservation()

    inner class Reservation : AutoCloseable {
        private var held = 0L
        private var closed = false

        fun charge(value: Any?) {
            check(!closed) { "reservation already closed" }
            val bytes = estimatedRetainedBytes(value)
            while (true) {
                val current = inFlight.get()
                val next = current + bytes
                if (next > capacity) {
                    if (held + bytes > capacity) {
                        throw BadRequestException("Root result exceeds the response memory limit")
                    }
                    throw TooManyRequestsException("Integration response memory is busy — retry shortly")
                }
                if (inFlight.compareAndSet(current, next)) {
                    held += bytes
                    return
                }
            }
        }

        override fun close() {
            if (!closed) {
                closed = true
                inFlight.addAndGet(-held)
            }
        }
    }
}

internal fun estimatedRetainedBytes(value: Any?): Long = when (value) {
    null -> 0L
    is JsonElement -> estimatedHeapBytes(value)
    is String -> 40L + value.length * 2L
    is Map<*, *> -> 64L + value.entries.sumOf { (key, child) ->
        45L + estimatedRetainedBytes(key.toString()) + estimatedRetainedBytes(child)
    }
    is Iterable<*> -> 64L + value.sumOf { 4L + estimatedRetainedBytes(it) }
    is Array<*> -> 64L + value.sumOf { 4L + estimatedRetainedBytes(it) }
    else -> 24L
}

/** Dedicated bounded executor for parsing and graphql-java orchestration. Service fetchers are
 * children of the request scope created on these workers, so timeout/disconnect cancellation
 * still reaches their futures. */
internal class IntegrationExecutor : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS) { runnable ->
        Thread(runnable, "integration-graphql").apply { isDaemon = true }
    }
    val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

    override fun close() = dispatcher.close()
}
