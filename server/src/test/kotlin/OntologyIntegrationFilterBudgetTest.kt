package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.entities.EntityFilter
import ch.nokillswit.entities.EntityGraphFilter
import ch.nokillswit.entities.EntityReadLedger
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.OntologyReadBudget
import ch.nokillswit.entities.ontologyErrors
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OntologyIntegrationFilterBudgetTest {
    private fun page() = PageRequest(1, 20, listOf(SortField("id", false)))

    @Test
    fun `integration inherited team filters charge their first target snapshot`() = testApplication {
        usePostgresTestcontainer()
        val owner = TestUsers.seed(uniqueEmail("gql-filter-budget"), "pw")
        val marker = "gql-filter-${UUID.randomUUID()}"
        val parentBlueprint = "$marker-parent"
        val childBlueprint = "$marker-child"
        val team = "$marker-team"
        val missingTeam = "$marker-missing-team"
        val parent = "$marker-parent-entity"
        val child = "$marker-child-entity"

        try {
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = parentBlueprint,
                    title = "Parent",
                    schema = BlueprintSchema(),
                    ownership = OwnershipDefinition(type = "Direct"),
                ),
                owner,
            )
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = childBlueprint,
                    title = "Child",
                    schema = BlueprintSchema(),
                    relations = mapOf(
                        "parent" to RelationDefinition(
                            title = "Parent",
                            target = parentBlueprint,
                            required = false,
                            many = false,
                        ),
                    ),
                    ownership = OwnershipDefinition(type = "Inherited", path = "parent"),
                ),
                owner,
            )
            TestEntities.service.create(EntityRequest(SYSTEM_TEAM_BLUEPRINT, team, "Team"), owner)
            TestEntities.service.create(
                EntityRequest(parentBlueprint, parent, "Parent", team = JsonPrimitive(team)),
                owner,
            )
            TestEntities.service.create(
                EntityRequest(
                    childBlueprint,
                    child,
                    "Child",
                    relations = buildJsonObject { put("parent", parent) },
                ),
                owner,
            )

            val listFilter = EntityFilter(childBlueprint, null, missingTeam)
            // Even an empty page reads the system team/user targets. Measure that legitimate
            // REST baseline instead of assuming the shared test workspace contains no targets.
            val ordinaryListLedger = EntityReadLedger()
            val ordinaryListService = TestEntities.tunedService(readLedger = ordinaryListLedger)
            assertEquals(0L, ordinaryListService.list(listFilter, page()).total)
            assertTrue(ordinaryListLedger.peak > 0)
            val listLedger = EntityReadLedger(capacity = ordinaryListLedger.peak)
            val listService = TestEntities.tunedService(readLedger = listLedger)

            assertEquals(0L, listService.list(listFilter, page()).total)
            assertFailsWith<BadRequestException> {
                listService.list(listFilter, page(), OntologyReadBudget())
            }

            val errorsFilter = EntityGraphFilter(listOf(childBlueprint), null, missingTeam)
            val baselineLedger = EntityReadLedger()
            val baselineService = TestEntities.tunedService(readLedger = baselineLedger)
            val baselineReport = baselineService.ontologyErrors(errorsFilter)
            assertEquals(0, baselineReport.checkedEntities)
            assertTrue(baselineLedger.peak > 0)

            val constrainedLedger = EntityReadLedger(capacity = baselineLedger.peak)
            val constrainedService = TestEntities.tunedService(readLedger = constrainedLedger)
            assertEquals(0, constrainedService.ontologyErrors(errorsFilter).checkedEntities)
            assertFailsWith<BadRequestException> {
                constrainedService.ontologyErrors(errorsFilter, OntologyReadBudget())
            }
        } finally {
            TestEntities.remove(child, parent, team)
            TestBlueprints.remove(childBlueprint, parentBlueprint)
        }
    }
}
