package ch.nokillswit

import ch.nokillswit.telemetry.MAX_LABEL_LENGTH
import ch.nokillswit.telemetry.MetricDef
import ch.nokillswit.telemetry.MetricGroup
import ch.nokillswit.telemetry.MetricKind
import ch.nokillswit.telemetry.MetricUnit
import ch.nokillswit.telemetry.Reading
import ch.nokillswit.telemetry.TelemetryMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `telemetry/TelemetryMetrics.kt`: the registry's names are unique and fit the `metric` column, labels are cut to theirs. */
class TelemetryMetricsTest {
    @Test
    fun `every metric name is unique and within the column width`() {
        val names = TelemetryMetrics.all.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate metric names: $names")
        assertEquals(emptyList(), names.filter { it.length > 48 })
    }

    @Test
    fun `a too long metric name is refused and a too long label is truncated`() {
        assertFailsWith<IllegalArgumentException> { MetricDef("x".repeat(49), MetricKind.GAUGE, MetricUnit.COUNT, MetricGroup.JVM) }
        assertEquals(MAX_LABEL_LENGTH, Reading(TelemetryMetrics.THREADS, 1.0, "l".repeat(500)).label.length)
    }
}
