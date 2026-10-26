package ch.nokillswit.telemetry

/** The `range` query parameter: how far back a history reaches. */
internal enum class HistoryRange(val param: String, val millis: Long) {
    H24("24h", MILLIS_PER_DAY),
    D7("7d", 7 * MILLIS_PER_DAY),
    D90("90d", 90 * MILLIS_PER_DAY),
    ;

    companion object {
        fun parse(value: String): HistoryRange? = entries.firstOrNull { it.param == value }
    }
}

/** The `group` query parameter: one section of the panel, naming the [MetricGroup] whose metrics it charts. */
internal enum class HistoryGroup(val param: String, val metrics: MetricGroup) {
    MEMORY("memory", MetricGroup.MEMORY),
    WORKSPACE("workspace", MetricGroup.WORKSPACE),
    PRESSURE("pressure", MetricGroup.PRESSURE),
    REQUESTS("requests", MetricGroup.REQUESTS),
    JVM("jvm", MetricGroup.JVM),
    ;

    companion object {
        fun parse(value: String): HistoryGroup? = entries.firstOrNull { it.param == value }
    }
}

/** A series carries at most this many points, a response at most [MAX_SERIES] series. */
internal const val MAX_POINTS = 400
internal const val MAX_SERIES = 40

private const val HOUR_SECONDS = 3_600
private const val SIX_HOUR_SECONDS = 21_600
private const val DAY_SECONDS = 86_400

/**
 * The width of a history bucket in seconds: 24h reads the raw tier at the sample interval (widened to a whole multiple of it only
 * when the span would exceed [MAX_POINTS] points, i.e. an interval under 216 s), 7d the hourly tier (168 points) and 90d the hourly
 * tier folded to six hours (360 points).
 */
internal fun historyBucketSeconds(range: HistoryRange, intervalSeconds: Int): Int = when (range) {
    HistoryRange.H24 -> intervalSeconds * Math.ceilDiv(DAY_SECONDS, MAX_POINTS * intervalSeconds)
    HistoryRange.D7 -> HOUR_SECONDS
    HistoryRange.D90 -> SIX_HOUR_SECONDS
}

private val REGISTRY: Map<String, MetricDef> = TelemetryMetrics.all.associateBy { it.name }

/**
 * `GET /api/v1/telemetry/history`: the series of one panel section over a range. Every group is a SQL aggregation of
 * `telemetry_samples` per metric, label and bucket (`TelemetryReads.historyRows`), all processes merged - a gauge's mean is
 * n-weighted and its max the largest, a DELTA adds up - so a trend survives a restart. The newest raw buckets (and the hours the
 * rollup has not folded yet) are read from the raw tier, so the newest hour is never missing and no sample counts twice.
 */
internal class TelemetryHistoryReport(
    private val reads: TelemetryReads,
    private val intervalSeconds: Int,
) {
    suspend fun build(group: HistoryGroup, range: HistoryRange, now: Long): TelemetryHistory {
        val bucketSeconds = historyBucketSeconds(range, intervalSeconds)
        val bucketMillis = bucketSeconds * 1000L
        val from = now - range.millis
        val window = HistoryWindow(range, from - Math.floorMod(from, bucketMillis), bucketMillis)
        val metrics = TelemetryMetrics.all.filter { it.group == group.metrics }
        val series = mergeRows(reads.historyRows(metrics, window, topRoutes = group == HistoryGroup.REQUESTS))
        val historyFrom = reads.oldestProcessStart()?.let { maxOf(from, minOf(it, now)) } ?: now
        return TelemetryHistory(
            group = group.param, range = range.param, bucketSeconds = bucketSeconds, from = from, to = now, historyFrom = historyFrom,
            truncated = series.size > MAX_SERIES, series = series.take(MAX_SERIES),
        )
    }

    /** Folds the SQL groups into series: one per (metric, label), ordered by the registry, then the label. */
    private fun mergeRows(rows: List<HistoryRow>): List<TelemetrySeries> {
        val order = TelemetryMetrics.all.withIndex().associate { (index, def) -> def.name to index }
        return rows.filter { it.metric in REGISTRY }.groupBy { it.metric to it.label }.map { (identity, own) ->
            val (metric, label) = identity
            val def = REGISTRY.getValue(metric)
            val points = own.sortedBy { it.t }.map {
                TelemetryPoint(it.t, if (def.kind == MetricKind.DELTA) it.sum else it.sum / it.n, it.max)
            }
            TelemetrySeries(
                key = listOfNotNull(metric, label.ifEmpty { null }).joinToString(":"), metric = metric, label = label,
                unit = def.unit.name, kind = def.kind.name, points = points.takeLast(MAX_POINTS),
            )
        }.sortedWith(compareBy({ order.getValue(it.metric) }, { it.label }))
    }
}
