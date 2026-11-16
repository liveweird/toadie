package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.SYSTEM_USER_BLUEPRINT
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.EntityEventPageResponse
import ch.nokillswit.entities.EntityEventResponse
import ch.nokillswit.entities.EntityEventType
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityService
import ch.nokillswit.entities.import
import ch.nokillswit.entities.syncFromSource
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.users.UserService.GuardedMutation
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Entity history (V51) minted through the REAL service: which write kinds record what, what they
 * attribute it to, and what never rides an event. The pure diff rules are `EntityEventsTest`;
 * the rollback atomicity is `EntityAtomicHistoryTest`; the one-writer-per-site invariant is
 * `EntityWriteSitesTest`. Every test mints unique `bp-`/`ent-` identifiers and cleans up through
 * [TestEntities.remove] / [TestBlueprints.remove]; events are read back through [TestEntityEvents].
 */
class EntityHistoryTest {

    private val service: EntityService get() = TestEntities.service

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private suspend fun seedUser(prefix: String): UInt = TestUsers.seed(uniqueEmail(prefix), "pw", name = prefix, role = UserRole.USER)

    private fun doc(request: EntityRequest): JsonObject = blueprintJson.encodeToJsonElement(request).jsonObject

    private fun request(blueprint: String, identifier: String, title: String = "T", sourceUrl: String? = null) =
        EntityRequest(blueprint = blueprint, identifier = identifier, title = title, sourceUrl = sourceUrl)

    private suspend fun simpleBlueprint(id: String, owner: UInt) {
        TestBlueprints.service.create(BlueprintRequest(identifier = id, title = "T", schema = BlueprintSchema()), owner)
    }

    private fun EntityEventResponse.changed(): List<String> = params["changed"]?.split(",").orEmpty()

    private suspend fun setUpdatedAt(entityId: UInt, value: Long) = withContext(Dispatchers.IO) {
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { connection ->
            connection.prepareStatement("UPDATE entities SET updated_at = ? WHERE id = ?").use { statement ->
                statement.setLong(1, value)
                statement.setLong(2, entityId.toLong())
                statement.executeUpdate()
            }
        }
    }

    private fun sourceUrl(marker: String) = "https://example.com/$marker/entity.json"

    /** The stored `params` text of every event of [entityId], straight from the table. */
    private suspend fun rawParams(entityId: UInt): List<String> = withContext(Dispatchers.IO) {
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { connection ->
            connection.prepareStatement("SELECT params FROM entity_events WHERE entity_id = ? ORDER BY id").use { statement ->
                statement.setLong(1, entityId.toLong())
                statement.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
            }
        }
    }

    @Test
    fun `a create mints CREATED for the creator, and a no-op PUT records nothing while updatedAt moves`() = testApplication {
        usePostgresTestcontainer()
        val creator = seedUser("ehist-creator")
        val bpId = unique("bp-ehist-noop")
        val entId = unique("ent-ehist-noop")
        try {
            simpleBlueprint(bpId, creator)
            val created = service.create(request(bpId, entId), creator)
            val createdEvents = TestEntityEvents.of(created.id)
            assertEquals(listOf(EntityEventType.CREATED), createdEvents.map { it.type })
            assertEquals(creator, createdEvents.single().userId)
            assertEquals(mapOf("blueprint" to bpId), createdEvents.single().params)
            assertFalse(createdEvents.single().userServiceAccount)
            assertFalse(createdEvents.single().userDeleted)

            // Pin the stamp to a known past value (no clock dependence): the no-op PUT must still move it.
            setUpdatedAt(created.id, 1L)
            val editor = seedUser("ehist-editor")
            assertEquals(1, service.update(created.id, request(bpId, entId), editor).affected)
            assertEquals(1, TestEntityEvents.of(created.id).size, "a byte-identical PUT records no event")
            assertTrue(assertNotNull(service.read(created.id)).updatedAt > 1L, "...although the write itself still moved updatedAt")

            service.update(created.id, request(bpId, entId, title = "Renamed title"), editor)
            val edit = TestEntityEvents.of(created.id).last()
            assertEquals(EntityEventType.UPDATED, edit.type)
            assertEquals(editor, edit.userId)
            assertEquals(listOf("title"), edit.changed())
            assertEquals("T", edit.params["title.from"])
            assertEquals("Renamed title", edit.params["title.to"])
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a source-reference-only change records an UPDATED event, and clearing it records too`() = testApplication {
        usePostgresTestcontainer()
        val user = seedUser("ehist-source")
        val bpId = unique("bp-ehist-source")
        val entId = unique("ent-ehist-source")
        try {
            simpleBlueprint(bpId, user)
            val created = service.create(request(bpId, entId), user)
            val url = sourceUrl(entId)
            service.update(created.id, request(bpId, entId, sourceUrl = url), user)
            service.update(created.id, request(bpId, entId), user)

            val events = TestEntityEvents.of(created.id)
            assertEquals(listOf(EntityEventType.CREATED, EntityEventType.UPDATED, EntityEventType.UPDATED), events.map { it.type })
            assertEquals(listOf("sourceUrl"), events[1].changed())
            assertEquals(url, events[1].params["sourceUrl.to"])
            assertNull(events[1].params["sourceUrl.from"])
            assertEquals(url, events[2].params["sourceUrl.from"])
            assertNull(events[2].params["sourceUrl.to"])
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `an unchanged sync still records SYNCED, a changed one records the diff`() = testApplication {
        usePostgresTestcontainer()
        val user = seedUser("ehist-sync")
        val syncer = seedUser("ehist-syncer")
        val bpId = unique("bp-ehist-sync")
        val entId = unique("ent-ehist-sync")
        try {
            simpleBlueprint(bpId, user)
            val created = service.create(request(bpId, entId, sourceUrl = sourceUrl(entId)), user)

            assertEquals(1, service.syncFromSource(created.id, request(bpId, entId), syncer).affected)
            assertEquals(1, service.syncFromSource(created.id, request(bpId, entId), syncer).affected)
            assertEquals(1, service.syncFromSource(created.id, request(bpId, entId, title = "From remote"), syncer).affected)

            val events = TestEntityEvents.of(created.id)
            assertEquals(
                listOf(EntityEventType.CREATED, EntityEventType.SYNCED, EntityEventType.SYNCED, EntityEventType.SYNCED),
                events.map { it.type },
            )
            assertEquals(emptyMap(), events[1].params, "a sync that matched still records, with nothing changed")
            assertEquals(emptyMap(), events[2].params)
            assertEquals(syncer, events[2].userId)
            assertEquals(listOf("title"), events[3].changed())
            assertEquals("From remote", events[3].params["title.to"])
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a rename records the identifier change on the entity and one cascade event per rewritten referrer`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedUser("ehist-rename-owner")
        val renamer = seedUser("ehist-renamer")
        val targetBp = unique("bp-ehist-target")
        val referrerBp = unique("bp-ehist-referrer")
        val oldId = unique("ent-ehist-old")
        val newId = unique("ent-ehist-new")
        val referrerA = unique("ent-ehist-ref-a")
        val referrerB = unique("ent-ehist-ref-b")
        val bystander = unique("ent-ehist-bystander")
        try {
            simpleBlueprint(targetBp, owner)
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = referrerBp, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("target" to RelationDefinition(title = "Target", target = targetBp, required = false, many = false)),
                ),
                owner,
            )
            val target = service.create(request(targetBp, oldId), owner)
            fun referrer(id: String, target: String?) = request(referrerBp, id)
                .copy(relations = target?.let { buildJsonObject { put("target", it) } } ?: JsonObject(emptyMap()))
            val a = service.create(referrer(referrerA, oldId), owner)
            val b = service.create(referrer(referrerB, oldId), owner)
            val untouched = service.create(referrer(bystander, null), owner)

            val result = service.update(target.id, request(targetBp, newId), renamer)
            assertEquals(2, result.cascaded.size)

            val own = TestEntityEvents.of(target.id).last()
            assertEquals(EntityEventType.UPDATED, own.type)
            assertEquals(renamer, own.userId)
            assertEquals(listOf("identifier"), own.changed())
            assertEquals(oldId to newId, own.params["identifier.from"] to own.params["identifier.to"])
            assertEquals("2", own.params["cascaded"])
            assertNull(own.params["origin"], "the renamed entity's own event is an ordinary edit")

            for (referrerEntity in listOf(a, b)) {
                val events = TestEntityEvents.of(referrerEntity.id)
                assertEquals(listOf(EntityEventType.CREATED, EntityEventType.UPDATED), events.map { it.type })
                val cascade = events.last()
                assertEquals(renamer, cascade.userId, "the renaming actor, not the referrer's creator")
                assertEquals("cascade", cascade.params["origin"])
                assertEquals(target.id.toString(), cascade.params["causeEntityId"])
                assertEquals(targetBp, cascade.params["causeBlueprint"])
                assertEquals(oldId to newId, cascade.params["causeFrom"] to cascade.params["causeTo"])
                assertEquals(listOf("relations.target"), cascade.changed())
                assertEquals(oldId to newId, cascade.params["relations.target.from"] to cascade.params["relations.target.to"])
            }
            assertEquals(1, TestEntityEvents.of(untouched.id).size, "an entity that held no reference is not touched")
        } finally {
            TestEntities.remove(referrerA, referrerB, bystander, oldId, newId)
            TestBlueprints.remove(referrerBp, targetBp)
        }
    }

    @Test
    fun `renaming a _team or _user entity cascades into team and the format-team and format-user properties`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedUser("ehist-fmt-owner")
        val actor = seedUser("ehist-fmt-actor")
        val bpId = unique("bp-ehist-fmt")
        val oldTeam = unique("team-old")
        val newTeam = unique("team-new")
        val oldUser = uniqueEmail("ehist-reviewer-old")
        val newUser = uniqueEmail("ehist-reviewer-new")
        val holder = unique("ent-ehist-holder")
        try {
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = bpId, title = "T",
                    schema = BlueprintSchema(
                        properties = mapOf(
                            "owningTeam" to PropertyDefinition(type = "string", format = "team"),
                            "reviewer" to PropertyDefinition(type = "string", format = "user"),
                        ),
                    ),
                ),
                owner,
            )
            val team = service.create(EntityRequest(blueprint = SYSTEM_TEAM_BLUEPRINT, identifier = oldTeam, title = oldTeam), owner)
            fun userEntity(email: String) = EntityRequest(
                blueprint = SYSTEM_USER_BLUEPRINT, identifier = email, title = email, properties = buildJsonObject { put("email", email) },
            )
            val user = service.create(userEntity(oldUser), owner)
            val held = service.create(
                EntityRequest(
                    blueprint = bpId, identifier = holder, title = "T", team = JsonPrimitive(oldTeam),
                    properties = buildJsonObject { put("owningTeam", oldTeam); put("reviewer", oldUser) },
                ),
                owner,
            )

            val renamedTeam = EntityRequest(blueprint = SYSTEM_TEAM_BLUEPRINT, identifier = newTeam, title = newTeam)
            assertEquals(1, service.update(team.id, renamedTeam, actor).cascaded.size)
            assertEquals(1, service.update(user.id, userEntity(newUser), actor).cascaded.size)

            val events = TestEntityEvents.of(held.id)
            assertEquals(listOf(EntityEventType.CREATED, EntityEventType.UPDATED, EntityEventType.UPDATED), events.map { it.type })
            val teamCascade = events[1]
            assertEquals("cascade", teamCascade.params["origin"])
            assertEquals(SYSTEM_TEAM_BLUEPRINT, teamCascade.params["causeBlueprint"])
            assertEquals(listOf("team", "properties.owningTeam"), teamCascade.changed())
            assertEquals(oldTeam to newTeam, teamCascade.params["team.from"] to teamCascade.params["team.to"])
            val owningTeam = teamCascade.params["properties.owningTeam.from"] to teamCascade.params["properties.owningTeam.to"]
            assertEquals(oldTeam to newTeam, owningTeam)
            val userCascade = events[2]
            assertEquals(SYSTEM_USER_BLUEPRINT, userCascade.params["causeBlueprint"])
            assertEquals(listOf("properties.reviewer"), userCascade.changed())
            assertEquals(actor, userCascade.userId)
        } finally {
            TestEntities.remove(holder, oldTeam, newTeam, oldUser, newUser)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a sync that renames the entity records SYNCED with the identifier and cascades to its referrers`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedUser("ehist-syncrename-owner")
        val syncer = seedUser("ehist-syncrename-actor")
        val bpId = unique("bp-ehist-syncrename")
        val oldId = unique("ent-ehist-sr-old")
        val newId = unique("ent-ehist-sr-new")
        val referrer = unique("ent-ehist-sr-ref")
        try {
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = bpId, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("peer" to RelationDefinition(title = "Peer", target = bpId, required = false, many = false)),
                ),
                owner,
            )
            val target = service.create(request(bpId, oldId, sourceUrl = sourceUrl(oldId)), owner)
            val held = service.create(request(bpId, referrer).copy(relations = buildJsonObject { put("peer", oldId) }), owner)

            val result = service.syncFromSource(target.id, request(bpId, newId), syncer)
            assertEquals(1, result.cascaded.size)

            val own = TestEntityEvents.of(target.id).last()
            assertEquals(EntityEventType.SYNCED, own.type)
            assertEquals(oldId to newId, own.params["identifier.from"] to own.params["identifier.to"])
            assertEquals("1", own.params["cascaded"])
            val cascade = TestEntityEvents.of(held.id).last()
            assertEquals(EntityEventType.UPDATED, cascade.type)
            assertEquals("cascade", cascade.params["origin"])
            assertEquals(syncer, cascade.userId)
        } finally {
            TestEntities.remove(referrer, oldId, newId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `the import pipeline marks origin=import, records a stamped sync, and a no-change replace mints nothing`() = testApplication {
        usePostgresTestcontainer()
        val importer = seedUser("ehist-importer")
        val bpId = unique("bp-ehist-import")
        val entId = unique("ent-ehist-import")
        try {
            simpleBlueprint(bpId, importer)
            val first = service.import(listOf(doc(request(bpId, entId))), importer, replaceExisting = true) { }.single()
            assertEquals(OntologyImportStatus.CREATED, first.status)
            val id = assertNotNull(first.id)

            val unchanged = service.import(listOf(doc(request(bpId, entId))), importer, replaceExisting = true) { }.single()
            assertEquals(OntologyImportStatus.UPDATED, unchanged.status)
            assertEquals(1, TestEntityEvents.of(id).size, "a replace that changed nothing mints no event")

            service.import(listOf(doc(request(bpId, entId, title = "Imported v2"))), importer, replaceExisting = true) { }
            // A batch sourceUrl stamps a sync: the first stamp moves the reference (a change -> UPDATED), a repeat that
            // changes nothing is SYNCED(origin=import), and a changed document is UPDATED again.
            val url = sourceUrl(entId)
            val v2 = listOf(doc(request(bpId, entId, title = "Imported v2")))
            repeat(2) { service.import(v2, importer, replaceExisting = true, sourceUrl = url) { } }
            service.import(listOf(doc(request(bpId, entId, title = "Imported v3"))), importer, replaceExisting = true, sourceUrl = url) { }

            val events = TestEntityEvents.of(id)
            assertEquals(
                listOf(
                    EntityEventType.CREATED, EntityEventType.UPDATED, EntityEventType.UPDATED,
                    EntityEventType.SYNCED, EntityEventType.UPDATED,
                ),
                events.map { it.type },
            )
            assertTrue(events.all { it.userId == importer && it.params["origin"] == "import" }, events.toString())
            assertEquals(listOf("title"), events[1].changed())
            assertEquals(listOf("sourceUrl"), events[2].changed())
            assertEquals(emptyList(), events[3].changed())
            assertEquals("Imported v3", events[4].params["title.to"])
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `pass two of an import mints a second event with origin=import-deferred`() = testApplication {
        usePostgresTestcontainer()
        val importer = seedUser("ehist-pass2")
        val bpId = unique("bp-ehist-cycle")
        val a = unique("ent-ehist-a")
        val b = unique("ent-ehist-b")
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
            val rows = service.import(listOf(aDoc, bDoc), importer, replaceExisting = false) { }
            assertEquals(listOf(OntologyImportStatus.CREATED, OntologyImportStatus.CREATED), rows.map { it.status })

            val histories = rows.map { TestEntityEvents.of(assertNotNull(it.id)) }
            val deferred = histories.filter { it.size == 2 }
            assertEquals(1, deferred.size, "exactly the entity whose reference pass one withheld gets a second event")
            assertEquals(1, histories.count { it.size == 1 })
            val (created, restored) = deferred.single()
            assertEquals(EntityEventType.CREATED, created.type)
            assertEquals("import", created.params["origin"])
            assertEquals(EntityEventType.UPDATED, restored.type)
            assertEquals("import-deferred", restored.params["origin"])
            assertEquals(listOf("relations.peer"), restored.changed())
            assertEquals(importer, restored.userId)
        } finally {
            TestEntities.remove(a, b)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a bulk write mints an UPDATED event with origin=bulk, the run's bulkId and the field-level diff`() = testApplication {
        usePostgresTestcontainer()
        val actor = seedUser("ehist-bulk")
        val bpId = unique("bp-ehist-bulk")
        val entId = unique("ent-ehist-bulk")
        try {
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = bpId, title = "T",
                    schema = BlueprintSchema(properties = mapOf("size" to PropertyDefinition(type = "string"))),
                ),
                actor,
            )
            val created = service.create(request(bpId, entId), actor)
            val bulkRequest = request(bpId, entId).copy(properties = buildJsonObject { put("size", "L") })
            val result = service.update(
                created.id, bulkRequest, actor, ch.nokillswit.infra.fetch.SourceWrite.Keep, created.updatedAt,
                ch.nokillswit.entities.EntityWriteKind.BULK, "run-42",
            )
            assertEquals(1, result.affected)
            val (_, updated) = TestEntityEvents.of(created.id)
            assertEquals(EntityEventType.UPDATED, updated.type)
            assertEquals("bulk", updated.params["origin"])
            assertEquals("run-42", updated.params["bulkId"])
            assertEquals(listOf("properties.size"), updated.changed())
            assertEquals("L", updated.params["properties.size.to"])
            assertEquals(actor, updated.userId)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `EXISTS, INVALID and STALE import rows mint no event`() = testApplication {
        usePostgresTestcontainer()
        val importer = seedUser("ehist-refused")
        val bpId = unique("bp-ehist-refused")
        val entId = unique("ent-ehist-refused")
        try {
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = bpId, title = "T",
                    schema = BlueprintSchema(
                        properties = mapOf("language" to PropertyDefinition(type = "string")),
                        required = listOf("language"),
                    ),
                ),
                importer,
            )
            val valid = request(bpId, entId).copy(properties = buildJsonObject { put("language", "kotlin") })
            val created = service.import(listOf(doc(valid)), importer, replaceExisting = false) { }.single()
            val id = assertNotNull(created.id)
            assertEquals(1, TestEntityEvents.of(id).size)

            val exists = service.import(listOf(doc(valid.copy(title = "Other"))), importer, replaceExisting = false) { }.single()
            assertEquals(OntologyImportStatus.EXISTS, exists.status)

            val missingLanguage = listOf(doc(request(bpId, entId, title = "Missing language")))
            val invalid = service.import(missingLanguage, importer, replaceExisting = true) { }.single()
            assertEquals(OntologyImportStatus.INVALID, invalid.status)

            val current = assertNotNull(service.read(id)).updatedAt
            val stale = service.import(
                listOf(doc(valid.copy(title = "Stale"))), importer, replaceExisting = true, expected = mapOf(0 to current + 1),
            ) { }.single()
            assertEquals(OntologyImportStatus.STALE, stale.status)

            assertEquals(1, TestEntityEvents.of(id).size, "a refused row leaves the history exactly as it was")
            assertEquals("T", assertNotNull(service.read(id)).title)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `document text never rides an event, only the fact that it changed`() = testApplication {
        usePostgresTestcontainer()
        val user = seedUser("ehist-text")
        val bpId = unique("bp-ehist-text")
        val entId = unique("ent-ehist-text")
        val secretV1 = "CONFIDENTIAL-RUNBOOK-${UUID.randomUUID()}"
        val secretV2 = "REVISED-RUNBOOK-${UUID.randomUUID()}"
        val schemaYaml = "SPEC-YAML-${UUID.randomUUID()}"
        try {
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = bpId, title = "T",
                    schema = BlueprintSchema(
                        properties = mapOf(
                            "runbook" to PropertyDefinition(type = "string", format = "markdown"),
                            "spec" to PropertyDefinition(type = "string", format = "yaml"),
                            "note" to PropertyDefinition(type = "string"),
                        ),
                    ),
                ),
                user,
            )
            val created = service.create(
                request(bpId, entId).copy(properties = buildJsonObject { put("runbook", secretV1); put("note", "visible one") }),
                user,
            )
            service.update(
                created.id,
                request(bpId, entId).copy(
                    properties = buildJsonObject { put("runbook", secretV2); put("spec", schemaYaml); put("note", "visible two") },
                ),
                user,
            )

            val update = TestEntityEvents.of(created.id).last()
            // Document order: the old side's keys (runbook, note), then the one the new side added (spec).
            assertEquals(listOf("properties.runbook", "properties.note", "properties.spec"), update.changed())
            assertEquals("visible one" to "visible two", update.params["properties.note.from"] to update.params["properties.note.to"])
            val stored = rawParams(created.id).joinToString("\n")
            for (text in listOf(secretV1, secretV2, schemaYaml)) {
                assertFalse(stored.contains(text), "document text must never be stored in an event: $text")
            }
            assertNull(update.params["properties.runbook.from"])
            assertNull(update.params["properties.runbook.to"])
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `text of a property removed from the blueprint or carrying a spec never rides an event`() = testApplication {
        usePostgresTestcontainer()
        val user = seedUser("ehist-removed")
        val bpId = unique("bp-ehist-removed")
        val entId = unique("ent-ehist-removed")
        val markdownText = "REMOVED-RUNBOOK-${UUID.randomUUID()}"
        val specV1 = "SPEC-ONE-${UUID.randomUUID()}"
        val specV2 = "SPEC-TWO-${UUID.randomUUID()}"
        try {
            val withRunbook = BlueprintRequest(
                identifier = bpId, title = "T",
                schema = BlueprintSchema(
                    properties = mapOf(
                        "runbook" to PropertyDefinition(type = "string", format = "markdown"),
                        "api" to PropertyDefinition(type = "string", spec = "open-api"),
                    ),
                ),
            )
            val blueprint = TestBlueprints.service.create(withRunbook, user)
            val created = service.create(
                request(bpId, entId).copy(properties = buildJsonObject { put("runbook", markdownText); put("api", specV1) }),
                user,
            )
            // The admin drops `runbook` from the blueprint; the next save of the entity drops its stale value.
            val onlyApi = BlueprintSchema(properties = mapOf("api" to PropertyDefinition(type = "string", spec = "open-api")))
            TestBlueprints.service.update(blueprint.id, withRunbook.copy(schema = onlyApi))
            service.update(created.id, request(bpId, entId).copy(properties = buildJsonObject { put("api", specV2) }), user)

            val update = TestEntityEvents.of(created.id).last()
            assertEquals(listOf("properties.runbook", "properties.api"), update.changed())
            val stored = rawParams(created.id).joinToString("\n")
            for (text in listOf(markdownText, specV1, specV2)) assertFalse(stored.contains(text), "must not be stored: $text")
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a delete mints DELETED for the deleter and the history outlives the soft-deleted entity`() = testApplication {
        usePostgresTestcontainer()
        val creator = seedUser("ehist-del-creator")
        val deleter = seedUser("ehist-deleter")
        val bpId = unique("bp-ehist-delete")
        val entId = unique("ent-ehist-delete")
        try {
            simpleBlueprint(bpId, creator)
            val created = service.create(request(bpId, entId), creator)
            assertEquals(1, service.delete(created.id, deleter).affected)

            val events = TestEntityEvents.of(created.id)
            assertEquals(listOf(EntityEventType.CREATED, EntityEventType.DELETED), events.map { it.type })
            assertEquals(deleter, events.last().userId)
            assertEquals(emptyMap(), events.last().params)
            assertNull(service.read(created.id), "the entity itself is gone from reads")

            // A second delete of the soft-deleted row writes nothing.
            assertEquals(0, service.delete(created.id, deleter).affected)
            assertEquals(2, TestEntityEvents.count(created.id))
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    // ---- GET /api/v1/entities/{id}/events ---------------------------------------------------------------------------------

    @Test
    fun `the events route lists newest first with the acting user resolved`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("ehist-route-reader")
        val reader = TestUsers.seed(email, "pw", name = "Reader", role = UserRole.USER)
        val client = authedClient(email, "pw")
        val editorEmail = uniqueEmail("ehist-route-editor")
        val editor = TestUsers.seed(editorEmail, "pw", name = "Editor Person", role = UserRole.USER)
        val bpId = unique("bp-ehist-route")
        val entId = unique("ent-ehist-route")
        try {
            simpleBlueprint(bpId, reader)
            val created = service.create(request(bpId, entId), reader)
            service.update(created.id, request(bpId, entId, title = "Edited"), editor)

            val page: EntityEventPageResponse = client.get("/api/v1/entities/${created.id}/events").body()
            assertEquals(2, page.total)
            assertEquals(listOf(EntityEventType.UPDATED, EntityEventType.CREATED), page.items.map { it.type })
            val (update, creation) = page.items
            assertEquals(editor, update.userId)
            assertEquals("Editor Person", update.userName)
            assertEquals(created.id, update.entityId)
            assertEquals("Reader", creation.userName)
            assertEquals(listOf("title"), update.changed())
            assertFalse(update.userDeleted)
            assertFalse(update.userServiceAccount)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `the events route flags a soft-deleted actor and a service account`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("ehist-route-flags")
        val reader = TestUsers.seed(email, "pw", name = "Reader", role = UserRole.USER)
        val client = authedClient(email, "pw")
        val leaver = TestUsers.seed(uniqueEmail("ehist-route-leaver"), "pw", name = "Leaver", role = UserRole.USER)
        val serviceAccount = TestUsers.seedServiceAccount("ehist-route-client", uniqueEmail("ehist-route-svc"))
        val bpId = unique("bp-ehist-route-flags")
        val entId = unique("ent-ehist-route-flags")
        try {
            simpleBlueprint(bpId, reader)
            val created = service.create(request(bpId, entId), serviceAccount)
            service.update(created.id, request(bpId, entId, title = "By the leaver"), leaver)
            assertEquals(GuardedMutation.DONE, TestUsers.service.deleteGuarded(leaver))

            val items = client.get("/api/v1/entities/${created.id}/events").body<EntityEventPageResponse>().items
            val byLeaver = items.first { it.userId == leaver }
            assertTrue(byLeaver.userDeleted && !byLeaver.userServiceAccount, byLeaver.toString())
            assertEquals("Leaver", byLeaver.userName, "the recorded name is still shown")
            val bySvc = items.first { it.userId == serviceAccount }
            assertTrue(bySvc.userServiceAccount && !bySvc.userDeleted, bySvc.toString())
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `the events route pages, sorts within the whitelist and rejects any other sort field`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("ehist-route-paging")
        val user = TestUsers.seed(email, "pw", role = UserRole.USER)
        val client = authedClient(email, "pw")
        val bpId = unique("bp-ehist-route-paging")
        val entId = unique("ent-ehist-route-paging")
        try {
            simpleBlueprint(bpId, user)
            val created = service.create(request(bpId, entId), user)
            repeat(3) { service.update(created.id, request(bpId, entId, title = "v$it"), user) }
            val base = "/api/v1/entities/${created.id}/events"

            val second: EntityEventPageResponse = client.get("$base?page=2&pageSize=3").body()
            assertEquals(4, second.total)
            assertEquals(1, second.items.size)
            assertEquals(EntityEventType.CREATED, second.items.single().type, "page 2 of the newest-first order holds the oldest")

            val ascending: EntityEventPageResponse = client.get("$base?sort=timestamp,id").body()
            assertEquals(EntityEventType.CREATED, ascending.items.first().type)
            assertEquals(ascending.items.map { it.id }, ascending.items.map { it.id }.sorted())

            assertEquals(HttpStatusCode.BadRequest, client.get("$base?sort=title").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("$base?sort=userName").status)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `the events route is a plain 404 for an unknown or soft-deleted entity`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("ehist-route-404")
        val user = TestUsers.seed(email, "pw", role = UserRole.USER)
        val client = authedClient(email, "pw")
        val bpId = unique("bp-ehist-route-404")
        val entId = unique("ent-ehist-route-404")
        try {
            simpleBlueprint(bpId, user)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/entities/999999999/events").status)
            val created = service.create(request(bpId, entId), user)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/entities/${created.id}/events").status)
            service.delete(created.id, user)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/entities/${created.id}/events").status)
            assertEquals(2, TestEntityEvents.count(created.id), "...although the history itself is kept")
        } finally {
            TestBlueprints.remove(bpId)
        }
    }
}
