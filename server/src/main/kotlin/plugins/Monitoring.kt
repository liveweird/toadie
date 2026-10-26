package ch.nokillswit.plugins

import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import com.codahale.metrics.*
import io.ktor.server.metrics.dropwizard.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import io.ktor.http.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.response.*

/** The shortest non-zero `monitoring.reporterIntervalSeconds`: a tighter dump is the idle-allocation and log-volume cost avoided. */
internal const val MIN_REPORTER_INTERVAL_SECONDS = 30

/** The longest `monitoring.reporterIntervalSeconds` (one hour). */
internal const val MAX_REPORTER_INTERVAL_SECONDS = 3600

/**
 * An integer `application.yaml` property within [min]..[max], boot-validated the `readPoolBounds` way (a range failure throws
 * [IllegalArgumentException] naming the key); a non-numeric value fails the same way instead of with a bare
 * `NumberFormatException` that does not name the key. Shared by the `monitoring.*` and `telemetry.*` readers.
 */
internal fun boundedIntProperty(config: ApplicationConfig, key: String, min: Int, max: Int): Int {
    val raw = config.property(key).getString()
    val value = raw.trim().toIntOrNull()
    require(value != null && value in min..max) { "$key must be between $min and $max (was \"$raw\")" }
    return value
}

/**
 * `monitoring.reporterIntervalSeconds` (env `METRICS_REPORT_INTERVAL_SECONDS`, default 300): how often the Dropwizard registry is
 * dumped to the `metrics` logger. `null` = `0` = the reporter is off (the metrics are still collected). Anything else outside
 * [MIN_REPORTER_INTERVAL_SECONDS]..[MAX_REPORTER_INTERVAL_SECONDS], or non-numeric, refuses startup in every mode.
 */
internal fun reporterIntervalSeconds(config: ApplicationConfig): Int? {
    val key = "monitoring.reporterIntervalSeconds"
    val seconds = boundedIntProperty(config, key, min = 0, max = MAX_REPORTER_INTERVAL_SECONDS)
    require(seconds == 0 || seconds >= MIN_REPORTER_INTERVAL_SECONDS) {
        "$key must be 0 (off) or between $MIN_REPORTER_INTERVAL_SECONDS and $MAX_REPORTER_INTERVAL_SECONDS (was \"$seconds\")"
    }
    return seconds.takeIf { it != 0 }
}

/** `telemetry.sampleIntervalSeconds` bounds (the sampler's cadence, `telemetry/TelemetryConfig.kt`; read here for the timers' window). */
internal const val MIN_TELEMETRY_SAMPLE_INTERVAL_SECONDS = 60
internal const val MAX_TELEMETRY_SAMPLE_INTERVAL_SECONDS = 1800

/** One hour: the sample interval must divide it, so an hour's raw samples tile it exactly (the stored `resolution` is the interval). */
private const val SECONDS_PER_HOUR = 3600

/** The request timers never keep more than this much history, whatever the sample interval (see [TelemetryRegistry]). */
internal const val MAX_TIMER_WINDOW_SECONDS = 300L

/**
 * `telemetry.sampleIntervalSeconds` (env `TELEMETRY_SAMPLE_INTERVAL_SECONDS`, default 300), boot-validated: an integer in
 * [MIN_TELEMETRY_SAMPLE_INTERVAL_SECONDS]..[MAX_TELEMETRY_SAMPLE_INTERVAL_SECONDS] that divides 3600. Read by this plugin because
 * the registry's request timers are windowed by it ([TelemetryRegistry]) and the telemetry package sits ABOVE `plugins/` (nothing
 * may import it from here), so the one bound lives here and `telemetry/TelemetryConfig.kt` calls it.
 */
internal fun telemetrySampleIntervalSeconds(config: ApplicationConfig): Int {
    val key = "telemetry.sampleIntervalSeconds"
    val seconds = boundedIntProperty(
        config, key, min = MIN_TELEMETRY_SAMPLE_INTERVAL_SECONDS, max = MAX_TELEMETRY_SAMPLE_INTERVAL_SECONDS,
    )
    require(SECONDS_PER_HOUR % seconds == 0) { "$key must divide 3600, so the samples tile an hour (was \"$seconds\")" }
    return seconds
}

/** The timers' window: the sample interval, capped at [MAX_TIMER_WINDOW_SECONDS]. */
internal fun timerWindowSeconds(sampleIntervalSeconds: Int): Long = minOf(sampleIntervalSeconds.toLong(), MAX_TIMER_WINDOW_SECONDS)

/**
 * The Dropwizard registry whose ROUTE timers keep a TRUE sliding window ([SlidingTimeWindowArrayReservoir], [timerWindowSeconds]:
 * the last sample interval, at most 5 minutes) instead of Dropwizard's default exponentially-decaying reservoir, whose percentiles
 * lag a burst for minutes: the telemetry sampler reads p50/p95 of the window since its previous tick from the route timers
 * (`ktor.calls.<route>.timer`). Ktor's global `ktor.calls.duration` timer ([KTOR_CALLS_DURATION]) keeps the default reservoir: nothing
 * reads it windowed, and a second sliding timer would double the cost. The price is memory: a sliding reservoir keeps every
 * measurement of its window (two longs, ~16 bytes, per update) until it ages out, so a route timer holds about
 * `requests/s x window x 16 B` - at a sustained 2,000 requests/s for the full 300 s window that is 600,000 updates, about 10 MB,
 * against the 256 MiB heap; a typical Toadie (a few requests/s) holds a few KB. Ktor's plugin creates every timer through
 * [MetricRegistry.timer], which is the one method overridden. [clock] is injectable for tests.
 */
class TelemetryRegistry(
    private val windowSeconds: Long,
    private val clock: Clock = Clock.defaultClock(),
    private val maxCallNames: Int = MAX_CALL_METRIC_NAMES,
) : MetricRegistry() {
    private val callNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val capWarned = AtomicBoolean(false)

    private fun newTimer() = Timer(SlidingTimeWindowArrayReservoir(windowSeconds, TimeUnit.SECONDS, clock), clock)

    override fun timer(name: String): Timer = when {
        !register(name) -> newTimer()
        name == KTOR_CALLS_DURATION -> super.timer(name)
        else -> timer(name) { newTimer() }
    }

    override fun meter(name: String): Meter = if (register(name)) super.meter(name) else Meter(clock)

    /**
     * Whether [name] may be a REGISTERED instrument (a registered one is never evicted). Ktor's `DropwizardMetrics` (3.6) names its
     * per-route instruments `ktor.calls.<route>.meter` and `.timer` (both created when a call is ROUTED) and `.<status>` (when the
     * response is sent), where an UNROUTED call's `<route>` is `request.uri + "/(method:X)"` - client-controlled, query string
     * included. A registered instrument is never evicted, so an unauthenticated client could mint one per distinct URL. Hence:
     * - a name holding a `?` is never registered;
     * - past the fixed global names ([KTOR_CALLS_DURATION], `active`, `exceptions`, `ktor.calls.status.<code>`), a family name is
     *   registered only when it IS a route's `.meter` (only a routed call creates one) or its route's `.meter` is already
     *   registered - so a flood of unrouted URLs registers nothing and cannot crowd the cap out from the routes hit later;
     * - the family is capped at [maxCallNames] names (routed names are ~4 per route: a few hundred), with one WARN.
     * Past any of these the caller gets a detached instrument that counts nothing anyone can read and dies with the call. Names
     * outside the family are untouched.
     */
    private fun register(name: String): Boolean {
        if (!name.startsWith(KTOR_CALLS_FAMILY)) return true
        if (name in callNames) return true
        if (name in GLOBAL_CALL_NAMES || GLOBAL_STATUS_NAME.matches(name)) return true
        return admitted(name) && claimSlot(name)
    }

    /** A per-route name Ktor 3.6 derives from a ROUTED call: no `?`, and either a `.meter` or an instrument of a registered route. */
    private fun admitted(name: String): Boolean =
        '?' !in name && (name.endsWith(METER_SUFFIX) || routeOf(name)?.let { (it + METER_SUFFIX) in callNames } == true)

    /** Takes one of the [maxCallNames] slots for [name]; the first refusal for want of one is a WARN. */
    private fun claimSlot(name: String): Boolean {
        if (callNames.size >= maxCallNames) {
            if (capWarned.compareAndSet(false, true)) {
                LoggerFactory.getLogger(TelemetryRegistry::class.java)
                    .warn("The request metrics reached {} registered names; further ones are not recorded", maxCallNames)
            }
            return false
        }
        callNames.add(name)
        return true
    }

    /** The `ktor.calls.<route>` part of a per-route `.timer` or `.<3-digit status>` name; null for any other shape. */
    private fun routeOf(name: String): String? =
        if (name.endsWith(TIMER_SUFFIX)) name.removeSuffix(TIMER_SUFFIX) else ROUTE_STATUS_NAME.matchEntire(name)?.groupValues?.get(1)
}

private const val METER_SUFFIX = ".meter"
private const val TIMER_SUFFIX = ".timer"
private val ROUTE_STATUS_NAME = Regex("""(.+)\.\d{3}""")
private val GLOBAL_STATUS_NAME = Regex("""ktor\.calls\.status\.\d{3}""")

/** Ktor's `DropwizardMetrics` base name (the plugin default) plus its dot: the family whose names this registry bounds. */
internal const val KTOR_CALLS_FAMILY = "ktor.calls."

/** Ktor's global duration timer: it keeps the default reservoir (see [TelemetryRegistry]). */
internal const val KTOR_CALLS_DURATION = "ktor.calls.duration"

/** The plugin's other fixed global names, created once at install. */
private val GLOBAL_CALL_NAMES = setOf(KTOR_CALLS_DURATION, "ktor.calls.active", "ktor.calls.exceptions")

/** The most `ktor.calls.` names a [TelemetryRegistry] registers (routed series are ~4 per route: well under it). */
const val MAX_CALL_METRIC_NAMES = 2_000

/**
 * Published by [configureMonitoring]: the Dropwizard registry Ktor's request meters write into. The telemetry sampler reads the
 * timers from it (`telemetry/`); the registry is the seam, so nothing below `telemetry/` imports that package.
 */
val MetricRegistryKey = AttributeKey<MetricRegistry>("MetricRegistry")

/** The `metrics` log reporter once started, with the period in seconds it was started at (absent when the reporter is off). */
class StartedReporter(val reporter: Slf4jReporter, val periodSeconds: Long)

/** Published by [configureMonitoring] only when `monitoring.reporterIntervalSeconds` is not 0: `MonitoringReporterConfigTest`'s seam. */
val MetricsReporterKey = AttributeKey<StartedReporter>("MetricsReporter")

fun Application.configureMonitoring() {
    val intervalSeconds = reporterIntervalSeconds(environment.config)
    val telemetryRegistry = TelemetryRegistry(timerWindowSeconds(telemetrySampleIntervalSeconds(environment.config)))
    attributes.put(MetricRegistryKey, telemetryRegistry)
    var reporter: Slf4jReporter? = null
    install(DropwizardMetrics) {
        registry = telemetryRegistry
        // The default also registers Dropwizard's JVM gauge sets, whose thread-states gauge dumps every thread on each
        // reporter run; the telemetry sampler reads the MXBeans itself (threadCount, no dump).
        registerJvmMetricSets = false
        if (intervalSeconds != null) {
            val built = Slf4jReporter.forRegistry(registry)
                .outputTo(LoggerFactory.getLogger("metrics"))
                // Defence in depth: a name with a `?` is a request URI, query string included ([TelemetryRegistry] never registers one).
                .filter { name, _ -> '?' !in name }
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build()
            built.start(intervalSeconds.toLong(), TimeUnit.SECONDS)
            reporter = built
            this@configureMonitoring.attributes.put(MetricsReporterKey, StartedReporter(built, intervalSeconds.toLong()))
        }
    }
    // The reporter owns a scheduler thread — stop it with the application, or every
    // testApplication block (and every redeploy) leaks one.
    monitor.subscribe(ApplicationStopped) { reporter?.stop() }
    install(CallId) {
        header(HttpHeaders.XRequestId)
        // Client-supplied ids reach logs and OTel attributes, so cap them: ≤64 URL-safe
        // chars (UUIDs and trace ids fit); anything else is ignored, not an error.
        verify { callId: String ->
            callId.length in 1..64 && callId.all { it.isLetterOrDigit() || it in "-_." }
        }
    }
}
