package ch.nokillswit

import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.plugins.KTOR_CALLS_FAMILY
import ch.nokillswit.plugins.MetricRegistryKey
import ch.nokillswit.telemetry.LatencyCollector
import ch.nokillswit.telemetry.MetricDef
import ch.nokillswit.telemetry.MetricGroup
import ch.nokillswit.telemetry.Reading
import ch.nokillswit.telemetry.TelemetryMetrics
import ch.nokillswit.telemetry.TelemetryOverrides
import ch.nokillswit.telemetry.TelemetryOverridesKey
import ch.nokillswit.telemetry.TelemetrySamplerKey
import ch.nokillswit.telemetry.isCounted
import ch.nokillswit.telemetry.isLabelled
import ch.nokillswit.telemetry.normaliseRoute
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OK_ROUTE = "/api/v1/zz-latency-ok"
private const val GRAPH_ROUTE = "/api/v1/zz-latency/graph"
private const val EXPORT_ROUTE = "/api/v1/zz-latency/export"
private const val ERRORS_ROUTE = "/api/v1/zz-latency/errors"
private const val IMPORT_ROUTE = "/api/v1/zz-latency/import"
private const val TELEMETRY_ROUTE = "/api/v1/telemetry/zz-latency"
private const val PAGE_ROUTE = "/zz-latency-page"
private const val HANDLER_DELAY_MILLIS = 25L

/**
 * `telemetry/LatencyCollector.kt` over a real `testApplication`: Ktor's Dropwizard series become per-labelled-route and `*` rows
 * (count and 5xx/429 deltas, p50/p95 of the windowed timers), the telemetry's own routes and non-API paths stay out of the total, the
 * series-name normalisation and the label rule are pinned against the names Ktor 3.6 really writes for Toadie's routes (so an
 * upgrade that renames them, or a route that changes shape, fails here instead of silently emptying the panel), and every real
 * rate-limited route counts each 429 once (`observability.md` "Telemetry": no surplus correction is needed).
 */
class LatencyCollectorTest {
    private val store = telemetryStore()

    @Before
    fun emptyTelemetry() = runBlocking { wipeTelemetry(store) }

    private fun Application.syntheticRoutes() {
        routing {
            get(OK_ROUTE) { call.respondText("ok") }
            get(GRAPH_ROUTE) {
                delay(HANDLER_DELAY_MILLIS)
                call.respondText("graph")
            }
            get(EXPORT_ROUTE) { call.respond(HttpStatusCode.InternalServerError) }
            get(ERRORS_ROUTE) { error("boom") }
            get(IMPORT_ROUTE) { throw TooManyRequestsException("slow down") }
            get(TELEMETRY_ROUTE) { call.respondText("t") }
            get(PAGE_ROUTE) { call.respondText("page") }
        }
    }

    private fun List<Reading>.value(metric: MetricDef, label: String): Double? =
        firstOrNull { it.metric == metric && it.label == label }?.value

    /** One interval's readings, committed as the sampler does once they are stored. */
    private fun LatencyCollector.take(): List<Reading> = collect().also { commit() }

    /** A started application with the synthetic routes and a collector over its registry, whose baseline (startup traffic) is taken. */
    private fun withCollector(
        vararg overrides: Pair<String, String>,
        block: suspend ApplicationTestBuilder.(LatencyCollector) -> Unit,
    ) = testApplication {
        configureApp(*overrides)
        application { syntheticRoutes() }
        startApplication()
        val collector = LatencyCollector(application.attributes[MetricRegistryKey])
        collector.take()
        block(collector)
    }

    @Test
    fun `a labelled route's count and percentiles come from its timer, the total adds every counted route`() = withCollector { collector ->
        repeat(4) { client.get(GRAPH_ROUTE) }
        repeat(2) { client.get(OK_ROUTE) }

        val readings = collector.take()
        assertEquals(4.0, readings.value(TelemetryMetrics.HTTP_COUNT, GRAPH_ROUTE))
        val p50 = assertNotNull(readings.value(TelemetryMetrics.HTTP_P50, GRAPH_ROUTE))
        val p95 = assertNotNull(readings.value(TelemetryMetrics.HTTP_P95, GRAPH_ROUTE))
        assertTrue(p50 >= HANDLER_DELAY_MILLIS - 5 && p95 >= p50, "milliseconds of a $HANDLER_DELAY_MILLIS ms handler: p50 $p50 p95 $p95")
        assertEquals(6.0, readings.value(TelemetryMetrics.HTTP_COUNT, "*"))
        val totalP95 = assertNotNull(readings.value(TelemetryMetrics.HTTP_P95, "*"))
        assertTrue(totalP95 >= p50, "the total's percentile spans every route's observations")
        assertNull(readings.value(TelemetryMetrics.HTTP_COUNT, OK_ROUTE), "an unlabelled route counts in the total only")
    }

    @Test
    fun `counts are deltas of the previous collection, and a label with nothing new has no row`() = withCollector { collector ->
        repeat(3) { client.get(GRAPH_ROUTE) }
        assertEquals(3.0, collector.take().value(TelemetryMetrics.HTTP_COUNT, GRAPH_ROUTE))
        assertEquals(emptyList(), collector.take().filter { it.label == GRAPH_ROUTE }, "an idle interval writes nothing for the route")
        client.get(GRAPH_ROUTE)
        assertEquals(1.0, collector.take().value(TelemetryMetrics.HTTP_COUNT, GRAPH_ROUTE), "the next interval's own delta")
    }

    @Test
    fun `5xx and 429 responses are counted per labelled route and in the total, a thrown 429 once`() = withCollector { collector ->
        repeat(2) { client.get(EXPORT_ROUTE) }
        client.get(ERRORS_ROUTE)
        repeat(3) { client.get(IMPORT_ROUTE) }

        val readings = collector.take()
        assertEquals(2.0, readings.value(TelemetryMetrics.HTTP_5XX, EXPORT_ROUTE))
        assertEquals(1.0, readings.value(TelemetryMetrics.HTTP_5XX, ERRORS_ROUTE), "a thrown exception is one 5xx")
        assertNull(readings.value(TelemetryMetrics.HTTP_429, EXPORT_ROUTE))
        assertEquals(3.0, readings.value(TelemetryMetrics.HTTP_429, IMPORT_ROUTE), "TooManyRequestsException is answered once")
        assertEquals(3.0, readings.value(TelemetryMetrics.HTTP_5XX, "*"))
        assertEquals(3.0, readings.value(TelemetryMetrics.HTTP_429, "*"))
        assertEquals(6.0, readings.value(TelemetryMetrics.HTTP_COUNT, "*"))
        assertEquals(3.0, readings.value(TelemetryMetrics.HTTP_COUNT, IMPORT_ROUTE), "the request count is the timer's, never doubled")
    }

    @Test
    fun `the telemetry's own routes, the probes and non-API paths stay out of the total`() = withCollector { collector ->
        repeat(3) { client.get(TELEMETRY_ROUTE) }
        repeat(2) { client.get(PAGE_ROUTE) }
        repeat(4) { client.get("/healthz") }
        client.get("/readyz")
        assertEquals(emptyList(), collector.take().filter { it.metric.group == MetricGroup.REQUESTS })

        client.get(OK_ROUTE)
        assertEquals(1.0, collector.take().value(TelemetryMetrics.HTTP_COUNT, "*"))
    }

    @Test
    fun `an unrouted request leaves no row and registers no instrument`() = withCollector { collector ->
        client.get(GRAPH_ROUTE)
        collector.take()
        val before = application.attributes[MetricRegistryKey].names.filter { it.startsWith(KTOR_CALLS_FAMILY) }.toSet()
        client.get("/api/v1/zz-latency-unknown?q=1")
        client.get("/api/v1/zz-latency-unknown/graph")
        val readings = collector.take()
        assertEquals(emptyList(), readings.filter { it.label.contains("unknown") })
        assertNull(readings.value(TelemetryMetrics.HTTP_COUNT, GRAPH_ROUTE))
        val after = application.attributes[MetricRegistryKey].names.filter { it.startsWith(KTOR_CALLS_FAMILY) }.toSet()
        assertEquals(emptySet(), (after - before).filterNot { it.startsWith("${KTOR_CALLS_FAMILY}status.") }.toSet())
    }

    /**
     * The 429 correction Flow needed does not exist here (`observability.md` "Telemetry"): Ktor marks the RateLimit plugin's bodiless
     * 429 and StatusPages' re-answer of it, and the re-answer lands in the routed name's `429` meter a second time only on a route
     * whose name equals its URI (no selector, no path parameter, no query string). Every rate-limited Toadie route carries the
     * `(RateLimit x)` selector, so the re-answer falls back to a URI name `TelemetryRegistry` refuses - each rejection counts once,
     * with or without a query string, and the timer count (the request count) equals the meters'.
     */
    @Test
    fun `every real rate-limited route counts each 429 once, a query string notwithstanding`() = withCollector(
        "security.rateLimit.loginPerMinute" to "1",
        "security.rateLimit.refreshPerMinute" to "1",
        "security.rateLimit.passwordResetPerMinute" to "1",
    ) { collector ->
        for (path in listOf("/api/v1/login", "/api/v1/refresh", "/api/v1/password-reset")) {
            client.post(path) // admitted (a 400/401: no body)
            val rejected = listOf(path, "$path?probe=a", "$path?probe=b").map { client.post(it).status }
            assertEquals(listOf(HttpStatusCode.TooManyRequests), rejected.distinct(), path)
            val readings = collector.take()
            assertEquals(3.0, readings.value(TelemetryMetrics.HTTP_429, "*"), "$path: three rejections, not six")
            assertEquals(4.0, readings.value(TelemetryMetrics.HTTP_COUNT, "*"), "$path: one admitted + three rejected")
            assertTrue(readings.none { '?' in it.label }, "no label carries a query string: ${readings.map { it.label }}")
        }
    }

    /** MFA (10/min) and the reset confirmation (10/min) have a fixed bucket: exhaust it, then reject some more. */
    @Test
    fun `the fixed-limit MFA and reset-confirm routes count each 429 once, a query string notwithstanding`() = withCollector { collector ->
        for (path in listOf("/api/v1/login/mfa", "/api/v1/password-reset/confirm")) {
            repeat(10) { client.post(path) } // admitted: 400s (no body)
            val rejected = listOf(path, "$path?probe=a", "$path?probe=b").map { client.post(it).status }
            assertEquals(listOf(HttpStatusCode.TooManyRequests), rejected.distinct(), path)
            val readings = collector.take()
            assertEquals(3.0, readings.value(TelemetryMetrics.HTTP_429, "*"), "$path: three rejections, not six")
            assertEquals(13.0, readings.value(TelemetryMetrics.HTTP_COUNT, "*"), "$path: ten admitted + three rejected")
        }
    }

    @Test
    fun `the integration routes are labelled and each 429 counts once`() = withCollector(
        "integration.enabled" to "true",
        "security.rateLimit.integrationPerMinute" to "2",
    ) { collector ->
        repeat(2) { client.post("/integration/graphql") } // 401s: admitted, then unauthenticated
        repeat(3) { client.post("/integration/graphql?i=$it") }
        repeat(2) { client.get("/integration/graphql/schema") }
        client.post("/integration/mcp")

        val readings = collector.take()
        assertEquals(5.0, readings.value(TelemetryMetrics.HTTP_COUNT, "/integration/graphql"))
        assertEquals(3.0, readings.value(TelemetryMetrics.HTTP_429, "/integration/graphql"))
        assertEquals(2.0, readings.value(TelemetryMetrics.HTTP_429, "/integration/graphql/schema"))
        assertEquals(1.0, readings.value(TelemetryMetrics.HTTP_429, "/integration/mcp"))
        assertEquals(8.0, readings.value(TelemetryMetrics.HTTP_COUNT, "*"), "/integration/ routes are counted in the total")
        assertEquals(6.0, readings.value(TelemetryMetrics.HTTP_429, "*"))
    }

    @Test
    fun `route normalisation drops the selectors Ktor puts in a series name`() {
        val auth = "/(authenticate \"default\")"
        assertEquals("/api/v1/entities/graph", normaliseRoute("$auth/api/v1/entities/graph/(method:GET)"))
        assertEquals("/api/v1/files/{id}", normaliseRoute("$auth/api/v1/files/{id}/(method:GET)"))
        assertEquals("/api/v1/login", normaliseRoute("/(RateLimit login)/api/v1/login/(method:POST)"))
        assertEquals("/integration/graphql/schema", normaliseRoute("/(RateLimit integration)/integration/graphql/schema/(method:GET)"))
        assertEquals("/healthz", normaliseRoute("/healthz/(method:GET)"))
        assertEquals("/", normaliseRoute("/(method:GET)"))
    }

    @Test
    fun `the series Ktor really writes normalise to the labels the panel will read`() = withCollector { collector ->
        client.get("/api/v1/entities/graph") // anonymous: routed under `authenticate`, answered 401
        client.get("/api/v1/files/1")
        val names = application.attributes[MetricRegistryKey].timers.keys
        // The raw names, with the whole selector chain: if Ktor stops writing them this way the normaliser's input has changed.
        val graph = names.single { it.contains("/entities/graph") }
        assertEquals("ktor.calls./(authenticate \"default\")/api/v1/entities/graph/(method:GET).timer", graph)
        assertEquals("/api/v1/entities/graph", normaliseRoute(graph.removePrefix(KTOR_CALLS_FAMILY).removeSuffix(".timer")))
        val byId = names.single { it.contains("/files/{id}") }
        val byIdRoute = normaliseRoute(byId.removePrefix(KTOR_CALLS_FAMILY).removeSuffix(".timer"))
        assertEquals("/api/v1/files/{id}", byIdRoute, "{id} stays literal")
        assertTrue(names.none { it.contains("/files/1") }, "no concrete identifier in a route name: $names")
        val readings = collector.take()
        assertEquals(1.0, readings.value(TelemetryMetrics.HTTP_COUNT, "/api/v1/entities/graph"))
        assertNull(readings.value(TelemetryMetrics.HTTP_COUNT, "/api/v1/files/{id}"), "an unlabelled route is in the total only")
        assertEquals(2.0, readings.value(TelemetryMetrics.HTTP_COUNT, "*"))
    }

    /** Every leaf of the routing tree (one per path+method), named the way Ktor names it in a series. */
    private fun RoutingNode.leaves(): List<String> =
        children.flatMap { child -> if (child.selector is HttpMethodRouteSelector) listOf(child.toString()) else child.leaves() }

    @Test
    fun `the label rule selects exactly the heavy routes of the real routing tree`() = testApplication {
        configureApp("integration.enabled" to "true")
        startApplication()
        val routes = application.plugin(RoutingRoot).leaves().map(::normaliseRoute).distinct()
        assertTrue(routes.size > 50, "the walk found the application's routes: ${routes.size}")

        val labelled = routes.filter { isCounted(it) && isLabelled(it) }.sorted()
        assertEquals(
            listOf(
                "/api/v1/blueprints/fetch", "/api/v1/blueprints/import", "/api/v1/blueprints/import/check",
                "/api/v1/conversion-mappings/{id}/catalog-documents", "/api/v1/conversion-mappings/{id}/entity-documents",
                "/api/v1/entities/errors", "/api/v1/entities/fetch", "/api/v1/entities/graph", "/api/v1/entities/import",
                "/api/v1/entities/import/check", "/api/v1/files/errors", "/api/v1/files/export", "/api/v1/files/fetch",
                "/api/v1/files/graph", "/api/v1/files/import", "/api/v1/files/import/check", "/integration/graphql",
                "/integration/graphql/schema", "/integration/mcp",
            ),
            labelled,
        )
        // Everything counted but not labelled is in `*` only; the telemetry routes and the probes are not counted at all.
        assertTrue(routes.filter { isCounted(it) }.size > labelled.size)
        assertTrue(routes.none { isCounted(it) && it.startsWith("/api/v1/telemetry") })
        assertFalse(isCounted("/healthz") || isCounted("/readyz") || isCounted("/"), "probes and the SPA are not API traffic")
    }

    @Test
    fun `the label rule is a shape, so a new heavy route is labelled without a code change`() {
        for (route in listOf("/api/v1/zz/graph", "/api/v1/zz/errors", "/api/v1/zz/export", "/api/v1/zz/import", "/api/v1/zz/import/check",
            "/api/v1/zz/fetch", "/api/v1/zz/{id}/catalog-documents", "/integration/anything")) {
            assertTrue(isLabelled(route), route)
        }
        val plain = listOf("/api/v1/files", "/api/v1/files/{id}", "/api/v1/files/{id}/events", "/api/v1/login", "/api/v1/zz/{id}/sync")
        for (route in plain) assertFalse(isLabelled(route), route)
        assertTrue(isCounted("/api/v1/files") && isCounted("/integration/mcp"))
        assertFalse(isCounted("/api/v1/telemetry/current") || isCounted("/api/v1/telemetry"))
    }

    private fun runSampler(): Pair<Set<String>, Set<String>> = runBlocking {
        val identity = testIdentity()
        lateinit var app: Application
        var labels = emptySet<String>()
        var metrics = emptySet<String>()
        testApplication {
            configureApp("telemetry.enabled" to "true")
            application {
                app = this
                syntheticRoutes()
                attributes.put(TelemetryOverridesKey, TelemetryOverrides(identity = identity, firstTickDelayMillis = Long.MAX_VALUE / 4))
            }
            startApplication()
            client.get(GRAPH_ROUTE)
            assertNotNull(app.attributes.getOrNull(TelemetrySamplerKey)).tick()
            val rows = store.samples(assertNotNull(store.processId(identity))).filter { it.metric.startsWith("http.") }
            labels = rows.map { it.label }.toSet()
            metrics = rows.map { it.metric }.toSet()
        }
        labels to metrics
    }

    @Test
    fun `a sampled process writes its request rows`() {
        val (labels, metrics) = runSampler()
        assertEquals(setOf("*", GRAPH_ROUTE), labels)
        assertTrue("http.count" in metrics && "http.p50" in metrics && "http.p95" in metrics, "$metrics")
    }
}
