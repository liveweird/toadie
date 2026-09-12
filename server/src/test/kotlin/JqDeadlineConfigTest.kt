package ch.nokillswit

import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * The `computed.jq.deadlineMillis` fail-closed check (`infra/db/Database.kt`, phase 5's bounded
 * jq evaluator — `entities/JqCalculation.kt`): the configured deadline must fall within
 * `1..MAX_JQ_DEADLINE_MILLIS`, checked unconditionally at boot regardless of mode.
 */
class JqDeadlineConfigTest {

    @Test
    fun `a jq deadline outside 1 to 60000 refuses to start`() = testApplication {
        configureApp("computed.jq.deadlineMillis" to "0")
        assertStartupFails("computed.jq.deadlineMillis") { startApplication() }
    }
}
