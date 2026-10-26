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

/**
 * A per-process collector (the JVM, the cgroup, the connection pool, requests, pressure). Its readings are this process's own.
 * [commit] is called only after the tick's readings were durably inserted: a collector that keeps a delta baseline (requests,
 * pressure) advances it there, so a failed write loses no interval. A collector that threw is not committed.
 */
class ProcessCollector(val name: String, val commit: () -> Unit = {}, val collect: suspend () -> List<Reading>)

/**
 * A cheap cluster collector: runs INSIDE the cluster transaction, on the process that holds the lock, so it needs no dedupe of its
 * own; its readings are written attributed to that process. A failure rolls back only this collector's statements (a savepoint) and
 * the others still run.
 */
class ClusterCollector(val name: String, val collect: suspend R2dbcTransaction.(now: Long) -> List<Reading>)

/** A heavy run this process claimed for [bucket] (its marker row is already committed with value 0). */
private class HeavyClaim(val collector: HeavyCollector, val bucket: Long, val processId: Int)

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
 * own figures (JVM, cgroup, connection pool, requests, pressure) to `telemetry_samples`, and - on whichever process takes the cluster
 * lock for the interval - the cluster section (the hourly rollup and prune, and the claim of a due heavy collector, whose run
 * follows once that transaction has committed).
 *
 * It is a best-effort observer: every collector runs in [catchingFailures] and is isolated from the others, the light phase runs
 * under `interval / 2` and the heavy one under `2/5 interval`, and nothing it does touches readiness or request handling.
 * [clock] and the probes are the test seams
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
    private val heavyCollectors: List<HeavyCollector> = emptyList(),
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

    /** The newest bucket whose readings were stored (or that had none to store); a tick at or before it is skipped, see [collectLight]. */
    @Volatile
    private var lastStoredBucket: Long = Long.MIN_VALUE

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

    /** The heavy phase's budget: [TelemetryConfig.heavyBudgetMillis], settable so a test need not wait out a real one. */
    @Volatile
    internal var heavyBudgetMillis: Long = config.heavyBudgetMillis

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
     * One tick: the light phase (this process's samples and the cluster section) under its `interval / 2` budget, then the heavy
     * phase of whatever the cluster section claimed under [heavyBudgetMillis] of its own - so a slow count sweep can neither roll
     * back the cheap collectors nor delay the next tick past the cadence. A failure is one WARN per episode (`FailureLog`), never an
     * escape.
     */
    internal suspend fun tick() {
        var claims: List<HeavyClaim> = emptyList()
        val finished = withTimeoutOrNull(config.sampleIntervalMillis / 2) { claims = collectLight() }
        if (finished == null) failures.failed("tick", TimeoutException("a tick outran half the sample interval")) else failures.ok("tick")
        if (claims.isEmpty()) return
        val done = withTimeoutOrNull(heavyBudgetMillis) { runHeavy(claims) }
        if (done == null) failures.failed("heavy", TimeoutException("the heavy collectors outran their budget")) else failures.ok("heavy")
    }

    /** The light phase; the heavy collectors it claimed (committed with its readings) are returned for [runHeavy]. */
    private suspend fun collectLight(): List<HeavyClaim> {
        val now = clock()
        val bucket = now - Math.floorMod(now, config.sampleIntervalMillis)
        // A bucket at or before the newest one already stored (the wall clock stepped back, or a second tick in the same bucket)
        // would insert nothing - ON CONFLICT keeps the first row - yet commit every baseline and lose that interval's deltas. Such a
        // tick only stamps the process and collects nothing: the deltas roll into the next bucket.
        if (bucket <= lastStoredBucket) {
            ensureProcess(now)
            return emptyList()
        }
        val collected = processCollectors.mapNotNull { c -> guarded(c.name) { c.collect() }?.let { c to it } }
        val id = ensureProcess(now) ?: return emptyList()
        val rows = collected.flatMap { it.second }.mapNotNull { it.toRow(bucket, id) }
        val stored = rows.isEmpty() || guarded("store") { store.transaction { insertSampleRows(rows) } } != null
        // Baselines advance only for what is stored; each collector commits on its own, so one that fails cannot consume another's.
        if (stored) {
            lastStoredBucket = bucket
            collected.forEach { (c, _) -> guarded("${c.name}-commit") { c.commit() } }
        }
        return guarded("cluster") { runClusterSection(id, now, bucket) }.orEmpty()
    }

    /**
     * The heavy phase: each claimed collector reads through a [HeavyReader] (every statement a short transaction of its own), its
     * readings are inserted in one more, and its marker is set to the run's duration (at least 1 ms). A failure is one WARN per
     * collector (then "recovered") and leaves the marker at 0: the run is not retried before the next interval.
     */
    private suspend fun runHeavy(claims: List<HeavyClaim>) {
        val reader = HeavyReader(store)
        for (claim in claims) {
            guarded(claim.collector.name) {
                val started = timeSource.markNow()
                val readings = claim.collector.collect(reader)
                val millis = maxOf(1L, started.elapsedNow().inWholeMilliseconds).toDouble()
                val rows = readings.mapNotNull { it.toRow(claim.bucket, claim.processId) }
                store.transaction {
                    insertSampleRows(rows)
                    completeSample(config.sampleIntervalSeconds, claim.collector.marker, claim.bucket, claim.processId, millis)
                }
            }
        }
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
     * The cluster section, in ONE short transaction under the lock: the retention (rollup + prune) and the cluster collectors, and the
     * decision of which heavy collectors are due (their marker is at least their interval old) - each CLAIMED by a marker row of 0 that
     * commits with the readings, so a heavy run is attempted at most once per interval. Another process's committed marker for this
     * bucket skips the whole section. Returns the claims; a section that rolls back claims nothing.
     */
    private suspend fun runClusterSection(processId: Int, now: Long, bucket: Long): List<HeavyClaim> =
        store.clusterSection {
            if ((newestRawTs(TelemetryMetrics.CLUSTER_TICK) ?: Long.MIN_VALUE) >= bucket) return@clusterSection emptyList()
            val started = timeSource.markNow()
            val readings = (listOf(retention) + clusterCollectors).flatMap { collector ->
                isolated(collector.name) { collector.collect(this, now) }.orEmpty()
            }
            val due = heavyCollectors.filter { collector ->
                val newest = newestRawTs(collector.marker)
                newest == null || bucket - newest >= collector.intervalMillis
            }
            val marker = Reading(TelemetryMetrics.CLUSTER_TICK, started.elapsedNow().inWholeMilliseconds.toDouble())
            val claimed = due.map { Reading(it.marker, 0.0) }
            insertSampleRows((readings + marker + claimed).mapNotNull { it.toRow(bucket, processId) })
            due.map { HeavyClaim(it, bucket, processId) }
        }.orEmpty()

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
