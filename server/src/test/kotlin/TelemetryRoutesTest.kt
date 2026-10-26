package ch.nokillswit

import ch.nokillswit.entityquery.MAX_CONCURRENT_ENTITY_QUERIES
import ch.nokillswit.infra.concurrency.READ_BUDGET_BYTES
import ch.nokillswit.infra.fetch.UrlFetcher
import ch.nokillswit.telemetry.HistoryGroup
import ch.nokillswit.telemetry.HistoryRange
import ch.nokillswit.telemetry.MAX_PROCESSES
import ch.nokillswit.telemetry.TelemetryCurrent
import ch.nokillswit.telemetry.TelemetryCurrentReport
import ch.nokillswit.telemetry.TelemetryHistory
import ch.nokillswit.telemetry.TelemetryLimits
import ch.nokillswit.telemetry.TelemetryMetrics
import ch.nokillswit.telemetry.TelemetryOverrides
import ch.nokillswit.telemetry.TelemetryOverridesKey
import ch.nokillswit.telemetry.TelemetryReads
import ch.nokillswit.telemetry.TelemetrySeries
import ch.nokillswit.telemetry.hourFloor
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The telemetry read API (`telemetry/TelemetryRoutes.kt`, `GET /api/v1/telemetry/current` and `/history`): ADMIN only, 403 before
 * 400, and the shapes the panel reads - over rows seeded straight into the telemetry tables, never over a running sampler (the
 * suite boots none, `telemetry.enabled` is false).
 *
 * The clock is pinned (`TelemetryOverridesKey`) 1000 days in the past, hour-aligned plus 20 minutes, so the windows are fixed.
 * Isolation is NOT the pinned time: the telemetry tables are this class's own, emptied before each test (`wipeTelemetry`), and test
 * classes run serially.
 */
class TelemetryRoutesTest {
    private val store = telemetryStore()

    @Before
    fun emptyTelemetry() = runBlocking { wipeTelemetry(store) }

    private val now = hourFloor(System.currentTimeMillis() - 1000 * TELE_DAY) + 20 * TELE_MINUTE
    private val hourStart = hourFloor(now)

    /** The newest completed raw bucket at the pinned `now`. */
    private val latest = now - 5 * TELE_MINUTE

    private suspend fun ApplicationTestBuilder.boot(vararg overrides: Pair<String, String>, clock: Long = now) {
        configureApp(*overrides)
        val clockNow = clock
        application { attributes.put(TelemetryOverridesKey, TelemetryOverrides(clock = { clockNow })) }
        startApplication()
    }

    private suspend fun HttpClient.current(): TelemetryCurrent {
        val response = get("/api/v1/telemetry/current")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.history(group: String, range: String): TelemetryHistory {
        val response = get("/api/v1/telemetry/history?group=$group&range=$range")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private fun TelemetryHistory.series(key: String): TelemetrySeries =
        assertNotNull(series.firstOrNull { it.key == key }, "series $key in ${series.map { it.key }}")

    private fun TelemetrySeries.ts() = points.map { it.t }
    private fun TelemetrySeries.avgs() = points.map { it.avg }
    private fun TelemetrySeries.maxes() = points.map { it.max }

    // ---- guard and parsing ---------------------------------------------------------------------------------------------

    @Test
    fun `an ADMIN reads an empty store, every group and range`() = testApplication {
        boot()
        val admin = seededClient("telemetry-empty", UserRole.ADMIN)

        val current = admin.current()
        assertEquals(now, current.generatedAt)
        assertEquals(TELE_RAW_SECONDS, current.sampleIntervalSeconds)
        assertTrue(current.processes.isEmpty())
        assertNull(current.workspace)
        assertNull(current.requests.measuredAt)
        assertEquals(TELE_RAW_SECONDS, current.requests.windowSeconds)
        assertEquals(TELE_RAW_SECONDS, current.requests.percentileWindowSeconds)
        assertEquals(0L, current.requests.total.count)
        assertNull(current.requests.total.p50Ms)
        assertTrue(current.requests.routes.isEmpty())

        val buckets = mapOf("24h" to TELE_RAW_SECONDS, "7d" to 3600, "90d" to 21600)
        for (group in HistoryGroup.entries) {
            for (range in HistoryRange.entries) {
                val history = admin.history(group.param, range.param)
                assertEquals(group.param, history.group)
                assertEquals(range.param, history.range)
                assertEquals(buckets.getValue(range.param), history.bucketSeconds, "${group.param} ${range.param}")
                assertEquals(now - range.millis, history.from)
                assertEquals(now, history.to)
                assertFalse(history.truncated, "${group.param} ${range.param}")
                assertTrue(history.series.isEmpty(), "${group.param} ${range.param}: no samples, no series")
                assertEquals(now, history.historyFrom, "no sampler ever ran: nothing to hold")
            }
        }
    }

    @Test
    fun `the history groups are exactly memory workspace pressure requests and jvm`() {
        assertEquals(listOf("memory", "workspace", "pressure", "requests", "jvm"), HistoryGroup.entries.map { it.param })
        assertEquals(listOf("24h", "7d", "90d"), HistoryRange.entries.map { it.param })
    }

    @Test
    fun `current reports the live bounds as limits, the integration ones null while that API is off`() = testApplication {
        boot()
        val limits = seededClient("telemetry-limits", UserRole.ADMIN).current().limits

        val fetch = UrlFetcher().poolStats()
        assertEquals(READ_BUDGET_BYTES, limits.readLedgerBytes)
        assertEquals(MAX_CONCURRENT_ENTITY_QUERIES, limits.entityQueryPermits)
        assertTrue(limits.jqWorkers > 0 && limits.jqQueueCapacity > 0, "the jq pool's bounds: $limits")
        assertEquals(fetch.workers, limits.fetchWorkers)
        assertEquals(fetch.queueCapacity, limits.fetchQueueCapacity)
        assertFalse(limits.integrationEnabled)
        assertNull(limits.integrationPermits)
        assertNull(limits.integrationRetainedBytes)
        assertEquals(20, limits.poolMaxSize, "postgres.pool.maxSize")
    }

    @Test
    fun `current reports the integration bounds while that API is on`() = testApplication {
        boot("integration.enabled" to "true")
        val limits = seededClient("telemetry-limits-int", UserRole.ADMIN).current().limits

        assertTrue(limits.integrationEnabled)
        assertTrue(assertNotNull(limits.integrationPermits) > 0)
        assertTrue(assertNotNull(limits.integrationRetainedBytes) > 0)
    }

    @Test
    fun `a USER is refused 403 on both routes, before the query is read, and the denial is audited`() = testApplication {
        boot()
        val user = seededClient("telemetry-user")
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/telemetry/current").status)
            assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/telemetry/history?group=memory&range=24h").status)
            assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/telemetry/history?group=nope&range=never").status, "403 outranks 400")
            assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/telemetry/history").status, "403 outranks 400")
            assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/telemetry/history?group=memory&group=jvm").status, "403 outranks 400")
            assertNotNull(capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("path", "/api/v1/telemetry/current") })
            assertNotNull(
                capture.awaitEvent { event ->
                    event.message == "authz.denied" && event.keyValuePairs.any {
                        it.key == "path" && (it.value as? String)?.startsWith("/api/v1/telemetry/history") == true
                    }
                },
            )
        }
    }

    @Test
    fun `an unknown, missing, blank or repeated group or range is a 400 for an ADMIN`() = testApplication {
        boot()
        val admin = seededClient("telemetry-400", UserRole.ADMIN)
        for (
            query in listOf(
                "", "?range=24h", "?group=memory", "?group=nope&range=24h", "?group=memory&range=1h", "?group=MEMORY&range=24h",
                "?group=memory&range=24h&group=jvm", "?group=memory&range=24h&range=7d", "?group=&range=24h", "?group=memory&range=",
                "?group=sync&range=24h", "?group=storage&range=24h",
            )
        ) {
            assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/telemetry/history$query").status, query)
        }
    }

    // ---- current -------------------------------------------------------------------------------------------------------

    @Test
    fun `current lists the live process with its latest sample and the percentage of the container limit`() = testApplication {
        boot()
        val limit = 512L shl 20
        val live = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 3 * TELE_HOUR, limitBytes = limit)
        val m = TelemetryMetrics
        store.put(
            // An older tick: superseded by the newest bucket, and a metric only it wrote must not be carried over.
            rawRow(live, latest - 5 * TELE_MINUTE, m.HEAP_USED, 1.0), rawRow(live, latest - 5 * TELE_MINUTE, m.CGROUP_PEAK, 777.0),
            rawRow(live, latest, m.HEAP_USED, 100_000_000.0), rawRow(live, latest, m.HEAP_COMMITTED, 150_000_000.0),
            rawRow(live, latest, m.OLD_GEN_PEAK, 60_000_000.0), rawRow(live, latest, m.NON_HEAP_USED, 70_000_000.0),
            rawRow(live, latest, m.DIRECT_BUFFERS, 1_000_000.0), rawRow(live, latest, m.RSS, 300_000_000.0),
            rawRow(live, latest, m.CGROUP_CURRENT, 280_000_000.0), rawRow(live, latest, m.CGROUP_ANON, 250_000_000.0),
            rawRow(live, latest, m.CGROUP_WORKING_SET, (256L shl 20).toDouble()),
            rawRow(live, latest, m.CPU_RATIO, 0.25), rawRow(live, latest, m.THREADS, 42.0),
            rawRow(live, latest, m.GC_COUNT, 3.0, "Copy"), rawRow(live, latest, m.GC_COUNT, 1.0, "MarkSweepCompact"),
            rawRow(live, latest, m.GC_TIME, 20.0, "Copy"), rawRow(live, latest, m.GC_TIME, 90.0, "MarkSweepCompact"),
            rawRow(live, latest, m.POOL_ACQUIRED, 2.0), rawRow(live, latest, m.POOL_IDLE, 3.0),
            rawRow(live, latest, m.POOL_PENDING, 0.0), rawRow(live, latest, m.POOL_ALLOCATED, 5.0),
        )
        val process = seededClient("telemetry-current", UserRole.ADMIN).current().processes.single()

        assertEquals(live, process.id)
        assertEquals(now - 3 * TELE_HOUR, process.startedAt)
        assertEquals(now - 10_000, process.lastSeenAt)
        assertEquals(latest, process.sampledAt)
        assertFalse(process.stale)
        assertEquals("9.9.9", process.version)
        assertEquals("abc1234", process.commit)
        val memory = process.memory
        assertEquals(100_000_000L, memory.heapUsedBytes)
        assertEquals(150_000_000L, memory.heapCommittedBytes)
        assertEquals(256L shl 20, memory.heapMaxBytes)
        assertEquals(60_000_000L, memory.oldGenPeakBytes)
        assertEquals(70_000_000L, memory.nonHeapBytes)
        assertEquals(1_000_000L, memory.directBytes)
        assertEquals(300_000_000L, memory.rssBytes)
        assertEquals(280_000_000L, memory.cgroupCurrentBytes)
        assertEquals(250_000_000L, memory.cgroupAnonBytes)
        assertEquals(256L shl 20, memory.workingSetBytes)
        assertNull(memory.cgroupPeakBytes, "an older tick's figure is not carried into the newest")
        assertEquals(limit, memory.containerLimitBytes)
        assertEquals(50.0, memory.percentOfLimit)
        val jvm = process.jvm
        assertEquals(0.25, jvm.cpuRatio)
        assertEquals(42, jvm.threads)
        assertEquals(4L, jvm.gcCount, "collectors add up")
        assertEquals(110L, jvm.gcTimeMs)
        assertEquals(listOf(2, 3, 0, 5), listOf(jvm.poolAcquired, jvm.poolIdle, jvm.poolPending, jvm.poolAllocated))
    }

    @Test
    fun `a process without a limit has no percentage, and a figure its newest tick did not write is null`() = testApplication {
        boot()
        val live = store.seedProcess(lastSeen = now - 10_000)
        store.put(
            rawRow(live, latest, TelemetryMetrics.HEAP_USED, 50_000_000.0),
            rawRow(live, latest, TelemetryMetrics.CGROUP_WORKING_SET, 2.0e8),
        )
        val process = seededClient("telemetry-nolimit", UserRole.ADMIN).current().processes.single()

        assertNull(process.memory.containerLimitBytes)
        assertNull(process.memory.percentOfLimit, "no limit, no percentage")
        assertEquals(200_000_000L, process.memory.workingSetBytes)
        assertNull(process.memory.rssBytes)
        assertNull(process.jvm.cpuRatio)
        assertNull(process.jvm.gcCount)
    }

    @Test
    fun `the stale matrix - a wedged process within the hour stays, older ones are dropped beside a live one`() = testApplication {
        boot()
        val live = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 3 * TELE_HOUR)
        val replaced = store.seedProcess(lastSeen = now - 2 * TELE_HOUR) // stale long ago, a live one exists: not listed
        val wedged = store.seedProcess(lastSeen = now - 30 * TELE_MINUTE) // stale but seen within the hour: listed, flagged
        val silent = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 3 * TELE_HOUR) // ticks but samples are old
        val ancient = store.seedProcess(lastSeen = now - 25 * TELE_HOUR) // unseen for more than a day: not listed
        val m = TelemetryMetrics
        store.put(rawRow(live, latest, m.HEAP_USED, 100.0), rawRow(silent, now - TELE_HOUR, m.HEAP_USED, 1.0))
        val admin = seededClient("telemetry-stale", UserRole.ADMIN)

        val processes = admin.current().processes.associateBy { it.id }
        assertEquals(setOf(live, wedged, silent), processes.keys, "$replaced and $ancient are not listed")
        assertFalse(processes.getValue(live).stale)
        assertTrue(processes.getValue(wedged).stale, "a wedged process stays visible, flagged")
        assertTrue(processes.getValue(silent).stale, "fresh ticks but a sample an hour old is stale")
    }

    @Test
    fun `the staleness limit is three intervals on the last tick and on the age of a never-sampled process`() = testApplication {
        boot()
        val limit = 3 * TELE_RAW_SECONDS * 1000L
        val m = TelemetryMetrics
        val edge = store.seedProcess(lastSeen = now - limit, startedAt = now - TELE_HOUR) // exactly three intervals: still live
        val over = store.seedProcess(lastSeen = now - limit - 1, startedAt = now - TELE_HOUR) // one millisecond more: stale
        val youngSilent = store.seedProcess(lastSeen = now - 10_000, startedAt = now - limit) // no sample yet, booted three intervals ago
        val oldSilent = store.seedProcess(lastSeen = now - 10_000, startedAt = now - limit - 1) // no sample, one millisecond older
        store.put(rawRow(edge, now - limit, m.HEAP_USED, 1.0), rawRow(over, now - limit - 1, m.HEAP_USED, 1.0))
        val processes = seededClient("telemetry-stale-edge", UserRole.ADMIN).current().processes.associateBy { it.id }

        assertFalse(processes.getValue(edge).stale)
        assertTrue(processes.getValue(over).stale)
        assertFalse(processes.getValue(youngSilent).stale, "a booting process has not missed its samples yet")
        assertTrue(processes.getValue(oldSilent).stale, "ticking without ever sampling is a wedged sampler")
    }

    @Test
    fun `with no live process only the newest stale one is listed, within the hour or older`() = testApplication {
        boot()
        val newest = store.seedProcess(lastSeen = now - 2 * TELE_HOUR)
        store.seedProcess(lastSeen = now - 3 * TELE_HOUR)
        store.seedProcess(lastSeen = now - 25 * TELE_HOUR) // unseen for more than a day
        val admin = seededClient("telemetry-none-live", UserRole.ADMIN)

        val processes = admin.current().processes
        assertEquals(listOf(newest), processes.map { it.id }, "the older stale ones and the day-old one are not listed")
        assertTrue(processes.single().stale)
        assertNull(processes.single().sampledAt)
        assertNull(processes.single().memory.heapUsedBytes)

        val recent = store.seedProcess(lastSeen = now - 40 * TELE_MINUTE)
        assertEquals(listOf(recent), admin.current().processes.map { it.id }, "a stale process seen within the hour is the newest one")
    }

    @Test
    fun `requests use the newest bucket every live process has written, so a process lagging one tick is not undercounted`() =
        testApplication {
            boot()
            val a = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 2 * TELE_HOUR)
            val b = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 2 * TELE_HOUR) // one bucket behind
            val m = TelemetryMetrics
            val lagging = latest - 5 * TELE_MINUTE
            store.put(
                rawRow(a, lagging, m.HEAP_USED, 1.0), rawRow(a, latest, m.HEAP_USED, 1.0), rawRow(b, lagging, m.HEAP_USED, 1.0),
                rawRow(a, lagging, m.HTTP_COUNT, 100.0, "*"), rawRow(a, lagging, m.HTTP_P50, 10.0, "*"),
                rawRow(a, lagging, m.HTTP_P95, 40.0, "*"),
                rawRow(b, lagging, m.HTTP_COUNT, 300.0, "*"), rawRow(b, lagging, m.HTTP_P50, 20.0, "*"),
                rawRow(b, lagging, m.HTTP_P95, 90.0, "*"),
                // The newest bucket of a alone: not the interval both processes have written.
                rawRow(a, latest, m.HTTP_COUNT, 5000.0, "*"), rawRow(a, latest, m.HTTP_P95, 9999.0, "*"),
            )
            val current = seededClient("telemetry-lag", UserRole.ADMIN).current()

            assertEquals(setOf(false), current.processes.map { it.stale }.toSet(), "both are live")
            val requests = current.requests
            assertEquals(lagging, requests.measuredAt)
            assertEquals(400L, requests.total.count, "the lagging process is counted, the newer bucket of a alone is not")
            assertEquals(17.5, requests.total.p50Ms)
            assertEquals(90.0, requests.total.p95Ms)
        }

    @Test
    fun `a live process lagging two buckets does not drag the requests of the healthy ones away`() = testApplication {
        boot()
        val i = TELE_RAW_SECONDS * 1000L
        val healthy = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 2 * TELE_HOUR)
        // Wedged at latest - 2i: still live (its sample is exactly three intervals old) and still readable (last_seen is recent enough).
        val wedged = store.seedProcess(lastSeen = latest - 2 * i + 60_000, startedAt = now - 2 * TELE_HOUR)
        val m = TelemetryMetrics
        store.put(
            rawRow(healthy, latest, m.HEAP_USED, 1.0), rawRow(healthy, latest, m.HTTP_COUNT, 100.0, "*"),
            rawRow(healthy, latest, m.HTTP_P95, 40.0, "*"),
            rawRow(wedged, latest - 2 * i, m.HEAP_USED, 1.0), rawRow(wedged, latest - 2 * i, m.HTTP_COUNT, 900.0, "*"),
            rawRow(wedged, latest - 2 * i, m.HTTP_P95, 700.0, "*"),
        )
        val current = seededClient("telemetry-lag-two", UserRole.ADMIN).current()

        assertEquals(setOf(false), current.processes.map { it.stale }.toSet(), "both are still live by the three interval rule")
        assertEquals(latest, current.requests.measuredAt)
        assertEquals(100L, current.requests.total.count, "the healthy process's traffic is shown, the wedged one is left out")
        assertEquals(40.0, current.requests.total.p95Ms)
    }

    @Test
    fun `the sample read pulls only the rows inside each process window, however much history a restart left`() = runBlocking {
        val i = TELE_RAW_SECONDS * 1000L
        val live = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 2 * TELE_DAY)
        val previous = store.seedProcess(lastSeen = now - 23 * TELE_HOUR, startedAt = now - 3 * TELE_DAY) // the run a restart replaced
        val m = TelemetryMetrics
        val day = (0 until 288).map { latest - it * i }
        store.put(
            day.flatMap { listOf(rawRow(live, it, m.HEAP_USED, 1.0), rawRow(live, it, m.THREADS, 2.0)) } +
                day.map { rawRow(previous, it - 22 * TELE_HOUR, m.HEAP_USED, 3.0) },
        )
        val reads = TelemetryReads(store, TELE_RAW_SECONDS)
        val report = TelemetryCurrentReport(
            reads,
            { TelemetryLimits(1, 1, 1, 1, 1, 1, false, null, null, 1) },
            TELE_RAW_SECONDS,
        )

        val current = report.build(now)

        // The live process's window is [last_seen - 2i, ...): the two buckets at latest and latest - i, two metrics each. The
        // previous run (seen 23 hours ago, stale) is not even sampled while a live process exists.
        assertEquals(4, reads.sampleRowsRead, "rows pulled over the wire")
        val process = current.processes.single()
        assertEquals(live, process.id)
        assertEquals(latest, process.sampledAt)
        val sample = reads.latestPerProcess(listOf(reads.processesSeenSince(0).first { it.id == live }), listOf(m.HEAP_USED))
        assertEquals(setOf(latest, latest - i), sample.getValue(live).buckets.keys, "only the window's buckets")
        assertEquals(2, reads.sampleRowsRead)
    }

    @Test
    fun `a sample older than three intervals makes a process stale even while its last tick is fresh enough to read it`() =
        testApplication {
            boot()
            val i = TELE_RAW_SECONDS * 1000L
            val m = TelemetryMetrics
            // last_seen two intervals ago: the read window reaches back to four, so a sample 3.5 intervals old IS read.
            val old = store.seedProcess(lastSeen = now - 2 * i, startedAt = now - TELE_HOUR)
            val fresh = store.seedProcess(lastSeen = now - 2 * i, startedAt = now - TELE_HOUR)
            store.put(rawRow(old, now - i * 7 / 2, m.HEAP_USED, 1.0), rawRow(fresh, now - i * 5 / 2, m.HEAP_USED, 1.0))
            val processes = seededClient("telemetry-sample-age", UserRole.ADMIN).current().processes.associateBy { it.id }

            assertEquals(now - i * 7 / 2, processes.getValue(old).sampledAt, "the sample was read")
            assertTrue(processes.getValue(old).stale, "3.5 intervals old")
            assertEquals(now - i * 5 / 2, processes.getValue(fresh).sampledAt)
            assertFalse(processes.getValue(fresh).stale, "2.5 intervals old")
        }

    @Test
    fun `a timestamp in the future beyond the limit is stale, a small skew is not`() = testApplication {
        boot()
        val i = TELE_RAW_SECONDS * 1000L
        val m = TelemetryMetrics
        val futureSample = store.seedProcess(lastSeen = now - 10_000, startedAt = now - TELE_HOUR)
        val skewedSample = store.seedProcess(lastSeen = now - 10_000, startedAt = now - TELE_HOUR)
        val futureTick = store.seedProcess(lastSeen = now + 4 * i, startedAt = now - TELE_HOUR)
        store.put(
            rawRow(futureSample, now + 4 * i, m.HEAP_USED, 1.0), rawRow(skewedSample, now + i, m.HEAP_USED, 1.0),
            rawRow(futureTick, now + 4 * i, m.HEAP_USED, 1.0),
        )
        val processes = seededClient("telemetry-future", UserRole.ADMIN).current().processes.associateBy { it.id }

        assertTrue(processes.getValue(futureSample).stale, "a sample four intervals ahead is not fresh")
        assertFalse(processes.getValue(skewedSample).stale, "one interval of skew is within the limit")
        assertTrue(processes.getValue(futureTick).stale, "nor is a tick four intervals ahead")
    }

    @Test
    fun `at most as many processes are listed as the spec declares, the most recently seen kept`() = testApplication {
        boot()
        assertEquals(
            MAX_PROCESSES,
            OpenApiSpec.parsed.components.schemas.getValue("TelemetryCurrent").properties.getValue("processes").maxItems,
            "the cap and the spec's maxItems are one number",
        )
        val ids = (0 until MAX_PROCESSES + 5).map { store.seedProcess(lastSeen = now - 10_000 - it * 1000L) }
        val listed = seededClient("telemetry-cap-processes", UserRole.ADMIN).current().processes.map { it.id }

        assertEquals(MAX_PROCESSES, listed.size)
        assertEquals(ids.take(MAX_PROCESSES).toSet(), listed.toSet(), "the five least recently seen are the ones dropped")
    }

    @Test
    fun `current carries the pressure figures of the newest tick, null where it wrote nothing`() = testApplication {
        boot()
        val full = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 2 * TELE_HOUR)
        val sparse = store.seedProcess(lastSeen = now - 10_000, startedAt = now - TELE_HOUR)
        val m = TelemetryMetrics
        store.put(
            rawRow(full, latest - 5 * TELE_MINUTE, m.QUERY_IN_USE, 9.0), // an older tick: not carried
            rawRow(full, latest, m.LEDGER_IN_FLIGHT, 1_000_000.0), rawRow(full, latest, m.LEDGER_PEAK, 5_000_000.0),
            rawRow(full, latest, m.LEDGER_REFUSALS, 2.0), rawRow(full, latest, m.QUERY_IN_USE, 1.0),
            rawRow(full, latest, m.QUERY_REFUSALS, 3.0), rawRow(full, latest, m.JQ_QUARANTINED, 1.0),
            rawRow(full, latest, m.JQ_ACTIVE, 2.0), rawRow(full, latest, m.JQ_QUEUED, 4.0),
            rawRow(full, latest, m.FETCH_ACTIVE, 1.0), rawRow(full, latest, m.FETCH_QUEUED, 0.0),
            rawRow(full, latest, m.INTEGRATION_IN_USE, 2.0), rawRow(full, latest, m.INTEGRATION_RETAINED, 4096.0),
            rawRow(full, latest, m.INTEGRATION_REFUSALS, 7.0),
            rawRow(sparse, latest, m.HEAP_USED, 1.0), rawRow(sparse, latest, m.FETCH_ACTIVE, 0.0),
        )
        val processes = seededClient("telemetry-pressure", UserRole.ADMIN).current().processes.associateBy { it.id }

        val p = processes.getValue(full).pressure
        assertEquals(1_000_000L, p.ledgerInFlightBytes)
        assertEquals(5_000_000L, p.ledgerPeakBytes)
        assertEquals(2L, p.ledgerRefusals)
        assertEquals(1, p.queryInUse)
        assertEquals(3L, p.queryRefusals)
        assertEquals(listOf(1, 2, 4), listOf(p.jqQuarantined, p.jqActive, p.jqQueued))
        assertEquals(listOf(1, 0), listOf(p.fetchActive, p.fetchQueued))
        assertEquals(2, p.integrationInUse)
        assertEquals(4096L, p.integrationRetainedBytes)
        assertEquals(7L, p.integrationRefusals)

        val s = processes.getValue(sparse).pressure
        assertEquals(0, s.fetchActive)
        assertNull(s.fetchQueued)
        assertNull(s.ledgerInFlightBytes)
        assertNull(s.queryInUse)
        assertNull(s.integrationInUse, "no integration rows: the API is off")
        assertNull(s.integrationRefusals)
    }

    @Test
    fun `current reads the newest workspace measurement`() = testApplication {
        boot()
        val live = store.seedProcess(lastSeen = now - 10_000)
        val m = TelemetryMetrics
        val older = latest - TELE_HOUR
        val newer = latest - 30 * TELE_MINUTE
        store.put(
            rawRow(live, latest, m.HEAP_USED, 1.0),
            rawRow(live, older, m.DB_SIZE, 1.0), rawRow(live, older, m.DB_GROUP_SIZE, 1.0, "catalog"),
            rawRow(live, older, m.WS_ROWS, 1.0, "entities"),
            rawRow(live, newer, m.DB_SIZE, 9_000_000.0),
            rawRow(live, newer, m.DB_GROUP_SIZE, 6_000_000.0, "ontology"), rawRow(live, newer, m.DB_GROUP_SIZE, 1_000_000.0, "catalog"),
            rawRow(live, newer, m.WS_ROWS, 42.0, "entities"), rawRow(live, newer, m.WS_ROWS, 7.0, "blueprints"),
        )
        val workspace = assertNotNull(seededClient("telemetry-workspace", UserRole.ADMIN).current().workspace)

        assertEquals(newer, workspace.measuredAt, "the newest measurement")
        assertEquals(9_000_000L, workspace.databaseBytes)
        assertEquals(listOf("catalog" to 1_000_000L, "ontology" to 6_000_000L), workspace.groups.map { it.name to it.bytes })
        assertEquals(listOf("blueprints" to 7L, "entities" to 42L), workspace.rows.map { it.name to it.count })
    }

    @Test
    fun `current reads the busiest routes of the latest interval`() = testApplication {
        boot()
        val live = store.seedProcess(lastSeen = now - 10_000)
        val m = TelemetryMetrics
        val routeRows = (1..17).flatMap { i ->
            val route = "/api/v1/entities/r%02d/graph".format(i)
            listOf(rawRow(live, latest, m.HTTP_COUNT, i.toDouble(), route), rawRow(live, latest, m.HTTP_P95, i * 10.0, route))
        }
        store.put(
            rawRow(live, latest, m.HEAP_USED, 1.0),
            rawRow(live, latest, m.HTTP_COUNT, 200.0, "*"), rawRow(live, latest, m.HTTP_P50, 12.0, "*"),
            rawRow(live, latest, m.HTTP_P95, 80.0, "*"),
            rawRow(live, latest, m.HTTP_5XX, 3.0, "*"), rawRow(live, latest, m.HTTP_429, 2.0, "*"),
            rawRow(live, latest, m.HTTP_P50, 5.0, "/api/v1/entities/r17/graph"),
            rawRow(live, latest, m.HTTP_5XX, 1.0, "/api/v1/entities/r17/graph"),
            // An older interval: not "latest".
            rawRow(live, latest - 5 * TELE_MINUTE, m.HTTP_COUNT, 999.0, "/api/v1/entities/old/graph"),
            *routeRows.toTypedArray(),
        )
        val requests = seededClient("telemetry-routes", UserRole.ADMIN).current().requests

        assertEquals(latest, requests.measuredAt)
        assertEquals(200L, requests.total.count)
        assertEquals(12.0, requests.total.p50Ms)
        assertEquals(80.0, requests.total.p95Ms)
        assertEquals(3L, requests.total.serverErrors)
        assertEquals(2L, requests.total.rateLimited)
        assertEquals((17 downTo 3).map { "/api/v1/entities/r%02d/graph".format(it) }, requests.routes.map { it.route }, "top 15 by count")
        val top = requests.routes.first()
        assertEquals(17L, top.count)
        assertEquals(5.0, top.p50Ms)
        assertEquals(170.0, top.p95Ms)
        assertEquals(1L, top.serverErrors)
        assertEquals(0L, top.rateLimited, "an absent 429 row is zero")
        assertNull(requests.routes.last().p50Ms, "no p50 row, no p50")
    }

    @Test
    fun `requests merge several live processes - counts add, p50 weighted by requests, p95 the largest`() = testApplication {
        boot()
        val a = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 2 * TELE_HOUR)
        val b = store.seedProcess(lastSeen = now - 12_000, startedAt = now - 2 * TELE_HOUR)
        val stale = store.seedProcess(lastSeen = now - 30 * TELE_MINUTE, startedAt = now - 2 * TELE_HOUR)
        val m = TelemetryMetrics
        val route = "/api/v1/files/graph"
        store.put(
            rawRow(a, latest, m.HEAP_USED, 1.0), rawRow(b, latest, m.HEAP_USED, 1.0),
            rawRow(a, latest, m.HTTP_COUNT, 100.0, "*"), rawRow(a, latest, m.HTTP_P50, 10.0, "*"), rawRow(a, latest, m.HTTP_P95, 50.0, "*"),
            rawRow(a, latest, m.HTTP_5XX, 1.0, "*"), rawRow(a, latest, m.HTTP_429, 2.0, "*"),
            rawRow(b, latest, m.HTTP_COUNT, 300.0, "*"), rawRow(b, latest, m.HTTP_P50, 20.0, "*"), rawRow(b, latest, m.HTTP_P95, 80.0, "*"),
            rawRow(b, latest, m.HTTP_5XX, 4.0, "*"), rawRow(b, latest, m.HTTP_429, 1.0, "*"),
            // Only a wrote this route's p50: it weighs in alone; b's count still adds up.
            rawRow(a, latest, m.HTTP_COUNT, 10.0, route), rawRow(a, latest, m.HTTP_P50, 7.0, route),
            rawRow(a, latest, m.HTTP_P95, 9.0, route),
            rawRow(b, latest, m.HTTP_COUNT, 30.0, route),
            // A stale process is not "the latest interval".
            rawRow(stale, now - 30 * TELE_MINUTE, m.HTTP_COUNT, 7000.0, "*"),
        )
        val requests = seededClient("telemetry-merge-requests", UserRole.ADMIN).current().requests

        assertEquals(latest, requests.measuredAt)
        assertEquals(400L, requests.total.count)
        assertEquals(17.5, requests.total.p50Ms, "(10 * 100 + 20 * 300) / 400")
        assertEquals(80.0, requests.total.p95Ms, "the largest, not a mean of quantiles")
        assertEquals(5L, requests.total.serverErrors)
        assertEquals(3L, requests.total.rateLimited)
        val graph = requests.routes.single()
        assertEquals(route, graph.route)
        assertEquals(40L, graph.count)
        assertEquals(7.0, graph.p50Ms, "weighted by the requests of the processes that wrote it")
        assertEquals(9.0, graph.p95Ms)
    }

    // ---- history -------------------------------------------------------------------------------------------------------

    @Test
    fun `history merges the processes per bucket and keeps the trend across a restart`() = testApplication {
        boot()
        val first = store.seedProcess(lastSeen = now - TELE_HOUR, startedAt = now - 2 * TELE_HOUR) // stopped
        val second = store.seedProcess(lastSeen = now - 10_000, startedAt = now - TELE_HOUR) // its replacement
        val m = TelemetryMetrics
        val a = latest - 10 * TELE_MINUTE
        val b = latest - 5 * TELE_MINUTE
        store.put(
            rawRow(first, a, m.HEAP_USED, 100.0, max = 120.0), rawRow(second, a, m.HEAP_USED, 300.0, max = 350.0),
            rawRow(second, b, m.HEAP_USED, 400.0), rawRow(second, latest, m.HEAP_USED, 500.0),
            rawRow(second, a, m.RSS, 7.0),
            rawRow(first, a, m.GC_COUNT, 2.0, "Copy"), rawRow(second, a, m.GC_COUNT, 3.0, "Copy"),
            rawRow(second, b, m.THREADS, 40.0), rawRow(second, a, m.POOL_IDLE, 4.0),
        )
        val admin = seededClient("telemetry-merge", UserRole.ADMIN)

        val memory = admin.history("memory", "24h")
        assertEquals(listOf("mem.heap_used", "mem.rss"), memory.series.map { it.key })
        val heap = memory.series("mem.heap_used")
        assertEquals(listOf(a, b, latest), heap.ts())
        assertEquals(listOf(200.0, 400.0, 500.0), heap.avgs(), "the mean of the two processes in their shared bucket")
        assertEquals(listOf(350.0, 400.0, 500.0), heap.maxes(), "the largest of the two")
        assertEquals("mem.heap_used", heap.metric)
        assertEquals("", heap.label)
        assertEquals("BYTES", heap.unit)
        assertEquals("GAUGE", heap.kind)
        assertEquals(now - 2 * TELE_HOUR, memory.historyFrom, "the first sampler of the retained processes booted two hours ago")
        assertFalse(memory.truncated)
        assertEquals(TELE_RAW_SECONDS, memory.bucketSeconds)

        val jvm = admin.history("jvm", "24h")
        val gc = jvm.series("jvm.gc_count:Copy")
        assertEquals("DELTA", gc.kind)
        assertEquals("COUNT", gc.unit)
        assertEquals("Copy", gc.label)
        assertEquals(listOf(5.0), gc.avgs(), "a DELTA adds up: the bucket's total")
        assertEquals(listOf(3.0), gc.maxes(), "and keeps the largest single interval")
        assertEquals(setOf("jvm.gc_count:Copy", "jvm.threads", "pool.idle"), jvm.series.map { it.key }.toSet())
    }

    @Test
    fun `the workspace history is the sizes and counts of the heavy phase, one series per group and table`() = testApplication {
        boot()
        val live = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 5 * TELE_HOUR)
        val m = TelemetryMetrics
        val earlier = latest - 2 * TELE_HOUR
        val later = latest - TELE_HOUR
        store.put(
            rawRow(live, earlier, m.DB_SIZE, 1.0e9), rawRow(live, later, m.DB_SIZE, 2.0e9),
            rawRow(live, earlier, m.DB_GROUP_SIZE, 5.0e8, "ontology"), rawRow(live, later, m.DB_GROUP_SIZE, 6.0e8, "ontology"),
            rawRow(live, later, m.DB_GROUP_SIZE, 1.0e6, "catalog"),
            rawRow(live, earlier, m.WS_ROWS, 40.0, "entities"), rawRow(live, later, m.WS_ROWS, 42.0, "entities"),
            rawRow(live, later, m.HEAP_USED, 1.0), // another group's metric: not in this answer
        )
        val history = seededClient("telemetry-workspace-history", UserRole.ADMIN).history("workspace", "24h")

        assertEquals(
            listOf("db.size", "db.group.size:catalog", "db.group.size:ontology", "ws.rows:entities"),
            history.series.map { it.key },
        )
        val size = history.series("db.size")
        assertEquals(listOf(earlier, later), size.ts())
        assertEquals(listOf(1.0e9, 2.0e9), size.avgs())
        assertEquals("GAUGE", size.kind)
        assertEquals(listOf(5.0e8, 6.0e8), history.series("db.group.size:ontology").avgs())
        val rows = history.series("ws.rows:entities")
        assertEquals("COUNT", rows.unit)
        assertEquals("entities", rows.label)
        assertEquals(listOf(40.0, 42.0), rows.avgs())
    }

    @Test
    fun `the pressure history carries the gauges, and the refusal counts as bucket totals`() = testApplication {
        boot()
        val a = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 2 * TELE_HOUR)
        val b = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 2 * TELE_HOUR)
        val m = TelemetryMetrics
        store.put(
            rawRow(a, latest, m.LEDGER_REFUSALS, 2.0), rawRow(b, latest, m.LEDGER_REFUSALS, 3.0),
            rawRow(a, latest, m.LEDGER_IN_FLIGHT, 10.0, max = 30.0), rawRow(a, latest - 5 * TELE_MINUTE, m.LEDGER_IN_FLIGHT, 20.0),
            rawRow(a, latest, m.QUERY_IN_USE, 1.0), rawRow(a, latest, m.RSS, 1.0),
        )
        val history = seededClient("telemetry-pressure-history", UserRole.ADMIN).history("pressure", "24h")

        assertEquals(listOf("ledger.in_flight", "ledger.refusals", "query.in_use"), history.series.map { it.key })
        val refusals = history.series("ledger.refusals")
        assertEquals("DELTA", refusals.kind)
        assertEquals(listOf(5.0), refusals.avgs())
        assertEquals(listOf(3.0), refusals.maxes())
        assertEquals(listOf(20.0, 10.0), history.series("ledger.in_flight").avgs())
        assertEquals(listOf(20.0, 30.0), history.series("ledger.in_flight").maxes())
    }

    @Test
    fun `the 7 day history reads hourly rows plus the hours the rollup has not folded yet`() = testApplication {
        boot()
        val live = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 3 * TELE_DAY)
        val m = TelemetryMetrics
        val rolled = hourStart - 2 * TELE_HOUR
        val unrolled = hourStart - TELE_HOUR
        store.put(
            hourlyRow(live, rolled - 2 * TELE_HOUR, m.THREADS, avg = 100.0, max = 120.0),
            hourlyRow(live, rolled - TELE_HOUR, m.THREADS, avg = 200.0, max = 260.0),
            hourlyRow(live, rolled, m.THREADS, avg = 300.0, max = 310.0),
            hourlyRow(live, rolled, m.GC_COUNT, avg = 0.5, max = 2.0, label = "Copy"),
            // Raw rows of an hour that IS rolled up: already inside the hourly row, so counted once.
            rawRow(live, rolled + 5 * TELE_MINUTE, m.THREADS, 999.0), rawRow(live, rolled + 5 * TELE_MINUTE, m.GC_COUNT, 77.0, "Copy"),
            // Raw rows of the two hours after the newest hourly row: read time folds them.
            rawRow(live, unrolled, m.THREADS, 10.0), rawRow(live, unrolled + 5 * TELE_MINUTE, m.THREADS, 30.0),
            rawRow(live, hourStart, m.THREADS, 50.0),
            rawRow(live, unrolled, m.GC_COUNT, 1.0, "Copy"), rawRow(live, unrolled + 5 * TELE_MINUTE, m.GC_COUNT, 2.0, "Copy"),
        )
        val history = seededClient("telemetry-7d", UserRole.ADMIN).history("jvm", "7d")

        assertEquals(3600, history.bucketSeconds)
        val threads = history.series("jvm.threads")
        assertEquals(listOf(rolled - 2 * TELE_HOUR, rolled - TELE_HOUR, rolled, unrolled, hourStart), threads.ts())
        assertEquals(listOf(100.0, 200.0, 300.0, 20.0, 50.0), threads.avgs())
        assertEquals(listOf(120.0, 260.0, 310.0, 30.0, 50.0), threads.maxes())
        val gc = history.series("jvm.gc_count:Copy")
        assertEquals(listOf(rolled, unrolled), gc.ts())
        assertEquals(listOf(6.0, 3.0), gc.avgs(), "an hourly DELTA row's avg * n is the hour's total; the raw rows add up")
        assertEquals(listOf(2.0, 2.0), gc.maxes())
        assertEquals(now - 3 * TELE_DAY, history.historyFrom)
    }

    @Test
    fun `the 90 day history folds the hourly rows into six hour buckets`() = testApplication {
        boot()
        val live = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 50 * TELE_DAY)
        val m = TelemetryMetrics
        val bucket = 6 * TELE_HOUR
        val base = Math.floorDiv(now - 40 * TELE_DAY, bucket) * bucket
        store.put(
            hourlyRow(live, base, m.THREADS, avg = 100.0, max = 120.0),
            hourlyRow(live, base + TELE_HOUR, m.THREADS, avg = 200.0, max = 260.0),
            hourlyRow(live, base + bucket, m.THREADS, avg = 50.0, max = 60.0, n = 6),
            hourlyRow(live, base, m.GC_COUNT, avg = 1.0, max = 4.0, label = "MarkSweepCompact"),
            hourlyRow(live, base + TELE_HOUR, m.GC_COUNT, avg = 2.0, max = 3.0, label = "MarkSweepCompact"),
        )
        val history = seededClient("telemetry-90d", UserRole.ADMIN).history("jvm", "90d")

        assertEquals(21600, history.bucketSeconds)
        val threads = history.series("jvm.threads")
        assertEquals(listOf(base, base + bucket), threads.ts())
        assertEquals(listOf(150.0, 50.0), threads.avgs(), "the n-weighted mean of the hours in the bucket")
        assertEquals(listOf(260.0, 60.0), threads.maxes())
        assertEquals(listOf(12.0 + 24.0), history.series("jvm.gc_count:MarkSweepCompact").avgs(), "totals add up: 1 * 12 + 2 * 12")
        assertEquals(now - 50 * TELE_DAY, history.historyFrom)
    }

    @Test
    fun `a history carries at most 400 points per series`() = testApplication {
        boot("telemetry.sampleIntervalSeconds" to "60")
        val live = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 2 * TELE_DAY)
        val rows = (0 until 1440).map {
            rawRow(live, latest - it * TELE_MINUTE, TelemetryMetrics.HEAP_USED, it.toDouble(), resolution = 60)
        }
        store.put(rows)
        val admin = seededClient("telemetry-caps", UserRole.ADMIN)

        assertEquals(60, admin.current().sampleIntervalSeconds)
        val heap = admin.history("memory", "24h")
        assertEquals(240, heap.bucketSeconds, "a day of 60 s samples is 1440 points: four of them per bucket")
        val points = heap.series("mem.heap_used").points
        assertTrue(points.size in 358..361, "${points.size} points")
        assertTrue(points.size <= 400)
        assertEquals(points.map { it.t }.sorted(), points.map { it.t })
        assertTrue(points.all { it.t % 240_000L == 0L })
    }

    @Test
    fun `an unaligned 24 hour range is cut to exactly 400 points, the oldest partial bucket dropped`() = testApplication {
        val bucket = 216_000L // 72 s * ceil(86400 / (400 * 72)) = 3 samples per bucket
        // A clock 100 s into a bucket: the range starts 100 s into one too, so a full day touches 401 buckets (the first partial).
        val clock = Math.floorDiv(now, bucket) * bucket + 100_000
        boot("telemetry.sampleIntervalSeconds" to "72", clock = clock)
        val from = clock - TELE_DAY
        val alignedFrom = from - Math.floorMod(from, bucket)
        assertTrue(alignedFrom < from)
        val live = store.seedProcess(lastSeen = clock - 10_000, startedAt = clock - 2 * TELE_DAY)
        val times = generateSequence(alignedFrom) { it + 72_000 }.takeWhile { it < clock }.toList()
        store.put(times.map { rawRow(live, it, TelemetryMetrics.HEAP_USED, 1.0, resolution = 72) })
        assertEquals(401, times.map { it - Math.floorMod(it, bucket) }.distinct().size, "the seeded day touches 401 buckets")

        val heap = seededClient("telemetry-point-cap", UserRole.ADMIN).history("memory", "24h")
        assertEquals(216, heap.bucketSeconds)
        val points = heap.series("mem.heap_used").points
        assertEquals(400, points.size)
        assertEquals(points.map { it.t }.sorted(), points.map { it.t })
        assertTrue(points.all { it.t % bucket == 0L })
        assertEquals(alignedFrom + bucket, points.first().t, "the oldest (partial) bucket is the one dropped")
        assertEquals(alignedFrom + 400 * bucket, points.last().t)
    }

    @Test
    fun `more than 40 series are cut to 40 and flagged`() = testApplication {
        boot()
        val live = store.seedProcess(lastSeen = now - 10_000)
        store.put((1..45).map { rawRow(live, latest, TelemetryMetrics.GC_COUNT, it.toDouble(), "collector-%02d".format(it)) })
        val history = seededClient("telemetry-series-cap", UserRole.ADMIN).history("jvm", "24h")

        assertEquals(40, history.series.size)
        assertTrue(history.truncated)
    }

    @Test
    fun `the requests history keeps the total and the ten busiest routes`() = testApplication {
        boot()
        val a = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 2 * TELE_HOUR)
        val b = store.seedProcess(lastSeen = now - 10_000, startedAt = now - 2 * TELE_HOUR)
        val m = TelemetryMetrics
        val earlier = latest - 5 * TELE_MINUTE
        fun route(i: Int) = "/api/v1/entities/r%02d/graph".format(i)
        val rows = (1..12).flatMap { i ->
            listOf(earlier, latest).flatMap { ts ->
                listOf(
                    rawRow(a, ts, m.HTTP_COUNT, i.toDouble(), route(i)), rawRow(a, ts, m.HTTP_P50, i.toDouble(), route(i)),
                    rawRow(a, ts, m.HTTP_P95, i * 2.0, route(i)),
                )
            }
        } + listOf(
            rawRow(a, latest, m.HTTP_COUNT, 500.0, "*"), rawRow(a, latest, m.HTTP_P50, 9.0, "*"),
            rawRow(a, latest, m.HTTP_P95, 90.0, "*"), rawRow(a, latest, m.HTTP_5XX, 4.0, "*"),
            rawRow(a, latest, m.HTTP_429, 1.0, "*"), rawRow(a, latest, m.HTTP_5XX, 2.0, route(12)),
            // Another process of the same fleet: one series per route, not one per process.
            rawRow(b, latest, m.HTTP_COUNT, 3.0, route(12)), rawRow(b, latest, m.HTTP_COUNT, 100.0, "*"),
        )
        store.put(rows)
        val history = seededClient("telemetry-requests", UserRole.ADMIN).history("requests", "24h")

        val keys = history.series.map { it.key }.toSet()
        assertEquals(35, history.series.size, "5 for the total + 3 for each of ten routes")
        assertFalse(history.truncated)
        val totals = keys.filter { it.endsWith(":*") }.map { it.removePrefix("http.").removeSuffix(":*") }
        assertEquals(setOf("count", "p50", "p95", "5xx", "429"), totals.toSet())
        for (i in 3..12) assertTrue("http.count:${route(i)}" in keys && "http.p95:${route(i)}" in keys, route(i))
        assertTrue(keys.none { route(1) in it || route(2) in it }, "the two quietest routes are out")
        assertFalse("http.5xx:${route(12)}" in keys, "per-route errors are not in the history")
        val count = history.series("http.count:${route(12)}")
        assertEquals(route(12), count.label)
        assertEquals("DELTA", count.kind)
        assertEquals(listOf(12.0, 15.0), count.avgs(), "both processes add up in the shared bucket")
        assertEquals(600.0, history.series("http.count:*").avgs().single())
        assertEquals("MILLISECONDS", history.series("http.p95:*").unit)
        assertEquals(4.0, history.series("http.5xx:*").avgs().single())
    }

    // ---- rate limit ----------------------------------------------------------------------------------------------------

    @Test
    fun `both telemetry reads share one per-user bucket of 30 a minute`() = testApplication {
        boot()
        val admin = seededClient("telemetry-bucket", UserRole.ADMIN)
        val other = seededClient("telemetry-bucket-other", UserRole.ADMIN)
        repeat(15) {
            assertEquals(HttpStatusCode.OK, admin.get("/api/v1/telemetry/current").status)
            assertEquals(HttpStatusCode.OK, admin.get("/api/v1/telemetry/history?group=memory&range=24h").status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, admin.get("/api/v1/telemetry/current").status)
        assertEquals(HttpStatusCode.TooManyRequests, admin.get("/api/v1/telemetry/history?group=memory&range=24h").status)
        assertEquals(HttpStatusCode.OK, other.get("/api/v1/telemetry/current").status, "keyed by the user, not the address")
    }

    @Test
    fun `a regular user spends the same bucket on refusals, never on someone else's`() = testApplication {
        boot()
        val user = seededClient("telemetry-bucket-user")
        val admin = seededClient("telemetry-bucket-admin", UserRole.ADMIN)
        repeat(30) { assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/telemetry/current").status) }
        assertEquals(HttpStatusCode.TooManyRequests, user.get("/api/v1/telemetry/current").status, "the bucket guards the guard too")
        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/telemetry/current").status)
    }
}
