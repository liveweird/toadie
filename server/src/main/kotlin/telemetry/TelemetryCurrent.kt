package ch.nokillswit.telemetry

import ch.nokillswit.plugins.timerWindowSeconds
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A process with no sample (and no tick) for more than this many sample intervals is `stale`. */
private const val STALE_AFTER_INTERVALS = 3

/** A process unseen for longer than this is not listed at all (a stale one stays for a day if it is the last one). */
private const val LISTED_FOR_MILLIS = MILLIS_PER_DAY

/** A stale process last seen within this long stays listed even when another is live: a wedged replica must stay visible. */
private const val WEDGED_VISIBLE_MILLIS = MILLIS_PER_HOUR

private const val TOP_ROUTES = 15

/** The most processes `current` lists - the same number as `maxItems` of `TelemetryCurrent.processes` in the spec (pinned by a test). */
internal const val MAX_PROCESSES = 1000
private const val PERCENT = 100.0
private const val ONE_DECIMAL = 10.0

/** The per-process groups the newest raw tick is read for (the workspace group is cluster-wide). */
private val PROCESS_METRICS: List<MetricDef> = TelemetryMetrics.all.filter {
    it.group == MetricGroup.MEMORY || it.group == MetricGroup.JVM || it.group == MetricGroup.REQUESTS ||
        it.group == MetricGroup.PRESSURE
}

/**
 * `GET /api/v1/telemetry/current`: the processes with their latest sample, the capacities the pressure figures are measured
 * against, the newest workspace measurement and the busiest routes of the latest interval. Everything comes from the telemetry
 * tables (`TelemetryReads`) except [limits], which reads the live seams and the configuration.
 */
internal class TelemetryCurrentReport(
    private val reads: TelemetryReads,
    private val limits: () -> TelemetryLimits,
    private val intervalSeconds: Int,
) {
    suspend fun build(now: Long): TelemetryCurrent {
        val seen = reads.processesSeenSince(now - LISTED_FOR_MILLIS)
        // Only a process that can show FRESH figures is sampled together: after a restart the previous run stays in `seen` for a
        // day, and its old `last_seen` must not drag the read's `ts` floor back over the live process's whole history. A process
        // last seen before `freshSince` is stale by its last tick alone; the single newest of those is listed only when nothing
        // fresher exists, and its sample is read by a small query of its own.
        val freshSince = now - maxOf(WEDGED_VISIBLE_MILLIS, STALE_AFTER_INTERVALS * intervalSeconds * 1000L)
        val (fresh, older) = seen.partition { it.lastSeen >= freshSince }
        val oldest = if (fresh.isEmpty()) listOfNotNull(older.maxByOrNull { it.lastSeen }) else emptyList()
        val considered = fresh + oldest
        val samples = reads.latestPerProcess(fresh, PROCESS_METRICS) + reads.latestPerProcess(oldest, PROCESS_METRICS)
        val stale = considered.filter { isStale(it, samples[it.id], now) }.map { it.id }.toSet()
        val processes = visibleProcesses(considered, stale, now)
        val cluster = reads.latestCluster(with(TelemetryMetrics) { listOf(DB_SIZE, DB_GROUP_SIZE, WS_ROWS) })
        // Requests merge over the LIVE processes only: a stale one's last interval is not "the latest interval".
        val live = processes.filter { it.id !in stale }.mapNotNull { samples[it.id] }
        return TelemetryCurrent(
            generatedAt = now,
            sampleIntervalSeconds = intervalSeconds,
            limits = limits(),
            processes = processes.map { it.toWire(samples[it.id], it.id in stale) },
            workspace = workspace(cluster),
            requests = requests(live),
        )
    }

    /**
     * Stale = nothing for more than three sample intervals: no tick (`last_seen`), or ticks without a sample - the newest sample's
     * bucket is that old (the band between three and five intervals, where `last_seen` is still fresh enough to read it), or none
     * is retained for a process older than that (a sampler whose inserts fail while it still ticks). The age is absolute, so a
     * timestamp in the FUTURE beyond the limit (a skewed clock) is stale too instead of looking forever fresh.
     */
    private fun isStale(process: ProcessRow, sample: ProcessSample?, now: Long): Boolean {
        val limit = STALE_AFTER_INTERVALS * intervalSeconds * 1000L
        return abs(now - process.lastSeen) > limit || (sample?.let { abs(now - it.ts) > limit } ?: (now - process.startedAt > limit))
    }

    /**
     * The processes worth listing: every live one; every stale one seen within the last hour (a wedged process stays visible, so
     * does the container a rolling update just replaced, for that hour); and the newest older stale one when none is alive. At
     * most [MAX_PROCESSES] (the most recently seen kept).
     */
    private fun visibleProcesses(seen: List<ProcessRow>, stale: Set<Int>, now: Long): List<ProcessRow> {
        val (dead, live) = seen.partition { it.id in stale }
        val recent = dead.filter { now - it.lastSeen <= WEDGED_VISIBLE_MILLIS }
        val lastOne = if (live.isEmpty()) listOfNotNull(dead.maxByOrNull { it.lastSeen }) else emptyList()
        return (live + recent + lastOne).distinctBy { it.id }.sortedByDescending { it.lastSeen }.take(MAX_PROCESSES)
            .sortedWith(compareBy({ it.startedAt }, { it.id }))
    }

    private fun ProcessRow.toWire(sample: ProcessSample?, stale: Boolean): TelemetryProcess {
        fun bytes(metric: MetricDef) = sample?.value(metric)?.roundToLong()
        fun count(metric: MetricDef) = sample?.value(metric)?.roundToInt()
        fun whole(metric: MetricDef) = sample?.value(metric)?.roundToLong()
        val workingSet = bytes(TelemetryMetrics.CGROUP_WORKING_SET)
        val percent = if (workingSet != null && containerLimitBytes != null && containerLimitBytes > 0) {
            (workingSet.toDouble() / containerLimitBytes * PERCENT * ONE_DECIMAL).roundToLong() / ONE_DECIMAL
        } else {
            null
        }
        // A labelled metric (GC, per collector): all collectors summed, null when the tick wrote none.
        fun total(metric: MetricDef): Long? {
            val figures = sample?.values?.filterKeys { it.first == metric.name }?.values.orEmpty()
            return if (figures.isEmpty()) null else figures.sum().roundToLong()
        }
        return TelemetryProcess(
            id = id, instance = instance, startedAt = startedAt, lastSeenAt = lastSeen, sampledAt = sample?.ts,
            stale = stale, version = version, commit = commit,
            memory = TelemetryMemory(
                heapUsedBytes = bytes(TelemetryMetrics.HEAP_USED), heapCommittedBytes = bytes(TelemetryMetrics.HEAP_COMMITTED),
                heapMaxBytes = heapMaxBytes, oldGenPeakBytes = bytes(TelemetryMetrics.OLD_GEN_PEAK),
                nonHeapBytes = bytes(TelemetryMetrics.NON_HEAP_USED), directBytes = bytes(TelemetryMetrics.DIRECT_BUFFERS),
                rssBytes = bytes(TelemetryMetrics.RSS), cgroupCurrentBytes = bytes(TelemetryMetrics.CGROUP_CURRENT),
                cgroupAnonBytes = bytes(TelemetryMetrics.CGROUP_ANON), workingSetBytes = workingSet,
                cgroupPeakBytes = bytes(TelemetryMetrics.CGROUP_PEAK), containerLimitBytes = containerLimitBytes,
                percentOfLimit = percent,
            ),
            jvm = TelemetryJvm(
                cpuRatio = sample?.value(TelemetryMetrics.CPU_RATIO), threads = count(TelemetryMetrics.THREADS),
                gcCount = total(TelemetryMetrics.GC_COUNT), gcTimeMs = total(TelemetryMetrics.GC_TIME),
                poolAcquired = count(TelemetryMetrics.POOL_ACQUIRED), poolIdle = count(TelemetryMetrics.POOL_IDLE),
                poolPending = count(TelemetryMetrics.POOL_PENDING), poolAllocated = count(TelemetryMetrics.POOL_ALLOCATED),
            ),
            pressure = TelemetryPressure(
                ledgerInFlightBytes = bytes(TelemetryMetrics.LEDGER_IN_FLIGHT), ledgerPeakBytes = bytes(TelemetryMetrics.LEDGER_PEAK),
                ledgerRefusals = whole(TelemetryMetrics.LEDGER_REFUSALS),
                queryInUse = count(TelemetryMetrics.QUERY_IN_USE), queryRefusals = whole(TelemetryMetrics.QUERY_REFUSALS),
                jqQuarantined = count(TelemetryMetrics.JQ_QUARANTINED), jqActive = count(TelemetryMetrics.JQ_ACTIVE),
                jqQueued = count(TelemetryMetrics.JQ_QUEUED),
                fetchActive = count(TelemetryMetrics.FETCH_ACTIVE), fetchQueued = count(TelemetryMetrics.FETCH_QUEUED),
                integrationInUse = count(TelemetryMetrics.INTEGRATION_IN_USE),
                integrationRetainedBytes = bytes(TelemetryMetrics.INTEGRATION_RETAINED),
                integrationRefusals = whole(TelemetryMetrics.INTEGRATION_REFUSALS),
            ),
        )
    }

    private fun workspace(cluster: Map<String, ClusterSample>): TelemetryWorkspace? {
        val size = cluster[TelemetryMetrics.DB_SIZE.name] ?: return null
        val databaseBytes = size.values[""] ?: return null
        val groups = cluster[TelemetryMetrics.DB_GROUP_SIZE.name]?.values.orEmpty().entries
            .sortedBy { it.key }.map { TelemetryGroupSize(it.key, it.value.roundToLong()) }
        val rows = cluster[TelemetryMetrics.WS_ROWS.name]?.values.orEmpty().entries
            .sortedBy { it.key }.map { TelemetryRowCount(it.key, it.value.roundToLong()) }
        return TelemetryWorkspace(measuredAt = size.ts, databaseBytes = databaseBytes.roundToLong(), groups = groups, rows = rows)
    }

    /**
     * The latest interval's request figures over the live processes: counts and error counts add up, the p50 is the count-weighted
     * mean of the processes' p50s and the p95 the largest (a mean of quantiles understates a tail). The interval is the newest
     * bucket every CONTRIBUTING live process has written (the oldest of their newest buckets), so a process lagging one tick is
     * neither left out nor undercounted; one lagging more than one interval does not contribute at all; one with no row there
     * adds nothing. With one process this is that process's newest
     * bucket; the merge is stated and tested so a future replica never has to re-derive it.
     */
    private fun requests(allLive: List<ProcessSample>): TelemetryRequests {
        // A live process more than one interval behind the newest (wedged, but not yet 3 intervals stale) does not contribute: its
        // bucket is one the healthy processes no longer hold in their windows, and the stale rule will flag it soon enough.
        val newest = allLive.maxOfOrNull { it.ts }
        val live = allLive.filter { newest != null && newest - it.ts <= intervalSeconds * 1000L }
        val common = live.minOfOrNull { it.ts }
        val rows: List<Bucket> = live.mapNotNull { it.buckets[common] }
        fun Bucket.value(metric: MetricDef, label: String): Double? = this[metric.name to label]
        val labels = rows.flatMap { it.keys }.filter { it.first == TelemetryMetrics.HTTP_COUNT.name }.map { it.second }.distinct()
        fun stats(label: String): TelemetryRouteStats {
            val counts = rows.map { it.value(TelemetryMetrics.HTTP_COUNT, label) ?: 0.0 }
            val count = counts.sum()
            // Only the processes that wrote a p50 weigh in, divided by THEIR requests.
            val withP50 = rows.zip(counts).mapNotNull { (row, n) -> row.value(TelemetryMetrics.HTTP_P50, label)?.let { it to n } }
            val p50Count = withP50.sumOf { it.second }
            return TelemetryRouteStats(
                route = label,
                count = count.roundToLong(),
                p50Ms = if (p50Count > 0) withP50.sumOf { it.first * it.second } / p50Count else null,
                p95Ms = rows.mapNotNull { it.value(TelemetryMetrics.HTTP_P95, label) }.maxOrNull(),
                serverErrors = rows.sumOf { it.value(TelemetryMetrics.HTTP_5XX, label) ?: 0.0 }.roundToLong(),
                rateLimited = rows.sumOf { it.value(TelemetryMetrics.HTTP_429, label) ?: 0.0 }.roundToLong(),
            )
        }
        val routes = labels.filter { it != TOTAL_LABEL }.map(::stats).sortedWith(compareBy({ -it.count }, { it.route })).take(TOP_ROUTES)
        return TelemetryRequests(intervalSeconds, timerWindowSeconds(intervalSeconds).toInt(), common, stats(TOTAL_LABEL), routes)
    }
}
