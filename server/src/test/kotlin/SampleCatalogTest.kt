package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.ErrorStatus
import ch.nokillswit.catalog.ErrorsReport
import ch.nokillswit.catalog.ImportRequest
import ch.nokillswit.catalog.ImportResponse
import ch.nokillswit.catalog.ImportResultStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
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

        assertEquals(34, files.size, "expected all sample YAML documents")

        try {
            val first = client.postJson(CATALOG_FILES_PATH + "/import", ImportRequest(files)).body<ImportResponse>()
            importedIds += first.results.mapNotNull { it.fileId }

            assertEquals(34, first.results.size)
            assertEquals(32, first.results.count { it.status == ImportResultStatus.CREATED })
            assertEquals(2, first.results.count { it.status == ImportResultStatus.CREATED_WITH_FINDINGS })
            assertEquals(
                setOf("catalog-service", "legacy-invoicing"),
                first.results.filter { it.status == ImportResultStatus.CREATED_WITH_FINDINGS }.map { it.name }.toSet(),
            )

            val importedFindings = client.get(CATALOG_FILES_PATH + "/errors").body<ErrorsReport>().findings
                .filter { it.fileId in importedIds }
            assertEquals(34, importedFindings.count { it.status == ErrorStatus.SOURCE_MISSING })
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
            assertEquals(38, importedFindings.size, "34 source findings plus four deliberate reference findings")

            val second = client.postJson(CATALOG_FILES_PATH + "/import", ImportRequest(files)).body<ImportResponse>()
            assertEquals(34, second.results.size)
            assertTrue(second.results.all { it.status == ImportResultStatus.CONFLICT }, second.results.toString())
        } finally {
            importedIds.forEach { id ->
                val deleted = client.delete("$CATALOG_FILES_PATH/$id")
                assertEquals(HttpStatusCode.NoContent, deleted.status, "cleanup DELETE $id")
            }
        }
    }

    private fun sampleFiles(): List<CatalogFile> {
        val text = File("../sample-data/backstage/commerce-payments/catalog-info.yaml").readText()
        val yaml = ObjectMapper(YAMLFactory())
        val json = ObjectMapper()
        val documents = yaml.readerFor(JsonNode::class.java).readValues<JsonNode>(text).readAll()
        return documents.filterNot { it.isNull }.map { document ->
            (document as ObjectNode).remove("apiVersion")
            Json.decodeFromString(json.writeValueAsString(document))
        }
    }
}
