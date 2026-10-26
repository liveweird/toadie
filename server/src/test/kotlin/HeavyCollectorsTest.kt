package ch.nokillswit

import ch.nokillswit.telemetry.CgroupProbe
import ch.nokillswit.telemetry.HOURLY_RESOLUTION
import ch.nokillswit.telemetry.HeavyCollector
import ch.nokillswit.telemetry.JvmProbe
import ch.nokillswit.telemetry.Reading
import ch.nokillswit.telemetry.STORAGE_GROUPS
import ch.nokillswit.telemetry.TelemetryConfig
import ch.nokillswit.telemetry.TelemetryMetrics
import ch.nokillswit.telemetry.TelemetrySampler
import ch.nokillswit.telemetry.heavyCollectors
import ch.nokillswit.telemetry.hourFloor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Before
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_5_MINUTES = 300_000L

/**
 * The heavy phase (`telemetry/HeavyCollectors.kt`, `TelemetrySampler.kt`) through a real sampler on a pinned clock: the storage run is
 * CLAIMED by a marker row of 0 in the cluster section, attempted once per interval whatever its outcome, runs after that transaction
 * under its own budget, and completes the marker with its duration. A failing or stuck heavy collector takes nothing else down.
 */
class HeavyCollectorsTest {
    private val store = telemetryStore()
    private val identity = testIdentity()
    private val config = TelemetryConfig(enabled = true, sampleIntervalSeconds = 300, storageIntervalSeconds = 3600)
    private val samplerLog = TelemetrySampler::class.java.name

    @Before
    fun emptyTelemetry() = runBlocking { wipeTelemetry(store) }

    private fun sampler(heavy: List<HeavyCollector> = heavyCollectors(config), now: () -> Long) = TelemetrySampler(
        store, config, { identity }, now, JvmProbe(),
        CgroupProbe(Path.of("/nonexistent/cgroup"), Path.of("/nonexistent/status"), Path.of("/nonexistent/proc-cgroup")),
        { null }, emptyList(), emptyList(), heavy,
    )

    private suspend fun samples(metric: String, resolution: Int = config.sampleIntervalSeconds) =
        store.samples(assertNotNull(store.processId(identity)), resolution, metric)

    @Test
    fun `storage is claimed and measured hourly while the cheap collectors run every tick`() = runBlocking {
        var now = hourFloor(1_800_000_000_000L) + 5 * MILLIS_PER_HOUR + 123_456
        val sampler = sampler { now }
        val ticks = mutableListOf<Long>()
        repeat(7 * 12 + 1) { // seven hours of 5-minute ticks
            sampler.tick()
            ticks += now - Math.floorMod(now, MILLIS_PER_5_MINUTES)
            now += MILLIS_PER_5_MINUTES
        }

        assertEquals(ticks, samples("jvm.threads").map { it.ts }.sorted(), "the light phase every tick")
        val hourly = samples("db.size").map { it.ts }.sorted()
        assertEquals(8, hourly.size, "storage once per hour over seven hours of ticks")
        assertTrue(hourly.zipWithNext().all { (x, y) -> y - x == MILLIS_PER_HOUR }, "$hourly")
        val markers = samples("telemetry.storage_ms")
        assertEquals(hourly, markers.map { it.ts }.sorted(), "the marker is written with the readings")
        assertTrue(markers.all { it.avg >= 1.0 }, "every claim completed with the run's duration: $markers")
        assertEquals(hourly.size, samples("ws.rows").map { it.ts }.distinct().size)
    }

    @Test
    fun `the storage run writes its sizes and counts once, attributed to the claiming process`() = runBlocking {
        val sampler = sampler(now = { hourFloor(1_800_000_000_000L) + 5 * MILLIS_PER_HOUR })
        sampler.tick()
        assertEquals(STORAGE_GROUPS.keys, samples("db.group.size").map { it.label }.toSet())
        assertEquals(1, samples("db.size").size)
        assertTrue(samples("ws.rows").map { it.label }.containsAll(listOf("catalog_files", "catalog_file_events", "entities")))
        assertEquals(emptyList(), samples("db.size").filter { it.avg <= 0.0 })
    }

    @Test
    fun `a failing heavy collector claims its marker, is not retried this interval, and takes nothing else down`() = runBlocking {
        val calls = AtomicInteger()
        var failing = true
        val heavy = HeavyCollector("boom", TelemetryMetrics.STORAGE_TICK, intervalMillis = 2 * MILLIS_PER_HOUR) {
            calls.incrementAndGet()
            if (failing) error("kaboom") else listOf(Reading(TelemetryMetrics.DB_SIZE, 42.0))
        }
        var now = hourFloor(1_800_000_000_000L) + 5 * MILLIS_PER_HOUR + 1_000
        val sampler = sampler(listOf(heavy)) { now }
        val capture = LogCapture(samplerLog)
        try {
            // Three hours of ticks: long enough for an hourly rollup (retention) to commit while the heavy collector keeps failing.
            repeat(3 * 12 + 1) {
                sampler.tick()
                now += MILLIS_PER_5_MINUTES
            }
            assertEquals(2, calls.get(), "attempted once per 2-hour interval (hour 0 and hour 2), never on the ticks in between")
            val claims = samples("telemetry.storage_ms")
            assertEquals(2, claims.size)
            assertTrue(claims.all { it.avg == 0.0 }, "a claim that did not complete stays at 0: $claims")
            assertEquals(37, samples("jvm.threads").size, "the cheap collectors commit every tick")
            assertEquals(37, samples("telemetry.cluster_ms").size)
            assertTrue(samples("jvm.threads", HOURLY_RESOLUTION).isNotEmpty(), "retention (the hourly rollup) still commits")
            val warns = capture.events.filter { it.level == ch.qos.logback.classic.Level.WARN }
            assertEquals(1, warns.size, "one WARN, then DEBUG for the repeat")
            val expected = "Telemetry collector boom failed: java.lang.IllegalStateException: kaboom"
            assertTrue(warns.single().formattedMessage.startsWith(expected))

            // Recovered: the next interval's attempt succeeds, writes its readings, and completes the marker.
            failing = false
            now = hourFloor(now) + 2 * MILLIS_PER_HOUR
            sampler.tick()
            assertEquals(3, calls.get())
            assertEquals(42.0, samples("db.size").single().avg)
            val completed = samples("telemetry.storage_ms").maxBy { it.ts }
            assertTrue(completed.avg >= 1.0, "the marker now holds the run's duration: $completed")
            assertTrue(capture.events.any { it.formattedMessage == "Telemetry collector boom recovered" })
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `a heavy phase that outruns its budget is cancelled, warned about once and not retried before the next interval`() = runBlocking {
        val calls = AtomicInteger()
        val stuck = HeavyCollector("stuck", TelemetryMetrics.STORAGE_TICK, intervalMillis = MILLIS_PER_HOUR) {
            calls.incrementAndGet()
            awaitCancellation()
        }
        var now = hourFloor(1_800_000_000_000L) + 5 * MILLIS_PER_HOUR + 1_000
        val sampler = sampler(listOf(stuck)) { now }.also { it.heavyBudgetMillis = 300 }
        val capture = LogCapture(samplerLog)
        try {
            val started = System.nanoTime()
            sampler.tick()
            assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000, "the heavy phase is bounded by its own budget")
            now += MILLIS_PER_5_MINUTES
            sampler.tick()
            assertEquals(1, calls.get(), "the claim holds for the interval")
            assertEquals(listOf(0.0), samples("telemetry.storage_ms").map { it.avg })
            assertEquals(2, samples("jvm.threads").size, "the light phase committed both ticks")
            val warns = capture.events.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
            assertEquals(1, warns.size)
            assertTrue(warns.single().startsWith("Telemetry collector heavy failed: java.util.concurrent.TimeoutException"), warns.single())
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `the storage interval is configured per collector`() {
        val collectors = heavyCollectors(config.copy(storageIntervalSeconds = 900))
        assertEquals(listOf("storage"), collectors.map { it.name })
        assertEquals(900_000L, collectors.single().intervalMillis)
        assertEquals(TelemetryMetrics.STORAGE_TICK, collectors.single().marker)
    }
}
