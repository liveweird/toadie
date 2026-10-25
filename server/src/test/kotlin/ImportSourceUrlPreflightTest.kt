package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintImportRequest
import ch.nokillswit.blueprints.BlueprintImportResponse
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.catalog.ImportRequest
import ch.nokillswit.catalog.ImportResponse
import ch.nokillswit.catalog.MAX_IMPORT_FILES
import ch.nokillswit.entities.EntityImportRequest
import ch.nokillswit.entities.EntityImportResponse
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.infra.fetch.MAX_FETCH_URL_LENGTH
import ch.nokillswit.infra.fetch.SOURCE_URL_INVALID_DETAIL
import ch.nokillswit.infra.importing.MAX_IMPORT_DOCUMENTS
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The three import dry-runs (`/files`, `/blueprints`, `/entities` `…/import/check`) run the SAME
 * route-side preflight as the real import — batch cap, then the batch-level `sourceUrl`'s static
 * guards — after the same guard and `receive`, so an invalid URL is the same status and problem
 * body on both and a valid one changes no row classification. Nothing is fetched by either.
 * Every fixture is uniquely named; a refused request stores nothing.
 */
class ImportSourceUrlPreflightTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private val invalidSourceUrls = listOf(
        "not-a-url",
        "http://example.com/catalog-info.yaml",
        "https://user:secret@example.com/catalog-info.yaml",
        "https://example.com/${"x".repeat(MAX_FETCH_URL_LENGTH)}",
    )

    /** The wire-stable part of a problem body — `instance` names the path, so it legitimately differs. */
    private suspend fun HttpResponse.problem(): Triple<String, Int, String?> =
        body<ProblemDetail>().let { Triple(it.type, it.status, it.detail) }

    private suspend fun assertSameRefusal(check: HttpResponse, real: HttpResponse, detail: String) {
        assertEquals(HttpStatusCode.BadRequest, real.status)
        assertEquals(HttpStatusCode.BadRequest, check.status)
        val expected = real.problem()
        assertEquals(detail, expected.third)
        assertEquals(expected, check.problem())
    }

    private fun simpleBlueprint(id: String) = BlueprintRequest(identifier = id, title = "T", schema = BlueprintSchema())

    private fun blueprintDoc(id: String): JsonObject = blueprintJson.encodeToJsonElement(simpleBlueprint(id)).jsonObject

    private fun entityDoc(blueprint: String, id: String): JsonObject =
        blueprintJson.encodeToJsonElement(EntityRequest(blueprint = blueprint, identifier = id, title = "T $id")).jsonObject

    @Test
    fun `catalog dry-run refuses an invalid batch sourceUrl exactly like the real import`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("srcpre-files")
        val files = listOf(componentFile(uniqueEntityName("srcpre"), namespace = uniqueNamespace("srcpre")))
        for (invalid in invalidSourceUrls) {
            val request = ImportRequest(files = files, sourceUrl = invalid)
            assertSameRefusal(
                check = client.postJson("$CATALOG_FILES_PATH/import/check", request),
                real = client.postJson("$CATALOG_FILES_PATH/import", request),
                detail = SOURCE_URL_INVALID_DETAIL,
            )
        }
    }

    @Test
    fun `catalog preflight order - authentication first, then the batch cap, then the sourceUrl`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("srcpre-files-order")
        val request = ImportRequest(files = emptyList(), sourceUrl = "http://example.com/x.yaml")
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().postJson("$CATALOG_FILES_PATH/import/check", request).status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().postJson("$CATALOG_FILES_PATH/import", request).status)

        val tooMany = ImportRequest(
            files = List(MAX_IMPORT_FILES + 1) { componentFile(uniqueEntityName("cap")) },
            sourceUrl = "http://example.com/x.yaml",
        )
        assertSameRefusal(
            check = client.postJson("$CATALOG_FILES_PATH/import/check", tooMany),
            real = client.postJson("$CATALOG_FILES_PATH/import", tooMany),
            detail = "files must have at most $MAX_IMPORT_FILES entries",
        )
    }

    @Test
    fun `catalog dry-run accepts a valid or blank sourceUrl and classifies rows like the real run`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("srcpre-files-valid")
        val ns = uniqueNamespace("srcpre")
        val good = componentFile(uniqueEntityName("good"), namespace = ns)
        val ghostly = componentFile(uniqueEntityName("ghostly"), namespace = ns).let {
            it.copy(spec = it.spec.copy(dependsOn = listOf("component:$ns/${uniqueEntityName("ghost")}")))
        }
        for (url in listOf("https://example.com/${unique("repo")}/catalog-info.yaml", "   ", null)) {
            // Fresh names per round: the real run stores, so a repeat would CONFLICT.
            val batch = listOf(good, ghostly).map { it.copy(metadata = it.metadata.copy(name = uniqueEntityName("r"))) }
            val check = client.postJson("$CATALOG_FILES_PATH/import/check", ImportRequest(files = batch, sourceUrl = url))
            assertEquals(HttpStatusCode.OK, check.status)
            val real = client.postJson("$CATALOG_FILES_PATH/import", ImportRequest(files = batch, sourceUrl = url))
            assertEquals(HttpStatusCode.OK, real.status)
            assertEquals(
                check.body<ImportResponse>().results.map { it.status },
                real.body<ImportResponse>().results.map { it.status },
            )
        }
    }

    @Test
    fun `blueprint dry-run refuses an invalid batch sourceUrl exactly like the real import`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("srcpre-bp", UserRole.ADMIN)
        val documents = listOf(blueprintDoc(unique("bp-srcpre")))
        for (invalid in invalidSourceUrls) {
            val request = BlueprintImportRequest(documents = documents, sourceUrl = invalid)
            assertSameRefusal(
                check = client.postJson("/api/v1/blueprints/import/check", request),
                real = client.postJson("/api/v1/blueprints/import", request),
                detail = SOURCE_URL_INVALID_DETAIL,
            )
        }
    }

    @Test
    fun `blueprint preflight order - 401 and 403 win, then the batch cap, then the sourceUrl`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("srcpre-bp-order", UserRole.ADMIN)
        val user = seededClient("srcpre-bp-order-user", UserRole.USER)
        val bad = BlueprintImportRequest(documents = emptyList(), sourceUrl = "http://example.com/bp.json")
        for (path in listOf("/api/v1/blueprints/import/check", "/api/v1/blueprints/import")) {
            assertEquals(HttpStatusCode.Unauthorized, jsonClient().postJson(path, bad).status)
            assertEquals(HttpStatusCode.Forbidden, user.postJson(path, bad).status)
        }

        val tooMany = BlueprintImportRequest(
            documents = List(MAX_IMPORT_DOCUMENTS + 1) { blueprintDoc(unique("bp-cap")) },
            sourceUrl = "http://example.com/bp.json",
        )
        assertSameRefusal(
            check = admin.postJson("/api/v1/blueprints/import/check", tooMany),
            real = admin.postJson("/api/v1/blueprints/import", tooMany),
            detail = "documents must have at most $MAX_IMPORT_DOCUMENTS entries",
        )
    }

    @Test
    fun `blueprint dry-run accepts a valid or blank sourceUrl and classifies rows like the real run`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("srcpre-bp-valid", UserRole.ADMIN)
        val ids = mutableListOf<String>()
        try {
            for (url in listOf("https://example.com/${unique("repo")}/blueprint.json", "   ", null)) {
                val id = unique("bp-srcpre-ok").also { ids += it }
                val request = BlueprintImportRequest(documents = listOf(blueprintDoc(id)), sourceUrl = url)
                val check = client.postJson("/api/v1/blueprints/import/check", request)
                assertEquals(HttpStatusCode.OK, check.status)
                val real = client.postJson("/api/v1/blueprints/import", request)
                assertEquals(HttpStatusCode.OK, real.status)
                assertEquals(
                    check.body<BlueprintImportResponse>().results.map { it.status },
                    real.body<BlueprintImportResponse>().results.map { it.status },
                )
            }
        } finally {
            ids.forEach { TestBlueprints.remove(it) }
        }
    }

    @Test
    fun `entity dry-run refuses an invalid batch sourceUrl exactly like the real import`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("srcpre-ent")
        // The blueprint need not exist: the preflight refuses before any row is classified.
        val documents = listOf(entityDoc(unique("bp-srcpre"), unique("ent-srcpre")))
        for (invalid in invalidSourceUrls) {
            val request = EntityImportRequest(documents = documents, sourceUrl = invalid)
            assertSameRefusal(
                check = client.postJson("/api/v1/entities/import/check", request),
                real = client.postJson("/api/v1/entities/import", request),
                detail = SOURCE_URL_INVALID_DETAIL,
            )
        }
    }

    @Test
    fun `entity preflight order - 401 wins, then the batch cap, then the sourceUrl`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("srcpre-ent-order")
        val bad = EntityImportRequest(documents = emptyList(), sourceUrl = "http://example.com/ent.json")
        for (path in listOf("/api/v1/entities/import/check", "/api/v1/entities/import")) {
            assertEquals(HttpStatusCode.Unauthorized, jsonClient().postJson(path, bad).status)
        }

        val tooMany = EntityImportRequest(
            documents = List(MAX_IMPORT_DOCUMENTS + 1) { entityDoc("bp-cap", unique("ent-cap")) },
            sourceUrl = "http://example.com/ent.json",
        )
        assertSameRefusal(
            check = client.postJson("/api/v1/entities/import/check", tooMany),
            real = client.postJson("/api/v1/entities/import", tooMany),
            detail = "documents must have at most $MAX_IMPORT_DOCUMENTS entries",
        )
    }

    @Test
    fun `entity dry-run accepts a valid or blank sourceUrl and classifies rows like the real run`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("srcpre-ent-valid", UserRole.ADMIN)
        val bpId = unique("bp-srcpre-ent")
        val entIds = mutableListOf<String>()
        try {
            admin.postJson("/api/v1/blueprints", simpleBlueprint(bpId))
            for (url in listOf("https://example.com/${unique("repo")}/entity.json", "   ", null)) {
                val id = unique("ent-srcpre-ok").also { entIds += it }
                // A second document naming a missing blueprint keeps a non-CREATED row in the comparison.
                val documents = listOf(entityDoc(bpId, id), entityDoc(unique("bp-missing"), unique("ent-orphan")))
                val request = EntityImportRequest(documents = documents, sourceUrl = url)
                val check = admin.postJson("/api/v1/entities/import/check", request)
                assertEquals(HttpStatusCode.OK, check.status)
                val real = admin.postJson("/api/v1/entities/import", request)
                assertEquals(HttpStatusCode.OK, real.status)
                assertEquals(
                    check.body<EntityImportResponse>().results.map { it.status },
                    real.body<EntityImportResponse>().results.map { it.status },
                )
            }
        } finally {
            entIds.forEach { TestEntities.remove(it) }
            TestBlueprints.remove(bpId)
        }
    }
}
