package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.EntityBulkChangeRequest
import ch.nokillswit.entities.EntityBulkOperation
import ch.nokillswit.entities.EntityBulkStatus
import ch.nokillswit.entities.EntityBulkTarget
import ch.nokillswit.entities.EntityEventType
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityService
import ch.nokillswit.entities.bulkChange
import ch.nokillswit.entities.import
import ch.nokillswit.entities.syncFromSource
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.users.UserRole
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PostgreSQL-backed rollback regressions for entities and their product history
 * (`.claude/docs/persistence.md` "Entity mutations and product history"): the event is inserted in
 * the SAME transaction as the write, so a forced event-insert failure must leave NO trace of the
 * write — the `CatalogFileAtomicHistoryTest` twin. A BEFORE INSERT trigger on `entity_events`
 * fails only the event under test (its predicate names the actor and the target), so neighbours
 * and fixtures in the shared database are never disturbed.
 */
class EntityAtomicHistoryTest {

    private val service: EntityService get() = TestEntities.service

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun sqlLiteral(value: String): String = "'${value.replace("'", "''")}'"

    private suspend fun seedUser(prefix: String): UInt = TestUsers.seed(uniqueEmail(prefix), "pw", name = prefix, role = UserRole.USER)

    private fun request(blueprint: String, identifier: String, title: String = "T") =
        EntityRequest(blueprint = blueprint, identifier = identifier, title = title)

    private fun doc(request: EntityRequest): JsonObject = blueprintJson.encodeToJsonElement(request).jsonObject

    private suspend fun simpleBlueprint(id: String, owner: UInt) {
        TestBlueprints.service.create(BlueprintRequest(identifier = id, title = "T", schema = BlueprintSchema()), owner)
    }

    private fun jdbc() = DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)

    /** `row_to_json` of the entity row: EVERY column, so "byte-equal" means untouched including `updated_at` and the delete flag. */
    private fun rowJson(id: UInt): String = jdbc().use { connection ->
        connection.prepareStatement("SELECT row_to_json(e)::text FROM entities e WHERE e.id = ?").use { statement ->
            statement.setLong(1, id.toLong())
            statement.executeQuery().use { rows -> check(rows.next()) { "missing entity fixture $id" }; rows.getString(1) }
        }
    }

    private fun eventCount(entityId: UInt): Int = jdbc().use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM entity_events WHERE entity_id = ?").use { statement ->
            statement.setLong(1, entityId.toLong())
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun eventCountForActor(actorId: UInt): Int = jdbc().use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM entity_events WHERE user_id = ?").use { statement ->
            statement.setLong(1, actorId.toLong())
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun entityCount(identifier: String): Int = jdbc().use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM entities WHERE identifier = ?").use { statement ->
            statement.setString(1, identifier)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    /**
     * Installs a uniquely named BEFORE INSERT trigger on `entity_events` whose body raises when [predicate] (a plpgsql boolean over
     * `NEW`) holds, runs [block], and always removes the trigger and function again.
     */
    private suspend fun <T> withFailingEventInsert(predicate: String, block: suspend () -> T): T {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "fail_entity_event_$suffix"
        val trigger = "fail_entity_event_trigger_$suffix"
        var installed = false
        return try {
            withContext(NonCancellable + Dispatchers.IO) {
                jdbc().use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """
                                CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS ${'$'}body${'$'}
                                BEGIN
                                    IF $predicate THEN
                                        RAISE EXCEPTION 'forced entity history insert failure';
                                    END IF;
                                    RETURN NEW;
                                END
                                ${'$'}body${'$'}
                            """.trimIndent(),
                        )
                        try {
                            statement.execute(
                                "CREATE TRIGGER $trigger BEFORE INSERT ON entity_events FOR EACH ROW EXECUTE FUNCTION $function()",
                            )
                            installed = true
                        } catch (failure: Exception) {
                            runCatching { statement.execute("DROP FUNCTION IF EXISTS $function()") }
                            throw failure
                        }
                    }
                }
            }
            block()
        } finally {
            if (installed) {
                withContext(NonCancellable + Dispatchers.IO) {
                    jdbc().use { connection ->
                        connection.createStatement().use { statement ->
                            try {
                                statement.execute("DROP TRIGGER IF EXISTS $trigger ON entity_events")
                            } finally {
                                statement.execute("DROP FUNCTION IF EXISTS $function()")
                            }
                        }
                    }
                }
            }
        }
    }

    /** Fails the events [actorId] mints for the entity whose id is [entityId]. */
    private fun forActorOn(actorId: UInt, entityId: UInt) = "NEW.user_id = ${actorId.toLong()} AND NEW.entity_id = ${entityId.toLong()}"

    /** Fails the events [actorId] mints for whichever entity holds [identifier] (visible: same transaction as the entity insert). */
    private fun forActorOnIdentifier(actorId: UInt, identifier: String) =
        "NEW.user_id = ${actorId.toLong()} AND " +
            "EXISTS (SELECT 1 FROM entities WHERE id = NEW.entity_id AND identifier = ${sqlLiteral(identifier)})"

    @Test
    fun `a failed creation event rolls back the entity row`() = testApplication {
        usePostgresTestcontainer()
        val actor = seedUser("eatomic-create")
        val bpId = unique("bp-eatomic-create")
        val entId = unique("ent-eatomic-create")
        try {
            simpleBlueprint(bpId, actor)
            withFailingEventInsert(forActorOnIdentifier(actor, entId)) {
                assertFails { service.create(request(bpId, entId), actor) }
            }
            assertEquals(0, entityCount(entId), "the insert rolled back with its event")
            assertEquals(0, eventCountForActor(actor))
            // With the trigger gone the very same write succeeds: nothing was left half-done.
            assertEquals(entId, service.create(request(bpId, entId), actor).identifier)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `failed update sync and delete events leave the entity row byte-equal`() = testApplication {
        usePostgresTestcontainer()
        val creator = seedUser("eatomic-creator")
        val actor = seedUser("eatomic-actor")
        val bpId = unique("bp-eatomic-write")
        val entId = unique("ent-eatomic-write")
        try {
            simpleBlueprint(bpId, creator)
            val created = service.create(request(bpId, entId).copy(sourceUrl = "https://example.com/$entId/entity.json"), creator)
            val before = rowJson(created.id)
            val eventsBefore = eventCount(created.id)

            withFailingEventInsert(forActorOn(actor, created.id)) {
                assertFails { service.update(created.id, request(bpId, entId, title = "Edited"), actor) }
                assertEquals(before, rowJson(created.id), "a failed UPDATED event rolled the edit back")

                assertFails { service.syncFromSource(created.id, request(bpId, entId, title = "Synced"), actor) }
                assertEquals(before, rowJson(created.id), "a failed SYNCED event rolled the sync (and its stamp) back")

                assertFails { service.delete(created.id, actor) }
                assertEquals(before, rowJson(created.id), "a failed DELETED event left the entity undeleted")
            }
            assertEquals(eventsBefore, eventCount(created.id))
            assertEquals(0, eventCountForActor(actor))
            assertNotNull(service.read(created.id), "the entity is still readable after the failed delete")
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a failed event at the end of a rename rolls back the rename and every cascaded referrer`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedUser("eatomic-cascade-owner")
        val renamer = seedUser("eatomic-renamer")
        val bpId = unique("bp-eatomic-cascade")
        val oldId = unique("ent-eatomic-old")
        val newId = unique("ent-eatomic-new")
        val referrerA = unique("ent-eatomic-ref-a")
        val referrerB = unique("ent-eatomic-ref-b")
        try {
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = bpId, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("peer" to RelationDefinition(title = "Peer", target = bpId, required = false, many = false)),
                ),
                owner,
            )
            val target = service.create(request(bpId, oldId), owner)
            fun referrer(id: String) = request(bpId, id).copy(relations = buildJsonObject { put("peer", oldId) })
            val a = service.create(referrer(referrerA), owner)
            val b = service.create(referrer(referrerB), owner)
            val snapshot = listOf(target.id, a.id, b.id).associateWith { rowJson(it) }
            val counts = listOf(target.id, a.id, b.id).associateWith { eventCount(it) }

            // The renamed entity's OWN event is minted after every referrer's rewrite and cascade event, so failing it proves
            // that all of them (whatever order the cascade visited them in) roll back together with the rename.
            withFailingEventInsert(forActorOn(renamer, target.id)) {
                assertFails { service.update(target.id, request(bpId, newId), renamer) }
            }

            snapshot.forEach { (id, json) -> assertEquals(json, rowJson(id), "entity $id was rolled back") }
            counts.forEach { (id, count) -> assertEquals(count, eventCount(id), "entity $id gained no event") }
            assertEquals(0, eventCountForActor(renamer))
        } finally {
            TestEntities.remove(referrerA, referrerB, oldId, newId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a failed import row is an ERROR while its neighbours keep their rows and events`() = testApplication {
        usePostgresTestcontainer()
        val importer = seedUser("eatomic-import")
        val bpId = unique("bp-eatomic-import")
        val good1 = unique("ent-eatomic-good1")
        val failing = unique("ent-eatomic-failing")
        val good2 = unique("ent-eatomic-good2")
        try {
            simpleBlueprint(bpId, importer)
            val rows = withFailingEventInsert(forActorOnIdentifier(importer, failing)) {
                service.import(listOf(good1, failing, good2).map { doc(request(bpId, it)) }, importer, replaceExisting = false) { }
            }
            assertEquals(
                listOf(OntologyImportStatus.CREATED, OntologyImportStatus.ERROR, OntologyImportStatus.CREATED),
                rows.map { it.status },
            )
            assertEquals(0, entityCount(failing), "the failed row left no entity behind")
            for (row in listOf(rows[0], rows[2])) {
                val events = TestEntityEvents.of(assertNotNull(row.id))
                assertEquals(listOf(EntityEventType.CREATED), events.map { it.type })
                assertEquals("import", events.single().params["origin"])
            }
            assertEquals(2, eventCountForActor(importer))
        } finally {
            TestEntities.remove(good1, failing, good2)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a failed bulk-change event makes that row an ERROR while its neighbours keep their rows and events`() = testApplication {
        usePostgresTestcontainer()
        val actor = seedUser("eatomic-bulk")
        val bpId = unique("bp-eatomic-bulk")
        val good1 = unique("ent-eatomic-bulk1")
        val failing = unique("ent-eatomic-bulkx")
        val good2 = unique("ent-eatomic-bulk2")
        try {
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = bpId, title = "T",
                    schema = BlueprintSchema(properties = mapOf("size" to PropertyDefinition(type = "string"))),
                ),
                actor,
            )
            val created = listOf(good1, failing, good2).map { service.create(request(bpId, it), actor) }
            val failingRow = rowJson(created[1].id)
            val eventsBefore = created.associate { it.id to eventCount(it.id) }
            val bulk = EntityBulkChangeRequest(
                bpId, "properties.size", EntityBulkOperation.SET, JsonPrimitive("XL"), created.map { EntityBulkTarget(it.id) },
            )
            val response = withFailingEventInsert(forActorOn(actor, created[1].id)) { service.bulkChange(bulk, actor) { } }
            assertEquals(
                listOf(EntityBulkStatus.UPDATED, EntityBulkStatus.ERROR, EntityBulkStatus.UPDATED),
                response.results.map { it.status },
            )
            assertEquals(failingRow, rowJson(created[1].id), "the failed row rolled back with its event")
            assertEquals(eventsBefore.getValue(created[1].id), eventCount(created[1].id))
            for (neighbour in listOf(created[0], created[2])) {
                assertEquals("XL", (assertNotNull(service.read(neighbour.id)).properties["size"] as JsonPrimitive).content)
                val events = TestEntityEvents.of(neighbour.id)
                assertEquals(listOf(EntityEventType.CREATED, EntityEventType.UPDATED), events.map { it.type })
                assertEquals("bulk", events.last().params["origin"])
                assertEquals(response.bulkId, events.last().params["bulkId"])
            }
        } finally {
            TestEntities.remove(good1, failing, good2)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a failed pass-two event keeps pass one's row and event and reports the row as ERROR`() = testApplication {
        usePostgresTestcontainer()
        val importer = seedUser("eatomic-pass2")
        val bpId = unique("bp-eatomic-pass2")
        val a = unique("ent-eatomic-a")
        val b = unique("ent-eatomic-b")
        try {
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = bpId, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("peer" to RelationDefinition(title = "Peer", target = bpId, required = false, many = false)),
                ),
                importer,
            )
            val aDoc = doc(request(bpId, a).copy(relations = buildJsonObject { put("peer", b) }))
            val bDoc = doc(request(bpId, b).copy(relations = buildJsonObject { put("peer", a) }))
            val rows = withFailingEventInsert("NEW.user_id = ${importer.toLong()} AND NEW.params LIKE '%import-deferred%'") {
                service.import(listOf(aDoc, bDoc), importer, replaceExisting = false) { }
            }
            val failed = rows.single { it.status == OntologyImportStatus.ERROR }
            assertTrue(failed.message!!.contains("Stored without its deferred references"), failed.message)
            val failedId = assertNotNull(failed.id)
            assertEquals(listOf(EntityEventType.CREATED), TestEntityEvents.of(failedId).map { it.type })
            assertTrue(assertNotNull(service.read(failedId)).relations.isEmpty(), "pass two rolled back, pass one stays committed")
        } finally {
            TestEntities.remove(a, b)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `overlapping updates derive each history diff from the preceding committed row`() = testApplication {
        usePostgresTestcontainer()
        val actor = seedUser("eatomic-overlap")
        val bpId = unique("bp-eatomic-overlap")
        val entId = unique("ent-eatomic-overlap")
        try {
            simpleBlueprint(bpId, actor)
            val created = service.create(request(bpId, entId, title = "Original"), actor)
            // Both writers queue behind a held SHARE lock on `entities`; whichever the V28 lock admits second must diff against
            // the FIRST one's committed row, not against the stale state it was about to overwrite.
            val barrier = EntityWriteBarrier.acquire()
            try {
                coroutineScope {
                    val first = async { service.update(created.id, request(bpId, entId, title = "First"), actor) }
                    val second = async { service.update(created.id, request(bpId, entId, title = "Second"), actor) }
                    try {
                        barrier.awaitWriters(2)
                    } finally {
                        barrier.release()
                    }
                    withTimeout(15_000) {
                        assertEquals(1, first.await().affected)
                        assertEquals(1, second.await().affected)
                    }
                }
            } finally {
                barrier.release()
            }

            val updates = TestEntityEvents.of(created.id).filter { it.type == EntityEventType.UPDATED }
            assertEquals(2, updates.size)
            assertEquals("Original", updates[0].params["title.from"])
            assertEquals(updates[0].params["title.to"], updates[1].params["title.from"])
            assertEquals(assertNotNull(service.read(created.id)).title, updates[1].params["title.to"])
            assertEquals(setOf("First", "Second"), updates.map { it.params["title.to"] }.toSet())
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }
}
