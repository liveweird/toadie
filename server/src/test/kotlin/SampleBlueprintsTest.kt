package ch.nokillswit

import ch.nokillswit.SampleData.asRequest
import ch.nokillswit.blueprints.BlueprintImportRequest
import ch.nokillswit.blueprints.BlueprintImportResponse
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.blueprints.isSystemIdentifier
import ch.nokillswit.dictionaries.DictionaryEntryList
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.labels.LabelList
import ch.nokillswit.tags.TagCategoryList
import ch.nokillswit.types.EntityTypesList
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Executable documentation for `sample-data/blueprints/` — the eleven-blueprint baseline
 * ontology the platform catalog is built on (`.claude/docs/ontology.md`, v1.25.2; the sample
 * set since v1.25.3; adopting the v1.26.0 system blueprints and real ownership since v1.26.0).
 * Loads the numbered files through the real API in dependency order (every relation target must
 * already exist) via [SampleData.loadBlueprint] — `_team`/`_user` (`_`-prefixed identifiers) are
 * `PUT` extensions of the rows `V31__system_blueprints.sql` seeds, every other blueprint is a
 * fresh `POST` — pins the `blueprintJson` round trip (for `_team`/`_user` the file IS the full
 * desired definition, base shape included, so byte-for-structure equality still holds), and then
 * pins the four contracts the set makes:
 *
 * 1. **Vocabulary**: every enum that mirrors a V22-seeded registry (the per-kind type
 *    dictionaries, the lifecycles dictionary, the labels' closed value lists, the tag
 *    categories) carries EXACTLY the registry's values, read back from the running app — so a
 *    Backstage export is a copy and a registry edit that forgets the blueprint fails here.
 * 2. **Hierarchy**: the `hierarchyRelation` of each blueprint is the one the doc names, forming
 *    the org tree (`_team` → `_team`) and the architecture tree (domain → system → service/
 *    library/api/resource → workload, cluster → environment).
 * 3. **Ownership**: every blueprint but `_team`/`_user`/environment declares `ownership` — Direct
 *    on domain/system/service/library/api/resource/cluster (no `owned_by` relation: the team
 *    field IS the ownership, v1.26.0), Inherited via `service` on workload.
 * 4. **System blueprints stay put**: `_team`/`_user` are the seeded rows, not fresh creates.
 *
 * Test cwd is `server/` (the Gradle test task's default working directory), so the fixture
 * files are read via `../sample-data/blueprints`. The identifiers (`_team`, `domain`, …) are
 * plain, but the shared Testcontainers database is fine: this test restores the system
 * blueprints' base shape and removes every non-system identifier in `finally`, and
 * [SampleEntitiesTest] — which loads the same set — runs in the same single-fork sequence and
 * cleans up the same way.
 */
class SampleBlueprintsTest {

    @Test
    fun `the sample set loads in order and speaks the seeded vocabulary`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpsample", UserRole.ADMIN)
        val files = SampleData.numberedFiles("blueprints")
        // The `01-team.json`/`02-user.json` FILE names stay unrenamed (their JSON `identifier`
        // became `_team`/`_user`, not the filename) — so the loaded-in-order check compares the
        // decoded identifiers, the thing that actually matters for dependency order.
        val decoded = files.map { it to blueprintJson.decodeFromString<BlueprintRequest>(it.readText()) }
        assertEquals(EXPECTED_ORDER, decoded.map { it.second.identifier })

        val identifiers = decoded.map { it.second.identifier }
        val requests = decoded.associate { it.second.identifier to it.second }
        try {
            // Two passes (phase 5, v1.27.0): a forward-referencing aggregation target (domain ->
            // system, system -> service/workload) only exists once the FULL set has loaded, so
            // the round-trip check below runs against the post-pass-2 response, not per file.
            val responsesByIdentifier = SampleData.loadBlueprints(admin, files)
            decoded.forEach { (file, request) ->
                val reread = responsesByIdentifier.getValue(request.identifier)
                assertEquals(
                    SampleData.canonicalBlueprint(file.readText()),
                    Json.parseToJsonElement(blueprintJson.encodeToString(reread.asRequest())),
                    "${file.name} did not round-trip byte-for-structure through blueprintJson",
                )
            }

            assertHierarchy(requests)
            assertOwnership(requests)
            assertComputedProperties(requests)
            assertVocabulary(admin, requests)
        } finally {
            TestBlueprints.restoreSystemBlueprints()
            // domain <-> system form a reference cycle (domain's aggregation targets system,
            // system's own relation targets domain back) that the plain retry-based remove()
            // below cannot resolve on its own — see SampleData.stripAggregationsForCleanup's KDoc.
            SampleData.stripAggregationsForCleanup(requests)
            TestBlueprints.remove(*identifiers.toTypedArray())
        }
    }

    /**
     * Phase 6 (v1.28.0): the same eleven files are also a valid `POST /api/v1/blueprints/import`
     * BATCH, not just a sequential POST/PUT script — `_team`/`_user` already exist (seeded by
     * V31), so with `replaceExisting = true` they answer `UPDATED` and the other nine `CREATED`;
     * the planner's own ordering + two-pass deferral (`.claude/docs/port-data-model.md` "Import
     * and export") must resolve the exact same forward-referencing aggregations
     * (`loadBlueprints`'s pass 2, above) without any file-order hint from the caller — the
     * documents are submitted in their on-disk (dependency) order, but nothing about the import
     * endpoint requires that; the planner computes its own order from the definitions.
     */
    @Test
    fun `the sample set also imports as one batch, matching the two-pass load`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpimport", UserRole.ADMIN)
        val files = SampleData.numberedFiles("blueprints")
        val decoded = files.map { it to blueprintJson.decodeFromString<BlueprintRequest>(it.readText()) }
        val identifiers = decoded.map { it.second.identifier }
        val documents = files.map { Json.parseToJsonElement(it.readText()).jsonObject }

        try {
            val response = admin.postJson(
                "/api/v1/blueprints/import",
                BlueprintImportRequest(documents = documents, replaceExisting = true),
            ).body<BlueprintImportResponse>()

            assertEquals(files.size, response.results.size)
            decoded.forEachIndexed { index, (_, request) ->
                val row = response.results[index]
                assertEquals(request.identifier, row.identifier, "row $index identifier")
                val expected = if (isSystemIdentifier(request.identifier)) OntologyImportStatus.UPDATED else OntologyImportStatus.CREATED
                assertEquals(expected, row.status, "${request.identifier} import status")
                assertNotNull(row.id, "${request.identifier} must carry a stored id")
            }

            decoded.forEach { (file, request) ->
                val row = response.results.first { it.identifier == request.identifier }
                val reread = admin.get("/api/v1/blueprints/${row.id}").body<BlueprintResponse>()
                assertEquals(
                    SampleData.canonicalBlueprint(file.readText()),
                    Json.parseToJsonElement(blueprintJson.encodeToString(reread.asRequest())),
                    "${file.name} did not round-trip through the import batch",
                )
            }
        } finally {
            TestBlueprints.restoreSystemBlueprints()
            SampleData.stripAggregationsForCleanup(decoded.associate { it.second.identifier to it.second })
            TestBlueprints.remove(*identifiers.toTypedArray())
        }
    }

    private fun assertHierarchy(requests: Map<String, BlueprintRequest>) {
        assertEquals(EXPECTED_HIERARCHY, requests.mapValues { it.value.hierarchyRelation })
        EXPECTED_HIERARCHY.forEach { (blueprint, relation) ->
            if (relation == null) return@forEach
            val definition = assertNotNull(requests.getValue(blueprint).relations[relation], "$blueprint.$relation")
            assertTrue(!definition.many, "$blueprint's hierarchy relation must be single")
            assertEquals(EXPECTED_PARENT.getValue(blueprint), definition.target, "$blueprint's parent blueprint")
        }
    }

    /**
     * v1.26.0: ownership is the entity-level `team` field, not a Backstage `owned_by` relation.
     * Every blueprint but `_team`/`_user`/environment declares `ownership`: Direct for the plain
     * owning blueprints (no `owned_by` relation left over from the pre-v1.26.0 shape), Inherited
     * via `service` for workload.
     */
    private fun assertOwnership(requests: Map<String, BlueprintRequest>) {
        DIRECT_OWNERSHIP.forEach { blueprint ->
            val request = requests.getValue(blueprint)
            assertNull(request.relations["owned_by"], "$blueprint must not keep a Backstage owned_by relation")
            assertEquals("Direct", request.ownership?.type, "$blueprint.ownership.type")
        }
        assertNull(requests.getValue("workload").relations["owned_by"], "workload inherits ownership")
        assertEquals("Inherited", requests.getValue("workload").ownership?.type)
        assertEquals("service", requests.getValue("workload").ownership?.path)
        listOf("_team", "_user", "environment").forEach {
            assertNull(requests.getValue(it).ownership, "$it must declare no ownership")
        }
    }

    /**
     * Phase 5 (v1.27.0): [COMPUTED_PROPERTIES] pins the exact mirror/calculation/aggregation id
     * set per blueprint — every other blueprint declares none.
     */
    private fun assertComputedProperties(requests: Map<String, BlueprintRequest>) {
        requests.forEach { (blueprint, request) ->
            val actual = request.mirrorProperties.keys + request.calculationProperties.keys + request.aggregationProperties.keys
            assertEquals(
                COMPUTED_PROPERTIES[blueprint].orEmpty(),
                actual,
                "$blueprint's mirror/calculation/aggregation property ids",
            )
        }
    }

    private suspend fun assertVocabulary(admin: HttpClient, requests: Map<String, BlueprintRequest>) {
        val types = admin.get("/api/v1/entity-types").body<EntityTypesList>().items.associate { it.kind to it.types.toSet() }
        val lifecycles = admin.get("/api/v1/dictionaries/lifecycles").body<DictionaryEntryList>().items.map { it.value }.toSet()
        val labels = admin.get("/api/v1/labels").body<LabelList>().items.associate { it.key to it.values.toSet() }
        val tags = admin.get("/api/v1/tag-categories").body<TagCategoryList>().items.associate { it.name to it.tags.toSet() }

        fun enumOf(blueprint: String, property: String): Set<String> {
            val definition = assertNotNull(requests.getValue(blueprint).schema.properties[property], "$blueprint.$property")
            val enum = definition.enum ?: definition.items?.enum
            return assertNotNull(enum, "$blueprint.$property must be an enum").map { it.content }.toSet()
        }

        // Per-kind type dictionaries: the blueprint's `type` enum IS the kind's dictionary
        // (service = Component minus `library`, which is its own blueprint).
        assertEquals(types.getValue("Group"), enumOf("_team", "type"))
        assertEquals(types.getValue("Domain"), enumOf("domain", "type"))
        assertEquals(types.getValue("System"), enumOf("system", "type"))
        assertEquals(types.getValue("Component") - "library", enumOf("service", "type"))
        assertTrue("library" in types.getValue("Component"), "the Component dictionary must still carry library")
        assertEquals(types.getValue("API"), enumOf("api", "type"))
        assertEquals(types.getValue("Resource"), enumOf("resource", "type"))

        // The lifecycles dictionary on every Backstage kind that carries spec.lifecycle.
        listOf("service", "library", "api").forEach { assertEquals(lifecycles, enumOf(it, "lifecycle"), "$it.lifecycle") }

        // Labels: closed value lists, verbatim; the yes/no ones are booleans.
        LABEL_PROPERTIES.forEach { (label, sites) ->
            sites.forEach { (blueprint, property) ->
                assertEquals(labels.getValue(label), enumOf(blueprint, property), "$blueprint.$property ↔ label $label")
            }
        }
        listOf("gdpr", "pci_dss").forEach { property ->
            assertEquals("boolean", requests.getValue("resource").schema.properties.getValue(property).type, "resource.$property")
        }

        // Tag categories: languages/frameworks as unique-item arrays, engine as the Database ∪ Events union.
        listOf("service", "library").forEach { blueprint ->
            assertEquals(tags.getValue("Languages"), enumOf(blueprint, "languages"), "$blueprint.languages")
            assertEquals(tags.getValue("Framework"), enumOf(blueprint, "frameworks"), "$blueprint.frameworks")
            listOf("languages", "frameworks").forEach { property ->
                val definition: PropertyDefinition = requests.getValue(blueprint).schema.properties.getValue(property)
                assertEquals("array", definition.type, "$blueprint.$property")
                assertEquals(true, definition.uniqueItems, "$blueprint.$property must be uniqueItems")
            }
        }
        assertEquals(tags.getValue("Database") + tags.getValue("Events"), enumOf("resource", "engine"))
    }

    private companion object {
        val EXPECTED_ORDER = listOf(
            "_team", "_user", "domain", "system", "environment", "cluster", "resource", "library", "api", "service", "workload",
        )

        /** blueprint → its `hierarchyRelation` (null = roots its own entities). */
        val EXPECTED_HIERARCHY = mapOf(
            "_team" to "parent",
            "_user" to null,
            "domain" to "parent_domain",
            "system" to "domain",
            "environment" to null,
            "cluster" to "environment",
            "resource" to "system",
            "library" to "system",
            "api" to "system",
            "service" to "system",
            "workload" to "service",
        )

        /** blueprint → the blueprint its hierarchy relation targets. */
        val EXPECTED_PARENT = mapOf(
            "_team" to "_team",
            "domain" to "domain",
            "system" to "domain",
            "cluster" to "environment",
            "resource" to "system",
            "library" to "system",
            "api" to "system",
            "service" to "system",
            "workload" to "service",
        )

        /**
         * v1.26.0: `ownership.type == "Direct"` (the entity-level `team` field), no leftover
         * `owned_by` relation — every plain owning blueprint, `workload` excepted (Inherited).
         */
        val DIRECT_OWNERSHIP = listOf("domain", "system", "service", "library", "api", "resource", "cluster")

        /**
         * blueprint → its declared mirror/calculation/aggregation property ids (phase 5,
         * v1.27.0, `.claude/docs/ontology.md`) — every blueprint not listed here declares none.
         */
        val COMPUTED_PROPERTIES = mapOf(
            "_team" to setOf("member_count"),
            "domain" to setOf("critical_systems"),
            "system" to setOf("service_count", "workload_replicas", "deploys_per_week"),
            "service" to setOf("domain_title", "stack", "risk"),
            "workload" to setOf("service_lifecycle", "env_type", "languages"),
        )

        /** label key → the (blueprint, property) enums that mirror its closed value list. */
        val LABEL_PROPERTIES = mapOf(
            "criticality-tier" to listOf("system" to "criticality"),
            "support-mode" to listOf("system" to "support_mode"),
            "exposure" to listOf("system" to "exposure", "service" to "exposure", "api" to "exposure", "resource" to "exposure"),
            "hosting-model" to listOf(
                "system" to "hosting_model", "service" to "hosting_model", "api" to "hosting_model",
                "resource" to "hosting_model", "cluster" to "hosting_model",
            ),
            "technology-status" to listOf(
                "service" to "technology_status", "library" to "technology_status", "resource" to "technology_status",
            ),
            "data-classification" to listOf("resource" to "data_classification"),
        )
    }
}
