package ch.nokillswit

import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.opentelemetry.semconv.ServiceAttributes

// Typed as the concrete SDK (it implements OpenTelemetry) so the caller can shut it down on stop.
fun getOpenTelemetry(serviceName: String): OpenTelemetrySdk =
    AutoConfiguredOpenTelemetrySdk.builder()
        // Defaults via addPropertiesSupplier (lowest precedence) so OTEL_* env vars can override —
        // e.g. flip OTEL_LOGS_EXPORTER=otlp later to ship logs to a collector with no code change.
        .addPropertiesSupplier {
            mapOf(
                "otel.metrics.exporter" to "none",
                "otel.traces.exporter" to "none",
                "otel.logs.exporter" to "console", // interim: System.out; overridable by OTEL_LOGS_EXPORTER
            )
        }
        .addResourceCustomizer { oldResource, _ ->
            oldResource.toBuilder()
                .putAll(oldResource.attributes)
                .put(ServiceAttributes.SERVICE_NAME, serviceName)
                .build()
        }
        .build()
        .openTelemetrySdk
