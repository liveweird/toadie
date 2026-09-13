package ch.nokillswit

import ch.nokillswit.entityquery.EntityQueryProblem
import ch.nokillswit.entityquery.QueryDiagnosticCodes
import ch.nokillswit.entityquery.SavedEntityQuery
import ch.nokillswit.entityquery.SavedEntityQueryList
import ch.nokillswit.entityquery.SavedEntityQueryRequest
import ch.nokillswit.entityquery.SavedEntityQueryVisibility
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
 * Saved entity queries (PR3, phase 7 — `entityquery/SavedEntityQuery.kt`): the same
 * `lenses/LensTest.kt` case list retargeted at `/api/v1/entity-queries`, with the nine filter
 * slots replaced by ONE query text that must PARSE (but never has to resolve against the
 * CURRENT schema). Names are minted unique per test (`eq-<uuid8>`), so the shared container
 * never couples tests; every created row is soft-deleted in a `finally` regardless of which
 * owner ends up holding it.
 */
class SavedEntityQueryTest {

    private fun eqName() = "eq-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun request(
        name: String,
        visibility: SavedEntityQueryVisibility = SavedEntityQueryVisibility.PRIVATE,
        query: String = "MATCH (a) RETURN a",
    ) = SavedEntityQueryRequest(name = name, visibility = visibility, query = query)

    private suspend fun HttpClient.readQueries(): SavedEntityQueryList = get("/api/v1/entity-queries").body()

    private suspend fun cleanup(ids: List<UInt>) {
        ids.forEach { TestSavedEntityQueries.remove(it) }
    }

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/entity-queries").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/entity-queries").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/entity-queries/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/entity-queries/1").status)
    }

    @Test
    fun `CRUD round-trips - create, list, replace including visibility flip, soft delete`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("eqcrud")
        val ids = mutableListOf<UInt>()
        try {
            val name = eqName()
            val queryText = "MATCH (a) RETURN a"
            val create = client.postJson("/api/v1/entity-queries", request(name, query = queryText))
            assertEquals(HttpStatusCode.Created, create.status)
            val created = create.body<SavedEntityQuery>()
            ids += created.id
            assertEquals(name, created.name)
            assertEquals(SavedEntityQueryVisibility.PRIVATE, created.visibility)
            assertEquals(queryText, created.query)
            assertEquals("Test", created.creatorName)
            assertEquals(false, created.creatorDeleted)
            assertNotNull(create.headers["Location"])

            assertEquals(created, client.readQueries().items.single { it.id == created.id })

            // Whole-row replace: rename + the visibility flip + a new query text in one PUT.
            val renamed = eqName()
            val newQuery = "MATCH (b) RETURN b"
            val put = client.putJson(
                "/api/v1/entity-queries/${created.id}",
                request(renamed, visibility = SavedEntityQueryVisibility.PUBLIC, query = newQuery),
            )
            assertEquals(HttpStatusCode.NoContent, put.status)
            val replaced = client.readQueries().items.single { it.id == created.id }
            assertEquals(renamed, replaced.name)
            assertEquals(SavedEntityQueryVisibility.PUBLIC, replaced.visibility)
            assertEquals(newQuery, replaced.query)
            assertTrue(replaced.updatedAt >= replaced.createdAt)

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/entity-queries/${created.id}").status)
            assertTrue(client.readQueries().items.none { it.id == created.id })
            // Soft-delete convention: the row survives, flagged; a repeat delete is 404.
            assertTrue(TestSavedEntityQueries.rawRows().single { it.id == created.id }.markedAsDeleted)
            assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/entity-queries/${created.id}").status)
        } finally {
            cleanup(ids)
        }
    }

    @Test
    fun `a name clash is 409 per owner - freed by delete, and never across owners`() = testApplication {
        usePostgresTestcontainer()
        val alice = seededClient("eqdupa")
        val bob = seededClient("eqdupb")
        val ids = mutableListOf<UInt>()
        try {
            val name = eqName()
            val first = alice.postJson("/api/v1/entity-queries", request(name)).body<SavedEntityQuery>()
            ids += first.id
            assertEquals(HttpStatusCode.Conflict, alice.postJson("/api/v1/entity-queries", request(name)).status)
            // The partial index folds case: a case-variant twin clashes too.
            val clash = alice.postJson("/api/v1/entity-queries", request(name.uppercase()))
            assertEquals(HttpStatusCode.Conflict, clash.status)
            assertNotNull(clash.body<ProblemDetail>().detail)

            // Uniqueness is PER OWNER: another user may reuse the name (public or private).
            val bobCreate = bob.postJson(
                "/api/v1/entity-queries",
                request(name, visibility = SavedEntityQueryVisibility.PUBLIC),
            )
            assertEquals(HttpStatusCode.Created, bobCreate.status)
            ids += bobCreate.body<SavedEntityQuery>().id

            // The PUT side of the same index: RENAMING a query onto the owner's other active
            // name is the identical 23505 → 409 (the update runs after the ownership verdict).
            val sibling = alice.postJson("/api/v1/entity-queries", request(eqName())).body<SavedEntityQuery>()
            ids += sibling.id
            val renameClash = alice.putJson("/api/v1/entity-queries/${sibling.id}", request(name))
            assertEquals(HttpStatusCode.Conflict, renameClash.status)
            assertNotNull(renameClash.body<ProblemDetail>().detail)

            assertEquals(HttpStatusCode.NoContent, alice.delete("/api/v1/entity-queries/${first.id}").status)
            val second = alice.postJson("/api/v1/entity-queries", request(name))
            assertEquals(HttpStatusCode.Created, second.status)
            val secondCreated = second.body<SavedEntityQuery>()
            ids += secondCreated.id
            assertTrue(secondCreated.id != first.id, "re-adding a freed name mints a NEW id")
        } finally {
            cleanup(ids)
        }
    }

    @Test
    fun `visibility - a private query is invisible to others and public queries are read-only for them`() =
        testApplication {
            usePostgresTestcontainer()
            val alice = seededClient("eqvisa")
            val bob = seededClient("eqvisb")
            val admin = seededClient("eqvisadm", UserRole.ADMIN)
            val ids = mutableListOf<UInt>()
            try {
                val privateQuery = alice.postJson(
                    "/api/v1/entity-queries",
                    request(eqName()),
                ).body<SavedEntityQuery>()
                ids += privateQuery.id
                val publicQuery = alice.postJson(
                    "/api/v1/entity-queries",
                    request(eqName(), visibility = SavedEntityQueryVisibility.PUBLIC),
                ).body<SavedEntityQuery>()
                ids += publicQuery.id

                // Bob's list: the public query rides along, the private one does not exist for him.
                val bobItems = bob.readQueries().items
                assertNotNull(bobItems.singleOrNull { it.id == publicQuery.id })
                assertNull(bobItems.firstOrNull { it.id == privateQuery.id })

                // Foreign PRIVATE mutations are a uniform 404 (its existence is the secret)…
                assertEquals(
                    HttpStatusCode.NotFound,
                    bob.putJson("/api/v1/entity-queries/${privateQuery.id}", request(eqName())).status,
                )
                assertEquals(HttpStatusCode.NotFound, bob.delete("/api/v1/entity-queries/${privateQuery.id}").status)

                // …while foreign PUBLIC mutations are the honest 403 (it is in everyone's list),
                // audited as `authz.denied` on each attempt.
                withAuditCapture { capture ->
                    assertEquals(
                        HttpStatusCode.Forbidden,
                        bob.putJson("/api/v1/entity-queries/${publicQuery.id}", request(eqName())).status,
                    )
                    assertNotNull(
                        capture.awaitEvent { it.message == "authz.denied" },
                        "a foreign-PUBLIC PUT must audit authz.denied",
                    )

                    assertEquals(HttpStatusCode.Forbidden, bob.delete("/api/v1/entity-queries/${publicQuery.id}").status)
                    assertEquals(
                        2,
                        capture.events.count { it.message == "authz.denied" },
                        "a foreign-PUBLIC DELETE must also audit authz.denied",
                    )
                }

                // ADMIN gets no special content access: the exact same treatment as Bob.
                assertNull(admin.readQueries().items.firstOrNull { it.id == privateQuery.id })
                assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/entity-queries/${privateQuery.id}").status)
                assertEquals(HttpStatusCode.Forbidden, admin.delete("/api/v1/entity-queries/${publicQuery.id}").status)

                // The creator remains fully in charge of both.
                assertEquals(HttpStatusCode.NoContent, alice.delete("/api/v1/entity-queries/${privateQuery.id}").status)
                assertEquals(HttpStatusCode.NoContent, alice.delete("/api/v1/entity-queries/${publicQuery.id}").status)
            } finally {
                cleanup(ids)
            }
        }

    @Test
    fun `query text spans lines verbatim, control characters and length limits are 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("eqtext")
        val ids = mutableListOf<UInt>()
        try {
            // Multi-line text is kept VERBATIM (only tab/newline/CR are allowed control chars).
            val multiline = "MATCH (a)\n  RETURN a"
            val create = client.postJson("/api/v1/entity-queries", request(eqName(), query = multiline))
            assertEquals(HttpStatusCode.Created, create.status)
            val created = create.body<SavedEntityQuery>()
            ids += created.id
            assertEquals(multiline, created.query)

            // Any OTHER control character is a clean 400 — the plain ProblemDetail shape (the
            // sanitizer's own BadRequestException, never the query-diagnostics shape).
            val controlChar = client.postJson(
                "/api/v1/entity-queries",
                request(eqName(), query = "MATCH (a)RETURN a"),
            )
            assertEquals(HttpStatusCode.BadRequest, controlChar.status)
            assertNotNull(controlChar.body<ProblemDetail>().detail)

            val cases = listOf(
                request("   "),
                request("a".repeat(101)),
                request(eqName(), query = "   "),
                request(eqName(), query = "M".repeat(2001)),
            )
            for (case in cases) {
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.postJson("/api/v1/entity-queries", case).status,
                    "expected 400 for $case",
                )
            }
        } finally {
            cleanup(ids)
        }
    }

    @Test
    fun `a syntax-invalid query is 400 with positioned SYNTAX diagnostics, an unknown blueprint is accepted`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("eqsyntax")
            val ids = mutableListOf<UInt>()
            try {
                val badQuery = "MATCH (a RETURN a"
                val createBad = client.postJson("/api/v1/entity-queries", request(eqName(), query = badQuery))
                assertEquals(HttpStatusCode.BadRequest, createBad.status)
                val problem = createBad.body<EntityQueryProblem>()
                val diagnostic = problem.diagnostics.single()
                assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
                assertNotNull(diagnostic.line)
                assertNotNull(diagnostic.column)

                // The same shape on PUT: an existing row, replaced with the same bad text.
                val existing = client.postJson("/api/v1/entity-queries", request(eqName())).body<SavedEntityQuery>()
                ids += existing.id
                val putBad = client.putJson(
                    "/api/v1/entity-queries/${existing.id}",
                    request(eqName(), query = badQuery),
                )
                assertEquals(HttpStatusCode.BadRequest, putBad.status)
                val putProblem = putBad.body<EntityQueryProblem>()
                assertEquals(QueryDiagnosticCodes.SYNTAX, putProblem.diagnostics.single().code)

                // Schema validity (an unknown blueprint) is deliberately NOT required at save
                // time: blueprints change, and a stale query shows its diagnostics when applied.
                val unknownBlueprint = client.postJson(
                    "/api/v1/entity-queries",
                    request(eqName(), query = "MATCH (a:no_such_bp) RETURN a"),
                )
                assertEquals(HttpStatusCode.Created, unknownBlueprint.status)
                ids += unknownBlueprint.body<SavedEntityQuery>().id
            } finally {
                cleanup(ids)
            }
        }

    @Test
    fun `the PUT decides ownership before validating - 404 and 403 win over 400`() = testApplication {
        usePostgresTestcontainer()
        val alice = seededClient("eqordera")
        val bob = seededClient("eqorderb")
        val invalid = request(eqName(), query = "MATCH (a RETURN a")
        val ids = mutableListOf<UInt>()
        try {
            // Unknown id + invalid payload → the 404, not the 400 (the password-PUT precedent).
            assertEquals(HttpStatusCode.NotFound, alice.putJson("/api/v1/entity-queries/999999", invalid).status)

            val publicQuery = alice.postJson(
                "/api/v1/entity-queries",
                request(eqName(), visibility = SavedEntityQueryVisibility.PUBLIC),
            ).body<SavedEntityQuery>()
            ids += publicQuery.id
            // Foreign public + invalid payload → the 403, not the 400.
            assertEquals(
                HttpStatusCode.Forbidden,
                bob.putJson("/api/v1/entity-queries/${publicQuery.id}", invalid).status,
            )
            // The owner with the same invalid payload gets the 400.
            assertEquals(
                HttpStatusCode.BadRequest,
                alice.putJson("/api/v1/entity-queries/${publicQuery.id}", invalid).status,
            )
        } finally {
            cleanup(ids)
        }
    }

    @Test
    fun `mutations audit and a failed mutation does not`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("eqaudit")
        val ids = mutableListOf<UInt>()
        try {
            withAuditCapture { capture ->
                val name = eqName()
                val created = client.postJson(
                    "/api/v1/entity-queries",
                    request(name, visibility = SavedEntityQueryVisibility.PUBLIC),
                ).body<SavedEntityQuery>()
                ids += created.id
                val event = capture.awaitEvent { it.message == "entity_query.created" }
                assertNotNull(event, "create must audit")
                assertTrue(event.keyValuePairs?.any { it.key == "byUserId" } == true)
                assertTrue(event.hasKeyValue("entityQueryId", created.id.toLong()))
                assertTrue(event.hasKeyValue("name", name))
                assertTrue(event.hasKeyValue("visibility", "PUBLIC"))

                client.putJson("/api/v1/entity-queries/${created.id}", request(name))
                val updated = capture.awaitEvent { it.message == "entity_query.updated" }
                assertNotNull(updated, "update must audit")
                assertTrue(updated.hasKeyValue("entityQueryId", created.id.toLong()))
                assertTrue(updated.hasKeyValue("name", name))
                assertTrue(updated.hasKeyValue("visibility", "PRIVATE"))

                client.delete("/api/v1/entity-queries/${created.id}")
                val deleted = capture.awaitEvent { it.message == "entity_query.deleted" }
                assertNotNull(deleted, "delete must audit")
                assertTrue(deleted.hasKeyValue("entityQueryId", created.id.toLong()))

                val before = capture.events.count { it.message == "entity_query.created" }
                assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/entity-queries", request("   ")).status)
                assertEquals(
                    before,
                    capture.events.count { it.message == "entity_query.created" },
                    "failed create must not audit",
                )
            }
        } finally {
            cleanup(ids)
        }
    }
}
