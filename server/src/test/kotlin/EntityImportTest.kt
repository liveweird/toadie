package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.EntityImportRequest
import ch.nokillswit.entities.EntityImportResponse
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.entities.import
import ch.nokillswit.entities.importCheck
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.infra.importing.IMPORT_SCHEMA_MESSAGE
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The entity bulk-import route surface (phase 6, v1.28.0): any authenticated user (no admin
 * gate), the batch-size cap, an optional relation cycle landing via pass 2 (both sides readable
 * afterward), a required relation cycle rejected with findings, EXISTS-then-UPDATED with the
 * `replaceExisting` flag, a PUT-shaped blueprint change staying INVALID, dry-run parity, and the
 * import audit shape. Every test mints unique `bp-`/`ent-<uuid8>` identifiers and cleans up via
 * [TestEntities.remove]/[TestBlueprints.remove].
 */
class EntityImportTest {

    private fun identifier(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun doc(request: EntityRequest): JsonObject = blueprintJson.encodeToJsonElement(request).jsonObject

    private suspend fun HttpClient.createBlueprint(request: BlueprintRequest) =
        postJson("/api/v1/blueprints", request).body<ch.nokillswit.blueprints.BlueprintResponse>()

    private suspend fun HttpClient.createEntity(request: EntityRequest) =
        postJson("/api/v1/entities", request).body<EntityResponse>()

    private suspend fun HttpClient.import(request: EntityImportRequest) = postJson("/api/v1/entities/import", request)

    private suspend fun HttpClient.importCheck(request: EntityImportRequest) = postJson("/api/v1/entities/import/check", request)

    private fun sqlLiteral(value: String): String = "'${value.replace("'", "''")}'"

    /** Fails only the pass-2 UPDATE that restores this fixture's `peer` relation. */
    private suspend fun <T> withFailingRestoration(identifier: String, block: suspend () -> T): T {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "fail_entity_import_$suffix"
        val trigger = "fail_entity_import_trigger_$suffix"
        var installed = false
        return try {
            withContext(NonCancellable + Dispatchers.IO) {
                DriverManager.getConnection(
                    PostgresTestSupport.jdbcUrl,
                    PostgresTestSupport.user,
                    PostgresTestSupport.password,
                ).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """
                                CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS ${'$'}body${'$'}
                                BEGIN
                                    IF NEW.identifier = ${sqlLiteral(identifier)}
                                       AND NOT COALESCE((OLD.document::jsonb -> 'relations') ? 'peer', FALSE)
                                       AND COALESCE((NEW.document::jsonb -> 'relations') ? 'peer', FALSE) THEN
                                        RAISE EXCEPTION 'forced entity import restoration failure';
                                    END IF;
                                    RETURN NEW;
                                END
                                ${'$'}body${'$'}
                            """.trimIndent(),
                        )
                        try {
                            statement.execute(
                                "CREATE TRIGGER $trigger BEFORE UPDATE ON entities " +
                                    "FOR EACH ROW EXECUTE FUNCTION $function()",
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
                    DriverManager.getConnection(
                        PostgresTestSupport.jdbcUrl,
                        PostgresTestSupport.user,
                        PostgresTestSupport.password,
                    ).use { connection ->
                        connection.createStatement().use { statement ->
                            try {
                                statement.execute("DROP TRIGGER IF EXISTS $trigger ON entities")
                            } finally {
                                statement.execute("DROP FUNCTION IF EXISTS $function()")
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `anonymous is 401`() = testApplication {
        usePostgresTestcontainer()
        val anon = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, anon.import(EntityImportRequest(documents = emptyList())).status)
        assertEquals(HttpStatusCode.Unauthorized, anon.importCheck(EntityImportRequest(documents = emptyList())).status)
    }

    @Test
    fun `a batch over 200 documents is 400`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient(identifier("ent-import-user"), UserRole.USER)
        val tooMany = List(201) { doc(EntityRequest(blueprint = "whatever", identifier = identifier("ent-over"), title = "T")) }
        assertEquals(HttpStatusCode.BadRequest, user.import(EntityImportRequest(documents = tooMany)).status)
    }

    @Test
    fun `a USER may import entities — an optional relation cycle lands via pass 2`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient(identifier("ent-import-admin"), UserRole.ADMIN)
        val user = seededClient(identifier("ent-import-user"), UserRole.USER)
        val bpId = identifier("bp-cycle")
        val a = identifier("ent-a")
        val b = identifier("ent-b")
        try {
            admin.createBlueprint(
                BlueprintRequest(
                    identifier = bpId, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("peer" to RelationDefinition(title = "Peer", target = bpId, required = false, many = false)),
                ),
            )
            val aDoc = doc(EntityRequest(blueprint = bpId, identifier = a, title = "A", relations = buildJsonObject { put("peer", b) }))
            val bDoc = doc(EntityRequest(blueprint = bpId, identifier = b, title = "B", relations = buildJsonObject { put("peer", a) }))
            val request = EntityImportRequest(documents = listOf(aDoc, bDoc))

            withAuditCapture { capture ->
                val response = user.import(request).body<EntityImportResponse>()
                assertEquals(listOf(OntologyImportStatus.CREATED, OntologyImportStatus.CREATED), response.results.map { it.status })
                val event = capture.awaitEvent { it.message == "entity.created" && it.hasKeyValue("import", true) }
                assertNotNull(event, "expected an entity.created audit event with import flag")
            }

            val aId = TestEntities.rawRows().first { it.identifier.equals(a, ignoreCase = true) }.id
            val bId = TestEntities.rawRows().first { it.identifier.equals(b, ignoreCase = true) }.id
            val aRead: EntityResponse = admin.get("/api/v1/entities/$aId").body()
            val bRead: EntityResponse = admin.get("/api/v1/entities/$bId").body()
            assertEquals(b, aRead.relations["peer"]?.jsonPrimitive?.content, "pass 2 must have restored a's relation")
            assertEquals(a, bRead.relations["peer"]?.jsonPrimitive?.content)
        } finally {
            TestEntities.remove(a, b)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a failed pass-2 update reports ERROR while committed creates and replacement are audited once`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient(identifier("ent-import-admin"), UserRole.ADMIN)
        val bpId = identifier("bp-residual")
        val a = identifier("ent-residual-a")
        val b = identifier("ent-residual-b")
        val replaced = identifier("ent-replaced")
        try {
            admin.createBlueprint(
                BlueprintRequest(
                    identifier = bpId,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("peer" to RelationDefinition(title = "Peer", target = bpId, required = false, many = false)),
                ),
            )
            admin.createEntity(EntityRequest(blueprint = bpId, identifier = replaced, title = "Before"))
            val request = EntityImportRequest(
                replaceExisting = true,
                documents = listOf(
                    doc(EntityRequest(blueprint = bpId, identifier = a, title = "A", relations = buildJsonObject { put("peer", b) })),
                    doc(EntityRequest(blueprint = bpId, identifier = b, title = "B", relations = buildJsonObject { put("peer", a) })),
                    doc(EntityRequest(blueprint = bpId, identifier = replaced, title = "After")),
                ),
            )

            withAuditCapture { capture ->
                val response = withFailingRestoration(a) { admin.import(request).body<EntityImportResponse>() }
                assertEquals(
                    listOf(OntologyImportStatus.ERROR, OntologyImportStatus.CREATED, OntologyImportStatus.UPDATED),
                    response.results.map { it.status },
                )
                val residual = response.results[0]
                assertNotNull(residual.id)
                assertTrue(residual.message!!.contains("Stored without its deferred references"))

                assertNotNull(capture.awaitEvent { it.message == "entity.created" && it.hasKeyValue("identifier", a) })
                assertNotNull(capture.awaitEvent { it.message == "entity.updated" && it.hasKeyValue("identifier", replaced) })
                assertEquals(1, capture.events.count { it.message == "entity.created" && it.hasKeyValue("identifier", a) })
                assertEquals(1, capture.events.count { it.message == "entity.updated" && it.hasKeyValue("identifier", replaced) })

                val residualRead: EntityResponse = admin.get("/api/v1/entities/${residual.id}").body()
                assertTrue(residualRead.relations.isEmpty(), "pass 1 remains committed without the deferred relation")
                val replacedRow = TestEntities.rawRows().first { it.identifier == replaced }
                val replacedRead: EntityResponse = admin.get("/api/v1/entities/${replacedRow.id}").body()
                assertEquals("After", replacedRead.title)
            }
        } finally {
            TestEntities.remove(a, b, replaced)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a required relation cycle is INVALID with findings`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient(identifier("ent-import-admin"), UserRole.ADMIN)
        val bpId = identifier("bp-required-cycle")
        try {
            admin.createBlueprint(
                BlueprintRequest(
                    identifier = bpId, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("peer" to RelationDefinition(title = "Peer", target = bpId, required = true, many = false)),
                ),
            )
            val a = identifier("ent-a")
            val b = identifier("ent-b")
            val aDoc = doc(EntityRequest(blueprint = bpId, identifier = a, title = "A", relations = buildJsonObject { put("peer", b) }))
            val bDoc = doc(EntityRequest(blueprint = bpId, identifier = b, title = "B", relations = buildJsonObject { put("peer", a) }))
            val response = admin.import(EntityImportRequest(documents = listOf(aDoc, bDoc))).body<EntityImportResponse>()
            assertTrue(response.results.any { it.status == OntologyImportStatus.INVALID })
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `an existing entity reports EXISTS then UPDATED, and its identifier is reusable under another blueprint`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient(identifier("ent-import-admin"), UserRole.ADMIN)
        val bpId = identifier("bp-exists")
        val otherBpId = identifier("bp-other")
        val id = identifier("ent-x")
        try {
            admin.createBlueprint(BlueprintRequest(identifier = bpId, title = "T", schema = BlueprintSchema()))
            admin.createBlueprint(BlueprintRequest(identifier = otherBpId, title = "T2", schema = BlueprintSchema()))
            val entity = doc(EntityRequest(blueprint = bpId, identifier = id, title = "Original"))
            val existsResponse = admin.import(EntityImportRequest(documents = listOf(entity))).body<EntityImportResponse>()
            assertEquals(OntologyImportStatus.CREATED, existsResponse.results[0].status)

            val reimportOff = admin.import(EntityImportRequest(documents = listOf(entity))).body<EntityImportResponse>()
            assertEquals(OntologyImportStatus.EXISTS, reimportOff.results[0].status)
            assertNotNull(reimportOff.results[0].id)

            val updated = doc(EntityRequest(blueprint = bpId, identifier = id, title = "Updated"))
            val updateRequest = EntityImportRequest(documents = listOf(updated), replaceExisting = true)
            val reimportOn = admin.import(updateRequest).body<EntityImportResponse>()
            assertEquals(OntologyImportStatus.UPDATED, reimportOn.results[0].status)

            // The import pipeline matches existing rows by (blueprint, identifier), never by a
            // stored row id — so the same identifier under a DIFFERENT blueprint is simply a
            // distinct entity (the standing per-blueprint uniqueness rule), never a "move".
            val sameIdentifierOtherBlueprint = doc(EntityRequest(blueprint = otherBpId, identifier = id, title = "Distinct"))
            val otherRequest = EntityImportRequest(documents = listOf(sameIdentifierOtherBlueprint))
            val otherResponse = admin.import(otherRequest).body<EntityImportResponse>()
            assertEquals(OntologyImportStatus.CREATED, otherResponse.results[0].status)
        } finally {
            TestEntities.remove(id)
            TestBlueprints.remove(bpId, otherBpId)
        }
    }

    @Test
    fun `the dry-run predicts the same statuses as the real import without storing anything`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient(identifier("ent-import-admin"), UserRole.ADMIN)
        val bpId = identifier("bp-dry-run")
        try {
            admin.createBlueprint(BlueprintRequest(identifier = bpId, title = "T", schema = BlueprintSchema()))
            val entity = doc(EntityRequest(blueprint = bpId, identifier = identifier("ent-dry"), title = "T"))
            val before = TestEntities.rawRows().size
            val checkResponse = admin.importCheck(EntityImportRequest(documents = listOf(entity))).body<EntityImportResponse>()
            assertEquals(OntologyImportStatus.CREATED, checkResponse.results[0].status)
            assertEquals(before, TestEntities.rawRows().size, "the dry-run must store nothing")
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `read-only export metadata is rejected per row by import and dry-run`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient(identifier("ent-import-admin"), UserRole.ADMIN)
        val bpId = identifier("bp-metadata")
        val id = identifier("ent-metadata")
        try {
            admin.createBlueprint(BlueprintRequest(identifier = bpId, title = "T", schema = BlueprintSchema()))
            val base = doc(EntityRequest(blueprint = bpId, identifier = id, title = "T"))
            val request = EntityImportRequest(documents = listOf(JsonObject(base + ("createdAt" to JsonPrimitive(123L)))))
            val check = admin.importCheck(request).body<EntityImportResponse>().results.single()
            val real = admin.import(request).body<EntityImportResponse>().results.single()
            assertEquals(OntologyImportStatus.INVALID, check.status)
            assertEquals(OntologyImportStatus.INVALID, real.status)
            assertEquals(IMPORT_SCHEMA_MESSAGE, check.message)
            assertEquals(IMPORT_SCHEMA_MESSAGE, real.message)
            assertTrue(TestEntities.rawRows().none { !it.markedAsDeleted && it.identifier == id })
        } finally {
            TestEntities.remove(id)
            TestBlueprints.remove(bpId)
        }
    }

    // 2.4.0: the workspace document byte budget must reject the SAME row on the real run and its
    // dry-run — service-level via `TestEntities.tunedService` so a tiny budget makes the single
    // document overflow deterministically without a multi-megabyte fixture.
    @Test
    fun `the workspace byte budget makes import and its dry-run agree on the same INVALID row`() = testApplication {
        usePostgresTestcontainer()
        val userId = TestUsers.seed(email = uniqueEmail("ent-import-budget"), password = "pw", role = UserRole.USER)
        val bpId = identifier("bp-import-budget")
        try {
            TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = bpId,
                    title = "T",
                    schema = BlueprintSchema(properties = mapOf("note" to PropertyDefinition(type = "string"))),
                ),
                userId,
            )
            val tuned = TestEntities.tunedService(workspaceDocumentBytes = 100)
            val big = doc(
                EntityRequest(
                    blueprint = bpId,
                    identifier = identifier("ent-import-budget"),
                    title = "T",
                    properties = buildJsonObject { put("note", "x".repeat(200)) },
                ),
            )
            val checkRow = tuned.importCheck(listOf(big), replaceExisting = false).single()
            val realRow = tuned.import(listOf(big), userId, replaceExisting = false) {}.single()
            assertEquals(OntologyImportStatus.INVALID, checkRow.status)
            assertEquals(checkRow.status, realRow.status)
            assertEquals(checkRow.message, realRow.message)
            assertTrue(checkRow.message!!.contains("full"), checkRow.message!!)
        } finally {
            TestBlueprints.remove(bpId)
        }
    }
}
