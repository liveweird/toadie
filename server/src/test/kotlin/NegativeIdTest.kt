package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.SyncBlueprintRequest
import ch.nokillswit.catalog.SyncCatalogFileRequest
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.SyncEntityRequest
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A 2026-09 audit found ten `{id}` operations whose PUT sibling declares `400` while the
 * operation itself omitted it — `plugins/ErrorHandling.kt`'s pre-routing negative-id
 * interceptor rejects EVERY `/api/` path segment that looks like a negative integer, on every
 * verb, before routing/auth ever runs (kotlinx's `UInt` decoding would otherwise silently WRAP
 * a negative segment via `toInt().toUInt()`). The spec previously only advertised this on the
 * PUT of each resource; this test — and the accompanying spec fix — close the other ten.
 */
class NegativeIdTest {

    @Test
    fun `a negative id segment answers 400 on every by-id operation`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()

        // GET blueprints/{id} and GET entities/{id} are the only two by-id GETs among the
        // ten; the other eight resources declare no GET-by-id operation at all, so calling
        // GET on them would fail the conformance plugin as an undeclared method rather than
        // exercising the interceptor under test.
        assertNegative400(client.get("/api/v1/blueprints/-1"))
        assertNegative400(client.get("/api/v1/blueprints/-1/sync"))
        assertNegative400(
            client.postJson(
                "/api/v1/blueprints/-1/sync",
                SyncBlueprintRequest(document = BlueprintRequest(identifier = "bp", title = "T")),
            ),
        )
        assertNegative400(client.get("/api/v1/entities/-1"))
        assertNegative400(client.get("/api/v1/entities/-1/sync"))
        assertNegative400(
            client.postJson(
                "/api/v1/entities/-1/sync",
                SyncEntityRequest(document = EntityRequest(blueprint = "bp", identifier = "x", title = "T")),
            ),
        )
        assertNegative400(client.get("/api/v1/files/-1/sync"))
        assertNegative400(
            client.postJson(
                "/api/v1/files/-1/sync",
                SyncCatalogFileRequest(document = componentFile("neg-id")),
            ),
        )

        assertNegative400(client.delete("/api/v1/blueprints/-1"))
        assertNegative400(client.delete("/api/v1/entities/-1"))
        assertNegative400(client.delete("/api/v1/labels/-1"))
        assertNegative400(client.delete("/api/v1/tag-categories/-1"))
        assertNegative400(client.delete("/api/v1/entity-types/-1"))
        assertNegative400(client.delete("/api/v1/annotation-keys/-1"))
        assertNegative400(client.delete("/api/v1/lenses/-1"))
        assertNegative400(client.delete("/api/v1/entity-queries/-1"))
    }

    private suspend fun assertNegative400(response: HttpResponse) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Path id must be a non-negative integer", response.body<ProblemDetail>().detail)
    }
}
