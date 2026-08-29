package ch.nokillswit

import ch.nokillswit.types.EntityTypesList
import ch.nokillswit.types.EntityTypesRequest
import ch.nokillswit.types.EntityTypesResponse
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
 * The per-kind type-dictionary surface: CRUD semantics, the payload rules, per-kind
 * independence, and the authz split. The dictionaries are SHARED suite state and per-kind
 * SINGLETONS (V15 seeds all six type-bearing kinds), so mutating tests scope themselves
 * with [TestEntityTypes.withKindTypes] (restore-in-finally) instead of minting unique rows.
 */
class EntityTypesTest {

    private fun typeValue(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}".lowercase()

    private suspend fun HttpClient.readDictionaries(): EntityTypesList = get("/api/v1/entity-types").body()

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/entity-types").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/entity-types").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/entity-types/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/entity-types/1").status)
    }

    @Test
    fun `non-admin may read but not write - uniformly 403 even on an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("etuser")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/entity-types").status)
        val body = EntityTypesRequest(kind = "Component", types = listOf(typeValue("etu")))
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/v1/entity-types", body).status)
        // Guard-before-read: the probe cannot distinguish real from unknown ids.
        assertEquals(HttpStatusCode.Forbidden, client.putJson("/api/v1/entity-types/999999", body).status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/entity-types/999999").status)
    }

    @Test
    fun `the V15 seed defines the six type-bearing kinds with the well-known values`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("etseed")
        val byKind = client.readDictionaries().items.associateBy { it.kind }
        assertTrue("service" in byKind.getValue("Component").types)
        assertTrue("openapi" in byKind.getValue("API").types)
        assertTrue("team" in byKind.getValue("Group").types)
        assertTrue("database" in byKind.getValue("Resource").types)
        assertTrue("product" in byKind.getValue("System").types)
        assertTrue("product-area" in byKind.getValue("Domain").types)
        // Independence by construction: `service` may live under several kinds at once.
        assertTrue("service" in byKind.getValue("System").types)
        assertTrue("User" !in byKind, "User has no spec.type — it must never hold a dictionary")
    }

    @Test
    fun `admin CRUD round-trips - create, list, replace, delete (Domain parked meanwhile)`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("etcrud", UserRole.ADMIN)
        // Park Domain's seeded dictionary so create targets a kind without an active row.
        TestEntityTypes.withKindTypes("Domain", null) {
            val (a, b) = typeValue("eta") to typeValue("etb")
            val create = admin.postJson(
                "/api/v1/entity-types",
                EntityTypesRequest(kind = "Domain", types = listOf(a, b)),
            )
            assertEquals(HttpStatusCode.Created, create.status)
            val created = create.body<EntityTypesResponse>()
            assertEquals("Domain", created.kind)
            assertEquals(listOf(a, b), created.types)
            assertNotNull(create.headers["Location"])

            val listed = admin.readDictionaries().items.single { it.kind == "Domain" }
            assertEquals(created, listed)

            val c = typeValue("etc")
            val put = admin.putJson(
                "/api/v1/entity-types/${created.id}",
                EntityTypesRequest(kind = "Domain", types = listOf(c)),
            )
            assertEquals(HttpStatusCode.NoContent, put.status)
            assertEquals(listOf(c), admin.readDictionaries().items.single { it.id == created.id }.types)

            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/entity-types/${created.id}").status)
            assertTrue(admin.readDictionaries().items.none { it.id == created.id })
            // Soft-delete convention: the row survives, flagged.
            val raw = TestEntityTypes.rawRows().single { it.id == created.id }
            assertTrue(raw.markedAsDeleted, "delete must soft-delete, not remove")
        }
    }

    @Test
    fun `mutations on a missing or deleted id are 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("et404", UserRole.ADMIN)
        val body = EntityTypesRequest(kind = "Domain", types = listOf(typeValue("et404")))
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/entity-types/999999", body).status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/entity-types/999999").status)
        TestEntityTypes.withKindTypes("Domain", null) {
            val id = admin.postJson("/api/v1/entity-types", body).body<EntityTypesResponse>().id
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/entity-types/$id").status)
            assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/entity-types/$id").status)
            assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/entity-types/$id", body).status)
        }
    }

    @Test
    fun `payload sanitization canonicalizes the kind and trims types`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("etsan", UserRole.ADMIN)
        TestEntityTypes.withKindTypes("Domain", null) {
            val t = typeValue("ets")
            val create = admin.postJson(
                "/api/v1/entity-types",
                EntityTypesRequest(kind = " domain ", types = listOf(" $t ")),
            )
            assertEquals(HttpStatusCode.Created, create.status)
            val created = create.body<EntityTypesResponse>()
            assertEquals("Domain", created.kind)
            assertEquals(listOf(t), created.types)
        }
    }

    @Test
    fun `invalid payloads are 400 - unknown kind, User, emptiness, grammar, duplicates, overflow`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("etbad", UserRole.ADMIN)
            val cases = listOf(
                EntityTypesRequest(kind = "Location", types = listOf("url")),
                // User's spec has no type field — a dictionary for it is meaningless.
                EntityTypesRequest(kind = "User", types = listOf("person")),
                EntityTypesRequest(kind = "Domain", types = emptyList()),
                EntityTypesRequest(kind = "Domain", types = listOf("has space")),
                EntityTypesRequest(kind = "Domain", types = listOf("x".repeat(64))),
                EntityTypesRequest(kind = "Domain", types = listOf("dup", "DUP")),
                EntityTypesRequest(kind = "Domain", types = (1..101).map { "v$it" }),
            )
            for (case in cases) {
                assertEquals(
                    HttpStatusCode.BadRequest,
                    admin.postJson("/api/v1/entity-types", case).status,
                    "expected 400 for $case",
                )
            }
        }

    @Test
    fun `a kind with an active dictionary rejects a second one with 409 and soft-delete frees it`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("etdup", UserRole.ADMIN)
            // The seed row itself is the clash target — no setup needed.
            val clash = admin.postJson(
                "/api/v1/entity-types",
                EntityTypesRequest(kind = "Component", types = listOf(typeValue("etd"))),
            )
            assertEquals(HttpStatusCode.Conflict, clash.status)

            TestEntityTypes.withKindTypes("Domain", null) {
                // Domain's row is parked (soft-deleted) — the kind is free again.
                val recreate = admin.postJson(
                    "/api/v1/entity-types",
                    EntityTypesRequest(kind = "Domain", types = listOf(typeValue("etd2"))),
                )
                assertEquals(HttpStatusCode.Created, recreate.status)
                // Moving another kind's dictionary onto Component clashes too (the PUT path).
                val id = recreate.body<EntityTypesResponse>().id
                assertEquals(
                    HttpStatusCode.Conflict,
                    admin.putJson(
                        "/api/v1/entity-types/$id",
                        EntityTypesRequest(kind = "Component", types = listOf(typeValue("etd3"))),
                    ).status,
                )
            }
        }

    @Test
    fun `mutations audit and a failed mutation does not`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("etaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            TestEntityTypes.withKindTypes("Domain", null) {
                val created = admin.postJson(
                    "/api/v1/entity-types",
                    EntityTypesRequest(kind = "Domain", types = listOf(typeValue("eta"), typeValue("etb"))),
                ).body<EntityTypesResponse>()
                val event = capture.awaitEvent { it.message == "entity_types.created" }
                assertNotNull(event, "create must audit")
                assertTrue(event.hasKeyValue("kind", "Domain"))
                assertTrue(event.hasKeyValue("types", 2))

                admin.putJson(
                    "/api/v1/entity-types/${created.id}",
                    EntityTypesRequest(kind = "Domain", types = listOf(typeValue("etc"))),
                )
                assertNotNull(capture.awaitEvent { it.message == "entity_types.updated" }, "update must audit")

                admin.delete("/api/v1/entity-types/${created.id}")
                val deleted = capture.awaitEvent { it.message == "entity_types.deleted" }
                assertNotNull(deleted, "delete must audit")
                assertTrue(deleted.hasKeyValue("entityTypesId", created.id.toLong()))

                val before = capture.events.count { it.message == "entity_types.created" }
                assertEquals(
                    HttpStatusCode.BadRequest,
                    admin.postJson(
                        "/api/v1/entity-types",
                        EntityTypesRequest(kind = "Domain", types = listOf("BAD TYPE")),
                    ).status,
                )
                assertEquals(
                    before,
                    capture.events.count { it.message == "entity_types.created" },
                    "failed create must not audit",
                )
            }
        }
    }
}
