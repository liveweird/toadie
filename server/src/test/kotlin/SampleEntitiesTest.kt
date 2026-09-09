package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.entities.toDocument
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loads the numbered JSON files under `sample-data/entities` (the browsable Port-style entity
 * set, v1.24.1) through the real API, in dependency order, on top of the sample blueprint set
 * ([SampleBlueprintsTest]'s eight files, loaded here too since entities cannot exist without
 * their blueprint). Proves every file is a valid `POST /api/v1/entities` body under the EXACT
 * validators ([ch.nokillswit.entities.validateEntityRequest] + [ch.nokillswit.entities.entityFindings])
 * — every created row and its re-GET carry NO findings — and that the stored document round
 * trips (`properties`/`relations` equal the request's, after [toDocument]'s null-drop). A second
 * pass pins the "showcase" union over the whole set (`sample-data/README.md`'s promise): every
 * property of every sample blueprint is set on at least one of its entities, every enum value of
 * every enum property is used at least once, the `team` field is used both as a string and as an
 * array, at least one relation value is a (multi-element) array, and at least one relation was
 * sent as an explicit JSON `null`. A third pass proves the file numbering is itself
 * dependency-safe: every relation target's blueprint file index is `<=` the referring entity's
 * file index.
 *
 * Test cwd is `server/` (the Gradle test task's default working directory), so fixtures are read
 * via `../sample-data/{blueprints,entities}`. The blueprint identifiers (`team`, `domain`, …) and
 * entity identifiers (`platform`, `commerce`, …) are plain, but the shared Testcontainers
 * database is fine: this test removes every one of them in `finally` (entities first, then
 * blueprints — the plan's order), and no other test mints these exact identifiers.
 */
class SampleEntitiesTest {

    private fun blueprintFiles(): List<File> = SampleData.numberedFiles("blueprints")
    private fun entityFiles(): List<File> = SampleData.numberedFiles("entities")

    private suspend fun HttpClient.createBlueprintRaw(text: String): HttpResponse =
        post("/api/v1/blueprints") {
            contentType(ContentType.Application.Json)
            setBody(text)
        }

    private suspend fun HttpClient.createEntity(request: EntityRequest): HttpResponse =
        post("/api/v1/entities") {
            contentType(ContentType.Application.Json)
            setBody(blueprintJson.encodeToString(request))
        }

    @Test
    fun `the sample entity set loads in dependency order and round-trips`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("entsample", UserRole.ADMIN)

        val bpFiles = blueprintFiles()
        assertEquals(8, bpFiles.size, "expected the eight numbered sample blueprint files")
        val entFiles = entityFiles()
        assertEquals(8, entFiles.size, "expected the eight numbered sample entity files")

        val blueprintIdentifiers = mutableListOf<String>()
        val entityIdentifiers = mutableListOf<String>()
        val blueprintRequestsByIdentifier = mutableMapOf<String, BlueprintRequest>()
        val blueprintFileIndex = mutableMapOf<String, Int>()

        try {
            bpFiles.forEachIndexed { index, file ->
                val text = file.readText()
                val request = blueprintJson.decodeFromString<BlueprintRequest>(text)
                blueprintIdentifiers += request.identifier
                blueprintRequestsByIdentifier[request.identifier] = request
                blueprintFileIndex[request.identifier] = index

                val create = admin.createBlueprintRaw(text)
                assertEquals(HttpStatusCode.Created, create.status, "POST blueprints/${file.name}: ${create.bodyAsText()}")
            }

            val requestsByFile = mutableListOf<List<EntityRequest>>()

            entFiles.forEachIndexed { fileIndex, file ->
                val requests = blueprintJson.decodeFromString<List<EntityRequest>>(file.readText())
                requestsByFile += requests

                assertDependencyOrder(requests, fileIndex, blueprintRequestsByIdentifier, blueprintFileIndex, file.name)

                requests.forEach { request ->
                    val create = admin.createEntity(request)
                    assertEquals(
                        HttpStatusCode.Created,
                        create.status,
                        "POST entities/${file.name} ${request.identifier}: ${create.bodyAsText()}",
                    )
                    val created = create.body<EntityResponse>()
                    entityIdentifiers += created.identifier
                    assertTrue(
                        created.findings.isEmpty(),
                        "${file.name} ${request.identifier} was created with findings: ${created.findings}",
                    )

                    val get = admin.get("/api/v1/entities/${created.id}")
                    assertEquals(HttpStatusCode.OK, get.status)
                    val reread = get.body<EntityResponse>()
                    assertTrue(reread.findings.isEmpty(), "${file.name} ${request.identifier} reads back with findings: ${reread.findings}")

                    val expected = request.toDocument()
                    assertEquals(expected.properties, reread.properties, "${file.name} ${request.identifier} properties did not round-trip")
                    assertEquals(expected.relations, reread.relations, "${file.name} ${request.identifier} relations did not round-trip")
                }
            }

            assertShowcaseCoverage(requestsByFile, blueprintRequestsByIdentifier)
        } finally {
            TestEntities.remove(*entityIdentifiers.toTypedArray())
            TestBlueprints.remove(*blueprintIdentifiers.toTypedArray())
        }
    }

    /** Every relation target's blueprint must already be loaded — its file index `<=` [fileIndex] (self-relations included). */
    private fun assertDependencyOrder(
        requests: List<EntityRequest>,
        fileIndex: Int,
        blueprintRequestsByIdentifier: Map<String, BlueprintRequest>,
        blueprintFileIndex: Map<String, Int>,
        fileName: String,
    ) {
        requests.forEach { request ->
            val relations = blueprintRequestsByIdentifier.getValue(request.blueprint).relations
            request.relations.forEach { (relationId, value) ->
                if (value == JsonNull) return@forEach
                val target = relations.getValue(relationId).target
                val targetIndex = blueprintFileIndex.getValue(target)
                assertTrue(
                    targetIndex <= fileIndex,
                    "$fileName's ${request.identifier}.$relationId targets blueprint '$target' " +
                        "(file index $targetIndex), which loads AFTER this file (index $fileIndex)",
                )
            }
        }
    }

    /** Pins the union of Port entity features the set claims to demonstrate (`sample-data/README.md`'s table). */
    private fun assertShowcaseCoverage(
        requestsByFile: List<List<EntityRequest>>,
        blueprintRequestsByIdentifier: Map<String, BlueprintRequest>,
    ) {
        val allRequests = requestsByFile.flatten()
        val requestsByBlueprint = allRequests.groupBy { it.blueprint }

        blueprintRequestsByIdentifier.forEach { (blueprintId, blueprintRequest) ->
            val entityRequests = requestsByBlueprint[blueprintId].orEmpty()
            val usedPropertyKeys = entityRequests.flatMap { it.properties.keys }.toSet()
            assertEquals(
                blueprintRequest.schema.properties.keys,
                usedPropertyKeys,
                "every property of blueprint '$blueprintId' must be set at least once across its sample entities",
            )

            blueprintRequest.schema.properties.forEach { (propertyId, propertyDef) ->
                val enum = propertyDef.enum ?: return@forEach
                val usedValues = entityRequests.mapNotNull { (it.properties[propertyId] as? JsonPrimitive)?.content }.toSet()
                assertEquals(
                    enum.map { it.content }.toSet(),
                    usedValues,
                    "every enum value of '$blueprintId.$propertyId' must be used at least once",
                )
            }
        }

        val teamValues = allRequests.mapNotNull { it.team }
        assertTrue(teamValues.any { it is JsonPrimitive }, "the entity-level 'team' field must be used as a string on at least one entity")
        assertTrue(teamValues.any { it is JsonArray }, "the entity-level 'team' field must be used as an array on at least one entity")

        val allRelationValues = allRequests.flatMap { it.relations.values }
        assertTrue(
            allRelationValues.any { it is JsonArray && it.size > 1 },
            "at least one relation value must be a multi-element array",
        )
        assertTrue(allRelationValues.any { it == JsonNull }, "at least one relation must be sent as an explicit JSON null")
    }
}
