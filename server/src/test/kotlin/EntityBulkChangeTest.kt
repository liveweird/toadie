package ch.nokillswit

import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.blueprints.ArrayItems
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.EntityBulkChangeRequest
import ch.nokillswit.entities.EntityBulkChangeResponse
import ch.nokillswit.entities.EntityBulkOperation
import ch.nokillswit.entities.EntityBulkStatus
import ch.nokillswit.entities.EntityBulkTarget
import ch.nokillswit.entities.EntityEventType
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.entities.EntityService
import ch.nokillswit.entities.MAX_BULK_FIELD_CHARS
import ch.nokillswit.entities.bulkChange
import ch.nokillswit.entities.bulkChangeCheck
import ch.nokillswit.infra.concurrency.ReadLedger
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The entity bulk change over HTTP and through the real service (`entities/EntityBulk*.kt`, 2.38.0): authentication before
 * validation, any authenticated user, each request-level `400`, dry-run parity row by row, the STALE paths (a stale client stamp
 * and an edit landing under the write lock) leaving the foreign edit intact with no audit line and no event, UNCHANGED rows left
 * unwritten, the read ledger's `400`/`429`, the audit fields (never the value) and the history events (`origin=bulk`, `bulkId`).
 * Every test mints unique `bp-`/`ent-` identifiers and cleans up through [TestEntities.remove] / [TestBlueprints.remove].
 */
class EntityBulkChangeTest {

    private val service: EntityService get() = TestEntities.service

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun list(vararg items: String): JsonArray = buildJsonArray { items.forEach { add(it) } }

    private fun sqlLiteral(value: String): String = "'${value.replace("'", "''")}'"

    private class World(val bp: String, val a: EntityResponse, val b: EntityResponse, val c: EntityResponse) {
        val ids get() = listOf(a, b, c).map { it.identifier }
    }

    /** A blueprint with an enum `tier`, a list `tags`, and one/many relations to itself; three entities: tier gold / silver / gold. */
    private suspend fun HttpClient.world(owner: UInt): World {
        val bp = unique("bp-bulk")
        TestBlueprints.service.create(
            BlueprintRequest(
                identifier = bp, title = "T",
                schema = BlueprintSchema(
                    properties = mapOf(
                        "tier" to PropertyDefinition(type = "string", enum = listOf(JsonPrimitive("gold"), JsonPrimitive("silver"))),
                        "tags" to PropertyDefinition(type = "array", items = ArrayItems(type = "string")),
                    ),
                    required = listOf("tier"),
                ),
                relations = mapOf(
                    "owner" to RelationDefinition(title = "Owner", target = bp, required = false, many = false),
                    "peers" to RelationDefinition(title = "Peers", target = bp, required = false, many = true),
                ),
            ),
            owner,
        )
        suspend fun create(tier: String, tags: JsonArray? = null) = postJson(
            "/api/v1/entities",
            EntityRequest(
                blueprint = bp, identifier = unique("ent-bulk"), title = "Entity",
                properties = buildJsonObject { put("tier", tier); tags?.let { put("tags", it) } },
            ),
        ).body<EntityResponse>()
        return World(bp, create("gold"), create("silver"), create("gold"))
    }

    private suspend fun World.cleanup() {
        TestEntities.remove(*ids.toTypedArray())
        TestBlueprints.remove(bp)
    }

    private fun World.request(
        field: String,
        operation: EntityBulkOperation,
        value: JsonElement? = null,
        targets: List<EntityBulkTarget> = listOf(a, b, c).map { EntityBulkTarget(it.id) },
    ) = EntityBulkChangeRequest(bp, field, operation, value, targets)

    private suspend fun HttpClient.check(request: EntityBulkChangeRequest) = postJson("/api/v1/entities/bulk-change/check", request)

    private suspend fun HttpClient.apply(request: EntityBulkChangeRequest) = postJson("/api/v1/entities/bulk-change", request)

    private suspend fun HttpClient.applied(request: EntityBulkChangeRequest): EntityBulkChangeResponse = apply(request).body()

    private suspend fun HttpClient.checked(request: EntityBulkChangeRequest): EntityBulkChangeResponse = check(request).body()

    private suspend fun HttpClient.entity(id: UInt): EntityResponse = get("/api/v1/entities/$id").body()

    /** Overwrites an entity's stored document straight in the table — a state the strict API can no longer produce. */
    private suspend fun plantDocument(id: UInt, document: String) = withContext(Dispatchers.IO) {
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { c ->
            c.prepareStatement("UPDATE entities SET document = ? WHERE id = ?").use { statement ->
                statement.setString(1, document)
                statement.setLong(2, id.toLong())
                check(statement.executeUpdate() == 1) { "missing entity fixture $id" }
            }
        }
    }

    private suspend fun seedOwner(prefix: String): UInt = TestUsers.seed(uniqueEmail(prefix), "pw", name = prefix, role = UserRole.USER)

    /** Runs [block] with a BEFORE UPDATE row trigger on `entities` running the plpgsql [body] per updated row; always removed again. */
    private suspend fun <T> withUpdateTrigger(body: String, block: suspend () -> T): T {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "bulk_test_fn_$suffix"
        val trigger = "bulk_test_trg_$suffix"
        var installed = false
        fun connection() = DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
        return try {
            withContext(NonCancellable + Dispatchers.IO) {
                connection().use { c ->
                    c.createStatement().use { s ->
                        s.execute(
                            "CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS ${'$'}b${'$'}\n" +
                                "BEGIN\n$body\nRETURN NEW;\nEND\n${'$'}b${'$'}",
                        )
                        try {
                            s.execute("CREATE TRIGGER $trigger BEFORE UPDATE ON entities FOR EACH ROW EXECUTE FUNCTION $function()")
                            installed = true
                        } catch (failure: Exception) {
                            runCatching { s.execute("DROP FUNCTION IF EXISTS $function()") }
                            throw failure
                        }
                    }
                }
            }
            block()
        } finally {
            if (installed) {
                withContext(NonCancellable + Dispatchers.IO) {
                    connection().use { c ->
                        c.createStatement().use { s ->
                            try {
                                s.execute("DROP TRIGGER IF EXISTS $trigger ON entities")
                            } finally {
                                s.execute("DROP FUNCTION IF EXISTS $function()")
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- authentication and authorization ----------------------------------------------------------------------------------

    @Test
    fun `anonymous is 401 on both endpoints, before any body validation`() = testApplication {
        usePostgresTestcontainer()
        val anon = jsonClient()
        val invalid = EntityBulkChangeRequest("whatever", "team", EntityBulkOperation.SET, null, emptyList())
        assertEquals(HttpStatusCode.Unauthorized, anon.check(invalid).status)
        assertEquals(HttpStatusCode.Unauthorized, anon.apply(invalid).status)
    }

    @Test
    fun `a plain USER may bulk-change entities they did not create - no admin gate`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bulk-admin", UserRole.ADMIN)
        val user = seededClient("bulk-user", UserRole.USER)
        val world = admin.world(seedOwner("bulk-owner"))
        try {
            val response = user.apply(world.request("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver")))
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("silver", user.entity(world.a.id).properties["tier"].let { (it as JsonPrimitive).content })
        } finally {
            world.cleanup()
        }
    }

    // ---- request-level 400s ------------------------------------------------------------------------------------------------

    @Test
    fun `every request-level rule is a 400 on both endpoints`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("bulk-400", UserRole.USER)
        val world = user.world(seedOwner("bulk-400-owner"))
        try {
            val one = listOf(EntityBulkTarget(world.a.id))
            fun set(targets: List<EntityBulkTarget>) =
                world.request("properties.tier", EntityBulkOperation.SET, JsonPrimitive("gold"), targets)
            val cases = mapOf(
                "no targets" to set(emptyList()),
                "over 200 targets" to set(List(201) { EntityBulkTarget(world.a.id + it.toUInt()) }),
                "duplicate ids" to set(one + one),
                "negative updatedAt" to set(listOf(EntityBulkTarget(world.a.id, -5L))),
                "unknown blueprint" to set(one).copy(blueprint = unique("bp-none")),
                "undeclared property" to world.request("properties.nope", EntityBulkOperation.SET, JsonPrimitive("x")),
                "undeclared relation" to world.request("relations.nope", EntityBulkOperation.SET, JsonPrimitive("x")),
                "not a changeable field" to world.request("title", EntityBulkOperation.SET, JsonPrimitive("x")),
                "field over the length cap" to
                    world.request("properties.${"x".repeat(MAX_BULK_FIELD_CHARS)}", EntityBulkOperation.SET, JsonPrimitive("x")),
                "field outside the grammar" to world.request("properties.a b", EntityBulkOperation.SET, JsonPrimitive("x")),
                "blueprint over the length cap" to set(one).copy(blueprint = "b".repeat(101)),
                "ADD on a scalar" to world.request("properties.tier", EntityBulkOperation.ADD, list("gold")),
                "REMOVE on a one-relation" to world.request("relations.owner", EntityBulkOperation.REMOVE, list("x")),
                "SET without a value" to world.request("properties.tier", EntityBulkOperation.SET),
                "CLEAR with a value" to world.request("properties.tier", EntityBulkOperation.CLEAR, JsonPrimitive("gold")),
                "ADD with a non-array" to world.request("properties.tags", EntityBulkOperation.ADD, JsonPrimitive("x")),
                "ADD with an empty array" to world.request("properties.tags", EntityBulkOperation.ADD, JsonArray(emptyList())),
                "value over the document cap" to
                    world.request("properties.tier", EntityBulkOperation.SET, JsonPrimitive("x".repeat(300 * 1024))),
            )
            cases.forEach { (name, request) ->
                assertEquals(HttpStatusCode.BadRequest, user.check(request).status, "check: $name")
                assertEquals(HttpStatusCode.BadRequest, user.apply(request).status, "apply: $name")
            }
            // the refusal never echoes the offending field: a bounded fixed message only
            val long = "properties." + "q".repeat(5_000)
            val refused = user.apply(world.request(long, EntityBulkOperation.SET, JsonPrimitive("x")))
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(!refused.bodyAsText().contains("qqqqq"), "an over-long field is never echoed into the 400")
            // a negative id on the wire never wraps into a huge unsigned one
            val negative = """{"blueprint":"${world.bp}","field":"properties.tier","operation":"CLEAR","entities":[{"id":-1}]}"""
            val raw = user.post("/api/v1/entities/bulk-change") {
                contentType(ContentType.Application.Json)
                setBody(negative)
            }
            assertEquals(HttpStatusCode.BadRequest, raw.status)
        } finally {
            world.cleanup()
        }
    }

    // ---- classification parity, writes, audit and history -------------------------------------------------------------------

    @Test
    fun `the dry-run predicts the real run row by row, stores nothing, and the real run writes only the UPDATED rows`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("bulk-parity", UserRole.USER)
        val world = user.world(seedOwner("bulk-parity-owner"))
        val foreign = unique("bp-bulk-foreign")
        try {
            val foreignBlueprint = BlueprintRequest(identifier = foreign, title = "T", schema = BlueprintSchema())
            TestBlueprints.service.create(foreignBlueprint, seedOwner("bulk-foreign"))
            val otherRequest = EntityRequest(blueprint = foreign, identifier = unique("ent-bulk-x"), title = "X")
            val other = user.postJson("/api/v1/entities", otherRequest).body<EntityResponse>()
            val ghost = 9_999_999u
            // an entity already invalid for an unrelated reason: the whole-document findings refuse it even though `tier` is fine
            val broken = user.postJson(
                "/api/v1/entities",
                EntityRequest(
                    blueprint = world.bp, identifier = unique("ent-bulk-broken"), title = "Broken",
                    properties = buildJsonObject { put("tier", "gold") },
                ),
            ).body<EntityResponse>()
            plantDocument(broken.id, """{"properties":{"tier":"gold","tags":5},"relations":{}}""")
            val targets = listOf(world.a, world.b, other, world.c).map { EntityBulkTarget(it.id) } +
                EntityBulkTarget(ghost) + EntityBulkTarget(broken.id)
            val request = world.request("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver"), targets)
            val before = listOf(world.a, world.b, world.c).associate { it.id to it.updatedAt }
            val eventsBefore = listOf(world.a, world.b, world.c).associate { it.id to TestEntityEvents.count(it.id) }

            val predicted = user.check(request).body<EntityBulkChangeResponse>()
            assertNull(predicted.bulkId, "a dry-run mints no run")
            assertEquals(
                listOf(
                    EntityBulkStatus.UPDATED, EntityBulkStatus.UNCHANGED, EntityBulkStatus.INVALID, EntityBulkStatus.UPDATED,
                    EntityBulkStatus.STALE, EntityBulkStatus.INVALID,
                ),
                predicted.results.map { it.status },
            )
            assertTrue(predicted.results.last().findings.orEmpty().isNotEmpty(), "the findings ride the INVALID row")
            assertEquals(JsonPrimitive("gold"), predicted.results[0].before)
            assertEquals(JsonPrimitive("silver"), predicted.results[0].after)
            assertEquals(targets.map { it.id }, predicted.results.map { it.id }, "request order")
            before.forEach { (id, stamp) ->
                assertEquals(stamp, user.entity(id).updatedAt, "the dry-run wrote nothing")
                assertEquals(eventsBefore.getValue(id), TestEntityEvents.count(id), "the dry-run recorded nothing")
            }

            val applied = user.apply(request).body<EntityBulkChangeResponse>()
            assertNotNull(applied.bulkId)
            assertEquals(predicted.results.map { it.status }, applied.results.map { it.status }, "parity, row by row")
            assertEquals(predicted.results.map { it.message }, applied.results.map { it.message })
            assertEquals(predicted.results.map { it.findings }, applied.results.map { it.findings }, "findings: check = apply")
            assertEquals(listOf(world.a.id, world.c.id), applied.results.filter { it.status == EntityBulkStatus.UPDATED }.map { it.id })
            applied.results.filter { it.status == EntityBulkStatus.UPDATED }.forEach {
                assertTrue(assertNotNull(it.updatedAt) > before.getValue(it.id), "the new stamp rides the result")
                assertEquals(it.updatedAt, user.entity(it.id).updatedAt)
            }
            assertEquals("silver", (user.entity(world.a.id).properties["tier"] as JsonPrimitive).content)
            assertEquals(before.getValue(world.b.id), user.entity(world.b.id).updatedAt, "UNCHANGED is not written")
            assertEquals(eventsBefore.getValue(world.b.id), TestEntityEvents.count(world.b.id), "UNCHANGED mints no event")
            TestEntities.remove(other.identifier, broken.identifier)
        } finally {
            world.cleanup()
            TestBlueprints.remove(foreign)
        }
    }

    @Test
    fun `a byte-budget INVALID row is predicted identically by the check and the real run`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("bulk-budget", UserRole.USER)
        val owner = seedOwner("bulk-budget-owner")
        val world = user.world(owner)
        try {
            val targets = listOf(world.a, world.c).map { EntityBulkTarget(it.id) }
            val request = world.request("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver"), targets)
            // gold -> silver grows each document by 2 bytes: with 3 bytes of room the first row fits and the second does not
            val tuned = TestEntities.tunedService(workspaceDocumentBytes = service.importSnapshot().documentBytes + 3)
            val predicted = tuned.bulkChangeCheck(request)
            val applied = tuned.bulkChange(request, owner) { }
            assertEquals(listOf(EntityBulkStatus.UPDATED, EntityBulkStatus.INVALID), predicted.results.map { it.status })
            assertTrue(predicted.results.last().message.orEmpty().contains("workspace is full"))
            assertEquals(predicted.results.map { it.status }, applied.results.map { it.status }, "parity, row by row")
            assertEquals(predicted.results.map { it.message }, applied.results.map { it.message })
            assertEquals(predicted.results.map { it.before to it.after }, applied.results.map { it.before to it.after })
            assertEquals(JsonPrimitive("gold"), user.entity(world.c.id).properties["tier"], "the refused row was not written")
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `each written row mints an UPDATED event origin=bulk with the run's bulkId, and one entity-updated audit line without the value`() =
        testApplication {
            usePostgresTestcontainer()
            val user = seededClient("bulk-audit", UserRole.USER)
            val world = user.world(seedOwner("bulk-audit-owner"))
            try {
                val request = world.request("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver"))
                withAuditCapture { capture ->
                    user.check(request)
                    assertTrue(capture.events.none { it.hasKeyValue("bulk", true) }, "the dry-run audits nothing")

                    val applied = user.apply(request).body<EntityBulkChangeResponse>()
                    val bulkId = assertNotNull(applied.bulkId)
                    val lines = capture.events.filter { it.message == "entity.updated" && it.hasKeyValue("bulkId", bulkId) }
                    val audited = lines.map { line -> line.keyValuePairs.first { it.key == "entityId" }.value }.toSet()
                    assertEquals(setOf(world.a.id.toLong(), world.c.id.toLong()), audited)
                    lines.forEach { line ->
                        assertTrue(line.hasKeyValue("bulk", true))
                        assertTrue(line.hasKeyValue("field", "properties.tier"))
                        assertTrue(line.hasKeyValue("blueprint", world.bp))
                        assertTrue(line.keyValuePairs.any { it.key == "byUserId" })
                        val leaked = line.keyValuePairs.any { it.value == "silver" } || "silver" in line.formattedMessage
                        assertTrue(!leaked, "the value is never audited")
                    }
                    assertEquals(2, lines.size, "the UNCHANGED row audits nothing")

                    val history = TestEntityEvents.of(world.a.id)
                    assertEquals(listOf(EntityEventType.CREATED, EntityEventType.UPDATED), history.map { it.type })
                    val event = history.last()
                    assertEquals("bulk", event.params["origin"])
                    assertEquals(bulkId, event.params["bulkId"])
                    assertEquals("properties.tier", event.params["changed"])
                    assertEquals("gold", event.params["properties.tier.from"])
                    assertEquals("silver", event.params["properties.tier.to"])
                    assertEquals(1, TestEntityEvents.of(world.b.id).size, "the unchanged entity has only its creation")
                }
            } finally {
                world.cleanup()
            }
        }

    // ---- STALE ------------------------------------------------------------------------------------------------------------

    @Test
    fun `an edit between the check and the apply makes that row STALE - the edit is kept, no audit and no event, the other row updates`() =
        testApplication {
            usePostgresTestcontainer()
            val user = seededClient("bulk-stale", UserRole.USER)
            val world = user.world(seedOwner("bulk-stale-owner"))
            try {
                val targets = listOf(world.a, world.c).map { EntityBulkTarget(it.id, it.updatedAt) }
                val request = world.request("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver"), targets)
                assertEquals(listOf(EntityBulkStatus.UPDATED, EntityBulkStatus.UPDATED), user.checked(request).results.map { it.status })

                // someone edits `a` after the preview
                user.putJson(
                    "/api/v1/entities/${world.a.id}",
                    EntityRequest(
                        blueprint = world.bp, identifier = world.a.identifier, title = "Edited meanwhile", properties = world.a.properties,
                    ),
                )
                val eventsAfterEdit = TestEntityEvents.of(world.a.id)

                withAuditCapture { capture ->
                    val applied = user.apply(request).body<EntityBulkChangeResponse>()
                    assertEquals(listOf(EntityBulkStatus.STALE, EntityBulkStatus.UPDATED), applied.results.map { it.status })
                    val kept = user.entity(world.a.id)
                    assertEquals("Edited meanwhile", kept.title, "the foreign edit is kept")
                    assertEquals("gold", (kept.properties["tier"] as JsonPrimitive).content, "and the stale row was not changed")
                    assertEquals(eventsAfterEdit.size, TestEntityEvents.of(world.a.id).size, "no event for the stale row")
                    val staleAudited = capture.events.any {
                        it.message == "entity.updated" && it.hasKeyValue("entityId", world.a.id.toLong())
                    }
                    assertTrue(!staleAudited, "no audit line for the stale row")
                    assertTrue(capture.events.any { it.message == "entity.updated" && it.hasKeyValue("entityId", world.c.id.toLong()) })
                }
            } finally {
                world.cleanup()
            }
        }

    @Test
    fun `an edit landing under the write lock after the classification is STALE, never a silent overwrite`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("bulk-lock", UserRole.USER)
        val world = user.world(seedOwner("bulk-lock-owner"))
        try {
            val targets = listOf(world.a, world.c).map { EntityBulkTarget(it.id) }
            val request = world.request("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver"), targets)
            // writing `a` moves `c`'s write-version, as a concurrent edit of `c` would between the classification and `c`'s own write
            val applied = withUpdateTrigger(
                "IF NEW.identifier = ${sqlLiteral(world.a.identifier)} THEN " +
                    "UPDATE entities SET updated_at = updated_at + 1 WHERE identifier = ${sqlLiteral(world.c.identifier)}; END IF;",
            ) { user.apply(request).body<EntityBulkChangeResponse>() }
            assertEquals(listOf(EntityBulkStatus.UPDATED, EntityBulkStatus.STALE), applied.results.map { it.status })
            assertEquals("gold", (user.entity(world.c.id).properties["tier"] as JsonPrimitive).content)
            assertEquals(1, TestEntityEvents.of(world.c.id).size, "the stale row minted no event")
            // the write-time STALE row carries the CURRENT stored stamp (so a retry with it is not stale again) and its identity
            val stale = applied.results.last()
            assertEquals(user.entity(world.c.id).updatedAt, stale.updatedAt)
            assertTrue(assertNotNull(stale.updatedAt) > world.c.updatedAt, "the stamp moved under the classification")
            assertEquals(world.c.identifier, stale.identifier)
            assertNull(stale.before)
            assertNull(stale.after)
            val retryTarget = listOf(EntityBulkTarget(world.c.id, stale.updatedAt))
            val retry = user.applied(world.request("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver"), retryTarget))
            assertEquals(listOf(EntityBulkStatus.UPDATED), retry.results.map { it.status }, "a retry with result.updatedAt succeeds")
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `an entity that vanishes between the classification and its write is a STALE row without identity or stamp`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("bulk-gone", UserRole.USER)
        val world = user.world(seedOwner("bulk-gone-owner"))
        try {
            val targets = listOf(world.a, world.c).map { EntityBulkTarget(it.id) }
            val request = world.request("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver"), targets)
            // writing `a` soft-deletes `c`, as a concurrent delete of `c` would between the classification and `c`'s own write
            val applied = withUpdateTrigger(
                "IF NEW.identifier = ${sqlLiteral(world.a.identifier)} THEN " +
                    "UPDATE entities SET marked_as_deleted = true WHERE identifier = ${sqlLiteral(world.c.identifier)}; END IF;",
            ) { user.apply(request).body<EntityBulkChangeResponse>() }
            assertEquals(listOf(EntityBulkStatus.UPDATED, EntityBulkStatus.STALE), applied.results.map { it.status })
            val gone = applied.results.last()
            assertEquals(world.c.id, gone.id)
            assertNull(gone.identifier)
            assertNull(gone.title)
            assertNull(gone.updatedAt)
            assertNull(gone.before)
            assertNull(gone.after)
            assertEquals("the entity no longer exists", gone.message)
        } finally {
            world.cleanup()
        }
    }

    // ---- fields and operations end to end ------------------------------------------------------------------------------------

    @Test
    fun `list properties and many relations take ADD and REMOVE, and a REMOVE that empties a list drops the key`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("bulk-lists", UserRole.USER)
        val world = user.world(seedOwner("bulk-lists-owner"))
        try {
            val targets = listOf(world.a, world.b).map { EntityBulkTarget(it.id) }
            val add = user.applied(world.request("properties.tags", EntityBulkOperation.ADD, list("x", "y"), targets))
            assertEquals(listOf(EntityBulkStatus.UPDATED, EntityBulkStatus.UPDATED), add.results.map { it.status })
            assertEquals(list("x", "y"), user.entity(world.a.id).properties["tags"])
            // adding what is there is UNCHANGED
            val again = user.applied(world.request("properties.tags", EntityBulkOperation.ADD, list("y"), targets))
            assertEquals(listOf(EntityBulkStatus.UNCHANGED, EntityBulkStatus.UNCHANGED), again.results.map { it.status })
            // a SET that only reorders a list of scalars diffs to nothing (the history's reorder rule): UNCHANGED, not stored
            val reorder = user.applied(world.request("properties.tags", EntityBulkOperation.SET, list("y", "x"), targets))
            assertEquals(listOf(EntityBulkStatus.UNCHANGED, EntityBulkStatus.UNCHANGED), reorder.results.map { it.status })
            assertEquals(list("x", "y"), user.entity(world.a.id).properties["tags"])
            user.apply(world.request("properties.tags", EntityBulkOperation.REMOVE, list("x"), targets))
            assertEquals(list("y"), user.entity(world.b.id).properties["tags"])
            user.apply(world.request("properties.tags", EntityBulkOperation.REMOVE, list("y"), targets))
            assertTrue("tags" !in user.entity(world.b.id).properties, "an emptied list drops the key")

            val peers = user.applied(world.request("relations.peers", EntityBulkOperation.ADD, list(world.c.identifier), targets))
            assertEquals(listOf(EntityBulkStatus.UPDATED, EntityBulkStatus.UPDATED), peers.results.map { it.status })
            assertEquals(list(world.c.identifier), user.entity(world.a.id).relations["peers"])
            val ghost = user.applied(world.request("relations.owner", EntityBulkOperation.SET, JsonPrimitive("ghost"), targets))
            assertEquals(listOf("RELATION_TARGET_MISSING"), ghost.results.first().findings!!.map { it.code })
            // CLEAR of a required property fails the row's findings, CLEAR of an optional relation drops it
            val clearRequired = user.applied(world.request("properties.tier", EntityBulkOperation.CLEAR, targets = targets))
            assertEquals(listOf("REQUIRED_MISSING"), clearRequired.results.first().findings!!.map { it.code })
            user.apply(world.request("relations.peers", EntityBulkOperation.CLEAR, targets = targets))
            assertTrue("peers" !in user.entity(world.a.id).relations)
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `team takes SET, ADD, REMOVE and CLEAR`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("bulk-team", UserRole.USER)
        val world = user.world(seedOwner("bulk-team-owner"))
        val alpha = unique("team-bulk-a")
        val beta = unique("team-bulk-b")
        try {
            listOf(alpha, beta).forEach {
                user.postJson("/api/v1/entities", EntityRequest(blueprint = "_team", identifier = it, title = it))
            }
            val targets = listOf(world.a).map { EntityBulkTarget(it.id) }
            user.apply(world.request("team", EntityBulkOperation.SET, JsonPrimitive(alpha), targets))
            assertEquals(JsonPrimitive(alpha), user.entity(world.a.id).team)
            user.apply(world.request("team", EntityBulkOperation.ADD, list(beta), targets))
            assertEquals(list(alpha, beta), user.entity(world.a.id).team)
            user.apply(world.request("team", EntityBulkOperation.REMOVE, list(alpha, beta), targets))
            assertNull(user.entity(world.a.id).team)
            user.apply(world.request("team", EntityBulkOperation.SET, JsonPrimitive(alpha), targets))
            user.apply(world.request("team", EntityBulkOperation.CLEAR, targets = targets))
            assertNull(user.entity(world.a.id).team)
            val missing = user.applied(world.request("team", EntityBulkOperation.SET, JsonPrimitive("nobody"), targets))
            assertEquals(listOf("TEAM_TARGET_MISSING"), missing.results.single().findings!!.map { it.code })
        } finally {
            world.cleanup()
            TestEntities.remove(alpha, beta)
        }
    }

    // ---- the read ledger -------------------------------------------------------------------------------------------------------

    @Test
    fun `the targets' documents are admitted through the read ledger - 400 too large, 429 contention, released after`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("bulk-ledger", UserRole.USER)
        val world = user.world(seedOwner("bulk-ledger-owner"))
        try {
            val request = world.request("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver"))

            val tiny = ReadLedger(capacity = 1)
            assertFailsWith<BadRequestException> { TestEntities.tunedService(readLedger = tiny).bulkChangeCheck(request) }
            assertFailsWith<BadRequestException> { TestEntities.tunedService(readLedger = tiny).bulkChange(request, 1u) { } }
            assertEquals(0L, tiny.inFlightBytes)

            val capacity = 8L * 1024 * 1024
            val busy = ReadLedger(capacity = capacity)
            val other = busy.open().apply { reserve(capacity - 1) }
            try {
                assertFailsWith<TooManyRequestsException> { TestEntities.tunedService(readLedger = busy).bulkChangeCheck(request) }
                assertFailsWith<TooManyRequestsException> { TestEntities.tunedService(readLedger = busy).bulkChange(request, 1u) { } }
            } finally {
                other.close()
            }
            assertEquals(0L, busy.inFlightBytes)
            val stamps = listOf(world.a, world.b, world.c).map { user.entity(it.id).updatedAt }
            assertEquals(listOf(world.a.updatedAt, world.b.updatedAt, world.c.updatedAt), stamps, "a refused read wrote nothing")

            val ledger = ReadLedger()
            val ok = TestEntities.tunedService(readLedger = ledger).bulkChangeCheck(request)
            assertEquals(3, ok.results.size)
            assertEquals(0L, ledger.inFlightBytes, "released once the response is built")

            // the real run keeps only the written rows' after-documents while it writes, not the read documents: `b` is
            // already silver (UNCHANGED), so two of three rows are held, below the three-row read reservation
            val held = mutableListOf<Long>()
            val real = TestEntities.tunedService(readLedger = ledger)
            val applied = real.bulkChange(request, seedOwner("bulk-ledger-actor")) { held += ledger.inFlightBytes }
            val expected = listOf(EntityBulkStatus.UPDATED, EntityBulkStatus.UNCHANGED, EntityBulkStatus.UPDATED)
            assertEquals(expected, applied.results.map { it.status })
            assertEquals(2, held.size)
            assertTrue(held.all { it > 0 && it < ledger.peak }, "write-set only: held=$held peak=${ledger.peak}")
            assertEquals(0L, ledger.inFlightBytes, "released once the response is built")
        } finally {
            world.cleanup()
        }
    }
}
