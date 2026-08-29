package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.tags.TagCategoryList
import ch.nokillswit.tags.TagCategoryRequest
import ch.nokillswit.tags.TagCategoryResponse
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
 * The tag-category surface: CRUD semantics, the payload rules, the one-category-per-tag
 * invariant, and the authz split. The registry is SHARED suite state — every test mints
 * UNIQUE names and tags and removes what its assertions depend on.
 */
class TagCategoryTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun tag(prefix: String) = name(prefix).lowercase()

    private fun request(
        name: String,
        tags: List<String> = listOf(tag("t1"), tag("t2")),
        kinds: List<String> = listOf("Component"),
    ) = TagCategoryRequest(name = name, tags = tags, kinds = kinds)

    private suspend fun HttpClient.readCategories(): TagCategoryList = get("/api/v1/tag-categories").body()

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/tag-categories").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/tag-categories").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/tag-categories/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/tag-categories/1").status)
    }

    @Test
    fun `non-admin may read but not write - uniformly 403 even on an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("tcuser")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/tag-categories").status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/v1/tag-categories", request(name("tcu"))).status)
        // Guard-before-read: the probe cannot distinguish real from unknown ids.
        assertEquals(
            HttpStatusCode.Forbidden,
            client.putJson("/api/v1/tag-categories/999999", request(name("tcu"))).status,
        )
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/tag-categories/999999").status)
    }

    @Test
    fun `admin CRUD round-trips - create, list, replace, delete`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tccrud", UserRole.ADMIN)
        val n = name("tccrud")
        val (a, b) = tag("tca") to tag("tcb")

        val create = admin.postJson(
            "/api/v1/tag-categories",
            request(n, tags = listOf(a, b), kinds = listOf("Component", "API")),
        )
        assertEquals(HttpStatusCode.Created, create.status)
        val created = create.body<TagCategoryResponse>()
        assertEquals(n, created.name)
        assertEquals(listOf(a, b), created.tags)
        assertEquals(listOf("Component", "API"), created.kinds)
        assertNotNull(create.headers["Location"])

        val listed = admin.readCategories().items.single { it.name == n }
        assertEquals(created, listed)

        val renamed = name("tccrud2")
        val c = tag("tcc")
        val put = admin.putJson(
            "/api/v1/tag-categories/${created.id}",
            request(renamed, tags = listOf(c), kinds = listOf("System")),
        )
        assertEquals(HttpStatusCode.NoContent, put.status)
        val replaced = admin.readCategories().items.single { it.id == created.id }
        assertEquals(renamed, replaced.name)
        assertEquals(listOf(c), replaced.tags)
        assertEquals(listOf("System"), replaced.kinds)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/tag-categories/${created.id}").status)
        assertTrue(admin.readCategories().items.none { it.id == created.id })
        // Soft-delete convention: the row survives, flagged.
        val raw = TestTagCategories.rawRows().single { it.id == created.id }
        assertTrue(raw.markedAsDeleted, "delete must soft-delete, not remove")
    }

    @Test
    fun `mutations on a missing or deleted id are 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tc404", UserRole.ADMIN)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.putJson("/api/v1/tag-categories/999999", request(name("tc404"))).status,
        )
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/tag-categories/999999").status)
        val id = TestTagCategories.ensure(name("tc404gone"), listOf(tag("g")), listOf("Component"))
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/tag-categories/$id").status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/tag-categories/$id").status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/tag-categories/$id", request(name("tcb"))).status)
        // The family contract: a missing target is 404 even when the payload would ALSO
        // conflict (a tag another active category holds) — existence is checked first.
        val claimed = tag("tcheld")
        TestTagCategories.ensure(name("tc404holder"), listOf(claimed), listOf("Component"))
        assertEquals(
            HttpStatusCode.NotFound,
            admin.putJson("/api/v1/tag-categories/$id", request(name("tcc"), tags = listOf(claimed))).status,
        )
    }

    @Test
    fun `payload sanitization trims the name and canonicalizes kinds into the supported order`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tcsan", UserRole.ADMIN)
        val n = name("tcsan")
        val t = tag("tcs")
        val create = admin.postJson(
            "/api/v1/tag-categories",
            request("  $n  ", tags = listOf(" $t "), kinds = listOf(" api ", "component", "API")),
        )
        assertEquals(HttpStatusCode.Created, create.status)
        val created = create.body<TagCategoryResponse>()
        assertEquals(n, created.name)
        assertEquals(listOf(t), created.tags)
        // Case-variant kinds fold to canonical casing, dedupe, and take SUPPORTED_KINDS order.
        assertEquals(listOf("Component", "API"), created.kinds)
    }

    @Test
    fun `invalid payloads are 400 - name, tag grammar, emptiness, duplicates, unknown kind`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tcbad", UserRole.ADMIN)
        val cases = listOf(
            request(""),
            request("x".repeat(64)),
            request(name("k"), tags = emptyList()),
            request(name("k"), tags = listOf("Uppercase")),
            request(name("k"), tags = listOf("has space")),
            request(name("k"), tags = listOf("dup", "dup")),
            request(name("k"), tags = (1..101).map { "v$it" }),
            request(name("k"), kinds = emptyList()),
            request(name("k"), kinds = listOf("Location")),
        )
        for (case in cases) {
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.postJson("/api/v1/tag-categories", case).status,
                "expected 400 for $case",
            )
        }
        // The PUT path validates before the id lookup, so the same rejection fires there too.
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson("/api/v1/tag-categories/999999", cases.first()).status,
        )
    }

    @Test
    fun `an active name clash is 409 and a soft-deleted category frees name and tags`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tcdup", UserRole.ADMIN)
        val n = name("tcdup")
        val t = tag("tcd")
        val first = admin.postJson("/api/v1/tag-categories", request(n, tags = listOf(t))).body<TagCategoryResponse>()
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/tag-categories", request(n)).status)
        // The partial index folds case: a case-variant twin clashes too.
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/tag-categories", request(n.uppercase())).status)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/tag-categories/${first.id}").status)
        // Both the name AND the previously held tag are free again.
        val second = admin.postJson("/api/v1/tag-categories", request(n, tags = listOf(t)))
        assertEquals(HttpStatusCode.Created, second.status)
        assertTrue(second.body<TagCategoryResponse>().id != first.id, "re-adding a freed name mints a NEW id")
        // The PUT path hits the same partial index: renaming onto an active name is 409 too.
        val otherId = TestTagCategories.ensure(name("tcdupother"), listOf(tag("tcdo")), listOf("Component"))
        assertEquals(
            HttpStatusCode.Conflict,
            admin.putJson("/api/v1/tag-categories/$otherId", request(n, tags = listOf(tag("tcdo2")))).status,
        )
    }

    @Test
    fun `a tag belongs to exactly one category - cross-category clashes are 409 on create and update`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("tcone", UserRole.ADMIN)
            val owned = tag("tcone")
            TestTagCategories.ensure(name("tconeowner"), listOf(owned), listOf("Component"))

            val create = admin.postJson("/api/v1/tag-categories", request(name("tconeb"), tags = listOf(owned)))
            assertEquals(HttpStatusCode.Conflict, create.status)
            assertTrue(create.body<ProblemDetail>().detail!!.contains("already belongs to category"))
            // Case-folded: an uppercase variant would be the same tag (grammar forbids it in
            // storage, but the clash check must not be byte-blind either way).

            val otherId = TestTagCategories.ensure(name("tconec"), listOf(tag("tcfree")), listOf("Component"))
            assertEquals(
                HttpStatusCode.Conflict,
                admin.putJson("/api/v1/tag-categories/$otherId", request(name("tconec"), tags = listOf(owned))).status,
            )

            // A category may keep (re-save) its OWN tags — the check excludes its own row.
            val ownerId = TestTagCategories.ensure(name("tconeowner2"), listOf(tag("tcown")), listOf("Component"))
            val own = admin.readCategories().items.single { it.id == ownerId }
            assertEquals(
                HttpStatusCode.NoContent,
                admin.putJson(
                    "/api/v1/tag-categories/$ownerId",
                    request(own.name, tags = own.tags, kinds = own.kinds),
                ).status,
            )
        }

    @Test
    fun `moving a tag between categories works as remove-then-add in two saves`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tcmove", UserRole.ADMIN)
        val moving = tag("tcmove")
        val keeper = tag("tckeep")
        val fromId = TestTagCategories.ensure(name("tcmovefrom"), listOf(moving, keeper), listOf("Component"))
        val toId = TestTagCategories.ensure(name("tcmoveto"), listOf(tag("tcto")), listOf("Component"))
        val from = admin.readCategories().items.single { it.id == fromId }
        val to = admin.readCategories().items.single { it.id == toId }

        // Adding first trips the one-category rule…
        assertEquals(
            HttpStatusCode.Conflict,
            admin.putJson("/api/v1/tag-categories/$toId", request(to.name, tags = to.tags + moving)).status,
        )
        // …removing from the owner first, then adding, succeeds.
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/tag-categories/$fromId", request(from.name, tags = listOf(keeper))).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/tag-categories/$toId", request(to.name, tags = to.tags + moving)).status,
        )
    }

    @Test
    fun `mutations audit and a failed mutation does not`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tcaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            val n = name("tcaudit")
            val created = admin.postJson(
                "/api/v1/tag-categories",
                request(n, tags = listOf(tag("ta"), tag("tb")), kinds = listOf("Component", "API")),
            ).body<TagCategoryResponse>()
            val event = capture.awaitEvent { it.message == "tag_category.created" }
            assertNotNull(event, "create must audit")
            assertTrue(event.hasKeyValue("name", n))
            assertTrue(event.hasKeyValue("tags", 2))
            assertTrue(event.hasKeyValue("kinds", "Component,API"))

            admin.putJson("/api/v1/tag-categories/${created.id}", request(n, tags = listOf(tag("tc"))))
            assertNotNull(capture.awaitEvent { it.message == "tag_category.updated" }, "update must audit")

            admin.delete("/api/v1/tag-categories/${created.id}")
            val deleted = capture.awaitEvent { it.message == "tag_category.deleted" }
            assertNotNull(deleted, "delete must audit")
            assertTrue(deleted.hasKeyValue("categoryId", created.id.toLong()))

            val before = capture.events.count { it.message == "tag_category.created" }
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.postJson("/api/v1/tag-categories", request(name("tcx"), tags = listOf("BAD TAG"))).status,
            )
            assertEquals(
                before,
                capture.events.count { it.message == "tag_category.created" },
                "failed create must not audit",
            )
        }
    }
}
