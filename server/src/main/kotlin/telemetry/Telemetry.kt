package ch.nokillswit.telemetry

import ch.nokillswit.entities.EntityServiceKey
import ch.nokillswit.infra.concurrency.ReadLedgerKey
import ch.nokillswit.infra.db.ConnectionPoolKey
import ch.nokillswit.infra.db.DatabaseKey
import ch.nokillswit.infra.fetch.UrlFetcher
import ch.nokillswit.infra.fetch.UrlFetcherKey
import ch.nokillswit.integration.IntegrationPressureKey
import ch.nokillswit.plugins.MetricRegistryKey
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.util.AttributeKey
import io.r2dbc.pool.ConnectionPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val SHUTDOWN_JOIN_TIMEOUT_MS = 2_000L

/** Published once the sampler runs - `TelemetryLifecycleTest`'s "sampler started" seam, and the handle a test ticks by hand. */
val TelemetrySamplerKey = AttributeKey<TelemetrySampler>("TelemetrySampler")

/**
 * Test seam (`TelemetryLifecycleTest`): put on `Application.attributes` from a test's `application {}` block - which runs after the
 * yaml modules and before [ApplicationStarted], where the sampler reads it - to replace the clock, the probes, the process
 * identity or the first-tick delay of the sampler `configureTelemetry` starts. Every field defaults to the real thing.
 * `TelemetrySamplerTest` bypasses it and constructs the sampler directly.
 */
class TelemetryOverrides(
    val clock: () -> Long = System::currentTimeMillis,
    val jvm: JvmProbe? = null,
    val cgroup: CgroupProbe? = null,
    val identity: ProcessIdentity? = null,
    val firstTickDelayMillis: Long = FIRST_TICK_DELAY_MILLIS,
)

val TelemetryOverridesKey = AttributeKey<TelemetryOverrides>("TelemetryOverrides")

/**
 * The telemetry sampler's module (`.claude/docs/observability.md` "Telemetry"). `telemetry.*` is validated on every boot, enabled
 * or not; the sampler itself starts on [ApplicationStarted] (so a test's `application {}` overrides are visible and every module,
 * the pressure seams - `ReadLedgerKey`, `EntityServiceKey`, `UrlFetcherKey`, `IntegrationPressureKey` - has published its keys) and
 * is cancelled on [ApplicationStopping], before the connection pool is disposed (`ApplicationStopped`, `infra/db/Database.kt`).
 * Registered right after `configureBootstrap` (`application.yaml`): it reads `DatabaseKey` and `ConnectionPoolKey`.
 */
fun Application.configureTelemetry() {
    val config = readTelemetryConfig(environment.config)
    if (!config.enabled) return

    monitor.subscribe(ApplicationStarted) { app ->
        val overrides = app.attributes.getOrNull(TelemetryOverridesKey) ?: TelemetryOverrides()
        val pool = app.attributes.getOrNull(ConnectionPoolKey)
        val latency = app.attributes.getOrNull(MetricRegistryKey)?.let(::LatencyCollector)
        val pressure = pressureCollector(app)
        val sampler = TelemetrySampler(
            store = TelemetryStore(app.attributes[DatabaseKey]),
            config = config,
            // A provider, resolved inside the sampler's coroutine: detect() may block on a host-name lookup.
            identity = { overrides.identity ?: ProcessIdentity.detect() },
            clock = overrides.clock,
            jvm = overrides.jvm ?: JvmProbe(),
            cgroup = overrides.cgroup ?: CgroupProbe(),
            poolStats = { pool?.figures() },
            extraProcessCollectors = listOfNotNull(
                latency?.let { ProcessCollector("latency", commit = it::commit) { it.collect() } },
                ProcessCollector("pressure", commit = pressure::commit) { pressure.collect() },
            ),
            heavyCollectors = heavyCollectors(config),
            firstTickDelayMillis = overrides.firstTickDelayMillis,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sampler.start(scope)
        app.attributes.put(TelemetrySamplerKey, sampler)
        app.monitor.subscribe(ApplicationStopping) {
            val rootJob = scope.coroutineContext[Job]
            scope.cancel()
            // A bounded join, so an in-flight tick has released its pooled connection before the pool is disposed.
            if (rootJob != null) runBlocking { withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT_MS) { rootJob.join() } }
        }
    }
}

/**
 * The pressure seams, resolved at [ApplicationStarted] (every module has published its keys by then): an absent one writes no rows.
 * `UrlFetcherKey` is a test seam only - production routes build their own `UrlFetcher()` over the one shared `url-fetch` pool, so a
 * fresh one reads the same pool.
 */
private fun pressureCollector(app: Application): PressureCollector {
    val ledger = app.attributes.getOrNull(ReadLedgerKey)
    val entities = app.attributes.getOrNull(EntityServiceKey)
    val fetcher = app.attributes.getOrNull(UrlFetcherKey) ?: UrlFetcher()
    return PressureCollector(
        ledger = ledger?.let { { it.snapshot() } },
        endLedgerInterval = { ledger?.endInterval() },
        entities = entities?.let { { it.pressure() } },
        fetch = { fetcher.poolStats() },
        integration = app.attributes.getOrNull(IntegrationPressureKey),
    )
}

/** The pool's live figures (r2dbc-pool's `PoolMetrics`, pinned present for 1.0.2 by `ConnectionPoolTest`); null when it reports none. */
internal fun ConnectionPool.figures(): PoolFigures? =
    metrics.orElse(null)?.let { PoolFigures(it.acquiredSize(), it.idleSize(), it.pendingAcquireSize(), it.allocatedSize()) }
