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
        get("/api/v1/catalog-files/export" + (namespace?.let { "?namespace=$it" } ?: "")).body()

    private suspend fun HttpClient.import(files: List<CatalogFile>): ImportResponse =
        post("/api/v1/catalog-files/import") {
            contentType(ContentType.Application.Json)
            setBody(ImportRequest(files = files))
        }.body()

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
            client.delete("/api/v1/catalog-files/${deleted.id}").status,
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
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/catalog-files/${created.fileId}").status)

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
    fun `import reports an unregistered label as INVALID and stores a registered one`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("roundtrip")

        fun labeled(name: String, labels: Map<String, String>) =
            componentFile(name).let { it.copy(metadata = it.metadata.copy(labels = labels)) }

        // Grammar-valid, never registered in the label registry.
        val ghost = uniqueEntityName("ghostlbl")
        val rejected = client.import(listOf(labeled(uniqueEntityName("ghostdoc"), mapOf(ghost to "x"))))
            .results.single()
        assertEquals(ImportResultStatus.INVALID, rejected.status)
        assertNull(rejected.fileId)
        assertTrue(rejected.message!!.contains("not a defined label"))

        val lbl = uniqueLabel("rtlbl", values = listOf("backend"), kinds = listOf("Component"))
        val stored = client.import(listOf(labeled(uniqueEntityName("rtlbldoc"), mapOf(lbl to "backend"))))
            .results.single()
        assertEquals(ImportResultStatus.CREATED, stored.status)
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
        val response = client.postJson("/api/v1/catalog-files/import", ImportRequest(files = batch))
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
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/catalog-files/export").status)
        val response = client.postJson("/api/v1/catalog-files/import", ImportRequest(files = emptyList()))
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
