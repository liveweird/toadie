package ch.nokillswit.telemetry

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.plugins.safeForLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlin.time.TimeSource

/** The first tick lands this long after start, so a fresh process shows up on the panel at once instead of after up to an interval. */
internal const val FIRST_TICK_DELAY_MILLIS = 5_000L

private const val MAX_FAILURE_TEXT = 300

/** One savepoint at a time (collectors run in sequence): one fixed identifier, since a collector's name may not be a valid one. */
private const val SAVEPOINT_NAME = "telemetry_collector"

/** One reading of the pooled database connections (r2dbc-pool's `PoolMetrics`); the sampler's pool seam. */
data class PoolFigures(val acquired: Int, val idle: Int, val pendingAcquire: Int, val allocated: Int)

/** A per-process collector (the JVM, the cgroup, the connection pool). Its readings are this process's own. */
class ProcessCollector(val name: String, val collect: suspend () -> List<Reading>)

/**
 * A cheap cluster collector: runs INSIDE the cluster transaction, on the process that holds the lock, so it needs no dedupe of its
 * own; its readings are written attributed to that process. A failure rolls back only this collector's statements (a savepoint) and
 * the others still run.
 */
class ClusterCollector(val name: String, val collect: suspend R2dbcTransaction.(now: Long) -> List<Reading>)

/**
 * One failure line per collector, not one per tick: the first failure is a WARN (the exception's `toString`, never a stack),
 * further failures DEBUG, and the first success after a failure an INFO "recovered". A database failure is rendered through
 * `safeForLog` first (class chain and SQLSTATE only, `observability.md` "Database failures are logged by class and SQLSTATE").
 */
internal class FailureLog(private val log: org.slf4j.Logger) {
    private val failing = ConcurrentHashMap.newKeySet<String>()

    fun failed(name: String, e: Throwable) {
        val text = e.safeForLog().toString().take(MAX_FAILURE_TEXT)
        if (failing.add(name)) {
            log.warn("Telemetry collector {} failed: {}", name, text)
        } else {
            log.debug("Telemetry collector {} failed again: {}", name, text)
        }
    }

    fun ok(name: String) {
        if (failing.remove(name)) log.info("Telemetry collector {} recovered", name)
    }
}

/**
 * The sampler (`.claude/docs/observability.md` "Telemetry"): every [TelemetryConfig.sampleIntervalSeconds] it writes this process's
 * own figures (JVM, cgroup, connection pool) to `telemetry_samples`, and - on whichever process takes the cluster lock for the
 * interval - the cluster section (the hourly rollup and prune today).
 *
 * It is a best-effort observer: every collector runs in [catchingFailures] and is isolated from the others, the whole tick runs
 * under `interval / 2`, and nothing it does touches readiness or request handling. [clock] and the probes are the test seams
 * (`TelemetryOverridesKey`), the collector lists are constructor parameters.
 */
class TelemetrySampler internal constructor(
    private val store: TelemetryStore,
    private val config: TelemetryConfig,
    identity: () -> ProcessIdentity,
    private val clock: () -> Long,
    private val jvm: JvmProbe,
    private val cgroup: CgroupProbe,
    private val poolStats: () -> PoolFigures?,
    extraProcessCollectors: List<ProcessCollector> = emptyList(),
    private val clusterCollectors: List<ClusterCollector> = emptyList(),
    private val firstTickDelayMillis: Long = FIRST_TICK_DELAY_MILLIS,
) {
    private val log = LoggerFactory.getLogger(TelemetrySampler::class.java)
    private val failures = FailureLog(log)
    private val timeSource = TimeSource.Monotonic

    /**
     * Resolved once, on first use INSIDE the sampling coroutine: `InetAddress.getLocalHost()` (the host-name fallback when
     * `HOSTNAME` is unset) can block for seconds, and the sampler is built in the synchronous `ApplicationStarted` handler.
     */
    private val identity: ProcessIdentity by lazy(identity)

    @Volatile
    private var processId: Int? = null

    @Volatile
    private var containerLimit: Long? = null

    private val processCollectors: List<ProcessCollector> =
        listOf(
            ProcessCollector("jvm") { jvm.read() },
            ProcessCollector("cgroup") { cgroupReadings() },
            ProcessCollector("pool") { poolReadings() },
        ) + extraProcessCollectors

    private val retention = ClusterCollector("retention") { now ->
        if (rollupHourly(now, rollupSettleMillis(config.sampleIntervalMillis))) pruneTelemetry(now)
        emptyList()
    }

    /** The sampling loop's job, once [start]ed; `TelemetryLifecycleTest` asserts it is completed after the application stops. */
    @Volatile
    internal var loop: Job? = null
        private set

    /** The loop: the first tick [firstTickDelayMillis] after start, then one per aligned interval. Cancelling [scope] stops it. */
    fun start(scope: CoroutineScope) {
        loop = scope.launch {
            delay(firstTickDelayMillis)
            while (isActive) {
                tick()
                val interval = config.sampleIntervalMillis
                delay(interval - Math.floorMod(clock(), interval))
            }
        }
    }

    /**
     * One tick under its `interval / 2` budget, so a stuck statement can neither delay the next tick past the cadence nor hold a
     * pooled connection for long. A failure is one WARN per episode (`FailureLog`), never an escape.
     */
    internal suspend fun tick() {
        val finished = withTimeoutOrNull(config.sampleIntervalMillis / 2) { collectLight() }
        if (finished == null) failures.failed("tick", TimeoutException("a tick outran half the sample interval")) else failures.ok("tick")
    }

    /** The light phase: this process's samples, then the cluster section. */
    private suspend fun collectLight() {
        val now = clock()
        val bucket = now - Math.floorMod(now, config.sampleIntervalMillis)
        val readings = processCollectors.flatMap { collector -> guarded(collector.name) { collector.collect() }.orEmpty() }
        val id = ensureProcess(now) ?: return
        val rows = readings.mapNotNull { it.toRow(bucket, id) }
        if (rows.isNotEmpty()) guarded("store") { store.transaction { insertSampleRows(rows) } }
        guarded("cluster") { runClusterSection(id, now, bucket) }
    }

    private suspend fun ensureProcess(now: Long): Int? {
        val known = processId
        if (known != null && guarded("process") { store.touchProcess(known, now, containerLimit) } == true) return known
        // First tick, or the row vanished (an operator pruned it): (re-)register.
        val id = guarded("process") { store.registerProcess(identity, now, containerLimit) }
        processId = id
        return id
    }

    /**
     * The cluster section, in ONE short transaction under the lock: the retention (rollup + prune) and the cluster collectors, then
     * the marker that tells a second process this interval is served. Another process's committed marker for this bucket skips it.
     */
    private suspend fun runClusterSection(processId: Int, now: Long, bucket: Long) {
        store.clusterSection {
            if ((newestRawTs(TelemetryMetrics.CLUSTER_TICK) ?: Long.MIN_VALUE) >= bucket) return@clusterSection
            val started = timeSource.markNow()
            val readings = (listOf(retention) + clusterCollectors).flatMap { collector ->
                isolated(collector.name) { collector.collect(this, now) }.orEmpty()
            }
            val marker = Reading(TelemetryMetrics.CLUSTER_TICK, started.elapsedNow().inWholeMilliseconds.toDouble())
            insertSampleRows((readings + marker).mapNotNull { it.toRow(bucket, processId) })
        }
    }

    /** Runs a cluster collector in a savepoint: its failure is recorded, undone, and leaves the transaction usable for the next one. */
    private suspend fun <T> R2dbcTransaction.isolated(name: String, block: suspend () -> T): T? {
        val connection = connection()
        val savepoint = connection.setSavepoint(SAVEPOINT_NAME)
        return catchingFailures({
            block().also {
                connection.releaseSavepoint(savepoint)
                failures.ok(name)
            }
        }) {
            failures.failed(name, it)
            connection.rollback(savepoint)
            null
        }
    }

    private suspend fun <T> guarded(name: String, block: suspend () -> T): T? =
        catchingFailures({ block().also { failures.ok(name) } }) {
            failures.failed(name, it)
            null
        }

    private fun Reading.toRow(bucket: Long, processId: Int): SampleRow? =
        if (value.isFinite()) SampleRow(config.sampleIntervalSeconds, bucket, processId, metric.name, label, 1, value, value) else null

    private fun cgroupReadings(): List<Reading> {
        val c = cgroup.read()
        containerLimit = c.limitBytes
        return listOfNotNull(
            c.rssBytes?.let { Reading(TelemetryMetrics.RSS, it.toDouble()) },
            c.currentBytes?.let { Reading(TelemetryMetrics.CGROUP_CURRENT, it.toDouble()) },
            c.anonBytes?.let { Reading(TelemetryMetrics.CGROUP_ANON, it.toDouble()) },
            c.workingSetBytes?.let { Reading(TelemetryMetrics.CGROUP_WORKING_SET, it.toDouble()) },
            c.peakBytes?.let { Reading(TelemetryMetrics.CGROUP_PEAK, it.toDouble()) },
        )
    }

    private fun poolReadings(): List<Reading> {
        val p = poolStats() ?: return emptyList()
        return listOf(
            Reading(TelemetryMetrics.POOL_ACQUIRED, p.acquired.toDouble()),
            Reading(TelemetryMetrics.POOL_IDLE, p.idle.toDouble()),
            Reading(TelemetryMetrics.POOL_PENDING, p.pendingAcquire.toDouble()),
            Reading(TelemetryMetrics.POOL_ALLOCATED, p.allocated.toDouble()),
        )
    }
}
