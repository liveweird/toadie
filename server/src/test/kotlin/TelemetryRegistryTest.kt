package ch.nokillswit

import ch.nokillswit.plugins.MAX_CALL_METRIC_NAMES
import ch.nokillswit.plugins.MetricRegistryKey
import ch.nokillswit.plugins.TelemetryRegistry
import ch.nokillswit.plugins.timerWindowSeconds
import com.codahale.metrics.Clock
import com.codahale.metrics.ExponentiallyDecayingReservoir
import com.codahale.metrics.Histogram
import com.codahale.metrics.SlidingTimeWindowArrayReservoir
import com.codahale.metrics.Timer
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.server.testing.testApplication
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `plugins/Monitoring.kt`'s telemetry seam: the registry Ktor's request meters write into is a [TelemetryRegistry] whose timers keep
 * a TRUE sliding window (Dropwizard's default decaying reservoir would keep a burst's samples, weighted, for minutes), capped at
 * five minutes, published as `MetricRegistryKey`; Ktor's global `duration` timer keeps the default reservoir; Ktor's JVM metric sets
 * (the thread-dumping gauges) are not registered; and an unrouted URL - with or without a query string - registers no instrument,
 * because a route's other instruments register only after its `.meter` (only a routed call creates one).
 */
class TelemetryRegistryTest {
    private class ManualClock : Clock() {
        var nanos = 1_000_000_000L
        override fun getTick() = nanos
    }

    /** The reservoir behind a timer (Dropwizard keeps it in two private fields). */
    private fun reservoirOf(timer: Timer): Any {
        val histogram = Timer::class.java.getDeclaredField("histogram").apply { isAccessible = true }.get(timer)
        return Histogram::class.java.getDeclaredField("reservoir").apply { isAccessible = true }.get(histogram)
    }

    @Test
    fun `a timer forgets what is older than the window`() {
        val clock = ManualClock()
        val timer = TelemetryRegistry(windowSeconds = 60, clock = clock).timer("t")
        timer.update(100, TimeUnit.MILLISECONDS)
        clock.nanos += TimeUnit.SECONDS.toNanos(59)
        assertEquals(1, timer.snapshot.size(), "inside the window")
        clock.nanos += TimeUnit.SECONDS.toNanos(2)
        assertEquals(0, timer.snapshot.size(), "outside the window the sample is gone")
        assertEquals(1L, timer.count, "the lifetime count is untouched")
    }

    @Test
    fun `the window is the sample interval capped at five minutes`() {
        assertEquals(60L, timerWindowSeconds(60))
        assertEquals(300L, timerWindowSeconds(300))
        assertEquals(300L, timerWindowSeconds(600))
        assertEquals(300L, timerWindowSeconds(1800))
    }

    @Test
    fun `route timers use the sliding reservoir, Ktor's global duration timer keeps the default, and there are no JVM gauge sets`() =
        testApplication {
            usePostgresTestcontainer()
            val registry = application.attributes[MetricRegistryKey]
            assertIs<TelemetryRegistry>(registry)
            jsonClient().get("/healthz")
            val routeTimers = registry.timers.filterKeys { it.endsWith(".timer") }
            assertTrue(routeTimers.isNotEmpty(), "Ktor's route timers are created through the registry: ${registry.names}")
            for ((name, timer) in routeTimers) {
                assertIs<SlidingTimeWindowArrayReservoir>(reservoirOf(timer), "route timer $name")
            }
            val duration = registry.timers.getValue("ktor.calls.duration")
            assertIs<ExponentiallyDecayingReservoir>(reservoirOf(duration), "the global duration timer is not windowed")
            val jvm = registry.names.filter { it.startsWith("jvm.") }
            assertTrue(jvm.isEmpty(), "registerJvmMetricSets is off: $jvm")
        }

    @Test
    fun `a ktor calls name with a query string is never registered and the family is capped`() {
        val registry = TelemetryRegistry(windowSeconds = 60, maxCallNames = 5)
        val withQuery = registry.meter("ktor.calls./api/v1/login?x=1/(method:POST).429")
        withQuery.mark()
        registry.timer("ktor.calls./api/v1/x?y=2/(method:GET).timer").update(1, TimeUnit.MILLISECONDS)
        assertEquals(1L, withQuery.count, "a detached meter still works for its caller")
        assertTrue(registry.names.none { '?' in it }, "nothing with a query string is registered")
        for (i in 1..20) registry.meter("ktor.calls./route-$i/(method:GET).meter").mark()
        assertEquals(5, registry.names.count { it.startsWith("ktor.calls.") }, "the family stops at the cap")
        registry.meter("ktor.calls./route-1/(method:GET).meter").mark()
        assertEquals(2L, registry.meter("ktor.calls./route-1/(method:GET).meter").count, "a registered name keeps counting past the cap")
        registry.counter("other.counter").inc()
        assertTrue("other.counter" in registry.names, "names outside the family are untouched")
    }

    @Test
    fun `a route's other instruments register only after its meter, so unrouted names register nothing`() {
        val registry = TelemetryRegistry(windowSeconds = 60)
        val route = "ktor.calls./api/v1/entities/graph/(method:GET)"
        // Ktor 3.6 names an UNROUTED call after the request URI: only its status meter would ever be asked for.
        registry.meter("ktor.calls./x-1/(method:POST).404").mark()
        registry.timer("$route.timer").update(1, TimeUnit.MILLISECONDS)
        registry.meter("$route.200").mark()
        assertTrue(registry.names.none { it.startsWith("ktor.calls./") }, "no meter yet: ${registry.names}")
        // A routed call creates the .meter first, then the timer, then the status meter at the response.
        registry.meter("$route.meter").mark()
        registry.timer("$route.timer").update(1, TimeUnit.MILLISECONDS)
        registry.meter("$route.200").mark()
        assertEquals(setOf("$route.meter", "$route.timer", "$route.200"), registry.names.filter { it.startsWith("ktor.calls./") }.toSet())
        // The plugin's global names are fixed and always registered.
        registry.timer("ktor.calls.duration")
        registry.counter("ktor.calls.active")
        registry.meter("ktor.calls.exceptions")
        registry.meter("ktor.calls.status.404")
        assertTrue(listOf("duration", "active", "exceptions", "status.404").all { "ktor.calls.$it" in registry.names })
    }

    @Test
    fun `a flood of unrouted and rate-limited requests with varying query strings registers no query-string name`() = testApplication {
        configureApp("security.rateLimit.loginPerMinute" to "2")
        startApplication()
        val registry = application.attributes[MetricRegistryKey]
        repeat(150) { i ->
            client.get("/nope-$i/deep?x=$i")
            client.get("/api/v1/nope-$i?junk=$i")
            client.post("/api/v1/login?x=$i")
            client.delete("/api/v1/login?z=$i") // routing's 405
        }
        val names = registry.names.filter { it.startsWith("ktor.calls.") }
        assertTrue(names.size < MAX_CALL_METRIC_NAMES, "${names.size} names")
        assertTrue(names.none { '?' in it }, "registered: ${names.filter { '?' in it }.take(3)}")
        assertTrue(names.size < 100, "the flood leaves the family at its routed size, was ${names.size}")
    }

    @Test
    fun `a flood of unique unrouted POST paths registers nothing and a real route hit afterwards still registers`() = testApplication {
        usePostgresTestcontainer()
        val registry = application.attributes[MetricRegistryKey]
        val before = registry.names.filter { it.startsWith("ktor.calls.") }.toSet()
        repeat(300) { i -> client.post("/x-$i/deep") } // no query string: Ktor 3.6 would name each after the URI
        val afterFlood = registry.names.filter { it.startsWith("ktor.calls.") }.toSet()
        // The global per-status meters (`ktor.calls.status.<code>`) are fixed, bounded names; nothing else may appear.
        assertEquals(emptySet(), (afterFlood - before).filterNot { it.startsWith("ktor.calls.status.") }.toSet(), "no instrument per path")
        client.get("/healthz")
        val routed = registry.names.filter { it.startsWith("ktor.calls.") && "healthz" in it }
        assertTrue(routed.any { it.endsWith(".meter") } && routed.any { it.endsWith(".timer") }, "the real route registered: $routed")
    }
}
