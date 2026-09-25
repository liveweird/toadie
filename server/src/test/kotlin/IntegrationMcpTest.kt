package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.findByIdentity
import ch.nokillswit.integration.IntegrationScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The MCP (Model Context Protocol) endpoint (2.15.0, `POST /integration/mcp`, `integration/Mcp.kt`
 * + `McpTools.kt`/`McpReadTools.kt`/`McpWriteTools.kt`) — drives raw JSON-RPC 2.0 over
 * [jsonClient] (the [OpenApiConformance] plugin only validates `/api/` traffic, so it never sees
 * this path).
 *
 * **Stateless-transport finding (verified by running this class).** `respondIntegrationMcp` builds a
 * brand-new [io.modelcontextprotocol.kotlin.sdk.server.Server] +
 * [io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport] PER HTTP POST with
 * `setSessionIdGenerator(null)`. In that stateless mode the transport's `validateSession` returns
 * early, so a lone `tools/list`/`tools/call` POST needs NO prior `initialize` — every request here
 * is a single JSON-RPC object. A batch mixing `initialize` with another request is refused by the
 * transport (`Only one initialization request is allowed`), which is why the helper never batches.
 */
class IntegrationMcpTest {
    private suspend fun ApplicationTestBuilder.enabledApp() {
        configureApp("integration.enabled" to "true")
        startApplication()
        TestRefTargets.ensure()
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun initializeRpc(id: Int) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "initialize")
        put(
            "params",
            buildJsonObject {
                put("protocolVersion", "2025-06-18")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject { put("name", "test"); put("version", "1") })
            },
        )
    }

    private suspend fun HttpClient.mcp(key: String?, method: String, params: JsonObject? = null, id: Int = 1): HttpResponse {
        val call = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        }
        val body: JsonElement = if (method == "initialize") initializeRpc(id) else call
        return post("/integration/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            key?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body.toString())
        }
    }

    private fun toolCallParams(name: String, arguments: JsonObject = buildJsonObject {}) = buildJsonObject {
        put("name", name)
        put("arguments", arguments)
    }

    /** A top-level JSON-RPC 2.0 BATCH (an array of request objects) — the SDK transport dispatches
     *  its calls concurrently, which is exactly the shape M1's serialization/rate-charge guard covers. */
    private suspend fun HttpClient.mcpBatch(key: String?, calls: List<Pair<String, JsonObject>>): HttpResponse {
        val body = JsonArray(
            calls.mapIndexed { index, (method, params) ->
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", index + 1)
                    put("method", method)
                    put("params", params)
                }
            },
        )
        return post("/integration/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            key?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body.toString())
        }
    }

    private suspend fun HttpResponse.rpc(): JsonObject {
        val text = bodyAsText()
        check(status == HttpStatusCode.OK) { "MCP answered $status: $text" }
        val parsed = Json.parseToJsonElement(text)
        return when (parsed) {
            is JsonArray -> parsed.last().jsonObject
            is JsonObject -> parsed
            else -> error("unexpected MCP response shape: $text")
        }
    }

    private fun simpleBlueprint(id: String) = BlueprintRequest(
        identifier = id,
        title = "T",
        schema = BlueprintSchema(properties = mapOf("note" to PropertyDefinition(type = "string", title = "Note"))),
    )

    private fun referrerBlueprint(id: String, target: String) = BlueprintRequest(
        identifier = id,
        title = "Referrer",
        schema = BlueprintSchema(),
        relations = mapOf("target" to RelationDefinition(title = "Target", target = target, required = false, many = false)),
    )

    private fun entityDocument(blueprint: String, identifier: String, properties: JsonObject = buildJsonObject {}) =
        Json.encodeToJsonElement(
            EntityRequest.serializer(),
            EntityRequest(blueprint = blueprint, identifier = identifier, title = "Title $identifier", properties = properties),
        ).jsonObject

    @Test
    fun `disabled endpoint is absent`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(HttpStatusCode.NotFound, jsonClient().post("/integration/mcp").status)
    }

    @Test
    fun `authentication precedes the JSON-RPC body`() = testApplication {
        enabledApp()
        val plain = jsonClient()
        withAuditCapture { capture ->
            val response = plain.mcp(null, "initialize")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertNotNull(capture.awaitEvent { it.message == "integration.auth_failed" })
        }

        val email = uniqueEmail("mcp-auth")
        TestUsers.seed(email, "pw")
        val jwtClient = authedClient(email, "pw")
        // A login JWT never matches the `toadie_int_...` bearer grammar and is never accepted here.
        assertEquals(HttpStatusCode.Unauthorized, jwtClient.mcp(null, "initialize").status)
        assertEquals(HttpStatusCode.Unauthorized, plain.mcp("not-a-real-key", "initialize").status)
    }

    @Test
    fun `initialize answers server identity and tool capability`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-init"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-init-${UUID.randomUUID()}", owner)
        try {
            val response = jsonClient().mcp(key, "initialize")
            assertEquals(HttpStatusCode.OK, response.status)
            val result = response.rpc()["result"]!!.jsonObject
            assertEquals("toadie", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertNotNull(result["capabilities"]!!.jsonObject["tools"])
        } finally {
            TestIntegrationClients.service.revoke(clientId)
        }
    }

    @Test
    fun `tools list is the same ten tools for every key regardless of scope`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-toolslist"), "pw")
        val (readId, readKey) = TestIntegrationClients.service.create("mcp-read-${UUID.randomUUID()}", owner, IntegrationScope.READ)
        val (writeId, writeKey) = TestIntegrationClients.service.create("mcp-write-${UUID.randomUUID()}", owner, IntegrationScope.WRITE)
        val expected = setOf(
            "check_entities", "get_blueprint", "get_entity", "get_ontology_revision",
            "list_blueprints", "list_entities", "ontology_errors",
            "upsert_entity", "import_entities", "delete_entity",
        )
        try {
            val readTools = jsonClient().mcp(readKey, "tools/list").rpc()["result"]!!
                .jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(expected, readTools, "a read-scope key must still see all ten tools listed")

            val writeTools = jsonClient().mcp(writeKey, "tools/list").rpc()["result"]!!
                .jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(expected, writeTools)
        } finally {
            TestIntegrationClients.service.revoke(readId)
            TestIntegrationClients.service.revoke(writeId)
        }
    }

    @Test
    fun `a JSON-RPC batch runs every tools call and answers one result per call`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-batch"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-batch-${UUID.randomUUID()}", owner)
        try {
            val response = jsonClient().mcpBatch(
                key,
                List(3) { "tools/call" to toolCallParams("get_ontology_revision") },
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val results = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(3, results.size)
            val byId = results.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(setOf("1", "2", "3"), byId.keys)
            byId.values.forEach { entry ->
                val result = entry.jsonObject["result"]!!.jsonObject
                assertEquals(null, result["isError"]?.jsonPrimitive?.booleanOrNull)
                assertNotNull(result["structuredContent"]!!.jsonObject["revision"])
            }
        } finally {
            TestIntegrationClients.service.revoke(clientId)
        }
    }

    @Test
    fun `a batch call beyond the exhausted per-client bucket answers RATE_LIMITED`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-batchrate"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-batchrate-${UUID.randomUUID()}", owner)
        try {
            val client = jsonClient()
            // Warm up the shared per-client bucket (120/min) to exactly one token short of the
            // request this test itself is about to make, so that request's own top-level admission
            // consumes the LAST token — leaving the batch's second tool call with none.
            repeat(119) {
                val warmup = client.get("/integration/graphql/schema") { header(HttpHeaders.Authorization, "Bearer $key") }
                assertEquals(HttpStatusCode.OK, warmup.status)
            }
            val response = client.mcpBatch(
                key,
                listOf(
                    "tools/call" to toolCallParams("get_ontology_revision"),
                    "tools/call" to toolCallParams("get_ontology_revision"),
                ),
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val results = Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject["result"]!!.jsonObject }
            val outcomes = results.map { it["isError"]?.jsonPrimitive?.booleanOrNull == true }
            assertEquals(listOf(false, true).sorted(), outcomes.sorted(), "exactly one call must succeed and one must be rate-limited")
            val rateLimited = results.first { it["isError"]?.jsonPrimitive?.booleanOrNull == true }
            assertEquals("RATE_LIMITED", rateLimited["structuredContent"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        } finally {
            TestIntegrationClients.service.revoke(clientId)
        }
    }

    @Test
    fun `list_blueprints summary vs full shapes`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-lb"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-lb-${UUID.randomUUID()}", owner)
        val bpId = unique("bp-mcp-lb")
        try {
            TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            val summary = jsonClient().mcp(key, "tools/call", toolCallParams("list_blueprints")).rpc()
            val summaryContent = summary["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            val summaryRow = summaryContent["items"]!!.jsonArray
                .first { it.jsonObject["identifier"]!!.jsonPrimitive.content == bpId }.jsonObject
            assertNotNull(summaryRow["identifier"])
            assertNotNull(summaryRow["properties"])
            assertNotNull(summaryRow["relations"])
            assertEquals(null, summaryRow["schema"])

            val full = jsonClient().mcp(key, "tools/call", toolCallParams("list_blueprints", buildJsonObject { put("full", true) })).rpc()
            val fullContent = full["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            val fullRow = fullContent["items"]!!.jsonArray.first { it.jsonObject["identifier"]!!.jsonPrimitive.content == bpId }.jsonObject
            assertNotNull(fullRow["schema"])
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `get_blueprint hits and misses`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-gb"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-gb-${UUID.randomUUID()}", owner)
        val bpId = unique("bp-mcp-gb")
        try {
            TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            val hitParams = toolCallParams("get_blueprint", buildJsonObject { put("identifier", bpId) })
            val hit = jsonClient().mcp(key, "tools/call", hitParams).rpc()
            val hitResult = hit["result"]!!.jsonObject
            assertNotEquals(true, hitResult["isError"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals(bpId, hitResult["structuredContent"]!!.jsonObject["identifier"]!!.jsonPrimitive.content)

            val missParams = toolCallParams("get_blueprint", buildJsonObject { put("identifier", unique("bp-missing")) })
            val miss = jsonClient().mcp(key, "tools/call", missParams).rpc()
            val missResult = miss["result"]!!.jsonObject
            assertEquals(true, missResult["isError"]!!.jsonPrimitive.boolean)
            assertEquals("NOT_FOUND", missResult["structuredContent"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `list_entities filters by blueprint and gates properties behind includeProperties`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-le"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-le-${UUID.randomUUID()}", owner)
        val bpId = unique("bp-mcp-le")
        val entId = unique("ent-mcp-le")
        try {
            TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            TestEntities.service.create(
                EntityRequest(blueprint = bpId, identifier = entId, title = "T", properties = buildJsonObject { put("note", "hi") }),
                owner,
            )
            val bareParams = toolCallParams("list_entities", buildJsonObject { put("blueprint", bpId) })
            val bare = jsonClient().mcp(key, "tools/call", bareParams).rpc()
            val bareItems = bare["result"]!!.jsonObject["structuredContent"]!!.jsonObject["items"]!!.jsonArray
            val bareRow = bareItems.single().jsonObject
            assertEquals(entId, bareRow["identifier"]!!.jsonPrimitive.content)
            assertEquals(null, bareRow["properties"])

            val withProps = jsonClient().mcp(
                key,
                "tools/call",
                toolCallParams("list_entities", buildJsonObject { put("blueprint", bpId); put("includeProperties", true) }),
            ).rpc()
            val propsRow = withProps["result"]!!.jsonObject["structuredContent"]!!.jsonObject["items"]!!.jsonArray.single().jsonObject
            assertEquals("hi", propsRow["properties"]!!.jsonObject["note"]!!.jsonPrimitive.content)
            assertNotNull(withProps["result"]!!.jsonObject["structuredContent"]!!.jsonObject["revision"])
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `get_entity matches the REST detail response`() = testApplication {
        enabledApp()
        val ownerEmail = uniqueEmail("mcp-ge")
        val owner = TestUsers.seed(ownerEmail, "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-ge-${UUID.randomUUID()}", owner)
        val bpId = unique("bp-mcp-ge")
        val entId = unique("ent-mcp-ge")
        try {
            TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            val created = TestEntities.service.create(EntityRequest(blueprint = bpId, identifier = entId, title = "T"), owner)
            val rest = authedClient(ownerEmail, "pw").get("/api/v1/entities/${created.id}")
                .body<ch.nokillswit.entities.EntityResponse>()

            val response = jsonClient().mcp(key, "tools/call", toolCallParams("get_entity", buildJsonObject {
                put("blueprint", bpId)
                put("identifier", entId)
            })).rpc()
            val entity = response["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            assertEquals(rest.id.toString(), entity["id"]!!.jsonPrimitive.content)
            assertEquals(rest.identifier, entity["identifier"]!!.jsonPrimitive.content)
            assertEquals(rest.blueprint, entity["blueprint"]!!.jsonPrimitive.content)
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `ontology_errors reports a blueprint edit that leaves an entity stale`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-oe"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-oe-${UUID.randomUUID()}", owner)
        val bpId = unique("bp-mcp-oe")
        val entId = unique("ent-mcp-oe")
        try {
            val blueprint = TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            TestEntities.service.create(EntityRequest(blueprint = bpId, identifier = entId, title = "T"), owner)
            TestBlueprints.service.update(
                blueprint.id,
                BlueprintRequest(
                    identifier = bpId,
                    title = "T",
                    schema = BlueprintSchema(
                        properties = mapOf("note" to PropertyDefinition(type = "string", title = "Note")),
                        required = listOf("note"),
                    ),
                ),
            )

            val errorsParams = toolCallParams("ontology_errors", buildJsonObject { put("blueprint", bpId) })
            val response = jsonClient().mcp(key, "tools/call", errorsParams).rpc()
            val content = response["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            assertTrue(content["entities"]!!.jsonObject["total"]!!.jsonPrimitive.content.toInt() >= 1)
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `check_entities validates without storing anything`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-check"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-check-${UUID.randomUUID()}", owner)
        val bpId = unique("bp-mcp-check")
        try {
            TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            val validId = unique("ent-mcp-check-ok")
            val invalidId = unique("ent-mcp-check-bad")
            val documents = JsonArray(
                listOf(
                    entityDocument(bpId, validId),
                    entityDocument(bpId, invalidId, buildJsonObject { put("bogus", "x") }),
                ),
            )
            val checkParams = toolCallParams("check_entities", buildJsonObject { put("documents", documents) })
            val response = jsonClient().mcp(key, "tools/call", checkParams).rpc()
            val content = response["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            val summary = content["summary"]!!.jsonObject
            assertEquals(1, summary["CREATED"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, summary["INVALID"]!!.jsonPrimitive.content.toInt())

            val listParams = toolCallParams("list_entities", buildJsonObject { put("blueprint", bpId) })
            val total = jsonClient().mcp(key, "tools/call", listParams).rpc()
            assertEquals(
                0,
                total["result"]!!.jsonObject["structuredContent"]!!.jsonObject["total"]!!.jsonPrimitive.content.toInt(),
                "check_entities must store nothing",
            )
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `get_ontology_revision matches the direct counter read`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-rev"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-rev-${UUID.randomUUID()}", owner)
        try {
            val response = jsonClient().mcp(key, "tools/call", toolCallParams("get_ontology_revision")).rpc()
            val revision = response["result"]!!.jsonObject["structuredContent"]!!.jsonObject["revision"]!!.jsonPrimitive.content.toLong()
            assertEquals(TestOntologyRevision.current(), revision)
        } finally {
            TestIntegrationClients.service.revoke(clientId)
        }
    }

    @Test
    fun `upsert_entity creates then updates, attributed to the client's service account`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-upsert"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-upsert-${UUID.randomUUID()}", owner, IntegrationScope.WRITE)
        val bpId = unique("bp-mcp-upsert")
        val entId = unique("ent-mcp-upsert")
        try {
            TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            val client = jsonClient()
            withAuditCapture { capture ->
                val revisionBefore = TestOntologyRevision.current()
                val created = client.mcp(
                    key,
                    "tools/call",
                    toolCallParams("upsert_entity", buildJsonObject { put("document", entityDocument(bpId, entId)) }),
                ).rpc()
                val createdContent = created["result"]!!.jsonObject["structuredContent"]!!.jsonObject
                assertEquals("CREATED", createdContent["status"]!!.jsonPrimitive.content)
                assertEquals(revisionBefore + 1, TestOntologyRevision.current())

                val storedId = createdContent["id"]!!.jsonPrimitive.content.toUInt()
                val serviceUserId = checkNotNull(entityCreatedBy(storedId))
                assertNotEquals(owner, serviceUserId, "an MCP write must never be attributed to the login user")

                val createdEvent = assertNotNull(capture.awaitEvent { it.message == "entity.created" })
                assertTrue(createdEvent.hasKeyValue("byUserId", serviceUserId.toLong()))
                assertTrue(createdEvent.hasKeyValue("import", true))
                assertTrue(createdEvent.hasKeyValue("clientId", clientId.toLong()))

                val updated = client.mcp(
                    key,
                    "tools/call",
                    toolCallParams(
                        "upsert_entity",
                        buildJsonObject { put("document", entityDocument(bpId, entId, buildJsonObject { put("note", "v2") })) },
                    ),
                ).rpc()
                val updatedContent = updated["result"]!!.jsonObject["structuredContent"]!!.jsonObject
                assertEquals("UPDATED", updatedContent["status"]!!.jsonPrimitive.content)
            }
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `upsert_entity stamps an optional source reference`() = testApplication {
        enabledApp()
        val ownerEmail = uniqueEmail("mcp-src")
        val owner = TestUsers.seed(ownerEmail, "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-src-${UUID.randomUUID()}", owner, IntegrationScope.WRITE)
        val bpId = unique("bp-mcp-src")
        val entId = unique("ent-mcp-src")
        try {
            TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            val response = jsonClient().mcp(
                key,
                "tools/call",
                toolCallParams(
                    "upsert_entity",
                    buildJsonObject {
                        put("document", entityDocument(bpId, entId))
                        put("sourceUrl", "https://example.com/x.json")
                    },
                ),
            ).rpc()
            val id = response["result"]!!.jsonObject["structuredContent"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val rest = authedClient(ownerEmail, "pw").get("/api/v1/entities/$id").body<ch.nokillswit.entities.EntityResponse>()
            assertEquals("https://example.com/x.json", rest.sourceUrl)
            assertTrue(rest.lastSyncedAt > 0)
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `upsert_entity rejects a document violating the blueprint`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-invalid"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-invalid-${UUID.randomUUID()}", owner, IntegrationScope.WRITE)
        val bpId = unique("bp-mcp-invalid")
        val entId = unique("ent-mcp-invalid")
        try {
            TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            val response = jsonClient().mcp(
                key,
                "tools/call",
                toolCallParams("upsert_entity", buildJsonObject {
                    put("document", entityDocument(bpId, entId, buildJsonObject { put("nonExistentProperty", "x") }))
                }),
            ).rpc()
            val result = response["result"]!!.jsonObject
            assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
            val content = result["structuredContent"]!!.jsonObject
            assertEquals("INVALID", content["code"]!!.jsonPrimitive.content)
            assertTrue(content["findings"]!!.jsonArray.isNotEmpty())
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `import_entities is report-and-skip`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-import"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-import-${UUID.randomUUID()}", owner, IntegrationScope.WRITE)
        val bpId = unique("bp-mcp-import")
        val okId = unique("ent-mcp-import-ok")
        val badId = unique("ent-mcp-import-bad")
        try {
            TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            val documents = JsonArray(
                listOf(
                    entityDocument(bpId, okId),
                    entityDocument(bpId, badId, buildJsonObject { put("nonExistentProperty", "x") }),
                ),
            )
            val response = jsonClient().mcp(
                key,
                "tools/call",
                toolCallParams("import_entities", buildJsonObject { put("documents", documents) }),
            ).rpc()
            val summary = response["result"]!!.jsonObject["structuredContent"]!!.jsonObject["summary"]!!.jsonObject
            assertEquals(1, summary["CREATED"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, summary["INVALID"]!!.jsonPrimitive.content.toInt())
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestEntities.remove(okId, badId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `delete_entity is idempotent-in-effect (404 the second time)`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-delete"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-delete-${UUID.randomUUID()}", owner, IntegrationScope.WRITE)
        val bpId = unique("bp-mcp-delete")
        val entId = unique("ent-mcp-delete")
        try {
            TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            TestEntities.service.create(EntityRequest(blueprint = bpId, identifier = entId, title = "T"), owner)
            val client = jsonClient()
            val deleteParams = toolCallParams("delete_entity", buildJsonObject { put("blueprint", bpId); put("identifier", entId) })
            val first = client.mcp(key, "tools/call", deleteParams).rpc()
            val firstResult = first["result"]!!.jsonObject
            assertNotEquals(true, firstResult["isError"]?.jsonPrimitive?.booleanOrNull) // the SDK encodes a null isError explicitly
            assertEquals(true, firstResult["structuredContent"]!!.jsonObject["deleted"]!!.jsonPrimitive.boolean)

            val second = client.mcp(key, "tools/call", deleteParams).rpc()
            val secondResult = second["result"]!!.jsonObject
            assertEquals(true, secondResult["isError"]!!.jsonPrimitive.boolean)
            assertEquals("NOT_FOUND", secondResult["structuredContent"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `delete_entity refuses while another entity still references it`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-referrer"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-referrer-${UUID.randomUUID()}", owner, IntegrationScope.WRITE)
        val targetBpId = unique("bp-mcp-target")
        val referrerBpId = unique("bp-mcp-referrer")
        val targetEntId = unique("ent-mcp-target")
        val referrerEntId = unique("ent-mcp-referrer")
        try {
            TestBlueprints.service.create(simpleBlueprint(targetBpId), owner)
            TestBlueprints.service.create(referrerBlueprint(referrerBpId, targetBpId), owner)
            TestEntities.service.create(EntityRequest(blueprint = targetBpId, identifier = targetEntId, title = "T"), owner)
            TestEntities.service.create(
                EntityRequest(
                    blueprint = referrerBpId,
                    identifier = referrerEntId,
                    title = "R",
                    relations = buildJsonObject { put("target", targetEntId) },
                ),
                owner,
            )

            val response = jsonClient().mcp(
                key,
                "tools/call",
                toolCallParams("delete_entity", buildJsonObject { put("blueprint", targetBpId); put("identifier", targetEntId) }),
            ).rpc()
            val result = response["result"]!!.jsonObject
            assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
            val content = result["structuredContent"]!!.jsonObject
            assertEquals("CONFLICT", content["code"]!!.jsonPrimitive.content)
            assertTrue(content["referrers"]!!.jsonArray.any { it.jsonPrimitive.content == "$referrerBpId/$referrerEntId" })
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestEntities.remove(referrerEntId, targetEntId)
            TestBlueprints.remove(referrerBpId, targetBpId)
        }
    }

    @Test
    fun `a read-scope key cannot call a write tool and nothing is stored`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-readonly"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-readonly-${UUID.randomUUID()}", owner, IntegrationScope.READ)
        val bpId = unique("bp-mcp-readonly")
        val entId = unique("ent-mcp-readonly")
        try {
            TestBlueprints.service.create(simpleBlueprint(bpId), owner)
            withAuditCapture { capture ->
                val response = jsonClient().mcp(
                    key,
                    "tools/call",
                    toolCallParams("upsert_entity", buildJsonObject { put("document", entityDocument(bpId, entId)) }),
                ).rpc()
                val result = response["result"]!!.jsonObject
                assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
                assertEquals("FORBIDDEN", result["structuredContent"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                val denied = assertNotNull(capture.awaitEvent { it.message == "integration.scope_denied" })
                assertTrue(denied.hasKeyValue("clientId", clientId.toLong()))
                assertTrue(denied.hasKeyValue("tool", "upsert_entity"))
            }
            assertEquals(null, TestEntities.service.findByIdentity(bpId, entId), "a read-scope key must never store an entity")
        } finally {
            TestIntegrationClients.service.revoke(clientId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `an oversized body is rejected`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-oversized"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-oversized-${UUID.randomUUID()}", owner)
        try {
            val padding = "x".repeat(5 * 1024 * 1024)
            val response = jsonClient().post("/integration/mcp") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                header(HttpHeaders.Authorization, "Bearer $key")
                setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"padding":"$padding"}}""")
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        } finally {
            TestIntegrationClients.service.revoke(clientId)
        }
    }

    @Test
    fun `an out-of-range pageSize argument is BAD_REQUEST`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-badreq"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-badreq-${UUID.randomUUID()}", owner)
        try {
            val response = jsonClient().mcp(
                key,
                "tools/call",
                toolCallParams("list_blueprints", buildJsonObject { put("pageSize", 0) }),
            ).rpc()
            val result = response["result"]!!.jsonObject
            assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
            assertEquals("BAD_REQUEST", result["structuredContent"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        } finally {
            TestIntegrationClients.service.revoke(clientId)
        }
    }

    @Test
    fun `every tools call audits integration mcp_call with the tool name and outcome`() = testApplication {
        enabledApp()
        val owner = TestUsers.seed(uniqueEmail("mcp-audit"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("mcp-audit-${UUID.randomUUID()}", owner)
        try {
            withAuditCapture { capture ->
                jsonClient().mcp(key, "tools/call", toolCallParams("get_ontology_revision"))
                val event = assertNotNull(capture.awaitEvent { it.message == "integration.mcp_call" })
                assertTrue(event.hasKeyValue("tool", "get_ontology_revision"))
                assertTrue(event.hasKeyValue("ok", true))
                assertTrue(event.hasKeyValue("clientId", clientId.toLong()))
            }
        } finally {
            TestIntegrationClients.service.revoke(clientId)
        }
    }

    private suspend fun entityCreatedBy(id: UInt): UInt? = TestEntities.service.read(id)?.createdBy
}
