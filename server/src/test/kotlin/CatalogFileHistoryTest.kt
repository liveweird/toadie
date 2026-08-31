package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileEventPageResponse
import ch.nokillswit.catalog.CatalogFileEventType
import ch.nokillswit.catalog.CatalogFileResponse
import ch.nokillswit.catalog.CatalogFileWriteRequest
import ch.nokillswit.catalog.ImportRequest
import ch.nokillswit.catalog.ImportResponse
import ch.nokillswit.catalog.SyncCatalogFileRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The history endpoint's wiring: which mutations mint which event, the newest-first order,
 * paging, the read rule, and the params-stay-content-free invariant at the raw column. The
 * diff itself is pinned by the pure `CatalogFileEventsTest`.
 */
class CatalogFileHistoryTest {

    private suspend fun HttpClient.events(id: UInt, query: String = ""): CatalogFileEventPageResponse =
        get("$CATALOG_FILES_PATH/$id/events$query").body()

    private fun withSource(file: CatalogFile, url: String?) =
        CatalogFileWriteRequest(kind = file.kind, metadata = file.metadata, spec = file.spec, sourceUrl = url)

    @Test
    fun `the trail records every mutation, newest first, with the acting user resolved`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("history")
        val name = uniqueEntityName("hist")
        val url = "https://example.com/$name/catalog-info.yaml"

        val created: CatalogFileResponse =
            client.postJson(CATALOG_FILES_PATH, withSource(componentFile(name), url)).body()
        client.putJson(
            "$CATALOG_FILES_PATH/${created.id}",
            withSource(componentFile(name, owner = "group:default/platform", title = "Checkout"), url),
        )
        client.postJson(
            "$CATALOG_FILES_PATH/${created.id}/sync",
            SyncCatalogFileRequest(document = componentFile(name, title = "From the repo")),
        )

        val page = client.events(created.id)
        assertEquals(3, page.total.toInt())
        assertEquals(
            listOf(CatalogFileEventType.SYNCED, CatalogFileEventType.UPDATED, CatalogFileEventType.CREATED),
            page.items.map { it.type },
        )
        page.items.forEach { assertFalse(it.userName.isBlank()) }
        assertEquals("Component", page.items.last().params["kind"])
        // The edit set a title that was absent before: the `to` side alone.
        val updated = page.items[1]
        assertEquals("metadata.title", updated.params["changed"])
        assertEquals("Checkout", updated.params["metadata.title.to"])
        // The sync overwrote it with the repo's value: a real from→to pair.
        val synced = page.items.first()
        assertEquals("Checkout", synced.params["metadata.title.from"])
        assertEquals("From the repo", synced.params["metadata.title.to"])

        client.delete("$CATALOG_FILES_PATH/${created.id}")
    }

    @Test
    fun `a save that changed nothing records nothing`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("histnoop")
        val name = uniqueEntityName("noop")
        val created = client.createCatalogFile(componentFile(name))

        client.putJson("$CATALOG_FILES_PATH/${created.id}", componentFile(name))

        val page = client.events(created.id)
        assertEquals(listOf(CatalogFileEventType.CREATED), page.items.map { it.type })

        client.delete("$CATALOG_FILES_PATH/${created.id}")
    }

    @Test
    fun `label and tag edits name the entries that moved`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("histfields")
        val labelKey = uniqueLabel("histlbl", values = listOf("gold", "silver"))
        val tag = uniqueTag("histtag")
        uniqueTagCategory("histcat", tags = listOf(tag))
        val name = uniqueEntityName("fields")

        val created = client.createCatalogFile(componentFile(name))
        val edited = componentFile(name, owner = "group:default/platform").let {
            it.copy(metadata = it.metadata.copy(labels = mapOf(labelKey to "gold"), tags = listOf(tag)))
        }
        client.putJson("$CATALOG_FILES_PATH/${created.id}", edited)

        val updated = client.events(created.id).items.first()
        assertEquals("metadata.labels.$labelKey,metadata.tags", updated.params["changed"])
        assertEquals("gold", updated.params["metadata.labels.$labelKey.to"])
        assertEquals(tag, updated.params["metadata.tags.added"])

        client.delete("$CATALOG_FILES_PATH/${created.id}")
    }

    @Test
    fun `free text never reaches the stored params`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("histtext")
        val name = uniqueEntityName("text")
        val created = client.createCatalogFile(componentFile(name))

        val described = componentFile(name).let {
            it.copy(metadata = it.metadata.copy(description = "Strictly internal wording nobody should log."))
        }
        client.putJson("$CATALOG_FILES_PATH/${created.id}", described)

        val updated = client.events(created.id).items.first()
        assertEquals("metadata.description", updated.params["changed"])
        assertFalse(updated.params.containsKey("metadata.description.to"))
        assertTrue(
            TestCatalogFiles.rawEventParams(created.id).none { it.contains("internal wording") },
            "the description text reached the event params",
        )

        client.delete("$CATALOG_FILES_PATH/${created.id}")
    }

    @Test
    fun `an imported file's history marks the import origin`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("histimport")
        val name = uniqueEntityName("imported")

        val results: ImportResponse =
            client.postJson("$CATALOG_FILES_PATH/import", ImportRequest(files = listOf(componentFile(name)))).body()
        val fileId = checkNotNull(results.results.single().fileId)

        val event = client.events(fileId).items.single()
        assertEquals(CatalogFileEventType.CREATED, event.type)
        assertEquals("import", event.params["origin"])

        client.delete("$CATALOG_FILES_PATH/$fileId")
    }

    @Test
    fun `the history is paged and sorts only on its own fields`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("histpage")
        val name = uniqueEntityName("paged")
        val created = client.createCatalogFile(componentFile(name))
        client.putJson("$CATALOG_FILES_PATH/${created.id}", componentFile(name, title = "One"))
        client.putJson("$CATALOG_FILES_PATH/${created.id}", componentFile(name, title = "Two"))

        val first = client.events(created.id, "?pageSize=1")
        assertEquals(3, first.total.toInt())
        assertEquals(1, first.items.size)
        assertEquals("Two", first.items.single().params["metadata.title.to"])

        val second = client.events(created.id, "?pageSize=1&page=2")
        assertEquals("One", second.items.single().params["metadata.title.to"])

        // Oldest first is one explicit sort away.
        val oldest = client.events(created.id, "?sort=timestamp,id").items.first()
        assertEquals(CatalogFileEventType.CREATED, oldest.type)

        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("$CATALOG_FILES_PATH/${created.id}/events?sort=name").status,
        )

        client.delete("$CATALOG_FILES_PATH/${created.id}")
    }

    @Test
    fun `the history of an unknown or deleted file is a plain 404`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("hist404")
        val created = client.createCatalogFile(componentFile(uniqueEntityName("gone")))
        client.delete("$CATALOG_FILES_PATH/${created.id}")

        assertEquals(HttpStatusCode.NotFound, client.get("$CATALOG_FILES_PATH/${created.id}/events").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$CATALOG_FILES_PATH/999999/events").status)
        // The deletion event still exists — the trail outlives the (soft-deleted) file.
        assertTrue(TestCatalogFiles.rawEventParams(created.id).size >= 2)
    }

    @Test
    fun `reading a history requires authentication`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("$CATALOG_FILES_PATH/1/events").status)
    }
}
