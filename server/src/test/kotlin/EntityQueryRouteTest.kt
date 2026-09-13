package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.entities.EntityGraph
import ch.nokillswit.entities.EntityGraphFilter
import ch.nokillswit.entityquery.EntityQueryInvalidException
import ch.nokillswit.entities.EntityImportRequest
import ch.nokillswit.entities.EntityImportResponse
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entityquery.EntityQueryCheckRequest
import ch.nokillswit.entityquery.EntityQueryCheckResponse
import ch.nokillswit.entityquery.EntityQueryProblem
import ch.nokillswit.entityquery.QueryDiagnosticCodes
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URLEncoder
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The entity query bar's route surface (Port migration phase 7, 2.0.0 — PR2):
 * `GET /api/v1/entities/graph?query=` narrowing the shown set over the FULL active workspace
 * (traversal through hidden entities, the `$team`/hierarchy virtual edges, the both-ends rule)
 * and `POST /api/v1/entities/query/check` (the editor's live diagnostics, never audited). Every
 * test mints unique `bp-<uuid8>`/`ent-<uuid8>` identifiers and cleans up via
 * [TestEntities]/[TestBlueprints] in `finally`. See `EntityTest.kt` for the ordinary (query-less)
 * graph route coverage this file does not repeat.
 */
class EntityQueryRouteTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private suspend fun HttpClient.createBlueprint(request: BlueprintRequest) =
        postJson("/api/v1/blueprints", request).body<BlueprintResponse>()

    private fun simpleBlueprint(id: String) = BlueprintRequest(identifier = id, title = "T", schema = BlueprintSchema())

    private fun entityRequest(blueprint: String, identifier: String) =
        EntityRequest(blueprint = blueprint, identifier = identifier, title = "Title $identifier")

    private fun teamEntity(identifier: String) =
        EntityRequest(blueprint = SYSTEM_TEAM_BLUEPRINT, identifier = identifier, title = identifier)

    private fun EntityRequest.toImportDocument(): JsonObject = Json.encodeToJsonElement(this).jsonObject

    @Test
    fun `a query narrows the graph and the both-ends rule holds`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-narrow", UserRole.ADMIN)
        val aBp = unique("bp-eq-a")
        val bBp = unique("bp-eq-b")
        val a1 = unique("ent-eq-a1")
        val a2 = unique("ent-eq-a2")
        val b1 = unique("ent-eq-b1")
        try {
            client.createBlueprint(simpleBlueprint(bBp))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = aBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("rel" to RelationDefinition(title = "Rel", target = bBp, required = false, many = false)),
                ),
            )
            client.postJson("/api/v1/entities", entityRequest(bBp, b1))
            client.postJson("/api/v1/entities", entityRequest(aBp, a1).copy(relations = buildJsonObject { put("rel", b1) }))
            client.postJson("/api/v1/entities", entityRequest(aBp, a2))

            val query = "MATCH (a:`$aBp`)-[:rel]->(b:`$bBp`) RETURN a"
            val graph = client.get("/api/v1/entities/graph?query=${query.urlEncoded()}").body<EntityGraph>()

            assertEquals(setOf("$aBp|$a1"), graph.nodes.map { it.id }.toSet())
            assertTrue(graph.edges.isEmpty(), "b is not RETURNed, so the both-ends rule must drop the a->b edge")
        } finally {
            TestEntities.remove(a1, a2, b1)
            TestBlueprints.remove(aBp, bBp)
        }
    }

    @Test
    fun `traversal through a blueprint-hidden entity shows the ends, never the middle`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-through", UserRole.ADMIN)
        val aBp = unique("bp-eq-t-a")
        val bBp = unique("bp-eq-t-b")
        val cBp = unique("bp-eq-t-c")
        val a1 = unique("ent-eq-t-a")
        val b1 = unique("ent-eq-t-b")
        val c1 = unique("ent-eq-t-c")
        try {
            client.createBlueprint(simpleBlueprint(cBp))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = bBp, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("r2" to RelationDefinition(title = "R2", target = cBp, required = false, many = false)),
                ),
            )
            client.createBlueprint(
                BlueprintRequest(
                    identifier = aBp, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("r1" to RelationDefinition(title = "R1", target = bBp, required = false, many = false)),
                ),
            )
            client.postJson("/api/v1/entities", entityRequest(cBp, c1))
            client.postJson("/api/v1/entities", entityRequest(bBp, b1).copy(relations = buildJsonObject { put("r2", c1) }))
            client.postJson("/api/v1/entities", entityRequest(aBp, a1).copy(relations = buildJsonObject { put("r1", b1) }))

            val query = "MATCH (a:`$aBp`)-[:r1]->(b:`$bBp`)-[:r2]->(c:`$cBp`) RETURN a, c"
            val url = "/api/v1/entities/graph?blueprint=$aBp&blueprint=$cBp&query=${query.urlEncoded()}"
            val graph = client.get(url).body<EntityGraph>()

            assertEquals(setOf("$aBp|$a1", "$cBp|$c1"), graph.nodes.map { it.id }.toSet())
            assertTrue(graph.nodes.none { it.id == "$bBp|$b1" }, "the hidden middle entity must never surface as a node")
        } finally {
            TestEntities.remove(a1, b1, c1)
            TestBlueprints.remove(aBp, bBp, cBp)
        }
    }

    @Test
    fun `dollar-team edge reaches the _team node from an Inherited-team entity`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-team", UserRole.ADMIN)
        val pBp = unique("bp-eq-team-p")
        val cBp = unique("bp-eq-team-c")
        val teamA = unique("team-eq-a")
        val p1 = unique("ent-eq-team-p1")
        val c1 = unique("ent-eq-team-c1")
        try {
            client.createBlueprint(
                BlueprintRequest(
                    identifier = pBp, title = "T", schema = BlueprintSchema(),
                    ownership = OwnershipDefinition(type = "Direct"),
                ),
            )
            client.createBlueprint(
                BlueprintRequest(
                    identifier = cBp, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("parent" to RelationDefinition(title = "Parent", target = pBp, required = false, many = false)),
                    ownership = OwnershipDefinition(type = "Inherited", path = "parent"),
                ),
            )
            client.postJson("/api/v1/entities", teamEntity(teamA))
            client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = pBp, identifier = p1, title = "T", team = JsonPrimitive(teamA)),
            )
            client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = cBp, identifier = c1, title = "T", relations = buildJsonObject { put("parent", p1) }),
            )

            val query = "MATCH (c:`$cBp`)-[:\$team]->(t:`$SYSTEM_TEAM_BLUEPRINT`) RETURN c, t"
            val graph = client.get("/api/v1/entities/graph?query=${query.urlEncoded()}").body<EntityGraph>()

            assertEquals(setOf("$cBp|$c1", "$SYSTEM_TEAM_BLUEPRINT|$teamA"), graph.nodes.map { it.id }.toSet())
        } finally {
            TestEntities.remove(c1, p1, teamA)
            TestBlueprints.remove(pBp, cBp)
        }
    }

    @Test
    fun `a hierarchy id is a virtual edge type across two blueprints with different relation keys`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-hier", UserRole.ADMIN)
        val parentBp = unique("bp-eq-h-parent")
        val childBp = unique("bp-eq-h-child")
        val grandBp = unique("bp-eq-h-grand")
        val parentEnt = unique("ent-eq-h-parent")
        val childEnt = unique("ent-eq-h-child")
        val grandEnt = unique("ent-eq-h-grand")
        try {
            client.createBlueprint(simpleBlueprint(parentBp))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = childBp, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("parent" to RelationDefinition(title = "Parent", target = parentBp, required = false, many = false)),
                    hierarchyRelations = mapOf("composition" to "parent"),
                ),
            )
            client.createBlueprint(
                BlueprintRequest(
                    identifier = grandBp, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("owner" to RelationDefinition(title = "Owner", target = childBp, required = false, many = false)),
                    hierarchyRelations = mapOf("composition" to "owner"),
                ),
            )
            client.postJson("/api/v1/entities", entityRequest(parentBp, parentEnt))
            client.postJson(
                "/api/v1/entities",
                entityRequest(childBp, childEnt).copy(relations = buildJsonObject { put("parent", parentEnt) }),
            )
            client.postJson(
                "/api/v1/entities",
                entityRequest(grandBp, grandEnt).copy(relations = buildJsonObject { put("owner", childEnt) }),
            )

            val query = "MATCH (a)-[:composition]->(b) RETURN a, b"
            val url = "/api/v1/entities/graph?blueprint=$parentBp&blueprint=$childBp&blueprint=$grandBp&query=${query.urlEncoded()}"
            val graph = client.get(url).body<EntityGraph>()

            assertEquals(
                setOf("$parentBp|$parentEnt", "$childBp|$childEnt", "$grandBp|$grandEnt"),
                graph.nodes.map { it.id }.toSet(),
            )
        } finally {
            TestEntities.remove(parentEnt, childEnt, grandEnt)
            TestBlueprints.remove(parentBp, childBp, grandBp)
        }
    }

    @Test
    fun `a mistyped label is UNKNOWN_LABEL with a positioned suggestion`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-typo", UserRole.ADMIN)
        val bp = unique("bp-eq-typo")
        try {
            client.createBlueprint(simpleBlueprint(bp))
            val typo = bp + "x"
            val query = "MATCH (a:`$typo`) RETURN a"

            val response = client.get("/api/v1/entities/graph?query=${query.urlEncoded()}")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val problem = response.body<EntityQueryProblem>()
            val diagnostic = problem.diagnostics.single()
            assertEquals(QueryDiagnosticCodes.UNKNOWN_LABEL, diagnostic.code)
            // An unknown label is positioned on its NODE pattern — `MATCH ` is six characters, so
            // the `(` opens at column 7 (the validator spans the node, not the label token).
            assertEquals(1, diagnostic.line)
            assertEquals(7, diagnostic.column)
            assertEquals(bp, diagnostic.suggestion)
        } finally {
            TestBlueprints.remove(bp)
        }
    }

    @Test
    fun `an unsupported clause is UNSUPPORTED`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-unsupported", UserRole.ADMIN)
        val query = "MATCH (a) WITH a RETURN a"

        val response = client.get("/api/v1/entities/graph?query=${query.urlEncoded()}")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(QueryDiagnosticCodes.UNSUPPORTED, response.body<EntityQueryProblem>().diagnostics.single().code)
    }

    @Test
    fun `an over-long query is a plain 400 with no diagnostics`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-toolong", UserRole.ADMIN)
        val tooLong = "x".repeat(2001)

        val response = client.get("/api/v1/entities/graph?query=$tooLong")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue("diagnostics" !in body, "a plain length rejection must carry no diagnostics field: $body")
        response.body<ProblemDetail>() // decodes cleanly as a plain problem, never EntityQueryProblem
    }

    @Test
    fun `query check mirrors graph diagnostics, requires auth, and is never audited`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-check", UserRole.ADMIN)
        val bp = unique("bp-eq-check")
        try {
            client.createBlueprint(simpleBlueprint(bp))
            val typo = bp + "x"
            val invalidQuery = "MATCH (a:`$typo`) RETURN a"

            withAuditCapture { capture ->
                val invalid = client.postJson("/api/v1/entities/query/check", EntityQueryCheckRequest(invalidQuery))
                assertEquals(HttpStatusCode.OK, invalid.status)
                val invalidDiagnostics = invalid.body<EntityQueryCheckResponse>().diagnostics
                assertEquals(QueryDiagnosticCodes.UNKNOWN_LABEL, invalidDiagnostics.single().code)
                assertEquals(bp, invalidDiagnostics.single().suggestion)

                val valid = client.postJson("/api/v1/entities/query/check", EntityQueryCheckRequest("MATCH (a) RETURN a"))
                assertEquals(HttpStatusCode.OK, valid.status)
                assertEquals(emptyList(), valid.body<EntityQueryCheckResponse>().diagnostics)

                assertTrue(capture.events.none { it.message.startsWith("entity.") }, "a pure check must never audit")
            }

            val anonymous = jsonClient().postJson("/api/v1/entities/query/check", EntityQueryCheckRequest("MATCH (a) RETURN a"))
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        } finally {
            TestBlueprints.remove(bp)
        }
    }

    @Test
    fun `evaluation exceeding the binding cap is BINDING_LIMIT`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-bind", UserRole.ADMIN)
        val xBp = unique("bp-eq-bind-x")
        val yBp = unique("bp-eq-bind-y")
        val zBp = unique("bp-eq-bind-z")
        val zIds = (1..60).map { unique("ent-eq-bind-z$it") }
        val yIds = (1..60).map { unique("ent-eq-bind-y$it") }
        val xIds = (1..40).map { unique("ent-eq-bind-x$it") }
        try {
            client.createBlueprint(simpleBlueprint(zBp))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = yBp, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("next" to RelationDefinition(title = "Next", target = zBp, required = false, many = true)),
                ),
            )
            client.createBlueprint(
                BlueprintRequest(
                    identifier = xBp, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("next" to RelationDefinition(title = "Next", target = yBp, required = false, many = true)),
                ),
            )

            // 40 X rows each -next-> all 60 Y rows, each -next-> all 60 Z rows: 40*60*60 = 144,000
            // bindings, well past MAX_QUERY_BINDINGS (100,000) — created via one import batch.
            val zDocs = zIds.map { EntityRequest(blueprint = zBp, identifier = it, title = "T") }
            val yDocs = yIds.map { id ->
                EntityRequest(
                    blueprint = yBp, identifier = id, title = "T",
                    relations = buildJsonObject { put("next", JsonArray(zIds.map { JsonPrimitive(it) })) },
                )
            }
            val xDocs = xIds.map { id ->
                EntityRequest(
                    blueprint = xBp, identifier = id, title = "T",
                    relations = buildJsonObject { put("next", JsonArray(yIds.map { JsonPrimitive(it) })) },
                )
            }
            val documents = (zDocs + yDocs + xDocs).map { it.toImportDocument() }
            val imported = client.postJson("/api/v1/entities/import", EntityImportRequest(documents))
            assertEquals(HttpStatusCode.OK, imported.status)
            val rows = imported.body<EntityImportResponse>().results
            assertTrue(rows.all { it.status == OntologyImportStatus.CREATED }, "every fixture row must import cleanly: $rows")

            val query = "MATCH (x:`$xBp`)-->(y:`$yBp`)-->(z:`$zBp`) RETURN z"
            val url = "/api/v1/entities/graph?blueprint=$xBp&blueprint=$yBp&blueprint=$zBp&query=${query.urlEncoded()}"
            val response = client.get(url)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(QueryDiagnosticCodes.BINDING_LIMIT, response.body<EntityQueryProblem>().diagnostics.single().code)
        } finally {
            TestEntities.remove(*(xIds + yIds + zIds).toTypedArray())
            TestBlueprints.remove(xBp, yBp, zBp)
        }
    }

    // -- The service-level budget seams (`TestEntities.queryService`): the injected clock makes
    // the deadline and cancellation paths deterministic (every checkpoint reads it), and a
    // one-permit instance pins the 429 saturation rule without timing.

    @Test
    fun `a deadline miss is DEADLINE_EXCEEDED via the injected clock`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-deadline", UserRole.ADMIN)
        val bp = unique("bp-eq-deadline")
        val ids = (1..3).map { unique("ent-eq-deadline$it") }
        try {
            client.createBlueprint(simpleBlueprint(bp))
            ids.forEach { client.postJson("/api/v1/entities", entityRequest(bp, it)) }
            // The first read sets the deadline; every later read is ten seconds past it, so the
            // FIRST checkpoint trips — whatever the candidate count.
            var reads = 0
            val service = TestEntities.queryService(queryClock = { if (reads++ == 0) 0L else 10_000_000_000L })
            val failure = assertFailsWith<EntityQueryInvalidException> {
                service.graph(EntityGraphFilter(listOf(bp), q = null, query = "MATCH (a:`$bp`) RETURN a"))
            }
            assertEquals(QueryDiagnosticCodes.DEADLINE_EXCEEDED, failure.diagnostics.single().code)
        } finally {
            TestEntities.remove(*ids.toTypedArray())
            TestBlueprints.remove(bp)
        }
    }

    @Test
    fun `caller cancellation propagates out of the evaluation unchanged`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-cancel", UserRole.ADMIN)
        val bp = unique("bp-eq-cancel")
        val ids = (1..3).map { unique("ent-eq-cancel$it") }
        try {
            client.createBlueprint(simpleBlueprint(bp))
            ids.forEach { client.postJson("/api/v1/entities", entityRequest(bp, it)) }
            coroutineScope {
                var evaluation: Deferred<EntityGraph>? = null
                // The clock is first read when the budget is built, right before evaluation: cancel
                // the caller there, so the very next checkpoint observes it.
                val service = TestEntities.queryService(queryClock = { evaluation?.cancel(); 0L })
                val deferred = async(start = CoroutineStart.LAZY) {
                    service.graph(EntityGraphFilter(listOf(bp), q = null, query = "MATCH (a:`$bp`) RETURN a"))
                }
                evaluation = deferred
                deferred.start()
                // assertFailsWith pins the TYPE: a CancellationException, never the 400 mapping.
                assertFailsWith<CancellationException> { deferred.await() }
                assertTrue(deferred.isCancelled)
            }
        } finally {
            TestEntities.remove(*ids.toTypedArray())
            TestBlueprints.remove(bp)
        }
    }

    @Test
    fun `a saturated instance refuses a further query with 429 and recovers once a permit frees`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-eq-sat", UserRole.ADMIN)
        val bp = unique("bp-eq-sat")
        val ids = (1..2).map { unique("ent-eq-sat$it") }
        try {
            client.createBlueprint(simpleBlueprint(bp))
            ids.forEach { client.postJson("/api/v1/entities", entityRequest(bp, it)) }
            val started = CountDownLatch(1)
            val hold = CountDownLatch(1)
            val first = AtomicBoolean(true)
            // ONE permit; the held evaluation parks on its first clock read (after the transaction
            // closed, the permit still taken) until the test releases it.
            val service = TestEntities.queryService(
                queryClock = {
                    if (first.getAndSet(false)) {
                        started.countDown()
                        hold.await()
                    }
                    System.nanoTime()
                },
                queryPermits = 1,
            )
            val filter = EntityGraphFilter(listOf(bp), q = null, query = "MATCH (a:`$bp`) RETURN a")
            coroutineScope {
                val held = launch(Dispatchers.IO) { service.graph(filter) }
                try {
                    withContext(Dispatchers.IO) { assertTrue(started.await(10, TimeUnit.SECONDS), "the held evaluation never started") }
                    assertFailsWith<TooManyRequestsException> { service.graph(filter) }
                } finally {
                    hold.countDown()
                }
                held.join()
            }
            assertEquals(ids.toSet(), service.graph(filter).nodes.map { it.identifier }.toSet())
        } finally {
            TestEntities.remove(*ids.toTypedArray())
            TestBlueprints.remove(bp)
        }
    }
}
