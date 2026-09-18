package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.entities.EntityFilter
import ch.nokillswit.entities.EntityGraphFilter
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.OntologyReadBudget
import ch.nokillswit.entities.ontologyErrors
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OntologyIntegrationReadTest {
    private fun page(size: Int) = PageRequest(1, size, listOf(SortField("id", false)))

    @Test
    fun `computed arrays are charged before a whole page of responses accumulates`() = testApplication {
        usePostgresTestcontainer()
        val owner = TestUsers.seed(uniqueEmail("gql-computed-budget"), "pw")
        val marker = "gql-computed-${UUID.randomUUID()}"
        TestBlueprints.service.create(BlueprintRequest(
            marker, "Computed budget", calculationProperties = (1..20).associate { index ->
                "computed$index" to CalculationPropertyDefinition(
                    title = "Generated array", type = "array", calculation = "[range(0;10000) | 0]",
                )
            },
        ), owner)
        try {
            val entity = TestEntities.service.create(EntityRequest(marker, marker, "Small stored entity"), owner)
            assertEquals(20, entity.properties.size)
            assertFailsWith<BadRequestException> { TestEntities.service.read(entity.id, OntologyReadBudget()) }
            assertFailsWith<BadRequestException> {
                TestEntities.service.list(EntityFilter(marker, null), page(1), OntologyReadBudget())
            }
        } finally {
            TestEntities.remove(marker)
            TestBlueprints.remove(marker)
        }
    }

    @Test
    fun `integration entity pages refuse cumulative documents and permit a smaller page`() = testApplication {
        usePostgresTestcontainer()
        val owner = TestUsers.seed(uniqueEmail("gql-doc-budget"), "pw")
        val marker = "gql-budget-${UUID.randomUUID()}"
        TestBlueprints.service.create(
            BlueprintRequest(marker, "Page budget", schema = BlueprintSchema(
                properties = mapOf("text" to PropertyDefinition(type = "string")),
            )), owner,
        )
        val identifiers = (1..5).map { "$marker-$it" }
        try {
            for (identifier in identifiers) {
                TestEntities.service.create(EntityRequest(
                    blueprint = marker, identifier = identifier, title = identifier,
                    properties = buildJsonObject { put("text", "x".repeat(240_000)) },
                ), owner)
            }
            val filter = EntityFilter(blueprint = marker, q = null)
            assertEquals(5, TestEntities.service.list(filter, page(100)).items.size)
            assertFailsWith<BadRequestException> {
                TestEntities.service.list(filter, page(100), OntologyReadBudget())
            }
            assertEquals(1, TestEntities.service.list(filter, page(1), OntologyReadBudget()).items.size)
        } finally {
            identifiers.forEach { TestEntities.remove(it) }
            TestBlueprints.remove(marker)
        }
    }

    @Test
    fun `integration list read and errors charge the blueprint registry before retaining it`() = testApplication {
        usePostgresTestcontainer()
        val owner = TestUsers.seed(uniqueEmail("gql-schema-budget"), "pw")
        val marker = "gql-schemas-${UUID.randomUUID()}"
        val identifiers = (1..5).map { "$marker-$it" }
        val entityIdentifier = "$marker-entity"
        try {
            for (identifier in identifiers) {
                TestBlueprints.service.create(BlueprintRequest(
                    identifier, identifier, schema = BlueprintSchema(properties = mapOf(
                        "text" to PropertyDefinition(type = "string", default = JsonPrimitive("x".repeat(240_000))),
                    )),
                ), owner)
            }
            val entity = TestEntities.service.create(EntityRequest(
                blueprint = identifiers.first(), identifier = entityIdentifier, title = "Small entity",
            ), owner)
            assertFailsWith<BadRequestException> {
                TestEntities.service.list(EntityFilter(blueprint = null, q = null), page(1), OntologyReadBudget())
            }
            assertFailsWith<BadRequestException> {
                TestEntities.service.read(entity.id, OntologyReadBudget())
            }
            assertFailsWith<BadRequestException> {
                TestEntities.service.ontologyErrors(EntityGraphFilter(blueprints = emptyList(), q = null), OntologyReadBudget())
            }
        } finally {
            TestEntities.remove(entityIdentifier)
            identifiers.forEach { TestBlueprints.remove(it) }
        }
    }
}
