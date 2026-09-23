package ch.nokillswit

import ch.nokillswit.catalog.CatalogFileEventPageResponse
import ch.nokillswit.catalog.CatalogFilePageResponse
import ch.nokillswit.catalog.CatalogFileResponse
import ch.nokillswit.catalog.CatalogFileWriteRequest
import ch.nokillswit.catalog.CatalogGraph
import ch.nokillswit.catalog.EXPECTED_REVISION_HEADER
import ch.nokillswit.catalog.SyncCatalogFileRequest
import ch.nokillswit.catalog.SyncStateResponse
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogFileConcurrencyTest {
    private fun writeRequest(file: ch.nokillswit.catalog.CatalogFile, sourceUrl: String? = null) =
        CatalogFileWriteRequest(file.kind, file.metadata, file.spec, sourceUrl)

    private suspend fun HttpClient.guardedPut(id: UInt, revision: Any, body: CatalogFileWriteRequest) =
        put("$CATALOG_FILES_PATH/$id") {
            header(EXPECTED_REVISION_HEADER, revision)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.guardedSync(id: UInt, revision: Long, document: ch.nokillswit.catalog.CatalogFile) =
        post("$CATALOG_FILES_PATH/$id/sync") {
            header(EXPECTED_REVISION_HEADER, revision)
            contentType(ContentType.Application.Json)
            setBody(SyncCatalogFileRequest(document))
        }

    private suspend fun HttpClient.guardedDelete(id: UInt, revision: Any) =
        delete("$CATALOG_FILES_PATH/$id") { header(EXPECTED_REVISION_HEADER, revision) }

    @Test
    fun `revision is exposed everywhere and advances only when a replacement changes state`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("catalog-revision")
        val name = uniqueEntityName("revision")
        val document = componentFile(name, title = "Original")
        val created = client.postJson(CATALOG_FILES_PATH, writeRequest(document)).body<CatalogFileResponse>()
        assertEquals(1, created.revision)

        assertEquals(
            created.revision,
            client.get("$CATALOG_FILES_PATH?name=$name").body<CatalogFilePageResponse>().items.single().revision,
        )
        val graphNode = client.get("$CATALOG_FILES_PATH/graph?name=$name")
            .body<CatalogGraph>().nodes.single { it.fileId == created.id }
        assertEquals(created.revision, graphNode.revision)
        assertEquals(
            created.revision,
            client.get("$CATALOG_FILES_PATH/${created.id}/sync").body<SyncStateResponse>().revision,
        )

        val noOp = client.guardedPut(created.id, created.revision, writeRequest(document))
        assertEquals(HttpStatusCode.NoContent, noOp.status)
        val afterNoOp = client.get("$CATALOG_FILES_PATH/${created.id}").body<CatalogFileResponse>()
        assertEquals(created.revision, afterNoOp.revision)

        val source = "https://example.com/$name/catalog-info.yaml"
        val sourceOnly = client.guardedPut(created.id, afterNoOp.revision, writeRequest(document, source))
        assertEquals(HttpStatusCode.NoContent, sourceOnly.status)
        val afterSource = client.get("$CATALOG_FILES_PATH/${created.id}").body<CatalogFileResponse>()
        assertEquals(afterNoOp.revision + 1, afterSource.revision)
        assertEquals(afterNoOp.updatedAt, afterSource.updatedAt)
        assertEquals(source, afterSource.sourceUrl)

        val eventCount = client.get("$CATALOG_FILES_PATH/${created.id}/events").body<CatalogFileEventPageResponse>().total
        val stale = client.guardedPut(
            created.id,
            created.revision,
            writeRequest(componentFile(name, title = "Stale"), source),
        )
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("urn:toadie:catalog-revision-conflict", stale.body<ProblemDetail>().type)
        val unchanged = client.get("$CATALOG_FILES_PATH/${created.id}").body<CatalogFileResponse>()
        assertEquals(afterSource, unchanged)
        assertEquals(
            eventCount,
            client.get("$CATALOG_FILES_PATH/${created.id}/events").body<CatalogFileEventPageResponse>().total,
        )
    }

    @Test
    fun `sync and delete reject stale revisions while legacy writes remain detectable`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("catalog-sync-revision")
        val name = uniqueEntityName("sync-revision")
        val document = componentFile(name)
        val source = "https://example.com/$name/catalog-info.yaml"
        val created = client.postJson(CATALOG_FILES_PATH, writeRequest(document, source)).body<CatalogFileResponse>()

        val synced = client.guardedSync(created.id, created.revision, document)
        assertEquals(HttpStatusCode.NoContent, synced.status)
        val afterSync = client.get("$CATALOG_FILES_PATH/${created.id}").body<CatalogFileResponse>()
        assertEquals(created.revision + 1, afterSync.revision)
        assertEquals(afterSync.revision, client.get("$CATALOG_FILES_PATH/${created.id}/sync").body<SyncStateResponse>().revision)

        val staleSync = client.guardedSync(created.id, created.revision, document)
        assertEquals(HttpStatusCode.Conflict, staleSync.status)
        assertEquals("urn:toadie:catalog-revision-conflict", staleSync.body<ProblemDetail>().type)
        val staleDelete = client.guardedDelete(created.id, created.revision)
        assertEquals(HttpStatusCode.Conflict, staleDelete.status)
        assertEquals("urn:toadie:catalog-revision-conflict", staleDelete.body<ProblemDetail>().type)
        assertEquals(afterSync, client.get("$CATALOG_FILES_PATH/${created.id}").body<CatalogFileResponse>())
        assertEquals(
            2L,
            client.get("$CATALOG_FILES_PATH/${created.id}/events").body<CatalogFileEventPageResponse>().total,
        )

        val legacy = client.putJson(
            "$CATALOG_FILES_PATH/${created.id}",
            writeRequest(componentFile(name, title = "Legacy"), source),
        )
        assertEquals(HttpStatusCode.NoContent, legacy.status)
        val afterLegacy = client.get("$CATALOG_FILES_PATH/${created.id}").body<CatalogFileResponse>()
        assertEquals(afterSync.revision + 1, afterLegacy.revision)
        assertEquals(HttpStatusCode.Conflict, client.guardedDelete(created.id, afterSync.revision).status)
        assertEquals(HttpStatusCode.NoContent, client.guardedDelete(created.id, afterLegacy.revision).status)
    }

    @Test
    fun `malformed expected revisions are rejected before mutation`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("catalog-bad-revision")
        val name = uniqueEntityName("bad-revision")
        val document = componentFile(name)
        val created = client.createCatalogFile(document)

        for (bad in listOf("0", "-1", "not-a-number", "9223372036854775808")) {
            val response = client.guardedPut(created.id, bad, writeRequest(document.copy(metadata = document.metadata.copy(title = bad))))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.body<ProblemDetail>().detail.orEmpty().contains(EXPECTED_REVISION_HEADER))
        }
        val duplicate = client.put("$CATALOG_FILES_PATH/${created.id}") {
            headers {
                append(EXPECTED_REVISION_HEADER, created.revision.toString())
                append(EXPECTED_REVISION_HEADER, created.revision.toString())
            }
            contentType(ContentType.Application.Json)
            setBody(writeRequest(document.copy(metadata = document.metadata.copy(title = "duplicate"))))
        }
        assertEquals(HttpStatusCode.BadRequest, duplicate.status)
        assertTrue(duplicate.body<ProblemDetail>().detail.orEmpty().contains(EXPECTED_REVISION_HEADER))
        assertEquals(created, client.get("$CATALOG_FILES_PATH/${created.id}").body<CatalogFileResponse>())
    }
}
