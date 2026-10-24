package ch.nokillswit

import ch.nokillswit.plugins.OpenTelemetrySdkKey
import io.ktor.server.testing.testApplication
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `plugins/OpenTelemetry.kt` shuts the SDK down on `ApplicationStopped` (`.claude/docs/observability.md`),
 * flushing the batched exporters. Observable without a mock: `OpenTelemetrySdk.shutdown()` answers a fresh,
 * aggregated result the first time and the shared success singleton on every later call. Spans no longer
 * work as the probe: since SDK 1.67 a tracer provider without span processors (traces exporter `none`, the
 * default) hands out no-op tracers even while live.
 */
class OpenTelemetryShutdownTest {

    private fun OpenTelemetrySdk.wasAlreadyShutDown(): Boolean = shutdown() === CompletableResultCode.ofSuccess()

    @Test
    fun `the OpenTelemetry SDK is shut down when the application stops`() {
        lateinit var sdk: OpenTelemetrySdk
        testApplication {
            configureApp()
            startApplication()
            sdk = application.attributes[OpenTelemetrySdkKey]
        }
        // testApplication{} has returned, so the embedded application stopped and fired ApplicationStopped.
        assertTrue(sdk.wasAlreadyShutDown(), "the SDK must be shut down once the application stopped")
    }

    @Test
    fun `the probe tells a live SDK from a shut-down one`() {
        testApplication {
            configureApp()
            startApplication()
            val sdk = application.attributes[OpenTelemetrySdkKey]
            assertFalse(sdk.wasAlreadyShutDown(), "a running application's SDK must still be live")
            assertTrue(sdk.wasAlreadyShutDown(), "the probe's own shutdown must register")
        }
    }
}
