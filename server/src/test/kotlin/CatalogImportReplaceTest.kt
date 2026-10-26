package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileEventPageResponse
import ch.nokillswit.catalog.CatalogFileEventType
import ch.nokillswit.catalog.CatalogFileResponse
import ch.nokillswit.catalog.CatalogFileServiceKey
import ch.nokillswit.catalog.CatalogFileWriteRequest
import ch.nokillswit.catalog.ExpectedFileState
import ch.nokillswit.catalog.ImportFileResult
import ch.nokillswit.catalog.ImportRequest
import ch.nokillswit.catalog.ImportResponse
import ch.nokillswit.catalog.ImportResultStatus
import ch.nokillswit.catalog.SyncCatalogFileRequest
import ch.nokillswit.catalog.StoredRevision
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
        expected: List<ExpectedFileState> = emptyList(),
    ): List<ImportFileResult> = postJson(
        "$CATALOG_FILES_PATH/import",
        ImportRequest(files = files, sourceUrl = sourceUrl, replaceExisting = replaceExisting, expected = expected),
    ).body<ImportResponse>().results

    private suspend fun HttpClient.checkFiles(
        files: List<CatalogFile>,
        replaceExisting: Boolean = true,
        sourceUrl: String? = null,
        expected: List<ExpectedFileState> = emptyList(),
    ): List<ImportFileResult> = postJson(
        "$CATALOG_FILES_PATH/import/check",
        ImportRequest(files = files, sourceUrl = sourceUrl, replaceExisting = replaceExisting, expected = expected),
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
            activeRowLookup = { StoredRevision(gone.id, 1) },
        ).single()
        assertEquals(ImportResultStatus.CREATED, row.status)
        assertNotEquals(gone.id, row.fileId)
        assertEquals(HttpStatusCode.OK, client.get("$CATALOG_FILES_PATH/${row.fileId}").status)
    }

    // -- expected revisions (2.29.0) -----------------------------------------------------------------------

    /** Edits the file through the ordinary PUT and returns its new revision. */
    private suspend fun HttpClient.edit(id: UInt, file: CatalogFile): Long {
        val response = putJson("$CATALOG_FILES_PATH/$id", file)
        assertTrue(response.status.value in 200..299, response.status.toString())
        return file(id).revision
    }

    @Test
    fun `a matching revision replaces as before, UNCHANGED included, and a stale one is STALE with nothing written`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-exp")
        val ns = uniqueNamespace("replexp")
        val fresh = uniqueEntityName("fresh")
        val stale = uniqueEntityName("stale")
        val freshFile = client.createCatalogFile(componentFile(fresh, namespace = ns, title = "Old"))
        val staleFile = client.createCatalogFile(componentFile(stale, namespace = ns, title = "Old"))
        val editedRevision = client.edit(staleFile.id, componentFile(stale, namespace = ns, title = "Edited elsewhere"))
        assertEquals(staleFile.revision + 1, editedRevision)
        val eventsBefore = client.events(staleFile.id).items.size
        val batch = listOf(componentFile(fresh, namespace = ns, title = "New"), componentFile(stale, namespace = ns, title = "New"))
        // file 0 holds the matching token, file 1 the pre-edit one.
        val expected = listOf(ExpectedFileState(0, freshFile.revision), ExpectedFileState(1, staleFile.revision))

        val predicted = client.checkFiles(batch, expected = expected)
        assertEquals(listOf(ImportResultStatus.UPDATED, ImportResultStatus.STALE), predicted.map { it.status })
        assertNull(predicted[1].fileId, "the dry-run keeps fileId null")
        assertTrue(!predicted[1].message.isNullOrBlank(), "STALE names its reason")

        withAuditCapture { capture ->
            val rows = client.importFiles(batch, expected = expected)
            assertEquals(predicted.map { it.status }, rows.map { it.status }, "dry-run parity")
            assertEquals(predicted[1].message, rows[1].message)
            assertEquals(staleFile.id, rows[1].fileId, "the real run names the counterpart")
            assertEquals(freshFile.id, rows[0].fileId)
            fun updatedAudit(id: UInt) = { event: ch.qos.logback.classic.spi.ILoggingEvent ->
                event.message == "catalog_file.updated" && event.hasKeyValue("catalogFileId", id.toLong())
            }
            assertTrue(capture.events.none(updatedAudit(staleFile.id)), "a STALE row emits nothing")
            assertNotNull(capture.awaitEvent(updatedAudit(freshFile.id)))
        }
        val kept = client.file(staleFile.id)
        assertEquals("Edited elsewhere", kept.metadata.title, "the concurrent edit survives")
        assertEquals(editedRevision, kept.revision, "no revision bump")
        assertEquals(eventsBefore, client.events(staleFile.id).items.size, "no history event")
        assertEquals("New", client.file(freshFile.id).metadata.title)

        // With the current token the second file replaces, and a repeat of the same document is UNCHANGED.
        val second = batch.drop(1)
        val updated = client.importFiles(second, expected = listOf(ExpectedFileState(0, editedRevision))).single()
        assertEquals(ImportResultStatus.UPDATED, updated.status)
        val afterUpdate = client.file(staleFile.id).revision
        val repeated = client.importFiles(second, expected = listOf(ExpectedFileState(0, afterUpdate))).single()
        assertEquals(ImportResultStatus.UNCHANGED, repeated.status)
    }

    @Test
    fun `the stale no-op - an identical document with a wrong token is STALE, with the right one UNCHANGED`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-noop")
        val ns = uniqueNamespace("replnoop")
        val name = uniqueEntityName("noop")
        val created = client.createCatalogFile(componentFile(name, namespace = ns, title = "Same"))
        val same = listOf(componentFile(name, namespace = ns, title = "Same"))
        val wrong = listOf(ExpectedFileState(0, created.revision + 5))
        assertEquals(ImportResultStatus.STALE, client.checkFiles(same, expected = wrong).single().status)
        assertEquals(ImportResultStatus.STALE, client.importFiles(same, expected = wrong).single().status)
        val right = listOf(ExpectedFileState(0, created.revision))
        assertEquals(ImportResultStatus.UNCHANGED, client.importFiles(same, expected = right).single().status)
    }

    @Test
    fun `expecting none is STALE against an active twin and creates otherwise, with or without replaceExisting`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-none")
        val ns = uniqueNamespace("replnone")
        val present = uniqueEntityName("present")
        val absent = uniqueEntityName("absent")
        val created = client.createCatalogFile(componentFile(present, namespace = ns, title = "Appeared"))
        val batch = listOf(componentFile(present, namespace = ns, title = "New"), componentFile(absent, namespace = ns))
        val expected = listOf(ExpectedFileState(0, 0), ExpectedFileState(1, 0))

        val predicted = client.checkFiles(batch, expected = expected).map { it.status }
        assertEquals(listOf(ImportResultStatus.STALE, ImportResultStatus.CREATED), predicted)
        assertEquals(predicted, client.importFiles(batch, expected = expected).map { it.status })
        assertEquals("Appeared", client.file(created.id).metadata.title)
        // Without replaceExisting an existing twin stays the ordinary CONFLICT, whatever the expectation.
        val off = client.importFiles(batch.take(1), replaceExisting = false, expected = expected.take(1)).single()
        assertEquals(ImportResultStatus.CONFLICT, off.status)
        val offCheck = client.checkFiles(batch.take(1), replaceExisting = false, expected = expected.take(1)).single()
        assertEquals(ImportResultStatus.CONFLICT, offCheck.status)
    }

    @Test
    fun `a counterpart that vanished is STALE and never resurrected - soft-deleted identity and the lookup seam`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-gone")
        val userId = TestUsers.seed(uniqueEmail("implace-gone-actor"), "pw", role = UserRole.USER)
        val service = application.attributes[CatalogFileServiceKey]
        val ns = uniqueNamespace("replgone")
        val name = uniqueEntityName("gone")
        val seam = uniqueEntityName("seam")
        val gone = client.createCatalogFile(componentFile(name, namespace = ns))
        val seamFile = client.createCatalogFile(componentFile(seam, namespace = ns))
        assertEquals(HttpStatusCode.NoContent, client.delete("$CATALOG_FILES_PATH/${gone.id}").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("$CATALOG_FILES_PATH/${seamFile.id}").status)

        // Soft-deleted identity: the conversion merged over a file that is gone, in both modes.
        val batch = listOf(componentFile(name, namespace = ns))
        val expected = listOf(ExpectedFileState(0, gone.revision))
        listOf(true, false).forEach { replace ->
            assertEquals(ImportResultStatus.STALE, client.checkFiles(batch, replaceExisting = replace, expected = expected).single().status)
            val row = client.importFiles(batch, replaceExisting = replace, expected = expected).single()
            assertEquals(ImportResultStatus.STALE, row.status)
            assertNull(row.fileId)
        }
        // The lookup seam still answers the (now soft-deleted) id: the locked update finds no active row.
        val seamRow = service.import(
            listOf(componentFile(seam, namespace = ns)), userId, replaceExisting = true,
            expected = mapOf(0 to ExpectedFileState(0, seamFile.revision)),
            activeRowLookup = { StoredRevision(seamFile.id, seamFile.revision) },
        ).single()
        assertEquals(ImportResultStatus.STALE, seamRow.status)
        // Nothing was created for either identity: an unguarded import still finds both identities free.
        val both = listOf(componentFile(name, namespace = ns), componentFile(seam, namespace = ns))
        val fresh = client.importFiles(both, replaceExisting = false)
        assertEquals(listOf(ImportResultStatus.CREATED, ImportResultStatus.CREATED), fresh.map { it.status })
    }

    @Test
    fun `a malformed expected list is 400 on both endpoints and stores nothing`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-bad")
        val ns = uniqueNamespace("replbad")
        val batch = listOf(componentFile(uniqueEntityName("bad"), namespace = ns))
        listOf(
            listOf(ExpectedFileState(1, 1)),
            listOf(ExpectedFileState(-1, 1)),
            listOf(ExpectedFileState(0, 1), ExpectedFileState(0, 2)),
            listOf(ExpectedFileState(0, -1)),
            listOf(ExpectedFileState(0, 0, fileId = 5u)), // a counterpart id beside "expect none"
        ).forEach { expected ->
            val request = ImportRequest(files = batch, replaceExisting = true, expected = expected)
            assertEquals(HttpStatusCode.BadRequest, client.postJson("$CATALOG_FILES_PATH/import", request).status, expected.toString())
            val check = client.postJson("$CATALOG_FILES_PATH/import/check", request)
            assertEquals(HttpStatusCode.BadRequest, check.status, expected.toString())
        }
        assertEquals(ImportResultStatus.CREATED, client.importFiles(batch, replaceExisting = false).single().status, "nothing was stored")
    }

    @Test
    fun `the locked update itself refuses a revision that moved after the lookup - nothing is written`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-lock")
        val userId = TestUsers.seed(uniqueEmail("implace-lock-actor"), "pw", role = UserRole.USER)
        val service = application.attributes[CatalogFileServiceKey]
        val ns = uniqueNamespace("repllock")
        val name = uniqueEntityName("lock")
        val created = client.createCatalogFile(componentFile(name, namespace = ns, title = "Old"))
        val editedRevision = client.edit(created.id, componentFile(name, namespace = ns, title = "Edited elsewhere"))
        val eventsBefore = client.events(created.id).items.size

        // The lookup answers the PRE-edit state (the snapshot went stale before the lock), so only the
        // revision compared under the row lock can catch it.
        val row = service.import(
            listOf(componentFile(name, namespace = ns, title = "Imported")), userId, replaceExisting = true,
            expected = mapOf(0 to ExpectedFileState(0, created.revision, created.id)),
            activeRowLookup = { StoredRevision(created.id, created.revision) },
        ).single()
        assertEquals(ImportResultStatus.STALE, row.status)
        assertEquals(created.id, row.fileId, "the lock-path STALE names the counterpart")
        val kept = client.file(created.id)
        assertEquals("Edited elsewhere", kept.metadata.title)
        assertEquals(editedRevision, kept.revision, "no revision bump")
        assertEquals(eventsBefore, client.events(created.id).items.size, "no history event")
    }

    @Test
    fun `a file deleted and re-created at the same revision is another file - STALE in both runs`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-swap")
        val userId = TestUsers.seed(uniqueEmail("implace-swap-actor"), "pw", role = UserRole.USER)
        val service = application.attributes[CatalogFileServiceKey]
        val ns = uniqueNamespace("replswap")
        val name = uniqueEntityName("swap")
        val old = client.createCatalogFile(componentFile(name, namespace = ns, title = "Old"))
        assertEquals(HttpStatusCode.NoContent, client.delete("$CATALOG_FILES_PATH/${old.id}").status)
        val reborn = client.createCatalogFile(componentFile(name, namespace = ns, title = "Reborn"))
        assertEquals(old.revision, reborn.revision, "revisions restart at 1 for every row")
        assertNotEquals(old.id, reborn.id)
        val batch = listOf(componentFile(name, namespace = ns, title = "Imported"))
        val expected = listOf(ExpectedFileState(0, old.revision, old.id))
        val eventsBefore = client.events(reborn.id).items.size

        val check = client.checkFiles(batch, expected = expected).single()
        val real = client.importFiles(batch, expected = expected).single()
        assertEquals(ImportResultStatus.STALE, check.status)
        assertEquals(ImportResultStatus.STALE, real.status)
        assertEquals(check.message, real.message)
        // The lookup seam still answers the OLD file (the swap landed between the lookup and the lock):
        // the update by the old id finds no active row, so it is STALE (vanished), never a create.
        val seam = service.import(
            batch, userId, replaceExisting = true, expected = mapOf(0 to expected.single()),
            activeRowLookup = { StoredRevision(old.id, old.revision) },
        ).single()
        assertEquals(ImportResultStatus.STALE, seam.status)

        val kept = client.file(reborn.id)
        assertEquals("Reborn", kept.metadata.title)
        assertEquals(reborn.revision, kept.revision)
        assertEquals(eventsBefore, client.events(reborn.id).items.size)
        // Without the file id the same revision number cannot tell the files apart - which is why a conversion sends it.
        val unpinned = client.importFiles(batch, expected = listOf(ExpectedFileState(0, old.revision))).single()
        assertEquals(ImportResultStatus.UPDATED, unpinned.status)
    }

    @Test
    fun `a STALE file consumes its identity - a later duplicate is CONFLICT in both runs`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("implace-dup")
        val ns = uniqueNamespace("repldup")
        val name = uniqueEntityName("dup")
        val created = client.createCatalogFile(componentFile(name, namespace = ns, title = "Old"))
        client.edit(created.id, componentFile(name, namespace = ns, title = "Edited"))
        val batch = listOf(componentFile(name, namespace = ns, title = "A"), componentFile(name, namespace = ns, title = "B"))
        val expected = listOf(ExpectedFileState(0, created.revision, created.id))
        val statuses = listOf(ImportResultStatus.STALE, ImportResultStatus.CONFLICT)

        assertEquals(statuses, client.checkFiles(batch, expected = expected).map { it.status })
        assertEquals(statuses, client.importFiles(batch, expected = expected).map { it.status })
        assertEquals("Edited", client.file(created.id).metadata.title)
    }
}
