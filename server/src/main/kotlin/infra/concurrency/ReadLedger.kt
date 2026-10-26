package ch.nokillswit.infra.concurrency

import ch.nokillswit.authz.TooManyRequestsException
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * The process-wide READ memory budget (2.4.0, relocated here from `entities/` in 2.19.0): every
 * whole-workspace read charges the raw bytes it loads and the estimated heap of what it decodes
 * against one [ReadLedger], so a read that cannot fit answers a named `400`/`429` instead of
 * exhausting the heap. Derived from `-Xmx256m` (`server/build.gradle.kts`): a ~170 MiB old
 * generation, ~130 MiB of it headroom after the ~21 MiB idle baseline, half of that headroom the
 * ledger — change the two together. Writers are exempt (serialized by the V28 lock, at most one
 * raw candidate set at a time). `.claude/docs/security.md` "Entity read memory budget".
 */
const val READ_BUDGET_BYTES = 64L * 1024 * 1024

/**
 * Thrown by [ReadLedger.Reservation.reserve] and [ReadLedger.Reservation.charge] when the bytes do
 * not fit. [ownRequest] is true when THIS reservation alone would exceed the capacity (the
 * caller's workspace is too large — a `400`), false when other in-flight reads hold the room
 * (contention — a `429`).
 */
class ReadBudgetExceeded(val ownRequest: Boolean, val requested: Long) :
    RuntimeException("read budget exceeded (ownRequest=$ownRequest, requested=$requested)")

// Operational only (never audited, `.claude/docs/observability.md` "Not audit events"): sizes, no
// caller and no content. The `JqCalculation.kt` named-logger idiom.
private val log = LoggerFactory.getLogger("ch.nokillswit.infra.concurrency.ReadLedger")

/**
 * One reading of the [ReadLedger] for the telemetry sampler ([ReadLedger.snapshot]): the bytes held now, the highest in-flight
 * total since the previous [ReadLedger.endInterval] ([intervalPeak]), the refusals since the process started (a cumulative counter -
 * the sampler differences it) and the [capacity].
 */
class LedgerSnapshot(val inFlightBytes: Long, val intervalPeak: Long, val refusals: Long, val capacity: Long)

/**
 * The process-wide in-flight read ledger — one per application; a test seam via its capacity.
 * A read PREPAYS: [Reservation.reserve] books its whole conservative estimate in one step (it is
 * admitted or refused up front, nothing is held while deciding), and the per-row decode
 * [Reservation.charge]s that follow draw that prepaid pool down instead of moving the shared
 * total. N concurrent reads therefore admit exactly floor(capacity / estimate) and the rest are
 * refused immediately, rather than every read discovering the overflow row by row.
 */
class ReadLedger(private val capacity: Long = READ_BUDGET_BYTES) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val inFlight = AtomicLong(0)
    private val peakSeen = AtomicLong(0)
    private val intervalPeak = AtomicLong(0)

    /** The highest in-flight total since the latest [snapshot]: what the next interval starts with when [endInterval] commits it. */
    private val sinceSnapshot = AtomicLong(0)
    private val refusalCount = AtomicLong(0)

    /** The highest in-flight total observed (a test seam: "one read, not two"). */
    val peak: Long get() = peakSeen.get()

    /** The bytes currently reserved across every open reservation. */
    val inFlightBytes: Long get() = inFlight.get()

    /** The reads refused so far (own-request 400s and contention 429s alike). */
    val refusals: Long get() = refusalCount.get()

    /**
     * The telemetry sampler's reading: in-flight bytes, the interval peak, the refusal count and the capacity. It does NOT end the
     * interval - that happens only when the sampler has durably stored the figure ([endInterval]), so a failed write loses no peak:
     * the next reading still covers it. It starts the "since this snapshot" accumulator that [endInterval] hands to the next
     * interval. The lifetime [peak] seam is untouched.
     */
    fun snapshot(): LedgerSnapshot {
        val held = inFlight.get()
        sinceSnapshot.set(held)
        return LedgerSnapshot(held, maxOf(held, intervalPeak.get()), refusalCount.get(), capacity)
    }

    /**
     * Ends the interval of the latest [snapshot], whose figures have been stored: the next interval starts from the highest total
     * reached SINCE that snapshot (a burst that came and went in between is kept, however small) and from what is held right now.
     * Admission and accounting are untouched - this only moves the telemetry peak.
     */
    fun endInterval() {
        // snapshot() and endInterval() both run on the sampler; only add() moves sinceSnapshot concurrently, so a changed value
        // after the set means a reservation landed in between - recompute so its peak is never dropped.
        do {
            val since = sinceSnapshot.get()
            intervalPeak.set(maxOf(since, inFlight.get()))
        } while (sinceSnapshot.get() != since)
    }

    fun open(): Reservation = Reservation()

    inner class Reservation : AutoCloseable {
        private var held = 0L
        private var prepaid = 0L
        private var reservedTotal = 0L
        private var chargedTotal = 0L
        private var overflowTotal = 0L
        private var closed = false

        /** Bytes booked through [reserve] (admission estimates), a test seam. */
        val reserved: Long get() = reservedTotal

        /** Bytes presented to [charge] so far (decode charges), a test seam. */
        val charged: Long get() = chargedTotal

        /** The part of [charged] that exceeded the prepaid pool and was reserved on top, a test seam. */
        val overflow: Long get() = overflowTotal

        /**
         * All-or-nothing admission: books the whole [estimate] on top of what this reservation
         * already holds in ONE step and adds it to the prepaid pool [charge] draws down, or throws
         * [ReadBudgetExceeded] with nothing held. May be called more than once per reservation (the
         * integration list's team-filter prologue precedes its read set — a bounded two-step).
         */
        fun reserve(estimate: Long) {
            check(!closed) { "reservation already closed" }
            if (estimate <= 0) return
            add(estimate)
            prepaid += estimate
            reservedTotal += estimate
        }

        /**
         * Accounts [bytes] decoded by this read: drawn from the prepaid pool first (the shared
         * total does not move); only the excess is reserved on top, and can still be refused as
         * [reserve] would be. Overflow stays charged because a dense-JSON workspace is charged at
         * its estimated heap (`.claude/docs/security.md` "Entity read memory budget"); with the
         * calibrated admission ratios it is ~zero for sample-shaped data.
         */
        fun charge(bytes: Long) {
            check(!closed) { "reservation already closed" }
            if (bytes <= 0) return
            val drawn = minOf(prepaid, bytes)
            val excess = bytes - drawn
            if (excess > 0) {
                add(excess)
                overflowTotal += excess
            }
            prepaid -= drawn
            chargedTotal += bytes
        }

        private fun add(bytes: Long) {
            while (true) {
                val current = inFlight.get()
                val next = current + bytes
                if (next > capacity) {
                    refusalCount.incrementAndGet()
                    val requested = held + bytes
                    log.info(
                        "read budget refused: ownRequest={} requested={} inFlight={} capacity={}",
                        requested > capacity, requested, current, capacity,
                    )
                    throw ReadBudgetExceeded(ownRequest = requested > capacity, requested = requested)
                }
                if (inFlight.compareAndSet(current, next)) {
                    held += bytes
                    peakSeen.accumulateAndGet(next, ::maxOf)
                    // sinceSnapshot first: endInterval() re-reads it to detect a reservation that landed while it ran.
                    sinceSnapshot.accumulateAndGet(next, ::maxOf)
                    intervalPeak.accumulateAndGet(next, ::maxOf)
                    return
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            inFlight.addAndGet(-held)
            held = 0
            prepaid = 0
        }
    }
}

/**
 * Published by `configureDatabase` where the one [ReadLedger] is built: the telemetry sampler reads [ReadLedger.snapshot] through
 * it (`telemetry/`, which nothing imports back).
 */
val ReadLedgerKey = AttributeKey<ReadLedger>("ReadLedger")

/**
 * `.claude/docs/security.md` "Entity read memory budget": the refusal mapping of a plain
 * (non-query) whole-workspace read — [ReadBudgetExceeded.ownRequest] means THIS call's own
 * [workspace] read is too large for the budget on its own (a plain `400`); otherwise other
 * in-flight reads hold the room (a `429` naming contention, not this caller's fault).
 */
fun throwReadBudgetHttp(cause: ReadBudgetExceeded, workspace: String): Nothing {
    if (cause.ownRequest) {
        throw BadRequestException("The $workspace workspace exceeds the server's read budget")
    }
    throw TooManyRequestsException("Too many workspace reads are in flight — retry shortly")
}
