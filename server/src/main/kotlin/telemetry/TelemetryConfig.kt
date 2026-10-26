package ch.nokillswit.telemetry

import ch.nokillswit.plugins.boundedIntProperty
import ch.nokillswit.plugins.telemetrySampleIntervalSeconds
import io.ktor.server.config.ApplicationConfig

/** `telemetry.storageIntervalSeconds` bounds (15 minutes .. one day). */
internal const val MIN_STORAGE_INTERVAL_SECONDS = 900
internal const val MAX_STORAGE_INTERVAL_SECONDS = 86_400

/**
 * The `telemetry.*` block (`application.yaml`, `.claude/docs/observability.md` "Telemetry"). Read and validated on EVERY boot in
 * every mode - a malformed value refuses startup naming its key even when `enabled` is false, the `postgres.pool.*` idiom.
 */
data class TelemetryConfig(
    val enabled: Boolean,
    /**
     * The sample cadence (60..1800, a divisor of 3600): the stored raw `resolution`, and the request timers' window
     * (capped at 5 minutes, `plugins/Monitoring.kt`).
     */
    val sampleIntervalSeconds: Int,
    /** How often the workspace sizes and counts are measured (`StorageCollectors.kt`, claimed by the heavy phase). */
    val storageIntervalSeconds: Int,
) {
    val sampleIntervalMillis: Long get() = sampleIntervalSeconds * 1000L

    /**
     * The budget of a tick's HEAVY phase (the storage collector, which runs after the cluster transaction has committed): two
     * fifths of the interval, so the light phase (at most half) plus the heavy one always fit in an interval.
     */
    val heavyBudgetMillis: Long get() = sampleIntervalMillis * 2 / 5
}

fun readTelemetryConfig(config: ApplicationConfig): TelemetryConfig {
    val enabledKey = "telemetry.enabled"
    val rawEnabled = config.property(enabledKey).getString()
    // true/false in any letter case (an operator's TELEMETRY_ENABLED=False); anything else refuses boot.
    val enabled = rawEnabled.lowercase().toBooleanStrictOrNull()
    require(enabled != null) { "$enabledKey must be true or false (was \"$rawEnabled\")" }
    return TelemetryConfig(
        enabled = enabled,
        sampleIntervalSeconds = telemetrySampleIntervalSeconds(config),
        storageIntervalSeconds = boundedIntProperty(
            config, "telemetry.storageIntervalSeconds", min = MIN_STORAGE_INTERVAL_SECONDS, max = MAX_STORAGE_INTERVAL_SECONDS,
        ),
    )
}
