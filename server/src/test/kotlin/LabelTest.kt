package ch.nokillswit

import ch.nokillswit.labels.LabelList
import ch.nokillswit.labels.LabelRequest
import ch.nokillswit.labels.LabelResponse
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The label-registry surface: the V22 seed, CRUD semantics, the payload rules, and the authz
 * split. The registry is SHARED suite state — every test mints UNIQUE keys and removes what
 * its assertions depend on; nothing ever touches another test's labels, and nothing touches
 * the seeded ones (see [TestLabels]).
 */
class LabelTest {

    private fun key(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun request(
        key: String,
        values: List<String> = listOf("backend", "frontend"),
        kinds: List<String> = listOf("Component"),
    ) = LabelRequest(key = key, values = values, kinds = kinds)

    private suspend fun HttpClient.readLabels(): LabelList = get("/api/v1/labels").body()

    @Test
    fun `the V22 seed registers the curated label keys with their closed value lists`() = testApplication {
        usePostgresTestcontainer()
        val byKey = seededClient("lblseed").readLabels().items.associateBy { it.key }
        // The registry shipped EMPTY until V22, so before it no file could carry a label at
        // all. Spot-check the two shapes that matter: a Resource-only key and a spanning one.
        assertEquals(listOf("yes", "no"), byKey.getValue("gdpr").values)
        assertEquals(listOf("Resource"), byKey.getValue("gdpr").kinds)
        assertEquals(
            listOf("Component", "API", "System", "Resource"),
            byKey.getValue("exposure").kinds,
            "allowed kinds are stored in canonical SUPPORTED_KINDS order",
        )
        assertTrue("24-7" in byKey.getValue("support-mode").values)
        val seeded = listOf(
            "criticality-tier", "data-classification", "exposure", "gdpr",
            "hosting-model", "pci-dss", "support-mode", "technology-status",
        )
        assertTrue(seeded.all { it in byKey }, "V22 must seed all eight keys: ${byKey.keys}")
    }

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/labels").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/labels").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/labels/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/labels/1").status)
    }

    @Test
    fun `non-admin may read but not write - uniformly 403 even on an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("lbluser")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/labels").status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/v1/labels", request(key("lblu"))).status)
        // Guard-before-read: the probe cannot distinguish real from unknown ids.
        assertEquals(HttpStatusCode.Forbidden, client.putJson("/api/v1/labels/999999", request(key("lblu"))).status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/labels/999999").status)
    }

    @Test
    fun `admin CRUD round-trips - create, list, replace, delete`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lblcrud", UserRole.ADMIN)
        val k = key("lblcrud")

        val create = admin.postJson("/api/v1/labels", request(k, kinds = listOf("Component", "API")))
        assertEquals(HttpStatusCode.Created, create.status)
        val created = create.body<LabelResponse>()
        assertEquals(k, created.key)
        assertEquals(listOf("backend", "frontend"), created.values)
        assertEquals(listOf("Component", "API"), created.kinds)
        assertNotNull(create.headers["Location"])

        val listed = admin.readLabels().items.single { it.key == k }
        assertEquals(created, listed)

        val renamed = key("lblcrud2")
        val put = admin.putJson(
            "/api/v1/labels/${created.id}",
            request(renamed, values = listOf("tier-1"), kinds = listOf("System")),
        )
        assertEquals(HttpStatusCode.NoContent, put.status)
        val replaced = admin.readLabels().items.single { it.id == created.id }
        assertEquals(renamed, replaced.key)
        assertEquals(listOf("tier-1"), replaced.values)
        assertEquals(listOf("System"), replaced.kinds)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/labels/${created.id}").status)
        assertTrue(admin.readLabels().items.none { it.id == created.id })
        // Soft-delete convention: the row survives, flagged.
        val raw = TestLabels.rawRows().single { it.id == created.id }
        assertTrue(raw.markedAsDeleted, "delete must soft-delete, not remove")
    }

    @Test
    fun `mutations on a missing or deleted id are 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lbl404", UserRole.ADMIN)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/labels/999999", request(key("lbl404"))).status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/labels/999999").status)
        val id = TestLabels.ensure(key("lbl404gone"), listOf("v1"), listOf("Component"))
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/labels/$id").status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/labels/$id").status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/labels/$id", request(key("lbl404b"))).status)
    }

    @Test
    fun `payload sanitization trims and canonicalizes kinds into the supported order`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lblsan", UserRole.ADMIN)
        val k = key("lblsan")
        val create = admin.postJson(
            "/api/v1/labels",
            request("  $k  ", values = listOf(" tier-1 ", "tier-2"), kinds = listOf(" api ", "component", "API")),
        )
        assertEquals(HttpStatusCode.Created, create.status)
        val created = create.body<LabelResponse>()
        assertEquals(k, created.key)
        assertEquals(listOf("tier-1", "tier-2"), created.values)
        // Case-variant kinds fold to canonical casing, dedupe, and take SUPPORTED_KINDS order.
        assertEquals(listOf("Component", "API"), created.kinds)
    }

    @Test
    fun `invalid payloads are 400 - grammar, emptiness, duplicates, unknown kind`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lblbad", UserRole.ADMIN)
        val cases = listOf(
            request("has space"),
            request("UPPER.example.com/${key("k")}"),
            request(key("k"), values = emptyList()),
            request(key("k"), values = listOf("has space")),
            request(key("k"), values = listOf("dup", "DUP")),
            request(key("k"), values = (1..101).map { "v$it" }),
            request(key("k"), kinds = emptyList()),
            request(key("k"), kinds = listOf("Location")),
        )
        for (case in cases) {
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.postJson("/api/v1/labels", case).status,
                "expected 400 for $case",
            )
        }
        // The PUT path validates before the id lookup, so the same rejection fires there too.
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/v1/labels/999999", cases.first()).status)
    }

    @Test
    fun `an active key clash is 409 and a soft-deleted label frees its key case-insensitively`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lbldup", UserRole.ADMIN)
        val k = key("lbldup")
        val first = admin.postJson("/api/v1/labels", request(k)).body<LabelResponse>()
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/labels", request(k)).status)
        // The partial index folds case: a case-variant twin clashes too.
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/labels", request(k.uppercase())).status)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/labels/${first.id}").status)
        val second = admin.postJson("/api/v1/labels", request(k))
        assertEquals(HttpStatusCode.Created, second.status)
        assertTrue(second.body<LabelResponse>().id != first.id, "re-adding a freed key mints a NEW id")
    }

    @Test
    fun `renaming onto a key an active label holds is 409`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lblren", UserRole.ADMIN)
        val (a, b) = key("lblrena") to key("lblrenb")
        admin.postJson("/api/v1/labels", request(a))
        val other = admin.postJson("/api/v1/labels", request(b)).body<LabelResponse>()
        val clash = admin.putJson("/api/v1/labels/${other.id}", request(a))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertNotNull(clash.body<ProblemDetail>().detail)
    }

    @Test
    fun `mutations audit and a failed mutation does not`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lblaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            val k = key("lblaudit")
            val created = admin.postJson(
                "/api/v1/labels",
                request(k, kinds = listOf("Component", "API")),
            ).body<LabelResponse>()
            val event = capture.awaitEvent { it.message == "label.created" }
            assertNotNull(event, "create must audit")
            assertTrue(event.hasKeyValue("key", k))
            assertTrue(event.hasKeyValue("values", 2))
            assertTrue(event.hasKeyValue("kinds", "Component,API"))

            admin.putJson("/api/v1/labels/${created.id}", request(k, values = listOf("solo")))
            assertNotNull(capture.awaitEvent { it.message == "label.updated" }, "update must audit")

            admin.delete("/api/v1/labels/${created.id}")
            val deleted = capture.awaitEvent { it.message == "label.deleted" }
            assertNotNull(deleted, "delete must audit")
            assertTrue(deleted.hasKeyValue("labelId", created.id.toLong()))

            val before = capture.events.count { it.message == "label.created" }
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.postJson("/api/v1/labels", request("bad key")).status,
            )
            assertEquals(before, capture.events.count { it.message == "label.created" }, "failed create must not audit")
        }
    }
}
