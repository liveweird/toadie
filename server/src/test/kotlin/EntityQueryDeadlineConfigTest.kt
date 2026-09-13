package ch.nokillswit

import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * The `entityQuery.deadlineMillis` fail-closed check (`infra/db/Database.kt`, phase 7's in-memory
 * entity-query evaluator — `entityquery/QueryBudget.kt`): the configured deadline must fall
 * within `1..MAX_ENTITY_QUERY_DEADLINE_MILLIS`, checked unconditionally at boot regardless of
 * mode — the `JqDeadlineConfigTest` idiom, one level down.
 */
class EntityQueryDeadlineConfigTest {

    @Test
    fun `an entity query deadline outside 1 to 60000 refuses to start`() = testApplication {
        configureApp("entityQuery.deadlineMillis" to "0")
        assertStartupFails("entityQuery.deadlineMillis") { startApplication() }
    }
}
