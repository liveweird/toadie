package ch.nokillswit

import ch.nokillswit.entities.EntityPressure
import ch.nokillswit.entities.JqPoolStats
import ch.nokillswit.infra.concurrency.ExecutorStats
import ch.nokillswit.infra.concurrency.LedgerSnapshot
import ch.nokillswit.infra.concurrency.ReadLedgerKey
import ch.nokillswit.integration.IntegrationPressure
import ch.nokillswit.integration.IntegrationPressureKey
import ch.nokillswit.telemetry.MetricDef
import ch.nokillswit.telemetry.MetricGroup
import ch.nokillswit.telemetry.PressureCollector
import ch.nokillswit.telemetry.Reading
import ch.nokillswit.telemetry.TelemetryMetrics
import ch.nokillswit.telemetry.TelemetryOverrides
import ch.nokillswit.telemetry.TelemetryOverridesKey
import ch.nokillswit.telemetry.TelemetrySamplerKey
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `telemetry/PressureCollector.kt`: every seam figure lands as a row of its metric, the refusal counters become per-interval deltas,
 * an absent seam (the integration API when disabled) writes nothing - and, over a real application, the keys the collector reads are
 * the ones the composition root publishes.
 */
class PressureCollectorTest {
    private val ledgerSnapshots = ArrayDeque<LedgerSnapshot>()
    private var entities = EntityPressure(0, 3, 0L, JqPoolStats(0, 0, 0, 4, 64))
    private var integration = IntegrationPressure(0, 4, 0L, 0L, 32L shl 20)

    private fun List<Reading>.value(metric: MetricDef): Double? = firstOrNull { it.metric == metric }?.value

    @Test
    fun `every seam figure lands as a row of its metric`() {
        ledgerSnapshots += LedgerSnapshot(inFlightBytes = 1_000, intervalPeak = 5_000, refusals = 2, capacity = 64L shl 20)
        entities = EntityPressure(2, 3, 4L, JqPoolStats(quarantined = 1, active = 3, queued = 5, workers = 4, queueCapacity = 64))
        integration = IntegrationPressure(inUse = 2, permits = 4, refusals = 6L, retainedBytes = 777L, retainedCapacity = 32L shl 20)
        val collector = PressureCollector(
            ledger = { ledgerSnapshots.removeFirst() },
            entities = { entities },
            fetch = { ExecutorStats(active = 1, queued = 7, workers = 4, queueCapacity = 16) },
            integration = { integration },
        )

        val readings = collector.collect()
        val expected = mapOf(
            TelemetryMetrics.LEDGER_IN_FLIGHT to 1_000.0, TelemetryMetrics.LEDGER_PEAK to 5_000.0, TelemetryMetrics.LEDGER_REFUSALS to 2.0,
            TelemetryMetrics.QUERY_IN_USE to 2.0, TelemetryMetrics.QUERY_REFUSALS to 4.0,
            TelemetryMetrics.JQ_QUARANTINED to 1.0, TelemetryMetrics.JQ_ACTIVE to 3.0, TelemetryMetrics.JQ_QUEUED to 5.0,
            TelemetryMetrics.FETCH_ACTIVE to 1.0, TelemetryMetrics.FETCH_QUEUED to 7.0,
            TelemetryMetrics.INTEGRATION_IN_USE to 2.0, TelemetryMetrics.INTEGRATION_RETAINED to 777.0,
            TelemetryMetrics.INTEGRATION_REFUSALS to 6.0,
        )
        for ((metric, value) in expected) assertEquals(value, readings.value(metric), metric.name)
        assertEquals(expected.size, readings.size, "nothing else is written")
        assertTrue(readings.all { it.metric.group == MetricGroup.PRESSURE && it.label.isEmpty() })
    }

    @Test
    fun `refusal counters are deltas of the previous committed read and never negative`() {
        var refusals = 3L
        val collector = PressureCollector(
            ledger = { LedgerSnapshot(0, 0, refusals, 1) },
            entities = { entities },
            integration = { integration },
        )
        fun take() = collector.collect().also { collector.commit() }
        assertEquals(3.0, take().value(TelemetryMetrics.LEDGER_REFUSALS), "the first read counts from the process start")
        refusals = 8
        entities = EntityPressure(0, 3, 5L, JqPoolStats(0, 0, 0, 4, 64))
        integration = IntegrationPressure(0, 4, 9L, 0L, 1L)
        var next = take()
        assertEquals(5.0, next.value(TelemetryMetrics.LEDGER_REFUSALS))
        assertEquals(5.0, next.value(TelemetryMetrics.QUERY_REFUSALS), "the entity counter's own first delta")
        assertEquals(9.0, next.value(TelemetryMetrics.INTEGRATION_REFUSALS))
        next = take()
        assertEquals(0.0, next.value(TelemetryMetrics.LEDGER_REFUSALS), "an idle interval writes a zero, not the running total")
        assertEquals(0.0, next.value(TelemetryMetrics.QUERY_REFUSALS))
        refusals = 1 // a counter that went backwards (a rebuilt ledger): clamped
        assertEquals(0.0, take().value(TelemetryMetrics.LEDGER_REFUSALS))
    }

    @Test
    fun `an uncommitted read advances nothing, so the next one reports both intervals and the ledger interval stays open`() {
        var refusals = 2L
        var peak = 300L
        val ended = mutableListOf<Long>()
        val collector = PressureCollector(
            ledger = { LedgerSnapshot(0, peak, refusals, 1) },
            integration = { integration },
            endLedgerInterval = { ended += it.intervalPeak },
        )
        collector.collect() // the write of this interval failed: no commit
        assertEquals(emptyList(), ended, "the ledger interval is not ended by a read")
        refusals = 5
        peak = 100
        val retried = collector.collect()
        assertEquals(5.0, retried.value(TelemetryMetrics.LEDGER_REFUSALS), "both intervals' refusals, from the last committed baseline")
        collector.commit()
        assertEquals(listOf(100L), ended, "the interval ends with the snapshot that was stored")
        refusals = 6
        assertEquals(1.0, collector.collect().value(TelemetryMetrics.LEDGER_REFUSALS))
    }

    @Test
    fun `an absent seam writes no rows`() {
        assertEquals(emptyList(), PressureCollector().collect())
        val withoutIntegration = PressureCollector(
            ledger = { LedgerSnapshot(1, 2, 0, 3) },
            entities = { entities },
            fetch = { ExecutorStats(0, 0, 4, 16) },
        ).collect()
        assertTrue(withoutIntegration.isNotEmpty())
        assertTrue(withoutIntegration.none { it.metric.name.startsWith("integration.") }, "no integration rows without the key")
    }

    private fun tickedMetrics(vararg overrides: Pair<String, String>): Set<String> = runBlocking {
        val store = telemetryStore()
        wipeTelemetry(store)
        val identity = testIdentity()
        lateinit var app: Application
        var metrics = emptySet<String>()
        testApplication {
            configureApp("telemetry.enabled" to "true", *overrides)
            application {
                app = this
                attributes.put(TelemetryOverridesKey, TelemetryOverrides(identity = identity, firstTickDelayMillis = Long.MAX_VALUE / 4))
            }
            startApplication()
            assertNotNull(app.attributes.getOrNull(TelemetrySamplerKey)).tick()
            metrics = store.samples(assertNotNull(store.processId(identity))).map { it.metric }.toSet()
        }
        metrics
    }

    @Test
    fun `a sampled application writes the seams the composition root publishes, the integration rows only when the API is on`() {
        val without = tickedMetrics("integration.enabled" to "false")
        for (metric in listOf("ledger.in_flight", "ledger.peak", "ledger.refusals", "query.in_use", "query.refusals")) {
            assertTrue(metric in without, "$metric in $without")
        }
        for (metric in listOf("jq.quarantined", "jq.active", "jq.queued", "fetch.active", "fetch.queued")) {
            assertTrue(metric in without, "$metric in $without")
        }
        assertTrue(without.none { it.startsWith("integration.") }, "$without")

        val with = tickedMetrics("integration.enabled" to "true")
        assertEquals(
            setOf("integration.in_use", "integration.retained", "integration.refusals"),
            with.filter { it.startsWith("integration.") }.toSet(),
        )
    }

    @Test
    fun `the keys are published where the seams are built`() = testApplication {
        configureApp("integration.enabled" to "false")
        startApplication()
        assertNotNull(application.attributes.getOrNull(ReadLedgerKey), "the one read ledger")
        assertNull(application.attributes.getOrNull(IntegrationPressureKey), "absent while the integration API is off")
    }

    @Test
    fun `the integration key is a live function of the limits`() = testApplication {
        configureApp("integration.enabled" to "true")
        startApplication()
        val pressure = assertNotNull(application.attributes.getOrNull(IntegrationPressureKey))
        val reading = pressure()
        assertEquals(0, reading.inUse)
        assertEquals(4, reading.permits)
        assertEquals(32L shl 20, reading.retainedCapacity)
    }
}
