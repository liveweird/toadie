package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.blueprints.toDefinition
import ch.nokillswit.entities.EntityImportRequest
import ch.nokillswit.entities.EntityImportResponse
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.entities.computedPropertyIds
import ch.nokillswit.entities.toDocument
import ch.nokillswit.infra.importing.OntologyImportStatus
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
                val request = blueprintJson.decodeFromString<BlueprintRequest>(file.readText())
                blueprintIdentifiers += request.identifier
                blueprintRequestsByIdentifier[request.identifier] = request
                blueprintFileIndex[request.identifier] = index
            }
            // Two passes (phase 5, v1.27.0): a forward-referencing aggregation target (domain ->
            // system, system -> service/workload) only exists once the full set has loaded —
            // see `sample-data/blueprints/load.sh` and `SampleData.loadBlueprints`'s KDoc.
            SampleData.loadBlueprints(admin, bpFiles)

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
                    // Phase 5 (v1.27.0): the response's `properties` is stored ∪ computed
                    // (`.claude/docs/port-data-model.md` "Computed properties") — strip the
                    // computed ids before comparing against the request's STORED shape.
                    val computedIds = computedPropertyIds(blueprintRequestsByIdentifier.getValue(request.blueprint).toDefinition())
                    val storedProperties = JsonObject(reread.properties.filterKeys { it !in computedIds })
                    assertEquals(expected.properties, storedProperties, "${file.name} ${request.identifier} properties did not round-trip")
                    assertEquals(expected.relations, reread.relations, "${file.name} ${request.identifier} relations did not round-trip")

                    responseByKey["${request.blueprint}/${request.identifier}"] = reread
                }
            }

            // Phase 5 (v1.27.0): an aggregation property (e.g. system.service_count) can name a
            // TARGET blueprint that loads LATER in dependency order (service/workload load after
            // system), so the response captured right after an entity's own creation predates
            // its dependents. Refresh every response now that the full set is loaded, before
            // deriving/asserting any computed value.
            responseByKey.keys.toList().forEach { key ->
                val id = responseByKey.getValue(key).id
                responseByKey[key] = admin.get("/api/v1/entities/$id").body<EntityResponse>()
            }

            assertShowcaseCoverage(requestsByFile, blueprintRequestsByIdentifier)
            assertTeamOwnership(requestsByFile, blueprintRequestsByIdentifier, responseByKey)
            assertComputedProperties(requestsByFile, responseByKey)
        } finally {
            TestEntities.remove(*entityIdentifiers.toTypedArray())
            TestBlueprints.restoreSystemBlueprints()
            // domain <-> system form a reference cycle (domain's aggregation targets system,
            // system's own relation targets domain back) that the plain retry-based remove()
            // below cannot resolve on its own — see SampleData.stripAggregationsForCleanup's KDoc.
            SampleData.stripAggregationsForCleanup(blueprintRequestsByIdentifier)
            TestBlueprints.remove(*blueprintIdentifiers.toTypedArray())
        }
    }

    /**
     * Phase 6 (v1.28.0): the 59 entity files also load as ONE `POST /api/v1/entities/import`
     * batch on top of the already-loaded blueprint set (the same [SampleData.loadBlueprints] as
     * above) — the planner's own ordering resolves every relation/`team`/format-property sibling
     * reference across all eleven files without any per-file sequencing from the caller, every
     * row lands `CREATED` and every re-GET carries NO findings, exactly like the sequential-POST
     * path in the main test (both go through the same [ch.nokillswit.entities.EntityService]
     * writes). A second identical run — `replaceExisting` left at its default `false` — reports
     * every row `EXISTS`, storing nothing.
     */
    @Test
    fun `the sample entity set also imports as one batch, twice`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("entimport", UserRole.ADMIN)

        val bpFiles = blueprintFiles()
        val bpRequestsByIdentifier = bpFiles.associate {
            val request = blueprintJson.decodeFromString<BlueprintRequest>(it.readText())
            request.identifier to request
        }
        val entFiles = entityFiles()
        val documents = entFiles.flatMap { file -> Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject } }

        val entityIdentifiers = mutableListOf<String>()
        try {
            SampleData.loadBlueprints(admin, bpFiles)

            val response = admin.postJson("/api/v1/entities/import", EntityImportRequest(documents = documents))
                .body<EntityImportResponse>()
            assertEquals(59, response.results.size, "expected the 59 sample entities")
            assertTrue(
                response.results.all { it.status == OntologyImportStatus.CREATED },
                "every row must be CREATED: ${response.results}",
            )
            response.results.forEach { row ->
                entityIdentifiers += row.identifier ?: error("import row ${row.index} carries no identifier")
                val reread = admin.get("/api/v1/entities/${row.id}").body<EntityResponse>()
                assertTrue(reread.findings.isEmpty(), "${row.blueprint}/${row.identifier} reads back with findings: ${reread.findings}")
            }

            val second = admin.postJson("/api/v1/entities/import", EntityImportRequest(documents = documents)).body<EntityImportResponse>()
            assertTrue(
                second.results.all { it.status == OntologyImportStatus.EXISTS },
                "a re-import must report EXISTS: ${second.results}",
            )
        } finally {
            TestEntities.remove(*entityIdentifiers.toTypedArray())
            TestBlueprints.restoreSystemBlueprints()
            SampleData.stripAggregationsForCleanup(bpRequestsByIdentifier)
            TestBlueprints.remove(*bpRequestsByIdentifier.keys.toTypedArray())
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

    /**
     * Phase 5 (v1.27.0): derives every computed value straight from the sample entity files
     * (never a hardcoded number) and checks it against the value the API actually returned —
     * `.claude/docs/ontology.md`'s "Computed properties" table. `deploys_per_week` is
     * time-dependent (`now` keeps moving), so it is only range-asserted: positive, and no
     * larger than the count of workloads it averaged over.
     */
    private fun assertComputedProperties(
        requestsByFile: List<List<EntityRequest>>,
        responseByKey: Map<String, EntityResponse>,
    ) {
        val allRequests = requestsByFile.flatten()
        fun byBlueprint(blueprint: String) = allRequests.filter { it.blueprint == blueprint }
        fun propertiesOf(blueprint: String, identifier: String) = responseByKey.getValue("$blueprint/$identifier").properties

        val teams = byBlueprint("_team")
        val users = byBlueprint("_user")
        val domains = byBlueprint("domain")
        val systems = byBlueprint("system")
        val services = byBlueprint("service")
        val workloads = byBlueprint("workload")
        val environments = byBlueprint("environment")

        // _team.member_count: direct aggregation over _user's own "team" relation.
        teams.forEach { team ->
            val expected = users.count { user -> team.identifier in teamValuesOf(user.relations["team"]) }
            assertEquals(
                JsonPrimitive(expected.toLong()),
                propertiesOf("_team", team.identifier)["member_count"],
                "_team/${team.identifier}.member_count",
            )
        }

        // domain.critical_systems: direct aggregation over system.domain, filtered to criticality = critical.
        domains.forEach { domain ->
            val expected = systems.count { system ->
                domain.identifier in teamValuesOf(system.relations["domain"]) &&
                    (system.properties["criticality"] as? JsonPrimitive)?.content == "critical"
            }
            assertEquals(
                JsonPrimitive(expected.toLong()),
                propertiesOf("domain", domain.identifier)["critical_systems"],
                "domain/${domain.identifier}.critical_systems",
            )
        }

        // system.service_count: direct aggregation over service.system.
        systems.forEach { system ->
            val expected = services.count { service -> system.identifier in teamValuesOf(service.relations["system"]) }
            assertEquals(
                JsonPrimitive(expected.toLong()),
                propertiesOf("system", system.identifier)["service_count"],
                "system/${system.identifier}.service_count",
            )
        }

        // system.workload_replicas / deploys_per_week: reverse pathFilter workload -> service -> system.
        val systemOfWorkload = workloads.associate { workload ->
            val serviceIdentifier = (workload.relations.getValue("service") as JsonPrimitive).content
            val service = services.first { it.identifier == serviceIdentifier }
            workload.identifier to (service.relations["system"] as? JsonPrimitive)?.content
        }
        var replicasSeen = false
        var deploysSeen = false
        systems.forEach { system ->
            val matching = workloads.filter { systemOfWorkload[it.identifier] == system.identifier }
            val replicasActual = propertiesOf("system", system.identifier)["workload_replicas"]
            val replicaValues = matching.mapNotNull { (it.properties["replicas"] as? JsonPrimitive)?.doubleOrNull }
            if (replicaValues.isEmpty()) {
                assertNull(replicasActual, "system/${system.identifier}.workload_replicas should be absent")
            } else {
                replicasSeen = true
                val sum = replicaValues.sum()
                val expectedReplicas = if (sum == Math.floor(sum)) JsonPrimitive(sum.toLong()) else JsonPrimitive(sum)
                assertEquals(expectedReplicas, replicasActual, "system/${system.identifier}.workload_replicas")
            }

            val deploysActual = propertiesOf("system", system.identifier)["deploys_per_week"]
            if (matching.isEmpty()) {
                assertNull(deploysActual, "system/${system.identifier}.deploys_per_week should be absent")
            } else {
                deploysSeen = true
                val primitive = assertNotNull(deploysActual as? JsonPrimitive, "system/${system.identifier}.deploys_per_week")
                val value = assertNotNull(primitive.doubleOrNull, "system/${system.identifier}.deploys_per_week must be numeric")
                assertTrue(value > 0, "system/${system.identifier}.deploys_per_week must be positive, was $value")
                assertTrue(
                    value <= matching.size,
                    "system/${system.identifier}.deploys_per_week ($value) must be at most its workload count (${matching.size})",
                )
            }
        }
        assertTrue(replicasSeen, "workload_replicas must be present on at least one system")
        assertTrue(deploysSeen, "deploys_per_week must be present on at least one system")

        // service.domain_title: mirror system.domain.$title.
        services.forEach { service ->
            val systemIdentifier = (service.relations["system"] as? JsonPrimitive)?.content
            val system = systems.firstOrNull { it.identifier == systemIdentifier }
            val domainIdentifier = (system?.relations?.get("domain") as? JsonPrimitive)?.content
            val domain = domains.firstOrNull { it.identifier == domainIdentifier }
            val actual = propertiesOf("service", service.identifier)["domain_title"]
            if (domain == null) {
                assertNull(actual, "service/${service.identifier}.domain_title should be absent")
            } else {
                assertEquals(JsonPrimitive(domain.title), actual, "service/${service.identifier}.domain_title")
            }
        }

        // service.stack: languages ∪ frameworks, joined.
        services.forEach { service ->
            val languages = teamValuesOf(service.properties["languages"])
            val frameworks = teamValuesOf(service.properties["frameworks"])
            val expected = (languages + frameworks).joinToString(", ")
            assertEquals(
                JsonPrimitive(expected),
                propertiesOf("service", service.identifier)["stack"],
                "service/${service.identifier}.stack",
            )
        }

        // service.risk: black-list/deprecated -> high, grey-zone/sunsetting -> medium, else low.
        services.forEach { service ->
            val techStatus = (service.properties["technology_status"] as? JsonPrimitive)?.content
            val lifecycle = (service.properties["lifecycle"] as? JsonPrimitive)?.content
            val expected = when {
                techStatus == "black-list" || lifecycle == "deprecated" -> "high"
                techStatus == "grey-zone" || lifecycle == "sunsetting" -> "medium"
                else -> "low"
            }
            assertEquals(
                JsonPrimitive(expected),
                propertiesOf("service", service.identifier)["risk"],
                "service/${service.identifier}.risk",
            )
        }

        // workload.service_lifecycle / env_type / languages: single-hop mirrors.
        workloads.forEach { workload ->
            val serviceIdentifier = (workload.relations.getValue("service") as JsonPrimitive).content
            val service = services.first { it.identifier == serviceIdentifier }
            val environmentIdentifier = (workload.relations["environment"] as? JsonPrimitive)?.content
            val environment = environments.firstOrNull { it.identifier == environmentIdentifier }

            assertEquals(
                service.properties["lifecycle"],
                propertiesOf("workload", workload.identifier)["service_lifecycle"],
                "workload/${workload.identifier}.service_lifecycle",
            )
            assertEquals(
                environment?.properties?.get("type"),
                propertiesOf("workload", workload.identifier)["env_type"],
                "workload/${workload.identifier}.env_type",
            )
            assertEquals(
                service.properties["languages"],
                propertiesOf("workload", workload.identifier)["languages"],
                "workload/${workload.identifier}.languages",
            )
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
