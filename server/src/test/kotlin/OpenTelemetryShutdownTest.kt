package ch.nokillswit

import ch.nokillswit.plugins.OpenTelemetrySdkKey
import io.ktor.server.testing.testApplication
import io.opentelemetry.sdk.OpenTelemetrySdk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `plugins/OpenTelemetry.kt` shuts the SDK down on `ApplicationStopped` (`.claude/docs/observability.md`),
 * flushing the batched exporters. Observable without a mock: a started SDK's tracer hands out
 * recording spans, a shut-down SDK's tracer hands out the no-op span.
 */
class OpenTelemetryShutdownTest {

    private fun OpenTelemetrySdk.startsRecordingSpan(): Boolean =
        getTracer("shutdown-test").spanBuilder("probe").startSpan().let { span ->
            span.isRecording.also { span.end() }
        }

    @Test
    fun `the OpenTelemetry SDK is shut down when the application stops`() {
        lateinit var sdk: OpenTelemetrySdk
        testApplication {
            configureApp()
            startApplication()
            sdk = application.attributes[OpenTelemetrySdkKey]
            assertTrue(sdk.startsRecordingSpan(), "the SDK must be live while the application runs")
        }
        // testApplication{} has returned, so the embedded application stopped and fired ApplicationStopped.
        assertFalse(sdk.startsRecordingSpan(), "the SDK must be shut down once the application stopped")
    }
}
