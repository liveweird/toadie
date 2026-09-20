package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entityquery.SavedEntityQuery
import ch.nokillswit.entityquery.SavedEntityQueryRequest
import ch.nokillswit.entityquery.SavedEntityQueryVisibility
import ch.nokillswit.integration.GraphQLHttpRequest
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(kotlin.ExperimentalUnsignedTypes::class)
class IntegrationOntologyParityTest {
    @Test
    fun `ontology findings include stale entities and broken blueprints but exclude saved queries`() = testApplication {
        configureApp("integration.enabled" to "true")
        startApplication()
        val email = uniqueEmail("gql-parity")
        val owner = TestUsers.seed(email, "pw")
        val (_, key) = TestIntegrationClients.service.create("Ontology parity", owner)
        val admin = authedClient(email, "pw")
        val marker = "gql-parity-${UUID.randomUUID()}"
        val query = admin.post("/api/v1/entity-queries") {
            contentType(ContentType.Application.Json)
            setBody(SavedEntityQueryRequest(
                "$marker-private", SavedEntityQueryVisibility.PRIVATE,
                "MATCH (n:`$marker-missing`) RETURN n",
            ))
        }.body<SavedEntityQuery>()
        try {
            val initial = BlueprintRequest(marker, "Parity", calculationProperties = mapOf(
                "broken" to CalculationPropertyDefinition("Broken", "string", calculation = "not valid jq (("),
                "computed" to CalculationPropertyDefinition("Computed", "number", calculation = "42"),
            ))
            val blueprint = TestBlueprints.service.create(initial, owner)
            val entity = TestEntities.service.create(EntityRequest(marker, marker, "Stale entity"), owner)
            TestBlueprints.service.update(blueprint.id, initial.copy(schema = BlueprintSchema(
                properties = mapOf("required" to PropertyDefinition(type = "string")),
                required = listOf("required"),
            )))
            suspend fun read(document: String): JsonObject {
                val response = jsonClient().post("/integration/graphql") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $key")
                    setBody(GraphQLHttpRequest(document))
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.body<JsonObject>()
                assertNull(body["errors"], body.toString())
                assertFalse(body.toString().contains(query.name))
                return body.getValue("data").jsonObject
            }
            val entities = read("""{ errors(blueprints:["$marker"]) {
                checkedEntities entities { items { id blueprintId blueprint blueprintTitle identifier title team
                findings { code field message } } page pageSize total }
            } }""").getValue("errors").jsonObject
            assertEquals("1", entities.getValue("checkedEntities").jsonPrimitive.content)
            val row = entities.getValue("entities").jsonObject.getValue("items").jsonArray.single().jsonObject
            assertEquals(entity.id.toString(), row.getValue("id").jsonPrimitive.content)
            // The stale finding plus the report-only SOURCE_MISSING (2.9.1 — the entity has no source).
            assertEquals(
                setOf("REQUIRED_MISSING", "SOURCE_MISSING"),
                row.getValue("findings").jsonArray.map { it.jsonObject.getValue("code").jsonPrimitive.content }.toSet(),
            )
            val blueprints = read("""{ errors(blueprints:["$marker"]) {
                blueprints { items { id identifier title findings { code field message } } total }
            } }""").getValue("errors").jsonObject.getValue("blueprints").jsonObject
            assertEquals("1", blueprints.getValue("total").jsonPrimitive.content)
            assertEquals("CALCULATION_COMPILE_FAILED", blueprints.getValue("items").jsonArray.single()
                .jsonObject.getValue("findings").jsonArray.single().jsonObject.getValue("code").jsonPrimitive.content)
            val direct = read("""{ entity(id:"${entity.id}") { properties } blueprint(id:"${blueprint.id}") { identifier } }""")
            assertEquals("42", direct.getValue("entity").jsonObject.getValue("properties")
                .jsonObject.getValue("computed").jsonPrimitive.content)
            assertEquals(marker, direct.getValue("blueprint").jsonObject.getValue("identifier").jsonPrimitive.content)
            val empty = read("""{ errors(blueprints:["$marker-unknown"]) { entities(page:2147483647) { items { id } total } } }""")
            assertTrue(empty.getValue("errors").jsonObject.getValue("entities").jsonObject.getValue("items").jsonArray.isEmpty())
            val rejected = jsonClient().post("/integration/graphql") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $key")
                setBody(GraphQLHttpRequest("{ errors { savedQueries { query } } }"))
            }.body<JsonObject>()
            assertTrue(rejected.getValue("errors").jsonArray.isNotEmpty())
            assertTrue(rejected["data"] == null || rejected["data"] == JsonNull)
            assertFalse(rejected.toString().contains(query.name))
        } finally {
            TestEntities.remove(marker)
            TestBlueprints.remove(marker)
            TestSavedEntityQueries.remove(query.id)
        }
    }
}
