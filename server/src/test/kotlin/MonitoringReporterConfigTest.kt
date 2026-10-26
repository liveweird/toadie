package ch.nokillswit

import ch.nokillswit.plugins.MetricsReporterKey
import ch.nokillswit.plugins.reporterIntervalSeconds
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `monitoring.reporterIntervalSeconds` (`plugins/Monitoring.kt`, env `METRICS_REPORT_INTERVAL_SECONDS`): `0` turns the
 * Dropwizard `Slf4jReporter` off, otherwise 30..3600 s; anything else refuses startup naming the key. The default ships as 300
 * in `application.yaml` (it was a fixed 10 s before V46's telemetry change).
 */
class MonitoringReporterConfigTest {

    private val key = "monitoring.reporterIntervalSeconds"

    private fun parsed(value: String) = reporterIntervalSeconds(MapApplicationConfig(key to value))

    @Test
    fun `zero turns the reporter off and the range edges are accepted`() {
        assertNull(parsed("0"))
        assertEquals(30, parsed("30"))
        assertEquals(300, parsed("300"))
        assertEquals(3600, parsed("3600"))
    }

    @Test
    fun `the shipped default starts the reporter at five minutes`() = testApplication {
        configureApp()
        startApplication()
        assertEquals(300, reporterIntervalSeconds(application.environment.config))
        val started = assertNotNull(application.attributes.getOrNull(MetricsReporterKey), "a reporter was started")
        assertEquals(300L, started.periodSeconds, "the period handed to Slf4jReporter.start")
    }

    @Test
    fun `a configured interval is the period the reporter is started with`() = testApplication {
        configureApp(key to "45")
        startApplication()
        assertEquals(45L, assertNotNull(application.attributes.getOrNull(MetricsReporterKey)).periodSeconds)
    }

    @Test
    fun `a zero interval boots and starts no reporter`() = testApplication {
        configureApp(key to "0")
        startApplication() // must not throw
        assertNull(reporterIntervalSeconds(application.environment.config))
        assertNull(application.attributes.getOrNull(MetricsReporterKey), "no Slf4jReporter was built or started")
    }

    @Test
    fun `a malformed or out-of-range interval refuses to start`() {
        listOf("1", "10", "29", "3601", "-1", "abc", "").forEach { value ->
            testApplication {
                configureApp(key to value)
                assertStartupFails(key) { startApplication() }
            }
        }
    }
}
