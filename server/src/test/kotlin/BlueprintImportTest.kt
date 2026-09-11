package ch.nokillswit

import ch.nokillswit.blueprints.AggregationCalculationSpec
import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.BlueprintImportRequest
import ch.nokillswit.blueprints.BlueprintImportResponse
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The blueprint bulk-import route surface (phase 6, v1.28.0): ADMIN-only guard-before-receive,
 * the batch-size cap, mixed statuses end to end (including a forward aggregation landing via
 * pass 2 and a relation cycle), EXISTS-then-UPDATED with the `replaceExisting` flag (including
 * the `_team` system-blueprint extension), dry-run parity, and the import audit shape. Every
 * test mints unique `bp-<uuid8>` identifiers and cleans up via [TestBlueprints.remove].
 */
class BlueprintImportTest {

    private fun identifier(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun simple(id: String) = BlueprintRequest(identifier = id, title = "T", schema = BlueprintSchema())

    private fun doc(request: BlueprintRequest): JsonObject = blueprintJson.encodeToJsonElement(request).jsonObject

    private suspend fun HttpClient.import(request: BlueprintImportRequest) =
        postJson("/api/v1/blueprints/import", request)

    private suspend fun HttpClient.importCheck(request: BlueprintImportRequest) =
        postJson("/api/v1/blueprints/import/check", request)

    @Test
    fun `anonymous is 401 and a regular user is 403`() = testApplication {
        usePostgresTestcontainer()
        val anon = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, anon.import(BlueprintImportRequest(documents = emptyList())).status)
        assertEquals(HttpStatusCode.Unauthorized, anon.importCheck(BlueprintImportRequest(documents = emptyList())).status)

        val user = seededClient(identifier("bp-import-user"), UserRole.USER)
        assertEquals(HttpStatusCode.Forbidden, user.import(BlueprintImportRequest(documents = emptyList())).status)
        assertEquals(HttpStatusCode.Forbidden, user.importCheck(BlueprintImportRequest(documents = emptyList())).status)
    }

    @Test
    fun `a batch over 200 documents is 400 and a non-object element is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient(identifier("bp-import-admin"), UserRole.ADMIN)
        val tooMany = List(201) { doc(simple(identifier("bp-over"))) }
        assertEquals(HttpStatusCode.BadRequest, admin.import(BlueprintImportRequest(documents = tooMany)).status)

        val bodyWithNonObject = buildJsonObject {
            put("documents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("not an object")) })
        }
        val response = admin.post("/api/v1/blueprints/import") {
            contentType(ContentType.Application.Json)
            setBody(bodyWithNonObject)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a mixed batch lands a forward aggregation via pass 2 and a relation cycle, dry-run predicting the same`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient(identifier("bp-import-admin"), UserRole.ADMIN)
        val a = identifier("bp-a")
        val b = identifier("bp-b")
        try {
            val aRequest = BlueprintRequest(
                identifier = a, title = "A", schema = BlueprintSchema(),
                aggregationProperties = mapOf(
                    "count" to AggregationPropertyDefinition(
                        title = "Count", target = b,
                        calculationSpec = AggregationCalculationSpec(calculationBy = "entities", func = "count"),
                    ),
                ),
            )
            val peer = RelationDefinition(title = "Peer", target = a, required = false, many = false)
            val bRequest = BlueprintRequest(identifier = b, title = "B", schema = BlueprintSchema(), relations = mapOf("peer" to peer))
            val request = BlueprintImportRequest(documents = listOf(doc(aRequest), doc(bRequest)))

            val checkResponse = admin.importCheck(request).body<BlueprintImportResponse>()
            assertEquals(listOf(OntologyImportStatus.CREATED, OntologyImportStatus.CREATED), checkResponse.results.map { it.status })
            assertTrue(checkResponse.results.all { it.id == null })

            val importResponse = admin.import(request).body<BlueprintImportResponse>()
            assertEquals(listOf(OntologyImportStatus.CREATED, OntologyImportStatus.CREATED), importResponse.results.map { it.status })
            val aId = importResponse.results[0].id!!

            val aRead: BlueprintResponse = admin.get("/api/v1/blueprints/$aId").body()
            assertEquals(1, aRead.aggregationProperties.size, "pass 2 must have restored the deferred aggregation")
        } finally {
            TestBlueprints.remove(a, b)
        }
    }

    @Test
    fun `an existing blueprint reports EXISTS then UPDATED, extending the system blueprint`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient(identifier("bp-import-admin"), UserRole.ADMIN)
        try {
            val extraProperty = PropertyDefinition(type = "string")
            val parentRelation = RelationDefinition(title = "Parent team", target = SYSTEM_TEAM_BLUEPRINT, required = false, many = false)
            val extended = BlueprintRequest(
                identifier = SYSTEM_TEAM_BLUEPRINT,
                title = "Team",
                schema = BlueprintSchema(properties = mapOf("extra" to extraProperty)),
                relations = mapOf("parent" to parentRelation),
            )
            val request = BlueprintImportRequest(documents = listOf(doc(extended)), replaceExisting = false)
            val existsResponse = admin.import(request).body<BlueprintImportResponse>()
            assertEquals(OntologyImportStatus.EXISTS, existsResponse.results[0].status)
            assertNotNull(existsResponse.results[0].id)

            val updateRequest = request.copy(replaceExisting = true)
            withAuditCapture { capture ->
                val updateResponse = admin.import(updateRequest).body<BlueprintImportResponse>()
                assertEquals(OntologyImportStatus.UPDATED, updateResponse.results[0].status)
                val event = capture.awaitEvent {
                    it.message == "blueprint.updated" && it.hasKeyValue("import", true) && it.hasKeyValue("system", true)
                }
                assertNotNull(event, "expected a blueprint.updated audit event with import/system flags")
            }
        } finally {
            TestBlueprints.restoreSystemBlueprints()
        }
    }

    @Test
    fun `a rejection is INVALID and never audited`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient(identifier("bp-import-admin"), UserRole.ADMIN)
        val unresolvable = RelationDefinition(title = "R", target = "nowhere-at-all", required = false, many = false)
        val bad = doc(
            BlueprintRequest(
                identifier = identifier("bp-bad"), title = "Bad", schema = BlueprintSchema(),
                relations = mapOf("r" to unresolvable),
            ),
        )
        val response = admin.import(BlueprintImportRequest(documents = listOf(bad))).body<BlueprintImportResponse>()
        assertEquals(OntologyImportStatus.INVALID, response.results[0].status)
        assertNull(response.results[0].id)
    }
}
