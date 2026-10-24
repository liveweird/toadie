package ch.nokillswit.infra.concurrency

import ch.nokillswit.authz.TooManyRequestsException
import io.ktor.server.plugins.BadRequestException
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
 * Thrown by [ReadLedger.Reservation.charge] when a charge does not fit. [ownRequest] is true
 * when THIS reservation alone would exceed the capacity (the caller's workspace is too large — a
 * `400`), false when other in-flight reads hold the room (contention — a `429`).
 */
class ReadBudgetExceeded(val ownRequest: Boolean, val requested: Long) :
    RuntimeException("read budget exceeded (ownRequest=$ownRequest, requested=$requested)")

/** The process-wide in-flight read ledger — one per application; a test seam via its capacity. */
class ReadLedger(private val capacity: Long = READ_BUDGET_BYTES) {
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
