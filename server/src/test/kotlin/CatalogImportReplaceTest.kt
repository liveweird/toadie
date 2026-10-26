package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileEventPageResponse
import ch.nokillswit.catalog.CatalogFileEventType
import ch.nokillswit.catalog.CatalogFileResponse
import ch.nokillswit.catalog.CatalogFileServiceKey
import ch.nokillswit.catalog.CatalogFileWriteRequest
import ch.nokillswit.catalog.ImportFileResult
import ch.nokillswit.catalog.ImportRequest
import ch.nokillswit.catalog.ImportResponse
import ch.nokillswit.catalog.ImportResultStatus
import ch.nokillswit.catalog.SyncCatalogFileRequest
import ch.nokillswit.catalog.SyncStateResponse
import ch.nokillswit.catalog.`import`
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catalog import's `replaceExisting`: an identity an ACTIVE file holds is replaced in
 * place through the ordinary replace path instead of conflicting, with the dry-run predicting
 * the same statuses row by row. Every test works in a UNIQUE namespace.
 */
class CatalogImportReplaceTest {

    private suspend fun HttpClient.importFiles(
        files: List<CatalogFile>,
        replaceExisting: Boolean = true,
        sourceUrl: String? = null,
    ): List<ImportFileResult> = postJson(
        "$CATALOG_FILES_PATH/import",
        ImportRequest(files = files, sourceUrl = sourceUrl, replaceExisting = replaceExisting),
    ).body<ImportResponse>().results

    private suspend fun HttpClient.checkFiles(
        files: List<CatalogFile>,
        replaceExisting: Boolean = true,
        sourceUrl: String? = null,
    ): List<ImportFileResult> = postJson(
        "$CATALOG_FILES_PATH/import/check",
        ImportRequest(files = files, sourceUrl = sourceUrl, replaceExisting = replaceExisting),
    ).body<ImportResponse>().results

    private suspend fun HttpClient.file(id: UInt): CatalogFileResponse = get("$CATALOG_FILES_PATH/$id").body()

    private suspend fun HttpClient.events(id: UInt): CatalogFileEventPageResponse =
        get("$CATALOG_FILES_PATH/$id/events").body()

    private fun source(name: String) = "https://example.com/$name/catalog-info.yaml"

    private fun withSource(file: CatalogFile, url: String?) =
        CatalogFileWriteRequest(kind = file.kind, metadata = file.metadata, spec = file.spec, sourceUrl = url)

    @Test
    fun `without the flag an existing identity still conflicts and the file is untouched`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-off")
        val ns = uniqueNamespace("replnoff")
        val name = uniqueEntityName("off")
        val created = client.createCatalogFile(componentFile(name, namespace = ns, title = "Old"))
        val batch = listOf(componentFile(name, namespace = ns, title = "New"))

        assertEquals(ImportResultStatus.CONFLICT, client.importFiles(batch, replaceExisting = false).single().status)
        // The flag is optional on the wire: a body that never mentions it behaves the same.
        val omitted = client.postJson("$CATALOG_FILES_PATH/import", mapOf("files" to batch)).body<ImportResponse>()
        assertEquals(ImportResultStatus.CONFLICT, omitted.results.single().status)
        assertEquals(ImportResultStatus.CONFLICT, client.checkFiles(batch, replaceExisting = false).single().status)

        val stored = client.file(created.id)
        assertEquals("Old", stored.metadata.title)
        assertEquals(created.revision, stored.revision)
        assertEquals(listOf(CatalogFileEventType.CREATED), client.events(created.id).items.map { it.type })
    }

    @Test
    fun `a replaced file is UPDATED in place - revision bump, field-diff history, same id`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-upd")
        val ns = uniqueNamespace("replupd")
        val name = uniqueEntityName("upd")
        val created = client.createCatalogFile(componentFile(name, namespace = ns, title = "Old"))
        val batch = listOf(componentFile(name, namespace = ns, title = "New"))

        // The dry run predicts it, stores nothing, and carries no fileId.
        val predicted = client.checkFiles(batch).single()
        assertEquals(ImportResultStatus.UPDATED, predicted.status)
        assertNull(predicted.fileId)
        assertEquals("Old", client.file(created.id).metadata.title)
        assertEquals(created.revision, client.file(created.id).revision)

        val row = client.importFiles(batch).single()
        assertEquals(ImportResultStatus.UPDATED, row.status)
        assertEquals(created.id, row.fileId, "replaced in place - never delete-and-create")
        assertNull(row.message)

        val stored = client.file(created.id)
        assertEquals("New", stored.metadata.title)
        assertEquals(created.revision + 1, stored.revision)
        val events = client.events(created.id).items
        assertEquals(listOf(CatalogFileEventType.UPDATED, CatalogFileEventType.CREATED), events.map { it.type })
        val update = events.first()
        assertEquals("metadata.title", update.params["changed"])
        assertEquals("Old", update.params["metadata.title.from"])
        assertEquals("New", update.params["metadata.title.to"])
        // A second identical import is then the no-op.
        assertEquals(ImportResultStatus.UNCHANGED, client.importFiles(batch).single().status)
    }

    @Test
    fun `waived soft findings give UPDATED_WITH_FINDINGS and an audited count`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-find")
        val ns = uniqueNamespace("replfind")
        val name = uniqueEntityName("find")
        val created = client.createCatalogFile(componentFile(name, namespace = ns))
        val ghost = uniqueEntityName("ghost-team")
        val batch = listOf(componentFile(name, namespace = ns, owner = "group:default/$ghost"))

        val predicted = client.checkFiles(batch).single()
        assertEquals(ImportResultStatus.UPDATED_WITH_FINDINGS, predicted.status)
        assertTrue(predicted.message!!.contains("does not resolve"))

        withAuditCapture { capture ->
            val row = client.importFiles(batch).single()
            assertEquals(ImportResultStatus.UPDATED_WITH_FINDINGS, row.status)
            assertEquals(created.id, row.fileId)
            assertEquals(predicted.message, row.message)
            val event = capture.awaitEvent {
                it.message == "catalog_file.updated" && it.hasKeyValue("catalogFileId", created.id.toLong())
            }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("waivedFindings", 1))
            assertTrue(event.hasKeyValue("import", true))
            assertTrue(event.hasKeyValue("withFindings", true))
        }
        // The waived finding was stored: the file really carries the ghost owner.
        assertEquals("group:default/$ghost", client.file(created.id).spec.owner)
    }

    @Test
    fun `without a batch URL an identical re-import is UNCHANGED and writes nothing`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-noop")
        val ns = uniqueNamespace("replnoop")
        val name = uniqueEntityName("noop")
        val created = client.createCatalogFile(componentFile(name, namespace = ns, title = "Same"))
        val batch = listOf(componentFile(name, namespace = ns, title = "Same"))

        val predicted = client.checkFiles(batch).single()
        assertEquals(ImportResultStatus.UNCHANGED, predicted.status)
        assertNull(predicted.fileId)

        val row = client.importFiles(batch).single()
        assertEquals(ImportResultStatus.UNCHANGED, row.status)
        assertEquals(created.id, row.fileId)
        // Keep posture: nothing at all was written - no revision, stamp or event.
        val after = client.file(created.id)
        assertEquals(created.revision, after.revision)
        assertEquals(created.updatedAt, after.updatedAt)
        assertEquals(created.lastSyncedAt, after.lastSyncedAt)
        assertEquals(listOf(CatalogFileEventType.CREATED), client.events(created.id).items.map { it.type })
    }

    @Test
    fun `an unchanged file that carries findings still reports them`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-noopf")
        val ns = uniqueNamespace("replnopf")
        val name = uniqueEntityName("noopf")
        val ghosty = componentFile(name, namespace = ns, owner = "group:default/${uniqueEntityName("ghost")}")
        client.postJson("$CATALOG_FILES_PATH?allowInvalid=true", ghosty)

        val predicted = client.checkFiles(listOf(ghosty)).single()
        val row = client.importFiles(listOf(ghosty)).single()
        assertEquals(ImportResultStatus.UNCHANGED, row.status)
        assertEquals(predicted.status, row.status)
        assertTrue(row.message!!.contains("does not resolve"))
        assertEquals(predicted.message, row.message)
    }

    @Test
    fun `a batch without a sourceUrl keeps the reference and sync state, one with a URL re-stamps it`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("implace-src")
            val ns = uniqueNamespace("replsrc")
            val name = uniqueEntityName("src")
            val original = source(name)
            val created: CatalogFileResponse = client.postJson(
                CATALOG_FILES_PATH,
                withSource(componentFile(name, namespace = ns, title = "Old"), original),
            ).body()

            // No batch URL: the document changes, the reference and (never-synced) state stay.
            client.importFiles(listOf(componentFile(name, namespace = ns, title = "Mid"))).single().also {
                assertEquals(ImportResultStatus.UPDATED, it.status)
            }
            val kept = client.file(created.id)
            assertEquals(original, kept.sourceUrl)
            assertEquals(0L, kept.lastSyncedAt)
            val keptEvent = client.events(created.id).items.first()
            assertFalse("sourceUrl" in keptEvent.params["changed"]!!.split(","))

            // A batch URL differing from the stored one: the reference moves, stamped synced with a baseline.
            val moved = source(uniqueEntityName("moved"))
            val newDoc = componentFile(name, namespace = ns, title = "New")
            assertEquals(ImportResultStatus.UPDATED, client.checkFiles(listOf(newDoc), sourceUrl = moved).single().status)
            assertEquals(ImportResultStatus.UPDATED, client.importFiles(listOf(newDoc), sourceUrl = moved).single().status)
            val restamped = client.file(created.id)
            assertEquals(moved, restamped.sourceUrl)
            assertTrue(restamped.lastSyncedAt > 0)
            assertEquals(restamped.updatedAt, restamped.lastSyncedAt)
            val state = client.get("$CATALOG_FILES_PATH/${created.id}/sync").body<SyncStateResponse>()
            assertEquals("New", state.syncedDocument!!.metadata.title)
            val movedEvent = client.events(created.id).items.first()
            assertEquals(listOf("metadata.title", "sourceUrl"), movedEvent.params["changed"]!!.split(","))

            // The same document from the same URL again: UNCHANGED for the document, but under a
            // batch URL it is still a RECORDED repo sync (a matching re-sync): SYNCED event, revision +1.
            val revision = restamped.revision
            val eventCount = client.events(created.id).total
            assertEquals(ImportResultStatus.UNCHANGED, client.checkFiles(listOf(newDoc), sourceUrl = moved).single().status)
            assertEquals(ImportResultStatus.UNCHANGED, client.importFiles(listOf(newDoc), sourceUrl = moved).single().status)
            val again = client.file(created.id)
            assertEquals(revision + 1, again.revision)
            assertEquals(eventCount + 1, client.events(created.id).total)
            assertEquals(CatalogFileEventType.SYNCED, client.events(created.id).items.first().type)
            assertTrue(again.lastSyncedAt >= restamped.lastSyncedAt)
            assertEquals(again.updatedAt, again.lastSyncedAt)

            // Same document, a DIFFERENT URL: the reference moves, so it is an update.
            val other = source(uniqueEntityName("other"))
            assertEquals(ImportResultStatus.UPDATED, client.checkFiles(listOf(newDoc), sourceUrl = other).single().status)
            assertEquals(ImportResultStatus.UPDATED, client.importFiles(listOf(newDoc), sourceUrl = other).single().status)
            assertEquals(other, client.file(created.id).sourceUrl)
        }

    @Test
    fun `an in-batch duplicate stays CONFLICT whether or not the identity existed`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-dup")
        val ns = uniqueNamespace("repldup")
        val existing = uniqueEntityName("dupex")
        val fresh = uniqueEntityName("dupnew")
        val created = client.createCatalogFile(componentFile(existing, namespace = ns, title = "Old"))
        val batch = listOf(
            componentFile(existing, namespace = ns, title = "First"),
            componentFile(existing, namespace = ns, title = "Second"),
            componentFile(fresh, namespace = ns),
            // Case-insensitive identity: the same name in another case is the same file.
            componentFile(fresh.uppercase(), namespace = ns),
        )

        val predicted = client.checkFiles(batch)
        val real = client.importFiles(batch)
        val expected = listOf(
            ImportResultStatus.UPDATED,
            ImportResultStatus.CONFLICT,
            ImportResultStatus.CREATED,
            ImportResultStatus.CONFLICT,
        )
        assertEquals(expected, predicted.map { it.status })
        assertEquals(expected, real.map { it.status })
        // The first occurrence won; the loser did not overwrite it.
        assertEquals("First", client.file(created.id).metadata.title)
    }

    @Test
    fun `a soft-deleted identity is created again, never resurrected`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-del")
        val ns = uniqueNamespace("repldel")
        val name = uniqueEntityName("del")
        val gone = client.createCatalogFile(componentFile(name, namespace = ns))
        assertEquals(HttpStatusCode.NoContent, client.delete("$CATALOG_FILES_PATH/${gone.id}").status)
        val batch = listOf(componentFile(name, namespace = ns, title = "Back"))

        assertEquals(ImportResultStatus.CREATED, client.checkFiles(batch).single().status)
        val row = client.importFiles(batch).single()
        assertEquals(ImportResultStatus.CREATED, row.status)
        assertNotEquals(gone.id, row.fileId)
        assertEquals(HttpStatusCode.NotFound, client.get("$CATALOG_FILES_PATH/${gone.id}").status)
        assertEquals("Back", client.file(checkNotNull(row.fileId)).metadata.title)
    }

    @Test
    fun `the dry-run matches the real run row by row on a mixed batch`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-mix")
        val ns = uniqueNamespace("replmix")
        val changed = uniqueEntityName("chg")
        val same = uniqueEntityName("same")
        client.createCatalogFile(componentFile(changed, namespace = ns, title = "Old"))
        client.createCatalogFile(componentFile(same, namespace = ns, title = "Same"))
        val invalid = CatalogFile(
            metadata = ch.nokillswit.catalog.CatalogFileMetadata(name = uniqueEntityName("bad"), namespace = ns),
            spec = ch.nokillswit.catalog.EntitySpec(),
        )
        val batch = listOf(
            componentFile(uniqueEntityName("new"), namespace = ns),
            componentFile(changed, namespace = ns, title = "New"),
            componentFile(same, namespace = ns, title = "Same"),
            invalid,
            componentFile(changed, namespace = ns, title = "Again"),
            componentFile(uniqueEntityName("ghosty"), namespace = ns, owner = "group:default/${uniqueEntityName("g")}"),
        )

        val predicted = client.checkFiles(batch)
        val real = client.importFiles(batch)
        assertEquals(
            listOf(
                ImportResultStatus.CREATED,
                ImportResultStatus.UPDATED,
                ImportResultStatus.UNCHANGED,
                ImportResultStatus.INVALID,
                ImportResultStatus.CONFLICT,
                ImportResultStatus.CREATED_WITH_FINDINGS,
            ),
            real.map { it.status },
        )
        assertEquals(real.map { it.status }, predicted.map { it.status })
        assertEquals(real.map { it.message }, predicted.map { it.message })
        assertTrue(predicted.all { it.fileId == null })
    }

    @Test
    fun `under a batch URL an unchanged never-synced file is a recorded sync, exactly like the sync endpoint`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("implace-basesync")
            val ns = uniqueNamespace("replbase")
            val name = uniqueEntityName("base")
            val url = source(name)
            val doc = componentFile(name, namespace = ns, title = "Same")
            // An editor-typed reference: the file was never synced from it.
            val created: CatalogFileResponse = client.postJson(CATALOG_FILES_PATH, withSource(doc, url)).body()
            assertEquals(0L, created.lastSyncedAt)

            assertEquals(ImportResultStatus.UNCHANGED, client.checkFiles(listOf(doc), sourceUrl = url).single().status)
            val row = client.importFiles(listOf(doc), sourceUrl = url).single()
            assertEquals(ImportResultStatus.UNCHANGED, row.status)
            assertEquals(created.id, row.fileId)

            val synced = client.file(created.id)
            assertEquals(created.revision + 1, synced.revision, "the sync path bumps the revision")
            assertTrue(synced.lastSyncedAt > 0)
            assertEquals(synced.updatedAt, synced.lastSyncedAt)
            val state = client.get("$CATALOG_FILES_PATH/${created.id}/sync").body<SyncStateResponse>()
            assertEquals("Same", state.syncedDocument!!.metadata.title)
            val first = client.events(created.id).items.first()
            assertEquals(CatalogFileEventType.SYNCED, first.type)
            assertTrue(first.params.isEmpty(), "a matching sync records an empty change list")

            // The sync endpoint on the same matching copy does exactly the same thing.
            client.postJson("$CATALOG_FILES_PATH/${created.id}/sync", SyncCatalogFileRequest(document = doc))
            val resynced = client.file(created.id)
            assertEquals(synced.revision + 1, resynced.revision)
            assertEquals(
                listOf(CatalogFileEventType.SYNCED, CatalogFileEventType.SYNCED, CatalogFileEventType.CREATED),
                client.events(created.id).items.map { it.type },
            )
        }

    @Test
    fun `replacing through a differently-cased name updates in place and shows the casing as a diff`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("implace-case")
            val ns = uniqueNamespace("replcase")
            val name = uniqueEntityName("Case")
            val created = client.createCatalogFile(componentFile(name, namespace = ns))
            val batch = listOf(componentFile(name.lowercase(), namespace = ns))

            assertEquals(ImportResultStatus.UPDATED, client.checkFiles(batch).single().status)
            val row = client.importFiles(batch).single()
            assertEquals(ImportResultStatus.UPDATED, row.status)
            assertEquals(created.id, row.fileId)
            assertEquals(name.lowercase(), client.file(created.id).metadata.name)
            val update = client.events(created.id).items.first()
            assertEquals("metadata.name", update.params["changed"])
            assertEquals(name, update.params["metadata.name.from"])
            assertEquals(name.lowercase(), update.params["metadata.name.to"])
        }

    @Test
    fun `a blank namespace resolves to the default and replaces the file stored there`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-blank")
        val custom = uniqueEntityName("replblank")
        TestNamespaces.withDefaultNamespace(custom) {
            val name = uniqueEntityName("blank")
            val created = client.createCatalogFile(componentFile(name, namespace = custom, title = "Old"))
            val batch = listOf(componentFile(name, namespace = "", title = "New"))

            val predicted = client.checkFiles(batch).single()
            assertEquals(ImportResultStatus.UPDATED, predicted.status)
            assertEquals(custom, predicted.namespace)
            val row = client.importFiles(batch).single()
            assertEquals(ImportResultStatus.UPDATED, row.status)
            assertEquals(custom, row.namespace)
            assertEquals(created.id, row.fileId)
            assertEquals("New", client.file(created.id).metadata.title)
        }
    }

    @Test
    fun `every replaced row audits catalog_file updated, UNCHANGED included`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-audit")
        val ns = uniqueNamespace("replaudit")
        val name = uniqueEntityName("audit")
        val created = client.createCatalogFile(componentFile(name, namespace = ns, title = "Same"))

        withAuditCapture { capture ->
            val row = client.importFiles(listOf(componentFile(name, namespace = ns, title = "Same"))).single()
            assertEquals(ImportResultStatus.UNCHANGED, row.status)
            val event = capture.awaitEvent {
                it.message == "catalog_file.updated" && it.hasKeyValue("catalogFileId", created.id.toLong())
            }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("import", true))
            assertFalse(event.keyValuePairs.orEmpty().any { it.key == "waivedFindings" || it.key == "withFindings" })
        }
    }

    @Test
    fun `a file that vanishes between the lookup and the update is created instead`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-vanish")
        val userId = TestUsers.seed(uniqueEmail("implace-vanish-actor"), "pw", role = UserRole.USER)
        val service = application.attributes[CatalogFileServiceKey]
        val ns = uniqueNamespace("replvanish")
        val name = uniqueEntityName("vanish")
        val gone = client.createCatalogFile(componentFile(name, namespace = ns))
        assertEquals(HttpStatusCode.NoContent, client.delete("$CATALOG_FILES_PATH/${gone.id}").status)

        // The lookup still answers the (now soft-deleted) id: the locked update finds no active row.
        val row = service.import(
            listOf(componentFile(name, namespace = ns)),
            userId,
            replaceExisting = true,
            activeIdLookup = { gone.id },
        ).single()
        assertEquals(ImportResultStatus.CREATED, row.status)
        assertNotEquals(gone.id, row.fileId)
        assertEquals(HttpStatusCode.OK, client.get("$CATALOG_FILES_PATH/${row.fileId}").status)
    }
}
