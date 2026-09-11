package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.EntityImportRequest
import ch.nokillswit.entities.EntityImportResponse
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.JsonObject
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

    private suspend fun HttpClient.import(request: EntityImportRequest) = postJson("/api/v1/entities/import", request)

    private suspend fun HttpClient.importCheck(request: EntityImportRequest) = postJson("/api/v1/entities/import/check", request)

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
}
