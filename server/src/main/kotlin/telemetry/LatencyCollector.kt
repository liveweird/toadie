package ch.nokillswit.telemetry

import ch.nokillswit.plugins.KTOR_CALLS_FAMILY
import com.codahale.metrics.MetricFilter
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.UniformSnapshot

private const val TIMER_SUFFIX = "timer"
private const val API_PREFIX = "/api/"
private const val INTEGRATION_PREFIX = "/integration/"
private const val TELEMETRY_API = "/api/v1/telemetry"
private const val NANOS_PER_MILLI = 1_000_000.0
private const val STATUS_DIGITS = 3
private const val SERVER_ERROR_FLOOR = 500
private const val RATE_LIMITED = 429

/** The `*` label: every `/api/` and `/integration/` route but the telemetry's own. */
internal const val TOTAL_LABEL = "*"

private val SELECTOR = Regex("""\([^)]*\)""")
private val SLASHES = Regex("/{2,}")

/**
 * The route name Ktor puts in a series name, normalised to the plain path: Ktor 3.6 names a route after its whole selector chain
 * (`/(authenticate "default")/api/v1/entities/graph/(method:GET)`), so every parenthesised selector is dropped and the doubled and
 * trailing slashes collapse (`/api/v1/entities/graph`). Path parameters (`{id}`) stay as written - never a concrete identifier.
 * Pinned by `LatencyCollectorTest`, so a Ktor upgrade that renames the series fails a test instead of silently emptying the panel.
 */
internal fun normaliseRoute(raw: String): String {
    val path = raw.replace(SELECTOR, "").replace(SLASHES, "/")
    return if (path.length > 1) path.trimEnd('/') else path
}

/** The two per-route series of Ktor's call metrics this reads. */
internal enum class SeriesKind { TIMER, STATUS }

/**
 * One Ktor series name split into its route and its kind: `ktor.calls.<route>.timer` is the route's duration timer and
 * `.<3-digit status>` the meter of that status' responses (Ktor also keeps `.meter`, the all-status meter marked when the routed call
 * STARTS, and the global `ktor.calls.status.<code>` / `.duration` / `.active` / `.exceptions`, none of which this reads). [rawRoute] is
 * the route as written in the name (the key a route's timer and status meters share); [route] is [normaliseRoute] of it.
 */
internal class KtorSeries(val rawRoute: String, val kind: SeriesKind, val status: Int?) {
    val route: String by lazy { normaliseRoute(rawRoute) }
}

/**
 * Splits a registry name, or null when it is not a per-route timer / status series of Ktor's call metrics. A name whose route holds a
 * `?` is never a routed series (it is the URI-named fallback of a response Ktor did not route, which `TelemetryRegistry` never registers).
 */
internal fun parseKtorSeries(name: String): KtorSeries? {
    if (!name.startsWith(KTOR_CALLS_FAMILY)) return null
    val dot = name.lastIndexOf('.')
    if (dot <= KTOR_CALLS_FAMILY.length) return null
    val suffix = name.substring(dot + 1)
    val rawRoute = name.substring(KTOR_CALLS_FAMILY.length, dot)
    if ('?' in rawRoute) return null
    return when {
        suffix == TIMER_SUFFIX -> KtorSeries(rawRoute, SeriesKind.TIMER, null)
        suffix.length == STATUS_DIGITS && suffix.all(Char::isDigit) -> KtorSeries(rawRoute, SeriesKind.STATUS, suffix.toInt())
        else -> null
    }
}

/** Every `/api/` and `/integration/` route but the telemetry's own: /healthz, /readyz and the static/SPA routes are not API traffic. */
internal fun isCounted(route: String) =
    (route.startsWith(API_PREFIX) || route.startsWith(INTEGRATION_PREFIX)) &&
        route != TELEMETRY_API && !route.startsWith("$TELEMETRY_API/")

private val LABELLED_SUFFIXES = listOf("/graph", "/errors", "/export", "/import", "/import/check", "/fetch", "-documents")

/**
 * Whether a counted route also gets a label of its own (the rest count in [TOTAL_LABEL] only): the heavy routes, found by SHAPE rather
 * than by a list, so a future graph/import/fetch route is labelled with no code change - a normalised path ending in one of
 * [LABELLED_SUFFIXES], or anything under `/integration/`. Bounded by construction (a route template, `{id}` literal; ~20 today).
 */
internal fun isLabelled(route: String) = route.startsWith(INTEGRATION_PREFIX) || LABELLED_SUFFIXES.any(route::endsWith)

/** What one label (a route or the total) saw since the previous collection. */
private class Window {
    var count = 0L
    var serverErrors = 0L
    var rateLimited = 0L
    val nanos = mutableListOf<LongArray>()
}

/** The windows a collection fills, one per label: every counted route feeds the total, a labelled route its own as well. */
private class Windows {
    private val byLabel = HashMap<String, Window>()

    fun each(route: String, apply: (Window) -> Unit) {
        if (!isCounted(route)) return
        apply(byLabel.getOrPut(TOTAL_LABEL) { Window() })
        if (isLabelled(route)) apply(byLabel.getOrPut(route) { Window() })
    }

    fun readings(): List<Reading> = byLabel.flatMap { (label, window) -> window.readings(label) }
}

private fun Window.readings(label: String): List<Reading> = buildList {
    if (count > 0) {
        add(Reading(TelemetryMetrics.HTTP_COUNT, count.toDouble(), label))
        if (nanos.isNotEmpty()) {
            val snapshot = UniformSnapshot(nanos.concat())
            add(Reading(TelemetryMetrics.HTTP_P50, snapshot.median / NANOS_PER_MILLI, label))
            add(Reading(TelemetryMetrics.HTTP_P95, snapshot.get95thPercentile() / NANOS_PER_MILLI, label))
        }
    }
    if (serverErrors > 0) add(Reading(TelemetryMetrics.HTTP_5XX, serverErrors.toDouble(), label))
    if (rateLimited > 0) add(Reading(TelemetryMetrics.HTTP_429, rateLimited.toDouble(), label))
}

private fun List<LongArray>.concat(): LongArray {
    val out = LongArray(sumOf { it.size })
    var at = 0
    for (part in this) {
        part.copyInto(out, at)
        at += part.size
    }
    return out
}

/**
 * The request figures of the process (`.claude/docs/observability.md` "Telemetry"): reads Ktor's Dropwizard TIMERS and status
 * METERS out of [registry] (`MetricRegistryKey`; never its gauges) every tick, and turns them into rows per labelled route
 * ([isLabelled]) plus the [TOTAL_LABEL] total.
 *
 * `http.count` and the status counts are DELTAS of the cumulative Dropwizard counts against the previous tick's value, kept here in
 * memory (the first tick counts everything since the process started; names absent from a tick are forgotten, so the map stays as
 * small as the registry); `http.p50`/`http.p95` come from the timers' windowed snapshots (`TelemetryRegistry`: a true sliding window
 * of the sample interval) of the timers that saw a request, merged into one set of observations per label so the total's percentile is
 * a real one, not a mean of percentiles. A label without a request, 5xx or 429 in the interval has no row.
 */
class LatencyCollector(private val registry: MetricRegistry) {
    private var previous = HashMap<String, Long>()
    private var pending: HashMap<String, Long>? = null

    /** Makes the baseline of the last [collect] the committed one: called by the sampler after that collect's readings were stored. */
    @Synchronized
    fun commit() {
        pending?.let { previous = it }
        pending = null
    }

    @Synchronized
    fun collect(): List<Reading> {
        val seen = HashMap<String, Long>()
        val windows = Windows()
        val timerDeltas = HashMap<String, Long>()
        registry.getTimers(MetricFilter { name, _ -> name.startsWith(KTOR_CALLS_FAMILY) && name.endsWith(".$TIMER_SUFFIX") })
            .forEach { (name, timer) ->
                val series = parseKtorSeries(name)?.takeIf { it.kind == SeriesKind.TIMER } ?: return@forEach
                val delta = deltaOf(seen, name, timer.count)
                timerDeltas[series.rawRoute] = delta
                // Evaluated once: a snapshot of a sliding window copies its values.
                if (delta > 0L) {
                    val nanos = timer.snapshot.values
                    windows.each(series.route) { it.count += delta; it.nanos += nanos }
                }
            }
        registry.getMeters(MetricFilter { name, _ -> name.startsWith(KTOR_CALLS_FAMILY) })
            .forEach { (name, meter) ->
                val series = parseKtorSeries(name)?.takeIf { it.kind == SeriesKind.STATUS } ?: return@forEach
                if (series.rawRoute !in timerDeltas) return@forEach // only a ROUTED call has a timer
                val delta = deltaOf(seen, name, meter.count)
                val status = series.status ?: return@forEach
                windows.each(series.route) {
                    if (status == RATE_LIMITED) it.rateLimited += delta
                    if (status >= SERVER_ERROR_FLOOR) it.serverErrors += delta
                }
            }
        pending = seen
        return windows.readings()
    }

    /** The growth of a cumulative count since the previous collection (never negative; a series seen first counts from zero). */
    private fun deltaOf(seen: MutableMap<String, Long>, name: String, current: Long): Long {
        seen[name] = current
        return (current - (previous[name] ?: 0L)).coerceAtLeast(0L)
    }
}
