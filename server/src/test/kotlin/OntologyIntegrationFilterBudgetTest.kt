package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.entities.EntityFilter
import ch.nokillswit.entities.EntityGraphFilter
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.OntologyReadBudget
import ch.nokillswit.entities.ontologyErrors
import ch.nokillswit.infra.concurrency.ReadLedger
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

            // Even an empty page reads the system team/user targets, and an Inherited `team` filter
            // adds its prologue (rowLookupFor → loadReadSet) on the SAME reservation — for REST and
            // integration alike (2.19.0). Measure both instead of assuming the shared workspace is empty.
            val listFilter = EntityFilter(childBlueprint, null, missingTeam)
            val noTeamListLedger = ReadLedger()
            val noTeamList = EntityFilter(childBlueprint, null)
            assertEquals(1L, TestEntities.tunedService(readLedger = noTeamListLedger).list(noTeamList, page()).total)
            val listLedger = ReadLedger()
            assertEquals(0L, TestEntities.tunedService(readLedger = listLedger).list(listFilter, page()).total)
            assertTrue(listLedger.peak > noTeamListLedger.peak, "the team prologue must be reserved on the request's ledger")

            val listFits = TestEntities.tunedService(readLedger = ReadLedger(capacity = listLedger.peak))
            assertEquals(0L, listFits.list(listFilter, page()).total)
            assertEquals(0L, listFits.list(listFilter, page(), OntologyReadBudget()).total)
            val listTight = TestEntities.tunedService(readLedger = ReadLedger(capacity = listLedger.peak - 1))
            assertFailsWith<BadRequestException> { listTight.list(listFilter, page()) }
            assertFailsWith<BadRequestException> { listTight.list(listFilter, page(), OntologyReadBudget()) }

            val errorsFilter = EntityGraphFilter(listOf(childBlueprint), null, missingTeam)
            val noTeamErrorsLedger = ReadLedger()
            TestEntities.tunedService(readLedger = noTeamErrorsLedger)
                .ontologyErrors(EntityGraphFilter(listOf(childBlueprint), "$marker-matches-nothing"))
            val baselineLedger = ReadLedger()
            val baselineReport = TestEntities.tunedService(readLedger = baselineLedger).ontologyErrors(errorsFilter)
            assertEquals(0, baselineReport.checkedEntities)
            assertTrue(baselineLedger.peak > noTeamErrorsLedger.peak)

            val errorsFits = TestEntities.tunedService(readLedger = ReadLedger(capacity = baselineLedger.peak))
            assertEquals(0, errorsFits.ontologyErrors(errorsFilter).checkedEntities)
            assertEquals(0, errorsFits.ontologyErrors(errorsFilter, OntologyReadBudget()).checkedEntities)
            val errorsTight = TestEntities.tunedService(readLedger = ReadLedger(capacity = baselineLedger.peak - 1))
            assertFailsWith<BadRequestException> { errorsTight.ontologyErrors(errorsFilter) }
            assertFailsWith<BadRequestException> { errorsTight.ontologyErrors(errorsFilter, OntologyReadBudget()) }
        } finally {
            TestEntities.remove(child, parent, team)
            TestBlueprints.remove(childBlueprint, parentBlueprint)
        }
    }
}
