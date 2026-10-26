package ch.nokillswit.telemetry

import ch.nokillswit.entities.EntityPressure
import ch.nokillswit.infra.concurrency.ExecutorStats
import ch.nokillswit.infra.concurrency.LedgerSnapshot
import ch.nokillswit.integration.IntegrationPressure

/**
 * The pressure section's per-process collector (`.claude/docs/observability.md` "Telemetry"): the five bounded things that answer
 * "is Toadie near a limit?" - the read ledger, the entity-query permits, the jq pool, the outbound fetch pool and the integration
 * API. Each source is a function seam resolved at `ApplicationStarted`; an absent one (the integration API when disabled) writes no
 * rows. Counters that only grow (the refusals) become DELTAS against the previous read, kept here, the first against zero (the
 * process start); a counter that went backwards is clamped to 0. The baselines (and the ledger's interval) advance in [commit], which
 * the sampler calls only after the readings were durably stored, so an interval whose write failed is not lost: the next [collect]
 * reports the deltas of both. Not thread-safe - the sampler ticks from one coroutine.
 */
class PressureCollector(
    private val ledger: (() -> LedgerSnapshot)? = null,
    private val entities: (() -> EntityPressure)? = null,
    private val fetch: (() -> ExecutorStats)? = null,
    private val integration: (() -> IntegrationPressure)? = null,
    /** Ends the ledger's interval once its snapshot was stored (`ReadLedger.endInterval`). */
    private val endLedgerInterval: (LedgerSnapshot) -> Unit = {},
) {
    private val previous = HashMap<String, Long>()
    private var pending = HashMap<String, Long>()
    private var pendingLedger: LedgerSnapshot? = null

    fun collect(): List<Reading> = buildList {
        val nextBaseline = HashMap<String, Long>()
        pendingLedger = null
        ledger?.invoke()?.let {
            pendingLedger = it
            add(Reading(TelemetryMetrics.LEDGER_IN_FLIGHT, it.inFlightBytes.toDouble()))
            add(Reading(TelemetryMetrics.LEDGER_PEAK, it.intervalPeak.toDouble()))
            add(Reading(TelemetryMetrics.LEDGER_REFUSALS, delta(nextBaseline, "ledger", it.refusals)))
        }
        entities?.invoke()?.let {
            add(Reading(TelemetryMetrics.QUERY_IN_USE, it.queryInUse.toDouble()))
            add(Reading(TelemetryMetrics.QUERY_REFUSALS, delta(nextBaseline, "query", it.queryRefusals)))
            add(Reading(TelemetryMetrics.JQ_QUARANTINED, it.jq.quarantined.toDouble()))
            add(Reading(TelemetryMetrics.JQ_ACTIVE, it.jq.active.toDouble()))
            add(Reading(TelemetryMetrics.JQ_QUEUED, it.jq.queued.toDouble()))
        }
        fetch?.invoke()?.let {
            add(Reading(TelemetryMetrics.FETCH_ACTIVE, it.active.toDouble()))
            add(Reading(TelemetryMetrics.FETCH_QUEUED, it.queued.toDouble()))
        }
        integration?.invoke()?.let {
            add(Reading(TelemetryMetrics.INTEGRATION_IN_USE, it.inUse.toDouble()))
            add(Reading(TelemetryMetrics.INTEGRATION_RETAINED, it.retainedBytes.toDouble()))
            add(Reading(TelemetryMetrics.INTEGRATION_REFUSALS, delta(nextBaseline, "integration", it.refusals)))
        }
        pending = nextBaseline
    }

    /** Called by the sampler once the readings of the last [collect] are stored: the baselines advance and the ledger interval ends. */
    fun commit() {
        previous.putAll(pending)
        pendingLedger?.let(endLedgerInterval)
        pendingLedger = null
    }

    /** The growth of a cumulative [current] since the last COMMITTED read of [key] (never negative); [next] takes the new baseline. */
    private fun delta(next: MutableMap<String, Long>, key: String, current: Long): Double {
        next[key] = current
        return (current - (previous[key] ?: 0L)).coerceAtLeast(0L).toDouble()
    }
}
