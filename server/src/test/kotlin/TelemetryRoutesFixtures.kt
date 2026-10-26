package ch.nokillswit

import ch.nokillswit.telemetry.HOURLY_RESOLUTION
import ch.nokillswit.telemetry.MetricDef
import ch.nokillswit.telemetry.SampleRow
import ch.nokillswit.telemetry.TelemetryStore
import ch.nokillswit.telemetry.insertSampleRows

internal const val TELE_RAW_SECONDS = 300
internal const val TELE_MINUTE = 60_000L
internal const val TELE_HOUR = 3_600_000L
internal const val TELE_DAY = 24 * TELE_HOUR

/** A raw (sample-interval) row of [metric]; `n` 1, `max` defaults to [avg]. */
internal fun rawRow(
    pid: Int,
    ts: Long,
    metric: MetricDef,
    avg: Double,
    label: String = "",
    max: Double = avg,
    resolution: Int = TELE_RAW_SECONDS,
) = SampleRow(resolution, ts, pid, metric.name, label, 1, avg, max)

/** An hourly rollup row of [metric] folding [n] raw samples. */
internal fun hourlyRow(pid: Int, ts: Long, metric: MetricDef, avg: Double, max: Double, n: Int = 12, label: String = "") =
    SampleRow(HOURLY_RESOLUTION, ts, pid, metric.name, label, n, avg, max)

internal suspend fun TelemetryStore.put(rows: List<SampleRow>) {
    transaction { insertSampleRows(rows) }
}

internal suspend fun TelemetryStore.put(vararg rows: SampleRow) = put(rows.toList())

/** Registers a process last seen at [lastSeen] (a unique instance unless named), with an optional container limit. */
internal suspend fun TelemetryStore.seedProcess(
    lastSeen: Long,
    startedAt: Long = lastSeen - TELE_HOUR,
    limitBytes: Long? = null,
    instance: String = "test-${java.util.UUID.randomUUID()}",
): Int = registerProcess(testIdentity(instance = instance, startedAt = startedAt), lastSeen, limitBytes)
