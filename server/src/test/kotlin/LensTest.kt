package ch.nokillswit

import ch.nokillswit.lenses.LensFilters
import ch.nokillswit.lenses.LensList
import ch.nokillswit.lenses.LensRequest
import ch.nokillswit.lenses.LensResponse
import ch.nokillswit.lenses.LensVisibility
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lenses surface: CRUD semantics, the structural payload rules, the per-owner name
 * uniqueness, and the visibility model (PRIVATE creator-only, PUBLIC read-for-all but
 * creator-only mutable — ADMIN included). Lens names are minted unique per test, so the
 * shared container never couples tests.
 */
class LensTest {

    private fun lensName(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun request(
        name: String,
        visibility: LensVisibility = LensVisibility.PRIVATE,
        filters: LensFilters = LensFilters(namespace = "team-a", kind = listOf("Component")),
    ) = LensRequest(name = name, visibility = visibility, filters = filters)

    private suspend fun HttpClient.readLenses(): LensList = get("/api/v1/lenses").body()

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/lenses").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/lenses").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/lenses/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/lenses/1").status)
    }

    @Test
    fun `CRUD round-trips - create, list, replace including visibility flip, soft delete`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("lenscrud")
        val name = lensName("lenscrud")
        val fullFilters = LensFilters(
            name = "pay",
            namespace = "team-a",
            kind = listOf("Component", "API"),
            tag = "billing",
            type = "service",
            lifecycle = "production",
            owner = "group:default/platform",
            label = "example.com/tier",
            labelValue = listOf("tier-1", "tier-2"),
        )

        val create = client.postJson("/api/v1/lenses", request(name, filters = fullFilters))
        assertEquals(HttpStatusCode.Created, create.status)
        val created = create.body<LensResponse>()
        assertEquals(name, created.name)
        assertEquals(LensVisibility.PRIVATE, created.visibility)
        assertEquals(fullFilters, created.filters)
        assertEquals("Test", created.creatorName)
        assertEquals(false, created.creatorDeleted)
        assertNotNull(create.headers["Location"])

        assertEquals(created, client.readLenses().items.single { it.id == created.id })

        // Whole-lens replace: rename + the visibility flip + a new payload in one PUT.
        val renamed = lensName("lenscrud2")
        val put = client.putJson(
            "/api/v1/lenses/${created.id}",
            request(renamed, visibility = LensVisibility.PUBLIC, filters = LensFilters(tag = "billing")),
        )
        assertEquals(HttpStatusCode.NoContent, put.status)
        val replaced = client.readLenses().items.single { it.id == created.id }
        assertEquals(renamed, replaced.name)
        assertEquals(LensVisibility.PUBLIC, replaced.visibility)
        assertEquals(LensFilters(tag = "billing"), replaced.filters)
        assertTrue(replaced.updatedAt >= replaced.createdAt)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/lenses/${created.id}").status)
        assertTrue(client.readLenses().items.none { it.id == created.id })
        // Soft-delete convention: the row survives, flagged; a repeat delete is 404.
        assertTrue(TestLenses.rawRows().single { it.id == created.id }.markedAsDeleted)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/lenses/${created.id}").status)
    }

    @Test
    fun `a name clash is 409 per owner - freed by delete, and never across owners`() = testApplication {
        usePostgresTestcontainer()
        val alice = seededClient("lensdupa")
        val bob = seededClient("lensdupb")
        val name = lensName("lensdup")

        val first = alice.postJson("/api/v1/lenses", request(name)).body<LensResponse>()
        assertEquals(HttpStatusCode.Conflict, alice.postJson("/api/v1/lenses", request(name)).status)
        // The partial index folds case: a case-variant twin clashes too.
        val clash = alice.postJson("/api/v1/lenses", request(name.uppercase()))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertNotNull(clash.body<ProblemDetail>().detail)

        // Uniqueness is PER OWNER: another user may reuse the name (public or private).
        assertEquals(
            HttpStatusCode.Created,
            bob.postJson("/api/v1/lenses", request(name, visibility = LensVisibility.PUBLIC)).status,
        )

        // The PUT side of the same index: RENAMING a lens onto the owner's other active
        // name is the identical 23505 → 409 (the update runs after the ownership verdict).
        val sibling = alice.postJson("/api/v1/lenses", request(lensName("lensdup-sib"))).body<LensResponse>()
        val renameClash = alice.putJson("/api/v1/lenses/${sibling.id}", request(name))
        assertEquals(HttpStatusCode.Conflict, renameClash.status)
        assertNotNull(renameClash.body<ProblemDetail>().detail)

        assertEquals(HttpStatusCode.NoContent, alice.delete("/api/v1/lenses/${first.id}").status)
        val second = alice.postJson("/api/v1/lenses", request(name))
        assertEquals(HttpStatusCode.Created, second.status)
        assertTrue(second.body<LensResponse>().id != first.id, "re-adding a freed name mints a NEW id")
    }

    @Test
    fun `visibility - a private lens is invisible to others and public lenses are read-only for them`() =
        testApplication {
            usePostgresTestcontainer()
            val alice = seededClient("lensvisa")
            val bob = seededClient("lensvisb")
            val admin = seededClient("lensvisadm", UserRole.ADMIN)

            val privateLens = alice.postJson(
                "/api/v1/lenses",
                request(lensName("lensvis-priv")),
            ).body<LensResponse>()
            val publicLens = alice.postJson(
                "/api/v1/lenses",
                request(lensName("lensvis-pub"), visibility = LensVisibility.PUBLIC),
            ).body<LensResponse>()

            // Bob's list: the public lens rides along, the private one does not exist for him.
            val bobItems = bob.readLenses().items
            assertNotNull(bobItems.singleOrNull { it.id == publicLens.id })
            assertNull(bobItems.firstOrNull { it.id == privateLens.id })

            // Foreign PRIVATE mutations are a uniform 404 (its existence is the secret)…
            assertEquals(HttpStatusCode.NotFound, bob.putJson("/api/v1/lenses/${privateLens.id}", request(lensName("x"))).status)
            assertEquals(HttpStatusCode.NotFound, bob.delete("/api/v1/lenses/${privateLens.id}").status)
            // …while foreign PUBLIC mutations are the honest 403 (it is in everyone's list).
            assertEquals(HttpStatusCode.Forbidden, bob.putJson("/api/v1/lenses/${publicLens.id}", request(lensName("x"))).status)
            assertEquals(HttpStatusCode.Forbidden, bob.delete("/api/v1/lenses/${publicLens.id}").status)

            // ADMIN gets no special content access: the exact same treatment as Bob.
            assertNull(admin.readLenses().items.firstOrNull { it.id == privateLens.id })
            assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/lenses/${privateLens.id}").status)
            assertEquals(HttpStatusCode.Forbidden, admin.delete("/api/v1/lenses/${publicLens.id}").status)

            // The creator remains fully in charge of both.
            assertEquals(HttpStatusCode.NoContent, alice.delete("/api/v1/lenses/${privateLens.id}").status)
            assertEquals(HttpStatusCode.NoContent, alice.delete("/api/v1/lenses/${publicLens.id}").status)
        }

    @Test
    fun `payload sanitization trims, drops empties, and canonicalizes kinds`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("lenssan")
        val name = lensName("lenssan")
        val create = client.postJson(
            "/api/v1/lenses",
            request(
                "  $name  ",
                filters = LensFilters(
                    name = "  pay  ",
                    namespace = "",
                    kind = listOf(" api ", "component", "API"),
                    labelValue = null,
                ),
            ),
        )
        assertEquals(HttpStatusCode.Created, create.status)
        val created = create.body<LensResponse>()
        assertEquals(name, created.name)
        // Empty strings normalize to ABSENT; case-variant kinds fold, dedupe, and reorder.
        assertEquals(LensFilters(name = "pay", kind = listOf("Component", "API")), created.filters)

        // An empty kind array is "every kind visible" — normalized to absent too.
        val allKinds = client.postJson(
            "/api/v1/lenses",
            request(lensName("lenssan-all"), filters = LensFilters(kind = emptyList())),
        ).body<LensResponse>()
        assertEquals(LensFilters(), allKinds.filters)
    }

    @Test
    fun `invalid payloads are 400 - name rules, unknown kind, orphaned labelValue`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("lensbad")
        val cases = listOf(
            request("   "),
            request("a".repeat(101)),
            request("ctl\u0007name"),
            request(lensName("k"), filters = LensFilters(kind = listOf("Location"))),
            request(lensName("k"), filters = LensFilters(labelValue = listOf("tier-1"))),
            request(lensName("k"), filters = LensFilters(namespace = "n".repeat(256))),
        )
        for (case in cases) {
            assertEquals(
                HttpStatusCode.BadRequest,
                client.postJson("/api/v1/lenses", case).status,
                "expected 400 for $case",
            )
        }
    }

    @Test
    fun `the PUT decides ownership before validating - 404 and 403 win over 400`() = testApplication {
        usePostgresTestcontainer()
        val alice = seededClient("lensordera")
        val bob = seededClient("lensorderb")
        val invalid = request(lensName("k"), filters = LensFilters(kind = listOf("Location")))

        // Unknown id + invalid payload → the 404, not the 400 (the password-PUT precedent).
        assertEquals(HttpStatusCode.NotFound, alice.putJson("/api/v1/lenses/999999", invalid).status)

        val publicLens = alice.postJson(
            "/api/v1/lenses",
            request(lensName("lensorder"), visibility = LensVisibility.PUBLIC),
        ).body<LensResponse>()
        // Foreign public + invalid payload → the 403, not the 400.
        assertEquals(HttpStatusCode.Forbidden, bob.putJson("/api/v1/lenses/${publicLens.id}", invalid).status)
        // The owner with the same invalid payload gets the 400.
        assertEquals(HttpStatusCode.BadRequest, alice.putJson("/api/v1/lenses/${publicLens.id}", invalid).status)
    }

    @Test
    fun `mutations audit and a failed mutation does not`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("lensaudit")
        withAuditCapture { capture ->
            val name = lensName("lensaudit")
            val created = client.postJson(
                "/api/v1/lenses",
                request(name, visibility = LensVisibility.PUBLIC),
            ).body<LensResponse>()
            val event = capture.awaitEvent { it.message == "lens.created" }
            assertNotNull(event, "create must audit")
            assertTrue(event.hasKeyValue("lensId", created.id.toLong()))
            assertTrue(event.hasKeyValue("name", name))
            assertTrue(event.hasKeyValue("visibility", "PUBLIC"))

            client.putJson("/api/v1/lenses/${created.id}", request(name))
            assertNotNull(capture.awaitEvent { it.message == "lens.updated" }, "update must audit")

            client.delete("/api/v1/lenses/${created.id}")
            val deleted = capture.awaitEvent { it.message == "lens.deleted" }
            assertNotNull(deleted, "delete must audit")
            assertTrue(deleted.hasKeyValue("lensId", created.id.toLong()))

            val before = capture.events.count { it.message == "lens.created" }
            assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/lenses", request("   ")).status)
            assertEquals(before, capture.events.count { it.message == "lens.created" }, "failed create must not audit")
        }
    }
}
