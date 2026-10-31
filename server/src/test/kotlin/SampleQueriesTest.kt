package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.EntityGraph
import ch.nokillswit.entities.EntityImportRequest
import ch.nokillswit.entities.EntityImportResponse
import ch.nokillswit.entityquery.EntityQueryCheckRequest
import ch.nokillswit.entityquery.EntityQueryCheckResponse
import ch.nokillswit.entityquery.SavedEntityQuery
import ch.nokillswit.entityquery.SavedEntityQueryList
import ch.nokillswit.entityquery.SavedEntityQueryRequest
import ch.nokillswit.entityquery.SavedEntityQueryVisibility
import ch.nokillswit.entityquery.MAX_SAVED_ENTITY_QUERY_NAME_LENGTH
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executable documentation for `sample-data/port/commerce-payments/queries/` — the public saved
 * entity queries that ship with the Port demo (sample-data only; no migration seeds them). Loads
 * the sample blueprints and the 76 sample entities through the real API (the blueprints as in
 * [SampleEntitiesTest], the entities as one `POST /api/v1/entities/import` batch), then for EVERY
 * numbered query file proves, against the real parser, validator and evaluator:
 *
 * 1. the file is a valid `SavedEntityQueryRequest` body — PUBLIC, a name within the cap, unique
 *    across the set — and `POST /api/v1/entity-queries` stores it (201); a second POST of the
 *    same file is the 409 `queries/load.sh` reports as "exists, skipped" (its idempotence rests
 *    on exactly that);
 * 2. `POST /api/v1/entities/query/check` answers NO diagnostics against the loaded schema — so a
 *    blueprint or relation rename that forgets a sample query fails here, not in a user's bar;
 * 3. `GET /api/v1/entities/graph?query=` returns the pinned, non-empty node set (per-blueprint
 *    counts for every query, recorded live against the disposable stack when the queries were
 *    written, plus the exact `blueprint/identifier` set for the small ones).
 *
 * Test cwd is `server/`, so fixtures are read via `../sample-data/port/commerce-payments/queries`.
 * Every saved query, entity, and blueprint is removed in `finally` (the [SampleEntitiesTest] order).
 */
class SampleQueriesTest {

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    @Test
    fun `every sample query saves once, validates, and shows a non-empty pinned set`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("qrysample", UserRole.ADMIN)

        val queryFiles = SampleData.numberedFiles("queries")
        assertEquals(
            EXPECTED_COUNTS.keys.toList(),
            queryFiles.map { it.name },
            "the pinned set must cover exactly the numbered query files",
        )
        val requests = queryFiles.map { blueprintJson.decodeFromString<SavedEntityQueryRequest>(it.readText()) }
        assertEquals(requests.size, requests.map { it.name.lowercase() }.toSet().size, "query names must be unique (case-insensitively)")
        requests.forEach {
            assertEquals(SavedEntityQueryVisibility.PUBLIC, it.visibility, "${it.name} must be PUBLIC")
            assertTrue(it.name.length <= MAX_SAVED_ENTITY_QUERY_NAME_LENGTH, "${it.name} exceeds the name cap")
        }

        val bpFiles = SampleData.numberedFiles("blueprints")
        val bpRequestsByIdentifier = bpFiles.associate {
            val request = blueprintJson.decodeFromString<BlueprintRequest>(it.readText())
            request.identifier to request
        }
        val documents = SampleData.numberedFiles("entities")
            .flatMap { file -> Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject } }

        val entityIdentifiers = mutableListOf<String>()
        val queryIds = mutableListOf<UInt>()
        val hierarchiesBefore = SampleData.snapshotHierarchies()
        try {
            TestHierarchies.ensure(*SampleData.requiredHierarchies)
            SampleData.loadBlueprints(admin, bpFiles)
            val imported = admin.postJson("/api/v1/entities/import", EntityImportRequest(documents = documents))
                .body<EntityImportResponse>()
            // Recorded BEFORE the assertion, so a failed import still cleans up what it stored.
            imported.results.forEach { row -> row.identifier?.let { entityIdentifiers += it } }
            assertTrue(
                imported.results.all { it.status == OntologyImportStatus.CREATED },
                "every sample entity must be CREATED: ${imported.results}",
            )

            queryFiles.zip(requests).forEach { (file, request) ->
                val created = admin.postJson("/api/v1/entity-queries", request)
                assertEquals(HttpStatusCode.Created, created.status, "POST ${file.name}: ${created.body<String>()}")
                val saved = created.body<SavedEntityQuery>()
                queryIds += saved.id
                assertEquals(request.query, saved.query, "${file.name} must be stored verbatim")

                assertEquals(
                    HttpStatusCode.Conflict,
                    admin.postJson("/api/v1/entity-queries", request).status,
                    "re-POSTing ${file.name} must be the 409 the loader reports as 'exists, skipped'",
                )

                val check = admin.postJson("/api/v1/entities/query/check", EntityQueryCheckRequest(request.query))
                    .body<EntityQueryCheckResponse>()
                assertTrue(check.diagnostics.isEmpty(), "${file.name} has diagnostics against the sample schema: ${check.diagnostics}")

                val graph = admin.get("/api/v1/entities/graph?query=${request.query.urlEncoded()}").body<EntityGraph>()
                assertTrue(graph.nodes.isNotEmpty(), "${file.name} returned no nodes")
                assertTrue(graph.edges.isNotEmpty(), "${file.name} returned no edges")
                assertEquals(
                    EXPECTED_COUNTS.getValue(file.name),
                    graph.nodes.groupingBy { it.blueprint }.eachCount().toSortedMap(),
                    "${file.name} node counts per blueprint",
                )
                EXPECTED_IDENTIFIERS[file.name]?.let { expected ->
                    assertEquals(
                        expected.toSortedSet(),
                        graph.nodes.map { "${it.blueprint}/${it.identifier}" }.toSortedSet(),
                        "${file.name} node identifiers",
                    )
                }
            }

            // The picker lists every sample query to every user — they are PUBLIC.
            val listed = admin.get("/api/v1/entity-queries").body<SavedEntityQueryList>().items.map { it.name }
            assertTrue(listed.containsAll(requests.map { it.name }), "the sample queries must all be listed")
        } finally {
            try {
                queryIds.forEach { TestSavedEntityQueries.remove(it) }
                TestEntities.remove(*entityIdentifiers.toTypedArray())
                TestBlueprints.restoreSystemBlueprints()
                SampleData.stripAggregationsForCleanup(bpRequestsByIdentifier)
                TestBlueprints.remove(*bpRequestsByIdentifier.keys.toTypedArray())
            } finally {
                SampleData.restoreHierarchies(hierarchiesBefore)
            }
        }
    }

    private companion object {
        /** query file → the exact `blueprint/identifier` node set, for the queries small enough to pin. */
        val EXPECTED_IDENTIFIERS: Map<String, Set<String>> = mapOf(
            "04-products-and-systems.json" to setOf(
                "product/commerce-suite", "product/pay", "product/shop", "product/invoicing", "product/dev-portal",
                "system/storefront", "system/payments", "system/acquirer", "system/invoicing", "system/developer-portal",
            ),
            "06-pci-dss-scope.json" to setOf(
                "service/payments-gateway", "service/ledger-worker", "service/settlement-pipeline",
                "system/payments",
                "api/acquirer-rest", "api/order-events",
                "dataset/payments-ledger", "dataset/settlements-daily",
                "resource/payments-db", "resource/ledger-log", "resource/settlement-warehouse",
            ),
            "07-dora-critical-functions.json" to setOf(
                "service/payments-gateway", "service/ledger-worker", "service/settlement-pipeline",
                "system/payments",
                "resource/payments-db", "resource/ledger-log", "resource/settlement-warehouse",
                "_team/platform-tribe", "_team/payments-squad",
            ),
            "09-workloads-outside-cluster-environment.json" to setOf(
                "workload/live-cart-gateway-test", "cluster/staging-k8s", "environment/staging", "environment/test",
            ),
        )


        /** query file → node count per blueprint, over the 76 sample entities (recorded live, 2.20.x). */
        val EXPECTED_COUNTS: Map<String, Map<String, Int>> = linkedMapOf(
            "01-api-consumption.json" to mapOf("api" to 4, "api_adoption" to 4, "service" to 3),
            "02-dataset-consumption.json" to mapOf("dataset" to 3, "dataset_adoption" to 3, "service" to 3),
            "03-adoptions-in-flight.json" to mapOf(
                "api" to 2, "api_adoption" to 2, "dataset" to 2, "dataset_adoption" to 2, "service" to 4,
            ),
            "04-products-and-systems.json" to mapOf("product" to 5, "system" to 5),
            "05-dataset-ownership-lineage.json" to mapOf("_team" to 2, "dataset" to 5, "service" to 7),
            "06-pci-dss-scope.json" to mapOf("api" to 2, "dataset" to 2, "resource" to 3, "service" to 3, "system" to 1),
            "07-dora-critical-functions.json" to mapOf("_team" to 2, "resource" to 3, "service" to 3, "system" to 1),
            "08-banking-outsourcing-scope.json" to mapOf("api" to 2, "dataset" to 2, "resource" to 3, "service" to 3, "system" to 2),
            "09-workloads-outside-cluster-environment.json" to mapOf("cluster" to 1, "environment" to 2, "workload" to 1),
        )
    }
}
