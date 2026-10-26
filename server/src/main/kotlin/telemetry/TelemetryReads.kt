package ch.nokillswit.telemetry

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** One `telemetry_processes` row. */
internal class ProcessRow(
    val id: Int,
    val instance: String,
    val startedAt: Long,
    val lastSeen: Long,
    val version: String?,
    val commit: String?,
    val heapMaxBytes: Long,
    val containerLimitBytes: Long?,
)

/** The figures one raw tick wrote, by `(metric, label)`. */
internal typealias Bucket = Map<Pair<String, String>, Double>

/**
 * A process's newest raw tick: its bucket [ts] and every `(metric, label)` figure the tick wrote ([values]), plus every retained
 * bucket within the read window ([buckets], newest included) so a merge across processes can use a bucket they ALL wrote.
 */
internal class ProcessSample(val ts: Long, val values: Bucket, val buckets: Map<Long, Bucket> = mapOf(ts to values)) {
    fun value(metric: MetricDef, label: String = ""): Double? = values[metric.name to label]
}

/** The newest cluster-wide measurement of one metric: its bucket [ts] and the figure per label. */
internal class ClusterSample(val ts: Long, val values: Map<String, Double>)

/**
 * One group of the history query: [n] samples folded, their [sum] (`avg * n`: the n-weighted mean's numerator, a DELTA's total) and
 * the largest [max], for one metric, label and bucket start [t] (all processes merged: a trend survives a restart).
 */
internal class HistoryRow(
    val metric: String,
    val label: String,
    val t: Long,
    val n: Long,
    val sum: Double,
    val max: Double,
)

/** How the history query reads the samples: the bucket width and the first bucket's start. */
internal class HistoryWindow(val range: HistoryRange, val alignedFrom: Long, val bucketMillis: Long)

/**
 * The telemetry tables' reads for the telemetry API (`TelemetryRoutes.kt`): nothing but SELECTs, each in a transaction of its own
 * under the global statement timeout (no per-transaction override, `.claude/docs/persistence.md` "Telemetry (V46)"). Every raw-tier
 * predicate is `resolution IN (RAW_RESOLUTIONS)` so the primary key gets equality prefixes. [intervalSeconds] is the configured
 * sample cadence (how far back a process's newest tick can lie).
 */
internal class TelemetryReads(private val store: TelemetryStore, private val intervalSeconds: Int) {
    private val intervalMillis = intervalSeconds * 1000L

    /** Test seam: the rows the last [latestPerProcess] pulled over the wire (pins the SQL-side window; never read in production). */
    @Volatile
    internal var sampleRowsRead: Int = 0

    /** Every process seen at or after [since], by id. */
    suspend fun processesSeenSince(since: Long): List<ProcessRow> = store.transaction {
        val p = TelemetryStore.Processes
        p.selectAll().where { p.lastSeen greaterEq since }.orderBy(p.id).toList().map {
            ProcessRow(
                it[p.id].value, it[p.instance], it[p.startedAt], it[p.lastSeen], it[p.buildVersion], it[p.buildCommit],
                it[p.heapMaxBytes], it[p.containerLimitBytes],
            )
        }
    }

    /**
     * Each of [processes]' raw ticks restricted to [metrics], by process id: the rows within two intervals of the process's
     * `last_seen`, the newest bucket being its sample (a figure the newest tick did not write is absent, not carried). The
     * predicate has two layers, BOTH in SQL: `process_id IN (ids)` and a `ts` floor (the oldest process's window) give the primary
     * key an index range, and each process's own `(process_id = x AND ts >= last_seen_x - 2 intervals)` drops the rows outside its
     * window in the database, so about three buckets per process cross the wire - never the process's whole history. Callers pass
     * only processes that can show fresh figures, so the floor stays near the present (`TelemetryCurrent.kt`).
     */
    suspend fun latestPerProcess(processes: List<ProcessRow>, metrics: List<MetricDef>): Map<Int, ProcessSample> {
        if (processes.isEmpty()) return emptyMap()
        val windowStart = processes.associate { it.id to it.lastSeen - 2 * intervalMillis }
        val floor = windowStart.values.min()
        return store.transaction {
            val s = TelemetryStore.Samples
            val ownWindow = windowStart.entries.map { (id, start) -> (s.processId eq id) and (s.ts greaterEq start) }
                .reduce { a, b -> a or b }
            val rows = s.select(s.processId, s.ts, s.metric, s.label, s.avg)
                .where {
                    (s.resolution inList RAW_RESOLUTIONS) and (s.metric inList metrics.map { it.name }) and
                        (s.processId inList windowStart.keys) and (s.ts greaterEq floor) and ownWindow
                }
                .toList()
            sampleRowsRead = rows.size
            rows.groupBy { it[s.processId] }.mapNotNull { (_, own) ->
                val buckets = own.groupBy { it[s.ts] }.mapValues { (_, atTs) ->
                    atTs.associate { (it[s.metric] to it[s.label]) to it[s.avg] }
                }
                val newest = buckets.keys.max()
                own.first()[s.processId] to ProcessSample(newest, buckets.getValue(newest), buckets)
            }.toMap()
        }
    }

    /** The newest raw measurement of each of [metrics] (cluster-wide: whichever process held the lock wrote them), by metric name. */
    suspend fun latestCluster(metrics: List<MetricDef>): Map<String, ClusterSample> = store.transaction {
        val s = TelemetryStore.Samples
        val raw = (s.resolution inList RAW_RESOLUTIONS) and (s.metric inList metrics.map { it.name })
        val newest = s.select(s.metric, s.ts.max()).where { raw }.groupBy(s.metric).toList()
            .associate { it[s.metric] to checkNotNull(it[s.ts.max()]) }
        if (newest.isEmpty()) return@transaction emptyMap()
        val atNewest = newest.map { (metric, ts) -> (s.metric eq metric) and (s.ts eq ts) }.reduce { a, b -> a or b }
        val rows = s.select(s.metric, s.label, s.avg).where { (s.resolution inList RAW_RESOLUTIONS) and atNewest }.toList()
        newest.mapValues { (metric, ts) ->
            ClusterSample(ts, rows.filter { it[s.metric] == metric }.associate { it[s.label] to it[s.avg] })
        }
    }

    /** The first moment any sampler booted (and so the oldest instant any retained sample can describe), or null with no process. */
    suspend fun oldestProcessStart(): Long? = store.transaction {
        val p = TelemetryStore.Processes
        p.select(p.startedAt.min()).toList().singleOrNull()?.get(p.startedAt.min())
    }

    /**
     * The history rows of [metrics] over [window], grouped per metric, label and bucket in SQL (every process merged). 24h reads the
     * raw tier; 7d and 90d read the hourly tier plus the raw rows of the hours the rollup has not folded yet (everything after the
     * newest hourly row), so the newest hour is never missing and no sample is counted twice. [topRoutes] (the requests group) keeps
     * `*` and the ten busiest routes of the range, the routes with the count and percentile metrics only (the series cap).
     */
    suspend fun historyRows(metrics: List<MetricDef>, window: HistoryWindow, topRoutes: Boolean): List<HistoryRow> =
        store.transaction {
            val s = TelemetryStore.Samples
            val rawTier = RAW_RESOLUTIONS.joinToString(",")
            val tier = if (window.range == HistoryRange.H24) {
                "s.resolution IN ($rawTier) AND s.ts >= ${window.alignedFrom}"
            } else {
                val newestHourly = s.select(s.ts.max()).where { s.resolution eq HOURLY_RESOLUTION }.toList().singleOrNull()?.get(s.ts.max())
                val rawFrom = maxOf(window.alignedFrom, newestHourly?.plus(MILLIS_PER_HOUR) ?: window.alignedFrom)
                "((s.resolution = $HOURLY_RESOLUTION AND s.ts >= ${window.alignedFrom}) OR " +
                    "(s.resolution IN ($rawTier) AND s.ts >= $rawFrom))"
            }
            // Metric names and the route-metric names come from the fixed registry, never from input.
            val names = metrics.joinToString(",") { "'${it.name}'" }
            val perRoute = with(TelemetryMetrics) { listOf(HTTP_COUNT, HTTP_P50, HTTP_P95) }.joinToString(",") { "'${it.name}'" }
            val top = if (topRoutes) {
                """
                WITH top AS (
                    SELECT s.label FROM telemetry_samples s
                    WHERE s.metric = '${TelemetryMetrics.HTTP_COUNT.name}' AND s.label <> '$TOTAL_LABEL' AND $tier
                    GROUP BY s.label ORDER BY SUM(s.avg * s.n) DESC, s.label LIMIT $TOP_ROUTES_IN_HISTORY
                )
                """.trimIndent()
            } else {
                ""
            }
            val routeFilter = if (topRoutes) {
                "AND (s.label = '$TOTAL_LABEL' OR (s.label IN (SELECT label FROM top) AND s.metric IN ($perRoute)))"
            } else {
                ""
            }
            query(
                """
                $top
                SELECT s.metric, s.label, (s.ts - s.ts % ${window.bucketMillis})::bigint AS t, SUM(s.n)::bigint,
                       SUM(s.avg * s.n)::float8, MAX(s.max)::float8
                FROM telemetry_samples s
                WHERE s.metric IN ($names) $routeFilter AND $tier
                GROUP BY s.metric, s.label, t
                """.trimIndent(),
            ) { row ->
                HistoryRow(
                    metric = row.get(0, String::class.java).orEmpty(),
                    label = row.get(1, String::class.java).orEmpty(),
                    t = row.get(2, Long::class.javaObjectType)!!,
                    n = row.get(3, Long::class.javaObjectType)!!,
                    sum = row.get(4, Double::class.javaObjectType)!!,
                    max = row.get(5, Double::class.javaObjectType)!!,
                )
            }
        }
}

/** The history's requests group keeps this many routes (plus `*`) so that 10 x 3 + 5 series stay inside the 40-series cap. */
internal const val TOP_ROUTES_IN_HISTORY = 10
