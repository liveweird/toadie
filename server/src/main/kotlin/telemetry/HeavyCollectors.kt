package ch.nokillswit.telemetry

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/**
 * How a heavy collector reads: every statement in a SHORT transaction of its own, so it holds no lock beyond that one statement and
 * nothing it reads is part of the cluster transaction - a deploy's `ALTER` is never queued behind a scan. No statement carries a
 * `queryTimeout` of its own (the global `postgres.statementTimeoutSeconds` and the heavy phase's `2/5 interval` budget bound it).
 */
class HeavyReader internal constructor(private val store: TelemetryStore) {
    suspend fun <T> read(block: suspend R2dbcTransaction.() -> T): T = store.transaction(block)
}

/**
 * A collector whose reads are too heavy for the cluster transaction (the workspace row counts,
 * `.claude/docs/observability.md` "Telemetry"). The cluster section only decides that one is due and CLAIMS the run by writing its
 * [marker] (value 0), under the lock and committed with the cheap collectors' readings, so at most one attempt is made per
 * [intervalMillis] whatever its outcome; the sampler then runs [collect] after that transaction, each statement through the
 * [HeavyReader], under the heavy phase's own budget, inserts the readings in a transaction of their own and sets the marker to the
 * run's duration in milliseconds (at least 1: a marker still at 0 is a claim that did not complete).
 */
class HeavyCollector(
    val name: String,
    val marker: MetricDef,
    val intervalMillis: Long,
    val collect: suspend HeavyReader.() -> List<Reading>,
)

/** The heavy collectors this process claims when it holds the cluster lock: the workspace storage sizes and counts. */
internal fun heavyCollectors(config: TelemetryConfig): List<HeavyCollector> = listOf(
    HeavyCollector("storage", TelemetryMetrics.STORAGE_TICK, config.storageIntervalSeconds * MILLIS_PER_SECOND) { storageReadings() },
)

private const val MILLIS_PER_SECOND = 1000L

/** Runs a SELECT and maps each row; the rows are read whole (every use is a handful of catalog rows or one count). */
internal suspend fun <T : Any> R2dbcTransaction.query(sql: String, map: (io.r2dbc.spi.Row) -> T): List<T> =
    exec(sql, explicitStatementType = StatementType.SELECT) { row -> map(row) }?.toList().orEmpty().filterNotNull()
