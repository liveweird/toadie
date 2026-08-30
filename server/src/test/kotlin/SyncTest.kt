package ch.nokillswit

import ch.nokillswit.catalog.CatalogFilePageResponse
import ch.nokillswit.catalog.CatalogFileResponse
import ch.nokillswit.catalog.CatalogFileWriteRequest
import ch.nokillswit.catalog.ErrorStatus
import ch.nokillswit.catalog.ErrorsReport
import ch.nokillswit.catalog.ImportRequest
import ch.nokillswit.catalog.ImportResponse
import ch.nokillswit.catalog.ImportResultStatus
import ch.nokillswit.catalog.SyncCatalogFileRequest
import ch.nokillswit.catalog.SyncStateResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The source-reference & repo-sync surface: `sourceUrl` on create/replace, the sync-state
 * read, the repo→DB sync, the `lastSyncedAt` sort field, the import-as-sync path, and the
 * report-only SOURCE_MISSING finding. Everything runs against the shared container, so
 * files are unique-named and cleaned up where they would pollute the errors report.
 */
class SyncTest {

    private fun sourceUrl(marker: String) = "https://example.com/$marker/catalog-info.yaml"

    private suspend fun HttpClient.createWithSource(
        request: CatalogFileWriteRequest,
    ): CatalogFileResponse = postJson(CATALOG_FILES_PATH, request).body()

    private fun withSource(file: ch.nokillswit.catalog.CatalogFile, url: String?) =
        CatalogFileWriteRequest(kind = file.kind, metadata = file.metadata, spec = file.spec, sourceUrl = url)

    @Test
    fun `a create with a sourceUrl stores the reference but starts unsynced`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("syncsrc")
        val name = uniqueEntityName("srced")
        val url = sourceUrl(name)

        val created = client.createWithSource(withSource(componentFile(name), url))
        assertEquals(url, created.sourceUrl)
        assertEquals(0L, created.lastSyncedAt)

        val state = client.get("$CATALOG_FILES_PATH/${created.id}/sync").body<SyncStateResponse>()
        assertEquals(url, state.sourceUrl)
        assertEquals(0L, state.lastSyncedAt)
        assertNull(state.syncedDocument)

        val row = client.get("$CATALOG_FILES_PATH?name=$name").body<CatalogFilePageResponse>().items.single()
        assertEquals(url, row.sourceUrl)
        assertEquals(0L, row.lastSyncedAt)
        client.delete("$CATALOG_FILES_PATH/${created.id}")
    }

    @Test
    fun `an invalid sourceUrl is a 400 on create and replace`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("syncbadurl")
        val name = uniqueEntityName("badurl")
        for (bad in listOf(
            "http://example.com/catalog-info.yaml", // not https
            "https://user:pw@example.com/catalog-info.yaml", // userinfo
            "not a url",
            "https://" + "a".repeat(2050) + ".com/x.yaml", // over the length cap
        )) {
            val response = client.postJson(CATALOG_FILES_PATH, withSource(componentFile(name), bad))
            assertEquals(HttpStatusCode.BadRequest, response.status, "expected 400 for $bad")
        }
        val id = client.createWithSource(withSource(componentFile(name), null)).id
        val put = client.putJson(
            "$CATALOG_FILES_PATH/$id",
            withSource(componentFile(name), "http://example.com/x.yaml"),
        )
        assertEquals(HttpStatusCode.BadRequest, put.status)
        client.delete("$CATALOG_FILES_PATH/$id")
    }

    @Test
    fun `a sync overwrites the document and stamps the sync state`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("syncstamp")
        val name = uniqueEntityName("stamped")
        val created = client.createWithSource(withSource(componentFile(name, title = "Old"), sourceUrl(name)))

        val repoCopy = componentFile(name, title = "From repo", lifecycle = "deprecated")
        val synced = client.postJson(
            "$CATALOG_FILES_PATH/${created.id}/sync",
            SyncCatalogFileRequest(document = repoCopy),
        )
        assertEquals(HttpStatusCode.NoContent, synced.status)

        val after = client.get("$CATALOG_FILES_PATH/${created.id}").body<CatalogFileResponse>()
        assertEquals("From repo", after.metadata.title)
        assertEquals("deprecated", after.spec.lifecycle)
        assertTrue(after.lastSyncedAt > 0)
        // The load-bearing stamp: sync sets both equal, so "DB changed" is updatedAt > lastSyncedAt.
        assertEquals(after.lastSyncedAt, after.updatedAt)

        val state = client.get("$CATALOG_FILES_PATH/${created.id}/sync").body<SyncStateResponse>()
        assertEquals("From repo", state.syncedDocument!!.metadata.title)

        // A later content edit bumps updatedAt past lastSyncedAt; the baseline stays put.
        client.putJson(
            "$CATALOG_FILES_PATH/${created.id}",
            withSource(componentFile(name, title = "Edited locally", lifecycle = "deprecated"), sourceUrl(name)),
        )
        val edited = client.get("$CATALOG_FILES_PATH/${created.id}").body<CatalogFileResponse>()
        assertTrue(edited.updatedAt > edited.lastSyncedAt)
        val stateAfterEdit = client.get("$CATALOG_FILES_PATH/${created.id}/sync").body<SyncStateResponse>()
        assertEquals("From repo", stateAfterEdit.syncedDocument!!.metadata.title)
        client.delete("$CATALOG_FILES_PATH/${created.id}")
    }

    @Test
    fun `a sync on a file without a source reference is a 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("syncnosrc")
        val name = uniqueEntityName("nosrc")
        val id = client.createWithSource(withSource(componentFile(name), null)).id
        // The source-less row still has a readable sync state — the all-default triple.
        val state = client.get("$CATALOG_FILES_PATH/$id/sync").body<SyncStateResponse>()
        assertEquals(null, state.sourceUrl)
        assertEquals(0, state.lastSyncedAt)
        assertEquals(null, state.syncedDocument)
        val response = client.postJson(
            "$CATALOG_FILES_PATH/$id/sync",
            SyncCatalogFileRequest(document = componentFile(name)),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        client.delete("$CATALOG_FILES_PATH/$id")
    }

    @Test
    fun `sync endpoints 404 on unknown ids and require authentication`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("sync404")
        assertEquals(HttpStatusCode.NotFound, client.get("$CATALOG_FILES_PATH/999999999/sync").status)
        val missing = client.postJson(
            "$CATALOG_FILES_PATH/999999999/sync",
            SyncCatalogFileRequest(document = componentFile(uniqueEntityName("ghost"))),
        )
        assertEquals(HttpStatusCode.NotFound, missing.status)
        val anonymous = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("$CATALOG_FILES_PATH/1/sync").status)
        val anonymousPost = anonymous.postJson(
            "$CATALOG_FILES_PATH/1/sync",
            SyncCatalogFileRequest(document = componentFile("x")),
        )
        assertEquals(HttpStatusCode.Unauthorized, anonymousPost.status)
    }

    @Test
    fun `a sync waives soft findings and audits them, structural rules stay hard`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("syncwaive")
        val name = uniqueEntityName("waived")
        val id = client.createWithSource(withSource(componentFile(name), sourceUrl(name))).id
        val ghostOwner = uniqueEntityName("ghost-team")

        withAuditCapture { capture ->
            val repoCopy = componentFile(name, owner = "group:default/$ghostOwner")
            val synced = client.postJson("$CATALOG_FILES_PATH/$id/sync", SyncCatalogFileRequest(document = repoCopy))
            assertEquals(HttpStatusCode.NoContent, synced.status)
            val event = capture.events.firstOrNull { it.message == "catalog_file.synced" }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("catalogFileId", id.toLong()))
            assertTrue(event.hasKeyValue("waivedFindings", 1))
        }
        // The waived finding lands on the Errors report (the import posture).
        val findings = client.get("$CATALOG_FILES_PATH/errors?name=$name").body<ErrorsReport>().findings
        assertTrue(findings.any { it.fileId == id && it.status == ErrorStatus.MISSING })

        // A structurally invalid repo copy is refused outright — no waiver for hard rules.
        val structural = client.postJson(
            "$CATALOG_FILES_PATH/$id/sync",
            SyncCatalogFileRequest(
                document = componentFile(name).let { it.copy(spec = it.spec.copy(type = null)) },
            ),
        )
        assertEquals(HttpStatusCode.BadRequest, structural.status)
        client.delete("$CATALOG_FILES_PATH/$id")
    }

    @Test
    fun `a repo-side rename landing on a taken identity is a 409`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("syncclash")
        val ns = uniqueNamespace("synclash")
        val taken = uniqueEntityName("taken")
        val name = uniqueEntityName("renamer")
        client.createCatalogFile(componentFile(taken, namespace = ns))
        val id = client.createWithSource(withSource(componentFile(name, namespace = ns), sourceUrl(name))).id
        val renamed = client.postJson(
            "$CATALOG_FILES_PATH/$id/sync",
            SyncCatalogFileRequest(document = componentFile(taken, namespace = ns)),
        )
        assertEquals(HttpStatusCode.Conflict, renamed.status)
    }

    @Test
    fun `changing or clearing the source reference resets the sync state without bumping updatedAt`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("syncreset")
            val name = uniqueEntityName("reset")
            val id = client.createWithSource(withSource(componentFile(name), sourceUrl(name))).id
            client.postJson("$CATALOG_FILES_PATH/$id/sync", SyncCatalogFileRequest(document = componentFile(name)))
            val synced = client.get("$CATALOG_FILES_PATH/$id").body<CatalogFileResponse>()
            assertTrue(synced.lastSyncedAt > 0)

            // Reference-only change: same document, different URL — sync state resets, the
            // document is untouched, and updatedAt does NOT move (no false "DB changed").
            val other = sourceUrl(uniqueEntityName("moved"))
            client.putJson("$CATALOG_FILES_PATH/$id", withSource(componentFile(name), other))
            val moved = client.get("$CATALOG_FILES_PATH/$id").body<CatalogFileResponse>()
            assertEquals(other, moved.sourceUrl)
            assertEquals(0L, moved.lastSyncedAt)
            assertEquals(synced.updatedAt, moved.updatedAt)
            assertNull(client.get("$CATALOG_FILES_PATH/$id/sync").body<SyncStateResponse>().syncedDocument)

            // Full-replace semantics: an omitted sourceUrl clears the reference.
            client.putJson("$CATALOG_FILES_PATH/$id", componentFile(name))
            val cleared = client.get("$CATALOG_FILES_PATH/$id").body<CatalogFileResponse>()
            assertNull(cleared.sourceUrl)
            client.delete("$CATALOG_FILES_PATH/$id")
        }

    @Test
    fun `the list sorts by lastSyncedAt with never-synced files first ascending`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("syncsort")
        val ns = uniqueNamespace("synsort")
        val never = uniqueEntityName("never")
        val fresh = uniqueEntityName("fresh")
        client.createWithSource(withSource(componentFile(never, namespace = ns), sourceUrl(never)))
        val freshId = client.createWithSource(withSource(componentFile(fresh, namespace = ns), sourceUrl(fresh))).id
        client.postJson(
            "$CATALOG_FILES_PATH/$freshId/sync",
            SyncCatalogFileRequest(document = componentFile(fresh, namespace = ns)),
        )

        val ascending = client.get("$CATALOG_FILES_PATH?namespace=$ns&sort=lastSyncedAt")
            .body<CatalogFilePageResponse>().items.map { it.name }
        assertEquals(listOf(never, fresh), ascending)
        val descending = client.get("$CATALOG_FILES_PATH?namespace=$ns&sort=-lastSyncedAt")
            .body<CatalogFilePageResponse>().items.map { it.name }
        assertEquals(listOf(fresh, never), descending)
    }

    @Test
    fun `an import with a sourceUrl stores the rows synced`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("syncimport")
        val ns = uniqueNamespace("synimp")
        val name = uniqueEntityName("imported")
        val url = sourceUrl(name)

        val response = client.postJson(
            "$CATALOG_FILES_PATH/import",
            ImportRequest(files = listOf(componentFile(name, namespace = ns)), sourceUrl = url),
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.body<ImportResponse>().results.single()
        assertEquals(ImportResultStatus.CREATED, result.status)

        val stored = client.get("$CATALOG_FILES_PATH/${result.fileId}").body<CatalogFileResponse>()
        assertEquals(url, stored.sourceUrl)
        assertTrue(stored.lastSyncedAt > 0)
        assertEquals(stored.updatedAt, stored.lastSyncedAt)
        val state = client.get("$CATALOG_FILES_PATH/${result.fileId}/sync").body<SyncStateResponse>()
        assertEquals(name, state.syncedDocument!!.metadata.name)

        // An invalid batch URL rejects the whole request before anything stores.
        val bad = client.postJson(
            "$CATALOG_FILES_PATH/import",
            ImportRequest(files = listOf(componentFile(uniqueEntityName("nope"), namespace = ns)), sourceUrl = "http://x"),
        )
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    @Test
    fun `a file without a source reference reports SOURCE_MISSING until one is set`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("syncerr")
        val name = uniqueEntityName("srcless")
        val id = client.createWithSource(withSource(componentFile(name), null)).id

        val before = client.get("$CATALOG_FILES_PATH/errors?name=$name").body<ErrorsReport>().findings
        assertEquals(
            listOf(Triple("source", "", ErrorStatus.SOURCE_MISSING)),
            before.filter { it.fileId == id }.map { Triple(it.field, it.reference, it.status) },
        )

        client.putJson("$CATALOG_FILES_PATH/$id", withSource(componentFile(name), sourceUrl(name)))
        val after = client.get("$CATALOG_FILES_PATH/errors?name=$name").body<ErrorsReport>().findings
        assertTrue(after.none { it.fileId == id })
        client.delete("$CATALOG_FILES_PATH/$id")
    }
}
