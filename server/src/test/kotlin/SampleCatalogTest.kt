package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogGraph
import ch.nokillswit.catalog.GraphNodeStatus
import ch.nokillswit.catalog.ErrorStatus
import ch.nokillswit.catalog.ErrorsReport
import ch.nokillswit.catalog.ImportRequest
import ch.nokillswit.catalog.ImportResponse
import ch.nokillswit.catalog.ImportResultStatus
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression coverage for the checked-in Backstage commerce/payments landscape. */
class SampleCatalogTest {

    @Test
    fun `the Backstage sample imports with its four deliberate reference findings`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("catalogsample")
        val files = sampleFiles()
        val importedIds = mutableListOf<UInt>()

        assertEquals(42, files.size, "expected all sample YAML documents")

        try {
            val first = client.postJson(CATALOG_FILES_PATH + "/import", ImportRequest(files)).body<ImportResponse>()
            importedIds += first.results.mapNotNull { it.fileId }

            assertEquals(42, first.results.size)
            assertEquals(40, first.results.count { it.status == ImportResultStatus.CREATED })
            assertEquals(2, first.results.count { it.status == ImportResultStatus.CREATED_WITH_FINDINGS })
            assertEquals(
                setOf("catalog-service", "legacy-invoicing"),
                first.results.filter { it.status == ImportResultStatus.CREATED_WITH_FINDINGS }.map { it.name }.toSet(),
            )

            val importedFindings = client.get(CATALOG_FILES_PATH + "/errors").body<ErrorsReport>().findings
                .filter { it.fileId in importedIds }
            assertEquals(42, importedFindings.count { it.status == ErrorStatus.SOURCE_MISSING })
            assertEquals(
                setOf(
                    "group:default/billing-squad" to ErrorStatus.MISSING,
                    "resource:default/invoice-archive" to ErrorStatus.MISSING,
                    "orders-db" to ErrorStatus.KIND_REQUIRED,
                    "template:default/nodejs-service-template" to ErrorStatus.WRONG_KIND,
                ),
                importedFindings.filter { it.status != ErrorStatus.SOURCE_MISSING }
                    .map { it.reference to it.status }
                    .toSet(),
            )
            assertEquals(46, importedFindings.size, "42 source findings plus four deliberate reference findings")

            // The README quotes the graph this sample draws; pin it (restricted to the imported files - the workspace may hold others).
            val graph = client.get(CATALOG_FILES_PATH + "/graph").body<CatalogGraph>()
            val stored = graph.nodes.filter { it.status == GraphNodeStatus.STORED && it.fileId in importedIds }
            val storedNodeIds = stored.map { it.id }.toSet()
            val edges = graph.edges.filter { it.sourceId in storedNodeIds }
            val missing = graph.nodes.filter { node -> node.status == GraphNodeStatus.MISSING && edges.any { it.targetId == node.id } }
            assertEquals(
                Triple(42, 2, 113),
                Triple(stored.size, missing.size, edges.size),
                "stored nodes, missing-reference nodes, edges drawn from the sample",
            )

            val second = client.postJson(CATALOG_FILES_PATH + "/import", ImportRequest(files)).body<ImportResponse>()
            assertEquals(42, second.results.size)
            assertTrue(second.results.all { it.status == ImportResultStatus.CONFLICT }, second.results.toString())
        } finally {
            importedIds.forEach { id ->
                val deleted = client.delete("$CATALOG_FILES_PATH/$id")
                assertEquals(HttpStatusCode.NoContent, deleted.status, "cleanup DELETE $id")
            }
        }
    }

    private fun sampleFiles(): List<CatalogFile> = SampleData.backstageSampleFiles()
}
