package ch.nokillswit.entities

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/**
 * The entity READ memory budget (2.4.0): every graph/list/read charges the raw bytes it loads
 * and the estimated heap of what it decodes against one process-wide ledger, so a read that
 * cannot fit answers a named `400`/`429` instead of exhausting the heap. Derived from
 * `-Xmx256m` (`server/build.gradle.kts`): ~130 MiB of old-generation headroom after the idle
 * baseline, half of it the ledger — change the two together. Writers are exempt (serialized by
 * the V28 lock, at most one raw candidate set at a time). `.claude/docs/security.md`
 * "Entity read memory budget".
 */
const val ENTITY_READ_BUDGET_BYTES = 64L * 1024 * 1024

// Measured against kotlinx-serialization-json 1.11.0 on JDK 21 (compact strings, SerialGC):
// a scalar is a JsonLiteral (24 B) + a String (40 B + content); an object is a LinkedHashMap
// (64 B) + 40 B per entry + ~5 B of table slot + the key String; an array is an ArrayList
// (64 B) + 4 B per slot. Reproduces seven measured shapes within ±5 %.
private const val SCALAR_OVERHEAD = 64L
private const val CONTAINER_OVERHEAD = 64L
private const val OBJECT_ENTRY_OVERHEAD = 45L
private const val STRING_OVERHEAD = 40L
private const val ARRAY_SLOT = 4L

/** Estimated retained heap bytes of a decoded [element] (a `JsonNull` costs nothing — it is a singleton). */
fun estimatedHeapBytes(element: JsonElement): Long = when (element) {
    is JsonNull -> 0L
    is JsonPrimitive -> SCALAR_OVERHEAD + element.content.length
    is JsonArray -> CONTAINER_OVERHEAD + element.sumOf { ARRAY_SLOT + estimatedHeapBytes(it) }
    is JsonObject -> CONTAINER_OVERHEAD +
        element.entries.sumOf { (k, v) -> OBJECT_ENTRY_OVERHEAD + STRING_OVERHEAD + k.length + estimatedHeapBytes(v) }
}

/**
 * Thrown by [EntityReadLedger.Reservation.charge] when a charge does not fit. [ownRequest] is true
 * when THIS reservation alone would exceed the capacity (the caller's workspace is too large — a
 * `400`), false when other in-flight reads hold the room (contention — a `429`).
 */
class ReadBudgetExceeded(val ownRequest: Boolean, val requested: Long) :
    RuntimeException("entity read budget exceeded (ownRequest=$ownRequest, requested=$requested)")

/** The process-wide in-flight read ledger — one per [EntityService]; a test seam via its capacity. */
class EntityReadLedger(private val capacity: Long = ENTITY_READ_BUDGET_BYTES) {
    private val inFlight = AtomicLong(0)
    private val peakSeen = AtomicLong(0)

    /** The highest in-flight total observed (a test seam: "one read, not two"). */
    val peak: Long get() = peakSeen.get()

    /** The bytes currently reserved across every open reservation. */
    val inFlightBytes: Long get() = inFlight.get()

    fun open(): Reservation = Reservation()

    inner class Reservation : AutoCloseable {
        private var held = 0L
        private var closed = false

        /** Charged so far by this reservation. */
        val charged: Long get() = held

        /** Reserves [bytes] on top of what this reservation already holds, or throws [ReadBudgetExceeded]. */
        fun charge(bytes: Long) {
            check(!closed) { "reservation already closed" }
            if (bytes <= 0) return
            while (true) {
                val current = inFlight.get()
                val next = current + bytes
                if (next > capacity) {
                    throw ReadBudgetExceeded(ownRequest = held + bytes > capacity, requested = held + bytes)
                }
                if (inFlight.compareAndSet(current, next)) {
                    held += bytes
                    peakSeen.accumulateAndGet(next, ::maxOf)
                    return
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            inFlight.addAndGet(-held)
            held = 0
        }
    }
}
