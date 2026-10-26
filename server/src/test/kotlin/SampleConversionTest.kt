package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.identityOf
import ch.nokillswit.conversion.CatalogDocumentsResponse
import ch.nokillswit.conversion.ConversionFindingCode
import ch.nokillswit.conversion.ConversionMapping
import ch.nokillswit.conversion.ConversionMappingRequest
import ch.nokillswit.conversion.ConversionRowStatus
import ch.nokillswit.conversion.parseMappingDocument
import ch.nokillswit.entities.EntityImportRequest
import ch.nokillswit.entities.EntityImportResponse
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Flagship test A of the Backstage <-> Port conversion: the checked-in Port sample
 * (`sample-data/port/commerce-payments/`), converted through the checked-in default mapping
 * (`mapping/backstage.json`), IS the checked-in Backstage sample
 * (`sample-data/backstage/commerce-payments/catalog-info.yaml`) - modulo an EXPLICIT deviation list.
 *
 * The Backstage sample is not hand-written: it was generated from the Port sample through the export
 * engine (set `TOADIE_DUMP_BACKSTAGE_SAMPLE=<file>` to have this test write the raw export response there -
 * the sample README describes the regeneration), and then the four deliberate reference findings the
 * Errors report needs to show something were restored by hand. Those restorations are the ONLY
 * differences the test tolerates, each named in [DEVIATIONS] with its reason; a deviation that stops
 * deviating, or a difference that is not listed, fails the test. There is no wildcard.
 *
 * Also pinned here: zero mapping findings and zero drift against the real registries, every Port entity
 * of a mapped blueprint converts (and only the multi-team ones carry a finding), every entity of a
 * Port-only blueprint is a SKIPPED row, nothing an entity carries goes unreported (every field a row
 * reports `unmapped` is one its pair declares lossy), and a second export is identical.
 */
class SampleConversionTest {

    /** One tolerated difference between the export of the Port sample and the checked-in Backstage sample. */
    private class Deviation(val identity: String, val field: String, val reason: String, val apply: (CatalogFile) -> CatalogFile)

    private val deviations = listOf(
        Deviation(
            "component:default/catalog-service",
            "spec.dependsOn",
            "adds template:default/nodejs-service-template - an UNVERIFIABLE reference (Template is not one of the seven stored kinds); " +
                "it has no Port counterpart, so the Backstage sample carries it by hand for the Errors report (WRONG_KIND)",
        ) {
            // The export first: exactly the two converted relations, no template entry - only then the hand edit.
            assertEquals(listOf("resource:default/catalog-db", "component:default/acme-commons"), it.spec.dependsOn)
            it.copy(spec = it.spec.copy(dependsOn = it.spec.dependsOn + "template:default/nodejs-service-template"))
        },
        Deviation(
            "component:default/legacy-invoicing",
            "spec.owner",
            "owner billing-squad - a Group that does not exist; Port owns the service by payments-squad. " +
                "Restored by hand so the Errors report has a MISSING owner",
        ) {
            assertEquals("group:default/payments-squad", it.spec.owner)
            it.copy(spec = it.spec.copy(owner = "group:default/billing-squad"))
        },
        Deviation(
            "component:default/legacy-invoicing",
            "spec.dependsOn",
            "invoice-archive (a Resource that does not exist -> MISSING) and a bare 'orders-db' with no kind (-> KIND_REQUIRED); " +
                "Port relates the service to orders-db properly, as resource:default/orders-db",
        ) {
            assertEquals(listOf("resource:default/orders-db"), it.spec.dependsOn)
            it.copy(spec = it.spec.copy(dependsOn = listOf("resource:default/invoice-archive", "orders-db")))
        },
    )

    /** The two Port entities with several teams: the export keeps the first and says so. */
    private val truncated = setOf("domain/payments", "service/payments-gateway")

    private fun CatalogFile.id() = identityOf(this).let { "${it.kind}:${it.namespace}/${it.name}" }

    @Test
    fun `the Port sample exports to the Backstage sample, modulo the explicit deviation list`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("convsample", UserRole.ADMIN)
        val blueprintFiles = SampleData.numberedFiles("blueprints")
        val requests = blueprintFiles.associate { file ->
            blueprintJson.decodeFromString<BlueprintRequest>(file.readText()).let { it.identifier to it }
        }
        val documents = SampleData.numberedFiles("entities")
            .flatMap { file -> Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject } }
        val hierarchiesBefore = SampleData.snapshotHierarchies()
        val entityIdentifiers = mutableListOf<String>()
        var mappingId: UInt? = null
        try {
            TestHierarchies.ensure(*SampleData.requiredHierarchies)
            SampleData.loadBlueprints(admin, blueprintFiles)
            val imported = admin.postJson("/api/v1/entities/import", EntityImportRequest(documents = documents))
                .body<EntityImportResponse>()
            imported.results.forEach { row -> row.identifier?.let { entityIdentifiers += it } }
            assertEquals(76, imported.results.size)
            assertTrue(imported.results.all { it.status == OntologyImportStatus.CREATED }, imported.results.toString())

            val mappingFile = File("../sample-data/port/commerce-payments/mapping/backstage.json")
            val stored = admin.postJson(
                "/api/v1/conversion-mappings",
                ConversionMappingRequest(
                    "sample-${java.util.UUID.randomUUID().toString().take(8)}",
                    Json.parseToJsonElement(mappingFile.readText()),
                ),
            )
            assertEquals(HttpStatusCode.Created, stored.status, stored.bodyAsText())
            val mapping = stored.body<ConversionMapping>()
            mappingId = mapping.id
            assertEquals(emptyList(), mapping.findings, "the default mapping has no blocking findings")
            assertEquals(emptyList(), mapping.drift, "the default mapping shows no drift against the registries")

            val path = "/api/v1/conversion-mappings/${mapping.id}/catalog-documents?merge=false"
            val response = admin.get(path)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val raw = response.bodyAsText()
            System.getenv("TOADIE_DUMP_BACKSTAGE_SAMPLE")?.takeIf { it.isNotBlank() }?.let { File(it).writeText(raw) }
            // The workspace is shared with sibling suites that may leak entities of these blueprints: judge only the sample's own.
            val sampleKeys = documents.map {
                it.getValue("blueprint").jsonPrimitive.content to it.getValue("identifier").jsonPrimitive.content
            }.toSet()
            val sampleOnly = { response: CatalogDocumentsResponse ->
                val rows = response.rows.filter { (it.blueprint to it.identifier) in sampleKeys }
                val names = rows.mapNotNull { it.name }.toSet()
                CatalogDocumentsResponse(response.files.filter { it.metadata.name in names }, rows)
            }
            val exported = sampleOnly(Json { ignoreUnknownKeys = true }.decodeFromString<CatalogDocumentsResponse>(raw))

            assertRows(exported, requests.keys, parseMappingDocument(Json.parseToJsonElement(mappingFile.readText())).document!!)
            assertMatchesSample(exported.files)

            val again = sampleOnly(admin.get(path).body<CatalogDocumentsResponse>())
            assertEquals(exported, again, "a second export is identical")
        } finally {
            try {
                mappingId?.let { admin.delete("/api/v1/conversion-mappings/$it") }
                TestEntities.remove(*entityIdentifiers.toTypedArray())
                TestBlueprints.restoreSystemBlueprints()
                SampleData.stripAggregationsForCleanup(requests)
                TestBlueprints.remove(*requests.keys.toTypedArray())
            } finally {
                SampleData.restoreHierarchies(hierarchiesBefore)
            }
        }
    }

    private fun assertRows(
        exported: CatalogDocumentsResponse,
        blueprints: Set<String>,
        mapping: ch.nokillswit.conversion.MappingDocument,
    ) {
        val pairs = mapping.pairs.associateBy { it.blueprint }
        val mine = exported.rows.filter { it.blueprint in blueprints }
        assertEquals(76, mine.size, "one row per sample entity, got ${mine.map { it.blueprint + "/" + it.identifier }.sorted()}")

        val skipped = mine.filter { it.status == ConversionRowStatus.SKIPPED }
        assertEquals(
            mapOf(
                "product" to 5, "environment" to 4, "cluster" to 4, "dataset" to 5,
                "workload" to 9, "api_adoption" to 4, "dataset_adoption" to 3,
            ),
            skipped.groupingBy { it.blueprint }.eachCount(),
            "SKIPPED rows are exactly the entities of the Port-only blueprints",
        )
        assertTrue(skipped.all { r -> r.findings.map { it.code } == listOf(ConversionFindingCode.NO_PAIR) })
        assertEquals(34, skipped.size)

        val converted = mine - skipped.toSet()
        assertEquals(42, converted.size)
        assertEquals(42, exported.files.size, "every converted row has a document, nothing else does")
        val invalid = converted.filter { it.status == ConversionRowStatus.INVALID }
        assertTrue(invalid.isEmpty(), invalid.toString())
        val withFindings = converted.filter { it.status == ConversionRowStatus.CONVERTED_WITH_FINDINGS }
        assertEquals(
            truncated,
            withFindings.map { "${it.blueprint}/${it.identifier}" }.toSet(),
            "only the two multi-team entities carry a finding",
        )
        withFindings.forEach { row ->
            assertEquals(listOf(ConversionFindingCode.OWNER_TRUNCATED), row.findings.map { it.code }, "${row.blueprint}/${row.identifier}")
        }

        // Nothing an entity carries is silently lost: what a row reports as unmapped is something its pair declares lossy.
        converted.forEach { row ->
            val lossy = pairs.getValue(row.blueprint).lossy
            val allowed = lossy.properties.map { "properties.$it" } + lossy.relations.map { "relations.$it" }
            val undeclared = row.unmapped - allowed.toSet()
            assertTrue(undeclared.isEmpty(), "${row.blueprint}/${row.identifier} loses undeclared $undeclared")
        }
    }

    private fun assertMatchesSample(exportedFiles: List<CatalogFile>) {
        val sample = SampleData.backstageSampleFiles()
        assertEquals(42, sample.size, "the Backstage sample holds one document per mapped Port entity")
        val exported = exportedFiles.associateBy { it.id() }
        val checkedIn = sample.associateBy { it.id() }
        assertEquals(exported.size, exportedFiles.size, "export identities are unique")
        assertEquals(checkedIn.size, sample.size, "sample identities are unique")
        assertEquals(checkedIn.keys, exported.keys, "the same Backstage entities")

        // The deviations are exactly the documents that differ - a listed one that no longer differs fails too.
        val differing = exported.keys.filter { exported.getValue(it) != checkedIn.getValue(it) }.toSet()
        assertEquals(deviations.map { it.identity }.toSet(), differing, "the differing documents are exactly the listed deviations")

        val expected = exported.mapValues { (identity, file) ->
            deviations.filter { it.identity == identity }.fold(file) { acc, deviation -> deviation.apply(acc) }
        }
        expected.keys.sorted().forEach { identity ->
            assertEquals(expected.getValue(identity), checkedIn.getValue(identity), "document $identity")
        }
        assertNotNull(deviations.firstOrNull(), "the deviation list is explicit, never empty by accident")
    }
}
