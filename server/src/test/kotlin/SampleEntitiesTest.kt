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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loads the numbered JSON files under `sample-data/entities` — the sample landscape for the
 * baseline ontology (v1.25.3; adopting the v1.26.0 system blueprints and real `team` ownership
 * since v1.26.0): the e-commerce/payments catalog `sample-data/catalog-info.yaml` describes,
 * re-told as Port entities of the eleven `sample-data/blueprints/` — through the real API, in
 * dependency order, on top of the blueprint set ([SampleBlueprintsTest]'s files, loaded here too
 * via [SampleData.loadBlueprint] since entities cannot exist without their blueprint —
 * `_team`/`_user` are `PUT` extensions of the V31-seeded rows, every other blueprint a fresh
 * `POST`). Proves every file is a valid `POST /api/v1/entities` body under the EXACT validators
 * ([ch.nokillswit.entities.validateEntityRequest] + [ch.nokillswit.entities.entityFindings]) —
 * every created row and its re-GET carry NO findings — and that the stored document round trips
 * (`properties`/`relations` equal the request's, after [toDocument]'s null-drop). A second pass
 * pins the coverage the set promises (`sample-data/README.md`): every blueprint has entities,
 * every property AND every relation of every blueprint is used at least once (the `_user.team`
 * and `_team.parent` relations included), every value of the registry-mirroring `type` and
 * `lifecycle` enums is used at least once (the dictionary pickers are all checkable), the `team`
 * field is used both as a string and as an array, at least one relation value is a multi-element
 * array, and at least one relation was sent as an explicit JSON `null`. A third pass proves the
 * file numbering is itself dependency-safe: every relation target's blueprint file index is `<=`
 * the referring entity's file index. A fourth pass pins `team` ownership itself: every entity of
 * a Direct-ownership blueprint carries a `team` naming one or more `_team` identifiers from
 * `01-team.json`, and every workload (the one Inherited blueprint) sends no `team` of its own and
 * reads back the exact `team` of the `service` entity its `service` relation names.
 *
 * Test cwd is `server/` (the Gradle test task's default working directory), so fixtures are read
 * via `../sample-data/{blueprints,entities}`. The blueprint identifiers (`_team`, `domain`, …)
 * and entity identifiers (`storefront`, `commerce`, …) are plain, but the shared Testcontainers
 * database is fine: this test removes every entity it created, restores the system blueprints'
 * base shape, then removes every non-system blueprint identifier in `finally` (the plan's order);
 * [SampleBlueprintsTest] loads the same blueprint set but runs in the same single-fork sequence
 * and cleans up the same way.
 */
class SampleEntitiesTest {

    private fun blueprintFiles(): List<File> = SampleData.numberedFiles("blueprints")
    private fun entityFiles(): List<File> = SampleData.numberedFiles("entities")

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
        assertEquals(11, bpFiles.size, "expected the eleven numbered sample blueprint files")
        val entFiles = entityFiles()
        assertEquals(11, entFiles.size, "expected the eleven numbered sample entity files")

        val blueprintIdentifiers = mutableListOf<String>()
        val entityIdentifiers = mutableListOf<String>()
        val blueprintRequestsByIdentifier = mutableMapOf<String, BlueprintRequest>()
        val blueprintFileIndex = mutableMapOf<String, Int>()
        val responseByKey = mutableMapOf<String, EntityResponse>()

        try {
            bpFiles.forEachIndexed { index, file ->
                val text = file.readText()
                val request = blueprintJson.decodeFromString<BlueprintRequest>(text)
                blueprintIdentifiers += request.identifier
                blueprintRequestsByIdentifier[request.identifier] = request
                blueprintFileIndex[request.identifier] = index

                SampleData.loadBlueprint(admin, text)
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

                    responseByKey["${request.blueprint}/${request.identifier}"] = reread
                }
            }

            assertShowcaseCoverage(requestsByFile, blueprintRequestsByIdentifier)
            assertTeamOwnership(requestsByFile, blueprintRequestsByIdentifier, responseByKey)
        } finally {
            TestEntities.remove(*entityIdentifiers.toTypedArray())
            TestBlueprints.restoreSystemBlueprints()
            TestBlueprints.remove(*blueprintIdentifiers.toTypedArray())
        }
    }

    /**
     * v1.26.0: `team` is real ownership. Every entity of a Direct-ownership blueprint must carry
     * a non-null `team` naming one or more `_team` identifiers loaded from `01-team.json`; a
     * workload (the one Inherited blueprint, via `service`) must send NO `team` of its own and
     * its re-`GET` must compute the exact `team` of the `service` entity its `service` relation
     * names (looked up from `10-service.json`'s requests). Blueprints with no `ownership` at all
     * (`_team`, `_user`, `environment`) are untouched by this check.
     */
    private fun assertTeamOwnership(
        requestsByFile: List<List<EntityRequest>>,
        blueprintRequestsByIdentifier: Map<String, BlueprintRequest>,
        responseByKey: Map<String, EntityResponse>,
    ) {
        val allRequests = requestsByFile.flatten()
        val teamIdentifiers = allRequests.filter { it.blueprint == "_team" }.map { it.identifier }.toSet()
        val teamByServiceIdentifier = allRequests.filter { it.blueprint == "service" }.associate { it.identifier to it.team }

        allRequests.forEach { request ->
            val ownership = blueprintRequestsByIdentifier.getValue(request.blueprint).ownership
            when (ownership?.type) {
                "Direct" -> {
                    val values = teamValuesOf(request.team)
                    assertTrue(
                        values.isNotEmpty(),
                        "${request.blueprint}/${request.identifier} must carry a team (Direct ownership)",
                    )
                    values.forEach { value ->
                        assertTrue(
                            value in teamIdentifiers,
                            "${request.blueprint}/${request.identifier}.team '$value' is not a _team identifier in 01-team.json",
                        )
                    }
                }
                "Inherited" -> {
                    val path = ownership.path ?: error("${request.blueprint}'s Inherited ownership must declare a path")
                    assertEquals(null, request.team, "${request.blueprint}/${request.identifier} must not send its own team (Inherited)")
                    val serviceIdentifier = (request.relations[path] as? JsonPrimitive)?.content
                        ?: error("${request.blueprint}/${request.identifier}.$path must name the owning service")
                    val expectedTeam = teamByServiceIdentifier[serviceIdentifier]
                    val actualTeam = responseByKey.getValue("${request.blueprint}/${request.identifier}").team
                    assertEquals(
                        expectedTeam,
                        actualTeam,
                        "${request.blueprint}/${request.identifier}'s computed team must equal '$serviceIdentifier's",
                    )
                }
                else -> Unit
            }
        }
    }

    /** [team] as a flat list of `_team`/entity identifiers, whether sent as a bare string or an array. */
    private fun teamValuesOf(team: JsonElement?): List<String> = when (team) {
        null, JsonNull -> emptyList()
        is JsonArray -> team.map { (it as JsonPrimitive).content }
        is JsonPrimitive -> listOf(team.content)
        else -> error("team must be a string or an array of strings, got $team")
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

    private companion object {
        /** The enum properties whose values are a seeded dictionary, exercised in full. */
        val DICTIONARY_PROPERTIES = setOf("type", "lifecycle")
    }

    /** Pins the coverage the set claims (`sample-data/README.md`'s table). */
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

            val usedRelationKeys = entityRequests.flatMap { it.relations.keys }.toSet()
            assertEquals(
                blueprintRequest.relations.keys,
                usedRelationKeys,
                "every relation of blueprint '$blueprintId' must be used at least once across its sample entities",
            )
        }

        // The registry-mirroring dictionaries (per-kind types, lifecycles) are exercised in full,
        // so every value of the Type/Lifecycle pickers has a sample entity behind it. A dictionary
        // is identified by (property id, enum values): `type` is one dictionary PER blueprint,
        // while `lifecycle` is the one global list shared by service/library/api, so its values
        // are counted across all three. Label/tag/Port-only enums only need the property set.
        val dictionaries = blueprintRequestsByIdentifier.values
            .flatMap { bp ->
                bp.schema.properties.filterKeys { it in DICTIONARY_PROPERTIES }.map { (id, def) -> Triple(bp.identifier, id, def.enum) }
            }
            .filter { it.third != null }
            .groupBy({ (_, id, enum) -> id to enum!!.map { it.content }.toSet() }, { (blueprintId, _, _) -> blueprintId })
        dictionaries.forEach { (dictionary, blueprintIds) ->
            val (propertyId, expected) = dictionary
            val used = blueprintIds.flatMap { requestsByBlueprint[it].orEmpty() }
                .mapNotNull { (it.properties[propertyId] as? JsonPrimitive)?.content }
                .toSet()
            assertEquals(expected, used, "every value of '$propertyId' on ${blueprintIds.sorted()} must be used at least once")
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
