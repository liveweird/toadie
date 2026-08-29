package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.catalog.ExportResponse
import ch.nokillswit.catalog.ImportRequest
import ch.nokillswit.catalog.ImportResponse
import ch.nokillswit.catalog.ImportResultStatus
import ch.nokillswit.catalog.MAX_IMPORT_FILES
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The YAML round-trip's server halves: export (structured documents, the SPA renders the YAML)
 * and import (report & skip — per-document independence). Shared-container isolation: each
 * test works in a UNIQUE namespace so export assertions can be exact.
 */
class RoundTripTest {


    private suspend fun HttpClient.export(namespace: String? = null): ExportResponse =
        get("$CATALOG_FILES_PATH/export" + (namespace?.let { "?namespace=$it" } ?: "")).body()

    private suspend fun HttpClient.import(files: List<CatalogFile>): ImportResponse =
        post("$CATALOG_FILES_PATH/import") {
            contentType(ContentType.Application.Json)
            setBody(ImportRequest(files = files))
        }.body()

    private suspend fun HttpClient.importCheck(files: List<CatalogFile>): ImportResponse =
        post("$CATALOG_FILES_PATH/import/check") {
            contentType(ContentType.Application.Json)
            setBody(ImportRequest(files = files))
        }.body()

    @Test
    fun `the dry-run predicts every row, stores nothing, audits nothing - and the real import matches`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("dryrun")
            val ns = uniqueNamespace("dryns")
            val existing = uniqueEntityName("dup")
            // The pre-existing identity (created BEFORE the audit capture — its own created
            // event must not pollute the silence assert below).
            client.createCatalogFile(componentFile(existing, namespace = ns))

            val good = componentFile(uniqueEntityName("good"), namespace = ns)
            val invalid = CatalogFile(
                metadata = CatalogFileMetadata(name = uniqueEntityName("bad"), namespace = ns),
                spec = EntitySpec(), // a Component without type/lifecycle/owner
            )
            val clash = componentFile(existing, namespace = ns)
            val ghostly = componentFile(uniqueEntityName("ghostly"), namespace = ns).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$ns/${uniqueEntityName("ghost")}")))
            }
            val twinName = uniqueEntityName("twin")
            val twinA = componentFile(twinName, namespace = ns)
            val twinB = componentFile(twinName, namespace = ns, title = "the second twin")
            val batch = listOf(good, invalid, clash, ghostly, twinA, twinB)

            val predicted = withAuditCapture { capture ->
                val response = client.importCheck(batch)
                assertEquals(
                    listOf(
                        ImportResultStatus.CREATED,
                        ImportResultStatus.INVALID,
                        ImportResultStatus.CONFLICT,
                        ImportResultStatus.CREATED_WITH_FINDINGS,
                        ImportResultStatus.CREATED,
                        // The intra-batch twin: the real run stores the first and 23505s
                        // the second — the prediction mirrors that ordering.
                        ImportResultStatus.CONFLICT,
                    ),
                    response.results.map { it.status },
                )
                assertTrue(response.results.all { it.fileId == null }, "predicted rows carry no fileId")
                assertTrue(response.results[3].message!!.contains("does not resolve"))
                // Nothing stored: the namespace still holds only the pre-existing file…
                assertEquals(listOf(existing), client.export(namespace = ns).files.map { it.metadata.name })
                // …and nothing audited (a pure computation, like the other check endpoints).
                assertEquals(0, capture.events.count { it.message == "catalog_file.created" })
                response.results
            }

            // The fidelity pin: the REAL import of the same batch matches the predictions.
            val real = client.import(batch)
            assertEquals(predicted.map { it.status }, real.results.map { it.status })
        }

    @Test
    fun `the dry-run shares the batch cap and requires authentication`() = testApplication {
        usePostgresTestcontainer()
        val tooMany = List(MAX_IMPORT_FILES + 1) { componentFile(uniqueEntityName("cap")) }
        val capped = seededClient("dryruncap").post("$CATALOG_FILES_PATH/import/check") {
            contentType(ContentType.Application.Json)
            setBody(ImportRequest(files = tooMany))
        }
        assertEquals(HttpStatusCode.BadRequest, capped.status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().post("$CATALOG_FILES_PATH/import/check") {
                contentType(ContentType.Application.Json)
                setBody(ImportRequest(files = emptyList()))
            }.status,
        )
    }

    @Test
    fun `export returns the namespace's active files ordered by name`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")
        val ns = uniqueNamespace("expns")
        // Created out of name order on purpose — the export must sort.
        client.createCatalogFile(componentFile("zz-last", namespace = ns))
        client.createCatalogFile(groupFile("aa-first", namespace = ns))
        client.createCatalogFile(apiFile("mm-middle", namespace = ns))

        val export = client.export(namespace = ns)
        assertEquals(listOf("aa-first", "mm-middle", "zz-last"), export.files.map { it.metadata.name })
        assertEquals(listOf("Group", "API", "Component"), export.files.map { it.kind })
        // The documents are the full stored shape — spot-check a spec field survived.
        assertEquals("openapi: 3.0.0", export.files[1].spec.definition)
    }

    @Test
    fun `export without a namespace spans namespaces in (namespace, name) order`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")
        val nsA = uniqueNamespace("aexp")
        val nsB = uniqueNamespace("bexp")
        client.createCatalogFile(componentFile(uniqueEntityName("c"), namespace = nsB))
        client.createCatalogFile(componentFile(uniqueEntityName("c"), namespace = nsA))

        val all = client.export()
        val mine = all.files.filter { it.metadata.namespace in setOf(nsA, nsB) }
        assertEquals(listOf(nsA, nsB), mine.map { it.metadata.namespace })
        // Global ordering holds too, not just within the two test namespaces.
        val namespaces = all.files.map { it.metadata.namespace.lowercase() }
        assertEquals(namespaces.sorted(), namespaces)
    }

    @Test
    fun `export excludes soft-deleted files`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")
        val ns = uniqueNamespace("delns")
        val kept = client.createCatalogFile(componentFile(uniqueEntityName("kept"), namespace = ns))
        val deleted = client.createCatalogFile(componentFile(uniqueEntityName("gone"), namespace = ns))
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("$CATALOG_FILES_PATH/${deleted.id}").status,
        )

        val export = client.export(namespace = ns)
        assertEquals(listOf(kept.metadata.name), export.files.map { it.metadata.name })
    }

    @Test
    fun `import handles each document independently and reports per-row statuses`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")
        val ns = uniqueNamespace("impns")
        val existing = uniqueEntityName("dup")
        client.createCatalogFile(componentFile(existing, namespace = ns))

        val good = componentFile(uniqueEntityName("good"), namespace = ns)
        val invalid = CatalogFile(
            metadata = CatalogFileMetadata(name = uniqueEntityName("bad"), namespace = ns),
            // A Component without type/lifecycle/owner fails the per-kind required rules.
            spec = EntitySpec(),
        )
        val clash = componentFile(existing, namespace = ns)

        val response = client.import(listOf(good, invalid, clash))
        assertEquals(3, response.results.size)
        assertEquals(listOf(0, 1, 2), response.results.map { it.index })

        val created = response.results[0]
        assertEquals(ImportResultStatus.CREATED, created.status)
        assertNotNull(created.fileId)
        assertNull(created.message)
        // The valid document is genuinely stored despite its failing neighbors.
        assertEquals(HttpStatusCode.OK, client.get("$CATALOG_FILES_PATH/${created.fileId}").status)

        val rejected = response.results[1]
        assertEquals(ImportResultStatus.INVALID, rejected.status)
        assertNull(rejected.fileId)
        assertTrue(rejected.message!!.contains("required for kind Component"))

        val conflicted = response.results[2]
        assertEquals(ImportResultStatus.CONFLICT, conflicted.status)
        assertNull(conflicted.fileId)
        assertNotNull(conflicted.message)
    }

    @Test
    fun `import resolves a blank namespace to the flagged default in the result row`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")
        val custom = uniqueEntityName("impdflt")
        TestNamespaces.withDefaultNamespace(custom) {
            val result = client.import(listOf(componentFile(uniqueEntityName("impblank"), namespace = "")))
                .results.single()
            assertEquals(ImportResultStatus.CREATED, result.status)
            assertEquals(custom, result.namespace, "the row reports the CONCRETE resolved namespace")
        }
    }

    @Test
    fun `import reports an undefined namespace as INVALID and stores nothing`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")
        // Grammar-valid, never registered in the namespaces dictionary.
        val undefined = uniqueEntityName("ghostns")

        val result = client.import(listOf(componentFile(uniqueEntityName("ghostdoc"), namespace = undefined)))
            .results.single()
        assertEquals(ImportResultStatus.INVALID, result.status)
        assertNull(result.fileId)
        assertTrue(result.message!!.contains("not a defined namespace"))
    }

    @Test
    fun `import stores an unregistered label with a findings row - a registered one is clean`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")

        fun labeled(name: String, labels: Map<String, String>) =
            componentFile(name).let { it.copy(metadata = it.metadata.copy(labels = labels)) }

        // Grammar-valid, never registered in the label registry — import waives the
        // registry check, so the document STORES and the row carries the finding.
        val ghost = uniqueEntityName("ghostlbl")
        val waived = client.import(listOf(labeled(uniqueEntityName("ghostdoc"), mapOf(ghost to "x"))))
            .results.single()
        assertEquals(ImportResultStatus.CREATED_WITH_FINDINGS, waived.status)
        assertNotNull(waived.fileId)
        assertTrue(waived.message!!.contains("not a defined label"))

        val lbl = uniqueLabel("rtlbl", values = listOf("backend"), kinds = listOf("Component"))
        val stored = client.import(listOf(labeled(uniqueEntityName("rtlbldoc"), mapOf(lbl to "backend"))))
            .results.single()
        assertEquals(ImportResultStatus.CREATED, stored.status)
    }

    @Test
    fun `import stores an unregistered tag with a findings row - a registered one is clean`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")

        fun tagged(name: String, tags: List<String>) =
            componentFile(name).let { it.copy(metadata = it.metadata.copy(tags = tags)) }

        // Grammar-valid, never registered in any tag category — stored anyway, with the
        // finding reported on the row (import always waives the soft checks).
        val ghost = uniqueTag("ghosttag")
        val waived = client.import(listOf(tagged(uniqueEntityName("ghosttagdoc"), listOf(ghost))))
            .results.single()
        assertEquals(ImportResultStatus.CREATED_WITH_FINDINGS, waived.status)
        assertNotNull(waived.fileId)
        assertTrue(waived.message!!.contains("is not a defined tag"))

        val t = uniqueTag("rttag")
        uniqueTagCategory("rttagcat", tags = listOf(t), kinds = listOf("Component"))
        val stored = client.import(listOf(tagged(uniqueEntityName("rttagdoc"), listOf(t))))
            .results.single()
        assertEquals(ImportResultStatus.CREATED, stored.status)
    }

    @Test
    fun `import resolves sibling references within the batch order-independently`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")
        val ns = uniqueNamespace("sibns")
        val team = uniqueEntityName("sibteam")
        val comp = uniqueEntityName("sibcomp")

        // The component comes FIRST and references the group that only appears later in the
        // same batch — the batch universe makes the order irrelevant.
        val results = client.import(
            listOf(
                componentFile(comp, namespace = ns, owner = team),
                groupFile(team, namespace = ns),
            ),
        ).results
        assertEquals(listOf(ImportResultStatus.CREATED, ImportResultStatus.CREATED), results.map { it.status })

        // A reference to an entity in NEITHER the workspace nor the batch still STORES
        // (import waives resolution) — the row reports the dangling reference.
        val waived = client.import(
            listOf(componentFile(uniqueEntityName("sibghost"), namespace = ns, owner = uniqueEntityName("nowhere"))),
        ).results.single()
        assertEquals(ImportResultStatus.CREATED_WITH_FINDINGS, waived.status)
        assertNotNull(waived.fileId)
        assertTrue(waived.message!!.contains("does not resolve to a stored entity"))

        // A document referencing ITSELF stores with a findings row too, even though its own
        // identity is in the batch universe — the self-reference rule beats batch resolution.
        val selfName = uniqueEntityName("sibself")
        val selfWaived = client.import(
            listOf(
                componentFile(selfName, namespace = ns).let {
                    it.copy(spec = it.spec.copy(subcomponentOf = "component:$ns/$selfName"))
                },
            ),
        ).results.single()
        assertEquals(ImportResultStatus.CREATED_WITH_FINDINGS, selfWaived.status)
        assertTrue(selfWaived.message!!.contains("must not point at the entity itself"))
    }

    @Test
    fun `import sanitizes before reporting — namespace folds and kind canonicalizes`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")
        val ns = uniqueNamespace("sanns")
        val name = uniqueEntityName("comp")
        val file = componentFile(name, namespace = ns.uppercase()).copy(kind = "component")

        val result = client.import(listOf(file)).results.single()
        assertEquals(ImportResultStatus.CREATED, result.status)
        assertEquals("Component", result.kind)
        assertEquals(ns.lowercase(), result.namespace)
        assertEquals(name, result.name)
    }

    @Test
    fun `import caps the batch at MAX_IMPORT_FILES`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")
        val ns = uniqueNamespace("capns")
        val batch = (0..MAX_IMPORT_FILES).map { componentFile(uniqueEntityName("cap$it"), namespace = ns) }
        val response = client.postJson("$CATALOG_FILES_PATH/import", ImportRequest(files = batch))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        // Nothing from the oversized batch was stored.
        assertTrue(client.export(namespace = ns).files.isEmpty())
    }

    @Test
    fun `the round-trip pin — re-importing an export conflicts on every document`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")
        val ns = uniqueNamespace("rtns")
        client.createCatalogFile(componentFile(uniqueEntityName("c"), namespace = ns))
        client.createCatalogFile(groupFile(uniqueEntityName("g"), namespace = ns))
        client.createCatalogFile(userFile(uniqueEntityName("u"), namespace = ns))

        val export = client.export(namespace = ns)
        assertEquals(3, export.files.size)

        // The exported documents ARE valid requests — identity survived the trip intact, so
        // every one clashes with its own stored original (report & skip: nothing overwritten).
        val reimport = client.import(export.files)
        assertEquals(
            List(3) { ImportResultStatus.CONFLICT },
            reimport.results.map { it.status },
        )
    }

    @Test
    fun `import audits created files with the import marker`() = testApplication {
        usePostgresTestcontainer()
        withAuditCapture { capture ->
            val client = seededClient("roundtrip")
            val file = componentFile(uniqueEntityName("aud"), namespace = uniqueNamespace("audns"))
            val result = client.import(listOf(file)).results.single()
            assertEquals(ImportResultStatus.CREATED, result.status)
            val event = capture.awaitEvent { logged ->
                logged.message == "catalog_file.created" &&
                    logged.hasKeyValue("catalogFileId", result.fileId!!.toLong())
            }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("import", true))
        }
    }

    @Test
    fun `export and import require authentication`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("$CATALOG_FILES_PATH/export").status)
        val response = client.postJson("$CATALOG_FILES_PATH/import", ImportRequest(files = emptyList()))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a storage-level failure becomes an ERROR row, not an exception`() = kotlinx.coroutines.runBlocking<Unit> {
        // The ERROR branch is unreachable through the route with the current schema (every
        // text column is validation-capped first, and the JSON content column escapes NUL),
        // so it is pinned directly: a service on a dead database connection fails at create —
        // and the import still completes with a classified row instead of throwing.
        val deadService = ch.nokillswit.catalog.CatalogFileService(
            org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase.connect(
                url = "r2dbc:postgresql://127.0.0.1:1/nowhere",
                user = "nobody",
                password = "nothing",
            ),
        )
        val results = deadService.import(listOf(componentFile(uniqueEntityName("err"))), createdByUserId = 1u)
        assertEquals(ImportResultStatus.ERROR, results.single().status)
        assertNotNull(results.single().message)
    }
}
