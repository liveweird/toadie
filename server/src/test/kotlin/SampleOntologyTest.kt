package ch.nokillswit

import ch.nokillswit.SampleData.asRequest
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.dictionaries.DictionaryEntryList
import ch.nokillswit.labels.LabelList
import ch.nokillswit.tags.TagCategoryList
import ch.nokillswit.types.EntityTypesList
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Executable documentation for `sample-data/ontology/` — the eleven-blueprint baseline the
 * platform catalog is built on (`.claude/docs/ontology.md`, v1.25.2). Loads the numbered files
 * through the real API in dependency order (every relation target must already exist), pins the
 * `blueprintJson` round trip, and then pins the three contracts the set makes:
 *
 * 1. **Vocabulary**: every enum that mirrors a V22-seeded registry (the per-kind type
 *    dictionaries, the lifecycles dictionary, the labels' closed value lists, the tag
 *    categories) carries EXACTLY the registry's values, read back from the running app — so a
 *    Backstage export is a copy and a registry edit that forgets the blueprint fails here.
 * 2. **Hierarchy**: the `hierarchyRelation` of each blueprint is the one the doc names, forming
 *    the org tree (team → team) and the architecture tree (domain → system → service/library/
 *    api/resource → workload, cluster → environment).
 * 3. **Backstage ownership**: every blueprint that maps to a Backstage kind requiring
 *    `spec.owner` has `owned_by → team` required and single.
 *
 * The identifiers overlap the showcase set's (`team`, `domain`, `service`, `environment`,
 * `workload`) on purpose — they are alternatives, never loaded together — so this test, like
 * [SampleBlueprintsTest], removes every one of them in `finally`; the two tests never run
 * concurrently within one JVM (Gradle's default single-fork test execution).
 */
class SampleOntologyTest {

    private suspend fun HttpClient.postRaw(text: String) =
        post("/api/v1/blueprints") {
            contentType(ContentType.Application.Json)
            setBody(text)
        }

    @Test
    fun `the baseline ontology loads in order and speaks the seeded vocabulary`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ontology", UserRole.ADMIN)
        val files = SampleData.numberedFiles("ontology")
        assertEquals(EXPECTED_ORDER, files.map { it.name.substringAfter('-').removeSuffix(".json") })

        val identifiers = mutableListOf<String>()
        val requests = mutableMapOf<String, BlueprintRequest>()
        try {
            files.forEach { file ->
                val text = file.readText()
                val request = blueprintJson.decodeFromString<BlueprintRequest>(text)
                requests[request.identifier] = request
                identifiers += request.identifier

                val create = admin.postRaw(text)
                assertEquals(HttpStatusCode.Created, create.status, "POST ${file.name}: ${create.bodyAsText()}")
                val created = create.body<BlueprintResponse>()
                val reread = admin.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
                assertEquals(
                    SampleData.canonicalBlueprint(text),
                    Json.parseToJsonElement(blueprintJson.encodeToString(reread.asRequest())),
                    "${file.name} did not round-trip byte-for-structure through blueprintJson",
                )
            }

            assertHierarchy(requests)
            assertBackstageOwnership(requests)
            assertVocabulary(admin, requests)
        } finally {
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

    private fun assertBackstageOwnership(requests: Map<String, BlueprintRequest>) {
        OWNER_REQUIRED.forEach { blueprint ->
            val owner = assertNotNull(requests.getValue(blueprint).relations["owned_by"], "$blueprint.owned_by")
            assertEquals("team", owner.target)
            assertTrue(owner.required && !owner.many, "$blueprint.owned_by must be required and single")
        }
        assertNull(requests.getValue("workload").relations["owned_by"], "workload inherits ownership")
        assertEquals("Inherited", requests.getValue("workload").ownership?.type)
        assertEquals("service", requests.getValue("workload").ownership?.path)
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
        assertEquals(types.getValue("Group"), enumOf("team", "type"))
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
            "team", "user", "domain", "system", "environment", "cluster", "resource", "library", "api", "service", "workload",
        )

        /** blueprint → its `hierarchyRelation` (null = roots its own entities). */
        val EXPECTED_HIERARCHY = mapOf(
            "team" to "parent",
            "user" to null,
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
            "team" to "team",
            "domain" to "domain",
            "system" to "domain",
            "cluster" to "environment",
            "resource" to "system",
            "library" to "system",
            "api" to "system",
            "service" to "system",
            "workload" to "service",
        )

        /** Backstage requires `spec.owner` on Domain, System, Component, API and Resource. */
        val OWNER_REQUIRED = listOf("domain", "system", "service", "library", "api", "resource")

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
