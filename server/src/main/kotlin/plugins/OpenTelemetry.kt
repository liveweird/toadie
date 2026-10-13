package ch.nokillswit.plugins

import ch.nokillswit.getOpenTelemetry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import java.util.concurrent.TimeUnit

/** The SDK this application instance owns, published so `OpenTelemetryShutdownTest` can observe its shutdown. */
val OpenTelemetrySdkKey = AttributeKey<OpenTelemetrySdk>("OpenTelemetrySdk")

fun Application.configureOpenTelemetry() {
    val openTelemetry = getOpenTelemetry(serviceName = "toadie")
    // Wire the Logback OTel appender to this SDK; flushes the pre-install buffer of boot logs.
    OpenTelemetryAppender.install(openTelemetry)
    attributes.put(OpenTelemetrySdkKey, openTelemetry)
    // Flushes the batched exporters (the logs pipeline) and releases the SDK's worker threads; bounded
    // so a stuck collector cannot hang a redeploy. Mirrors the pool/reporter disposal on stop.
    monitor.subscribe(ApplicationStopped) { openTelemetry.shutdown().join(5, TimeUnit.SECONDS) }

    install(KtorServerTelemetry) {
        setOpenTelemetry(openTelemetry)
        capturedRequestHeaders(HttpHeaders.UserAgent)
        // No spanKindExtractor: these are HTTP SERVER spans and the plugin's default says so.
        // (The Ktor scaffold's template maps POST→PRODUCER/else→CLIENT, which mislabels
        // every span for trace consumers — deliberately removed.)
        attributesExtractor {
            onStart {
                attributes.put("start-time", System.currentTimeMillis())
            }
            onEnd {
                attributes.put("end-time", System.currentTimeMillis())
            }
        }
    }
}
