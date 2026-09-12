package ch.nokillswit.entityquery

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * The evaluator's cooperative execution budget (`.claude/docs/entity-query-language.md`
 * "Evaluation semantics"): a per-query deadline plus a cap on how many intermediate join rows
 * (bindings) one evaluation may hold at once — the `computed.jq.deadlineMillis` idiom
 * (`entities/JqCalculation.kt`), but a plain cooperative Kotlin loop rather than a bounded
 * worker pool: the evaluator never blocks on I/O, so [checkpoint] only needs to yield back to
 * the caller's coroutine ([kotlinx.coroutines.ensureActive]) and check the clock.
 */

/** [checkpoint]/[QueryBudget.countBindings] refuse further work with one [code] — always a [QueryDiagnosticCodes] constant. */
class QueryBudgetExceeded(val code: String) : RuntimeException(code)

class QueryBudget(
    deadlineMillis: Long,
    val maxBindings: Int = MAX_QUERY_BINDINGS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val deadlineAt: Long = nanoTime() + deadlineMillis * NANOS_PER_MILLI
    private var checkpointCount = 0L

    /**
     * Called per produced candidate during evaluation — cheap on every call (an increment plus a
     * modulo check); every [CHECK_INTERVAL]th call also checks caller cancellation and the
     * deadline. Never catches [kotlinx.coroutines.CancellationException] — it must propagate
     * unchanged out of the evaluator.
     */
    suspend fun checkpoint() {
        checkpointCount++
        if (checkpointCount % CHECK_INTERVAL != 0L) return
        coroutineContext.ensureActive()
        if (nanoTime() >= deadlineAt) throw QueryBudgetExceeded(QueryDiagnosticCodes.DEADLINE_EXCEEDED)
    }

    /**
     * [n] is the CURRENT size of a growing binding list — the evaluator calls this after EVERY
     * appended row (inside a hop's fan-out loop, not only once a hop is complete), so the list
     * never grows more than one row past [maxBindings].
     */
    fun countBindings(n: Int) {
        if (n > maxBindings) throw QueryBudgetExceeded(QueryDiagnosticCodes.BINDING_LIMIT)
    }

    private companion object {
        const val CHECK_INTERVAL = 1024L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
