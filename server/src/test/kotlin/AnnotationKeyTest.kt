package ch.nokillswit

import ch.nokillswit.annotations.AnnotationKeyList
import ch.nokillswit.annotations.AnnotationKeyRequest
import ch.nokillswit.annotations.AnnotationKeyResponse
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
 * The annotation-key registry surface: CRUD semantics, the payload rules (values-free — the
 * labels registry's sibling), and the authz split. The registry is SHARED suite state —
 * every test mints UNIQUE keys and removes what its assertions depend on.
 */
class AnnotationKeyTest {

    private fun key(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun request(key: String, kinds: List<String> = listOf("Component")) =
        AnnotationKeyRequest(key = key, kinds = kinds)

    private suspend fun HttpClient.readKeys(): AnnotationKeyList = get("/api/v1/annotation-keys").body()

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/annotation-keys").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/annotation-keys").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/annotation-keys/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/annotation-keys/1").status)
    }

    @Test
    fun `non-admin may read but not write - uniformly 403 even on an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("annuser")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/annotation-keys").status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/v1/annotation-keys", request(key("annu"))).status)
        // Guard-before-read: the probe cannot distinguish real from unknown ids.
        assertEquals(
            HttpStatusCode.Forbidden,
            client.putJson("/api/v1/annotation-keys/999999", request(key("annu"))).status,
        )
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/annotation-keys/999999").status)
    }

    @Test
    fun `admin CRUD round-trips - create, list, replace, delete`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("anncrud", UserRole.ADMIN)
        val k = key("anncrud")

        val create = admin.postJson("/api/v1/annotation-keys", request(k, kinds = listOf("Component", "API")))
        assertEquals(HttpStatusCode.Created, create.status)
        val created = create.body<AnnotationKeyResponse>()
        assertEquals(k, created.key)
        assertEquals(listOf("Component", "API"), created.kinds)
        assertNotNull(create.headers["Location"])

        val listed = admin.readKeys().items.single { it.key == k }
        assertEquals(created, listed)

        val renamed = key("anncrud2")
        val put = admin.putJson("/api/v1/annotation-keys/${created.id}", request(renamed, kinds = listOf("System")))
        assertEquals(HttpStatusCode.NoContent, put.status)
        val replaced = admin.readKeys().items.single { it.id == created.id }
        assertEquals(renamed, replaced.key)
        assertEquals(listOf("System"), replaced.kinds)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/annotation-keys/${created.id}").status)
        assertTrue(admin.readKeys().items.none { it.id == created.id })
        // Soft-delete convention: the row survives, flagged.
        val raw = TestAnnotationKeys.rawRows().single { it.id == created.id }
        assertTrue(raw.markedAsDeleted, "delete must soft-delete, not remove")
    }

    @Test
    fun `mutations on a missing or deleted id are 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ann404", UserRole.ADMIN)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.putJson("/api/v1/annotation-keys/999999", request(key("ann404"))).status,
        )
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/annotation-keys/999999").status)
        val id = TestAnnotationKeys.ensure(key("ann404gone"), listOf("Component"))
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/annotation-keys/$id").status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/annotation-keys/$id").status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/annotation-keys/$id", request(key("annb"))).status)
    }

    @Test
    fun `payload sanitization trims the key and canonicalizes kinds into the supported order`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("annsan", UserRole.ADMIN)
        val k = key("annsan")
        val create = admin.postJson(
            "/api/v1/annotation-keys",
            request("  $k  ", kinds = listOf(" api ", "component", "API")),
        )
        assertEquals(HttpStatusCode.Created, create.status)
        val created = create.body<AnnotationKeyResponse>()
        assertEquals(k, created.key)
        assertEquals(listOf("Component", "API"), created.kinds)
    }

    @Test
    fun `invalid payloads are 400 - grammar, reserved keys, kind rules`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("annbad", UserRole.ADMIN)
        val cases = listOf(
            request("has space"),
            request("UPPER.example.com/${key("k")}"),
            // Server-written keys cannot be registered — the file writes reject them anyway.
            request("backstage.io/orphan"),
            request("backstage.io/managed-by-location"),
            request(key("k"), kinds = emptyList()),
            request(key("k"), kinds = listOf("Location")),
        )
        for (case in cases) {
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.postJson("/api/v1/annotation-keys", case).status,
                "expected 400 for $case",
            )
        }
        // The PUT path validates before the id lookup, so the same rejection fires there too.
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson("/api/v1/annotation-keys/999999", cases.first()).status,
        )
    }

    @Test
    fun `an active key clash is 409 and a soft-deleted key is reusable case-insensitively`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("anndup", UserRole.ADMIN)
        val k = key("anndup")
        val first = admin.postJson("/api/v1/annotation-keys", request(k)).body<AnnotationKeyResponse>()
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/annotation-keys", request(k)).status)
        // The partial index folds case: a case-variant twin clashes too.
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/annotation-keys", request(k.uppercase())).status)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/annotation-keys/${first.id}").status)
        val second = admin.postJson("/api/v1/annotation-keys", request(k))
        assertEquals(HttpStatusCode.Created, second.status)
        assertTrue(second.body<AnnotationKeyResponse>().id != first.id, "re-adding a freed key mints a NEW id")
    }

    @Test
    fun `renaming onto a key an active row holds is 409`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("annren", UserRole.ADMIN)
        val (a, b) = key("annrena") to key("annrenb")
        admin.postJson("/api/v1/annotation-keys", request(a))
        val other = admin.postJson("/api/v1/annotation-keys", request(b)).body<AnnotationKeyResponse>()
        val clash = admin.putJson("/api/v1/annotation-keys/${other.id}", request(a))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertNotNull(clash.body<ProblemDetail>().detail)
    }

    @Test
    fun `mutations audit and a failed mutation does not`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("annaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            val k = key("annaudit")
            val created = admin.postJson(
                "/api/v1/annotation-keys",
                request(k, kinds = listOf("Component", "API")),
            ).body<AnnotationKeyResponse>()
            val event = capture.awaitEvent { it.message == "annotation_key.created" }
            assertNotNull(event, "create must audit")
            assertTrue(event.hasKeyValue("key", k))
            assertTrue(event.hasKeyValue("kinds", "Component,API"))

            admin.putJson("/api/v1/annotation-keys/${created.id}", request(k, kinds = listOf("Component")))
            assertNotNull(capture.awaitEvent { it.message == "annotation_key.updated" }, "update must audit")

            admin.delete("/api/v1/annotation-keys/${created.id}")
            val deleted = capture.awaitEvent { it.message == "annotation_key.deleted" }
            assertNotNull(deleted, "delete must audit")
            assertTrue(deleted.hasKeyValue("annotationKeyId", created.id.toLong()))

            val before = capture.events.count { it.message == "annotation_key.created" }
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.postJson("/api/v1/annotation-keys", request("bad key")).status,
            )
            assertEquals(
                before,
                capture.events.count { it.message == "annotation_key.created" },
                "failed create must not audit",
            )
        }
    }
}
