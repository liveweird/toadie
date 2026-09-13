package ch.nokillswit.entityquery

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * The evaluator's cooperative execution budget (`.claude/docs/entity-query-language.md`
 * "Evaluation semantics"): a per-query deadline plus a cap on how many intermediate join rows
 * (bindings) one evaluation may hold at once — the `computed.jq.deadlineMillis` idiom
 * (`entities/JqCalculation.kt`), as a COOPERATIVE Kotlin loop: the evaluator never blocks on
 * I/O, so [checkpoint] only needs to observe the caller's coroutine
 * ([kotlinx.coroutines.ensureActive]) and the clock. Nothing can preempt a candidate mid-way,
 * which is why per-candidate work is itself capped (`MAX_STRING_OPERAND_CHARS`).
 */

/** [checkpoint]/[QueryBudget.countBindings] refuse further work with one [code] — always a [QueryDiagnosticCodes] constant. */
class QueryBudgetExceeded(val code: String) : RuntimeException(code)

class QueryBudget(
    deadlineMillis: Long,
    val maxBindings: Int = MAX_QUERY_BINDINGS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val deadlineAt: Long = nanoTime() + deadlineMillis * NANOS_PER_MILLI

    /**
     * Called per produced candidate during evaluation — EVERY call checks caller cancellation and
     * the clock (a `nanoTime` read costs ~25 ns, far less than producing a candidate), so the
     * deadline is observed within one candidate's work of passing; the per-candidate work itself
     * is bounded by the operand caps in `QueryValues.kt`. Never catches
     * [kotlinx.coroutines.CancellationException] — it must propagate unchanged out of the
     * evaluator.
     */
    suspend fun checkpoint() {
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
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
