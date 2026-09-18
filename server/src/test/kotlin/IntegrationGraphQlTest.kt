package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.integration.GraphQLHttpRequest
import ch.nokillswit.plugins.ProblemDetail
import graphql.introspection.IntrospectionQuery
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class IntegrationGraphQlTest {
    private suspend fun ApplicationTestBuilder.enabledApp() {
        configureApp("integration.enabled" to "true")
        startApplication()
        TestRefTargets.ensure()
    }

    private suspend fun HttpClient.graphql(key: String?, query: String): HttpResponse = post("/integration/graphql") {
        contentType(ContentType.Application.Json)
        key?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        setBody(GraphQLHttpRequest(query))
    }

    @Test
    fun `disabled endpoint is absent and authentication precedes body validation`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(HttpStatusCode.NotFound, jsonClient().post("/integration/graphql").status)
    }

    @Test
    fun `API key auth is exact and happens before malformed body decoding`() = testApplication {
        enabledApp()
        val plain = jsonClient()
        val missing = plain.post("/integration/graphql") {
            contentType(ContentType.Application.Json)
            setBody("{")
        }
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("Missing or invalid integration API key", missing.body<ProblemDetail>().detail)

        val email = uniqueEmail("gql-auth")
        val userId = TestUsers.seed(email, "pw")
        val (clientId, key) = TestIntegrationClients.service.create("gql-auth-${UUID.randomUUID()}", userId)
        val jwtClient = authedClient(email, "pw")
        assertEquals(HttpStatusCode.Unauthorized, jwtClient.get("/integration/graphql/schema").status)
        val randomKey = "toadie_int_" + "A".repeat(43)
        assertEquals(HttpStatusCode.Unauthorized, plain.graphql(randomKey, "{ __typename }").status)
        val doubled = plain.post("/integration/graphql") {
            contentType(ContentType.Application.Json)
            headers.append(HttpHeaders.Authorization, "Bearer $key")
            headers.append(HttpHeaders.Authorization, "Bearer $key")
            setBody(GraphQLHttpRequest("{ __typename }"))
        }
        assertEquals(HttpStatusCode.Unauthorized, doubled.status)

        val normalized = plain.post("/integration/graphql") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "bearer\t$key  ")
            setBody(GraphQLHttpRequest("{ __typename }"))
        }
        assertEquals(HttpStatusCode.OK, normalized.status)

        listOf("{", "{}", "{\"query\":7}", "{\"query\":\"{ __typename }\",\"unknown\":true}").forEach { invalid ->
            val response = plain.post("/integration/graphql") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $key")
                setBody(invalid)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val problem = response.body<ProblemDetail>()
            assertEquals("Request body is missing or not valid JSON", problem.detail)
            assertTrue(invalid !in (problem.detail ?: ""))
        }
        val wrongContentType = plain.post("/integration/graphql") {
            contentType(ContentType.Text.Plain)
            header(HttpHeaders.Authorization, "Bearer $key")
            setBody("{\"query\":\"{ __typename }\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, wrongContentType.status)
        assertEquals("Content-Type must be application/json", wrongContentType.body<ProblemDetail>().detail)

        val padding = "x".repeat(256 * 1024)
        val oversizedBody = """{"query":"{ __typename }","variables":{"padding":"$padding"}}"""
        val oversizedWithoutKey = plain.post("/integration/graphql") {
            contentType(ContentType.Application.Json)
            setBody(oversizedBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, oversizedWithoutKey.status)
        val oversizedWithKey = plain.post("/integration/graphql") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $key")
            setBody(oversizedBody)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversizedWithKey.status)

        val deepVariables = "{\"query\":\"{ __typename }\",\"variables\":" +
            "{\"x\":".repeat(65) + "0" + "}".repeat(65) + "}"
        val deepResponse = plain.post("/integration/graphql") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $key")
            setBody(deepVariables)
        }
        assertEquals(HttpStatusCode.BadRequest, deepResponse.status)

        val schema = plain.get("/integration/graphql/schema") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }
        assertEquals(HttpStatusCode.OK, schema.status)
        assertTrue(schema.body<String>().contains("type Query"))

        assertEquals(ch.nokillswit.integration.RevokeOutcome.REVOKED, TestIntegrationClients.service.revoke(clientId))
        assertEquals(HttpStatusCode.Unauthorized, plain.graphql(key, "{ __typename }").status)
    }

    @Test
    fun `blueprints entities null roots introspection and exact JSON numbers round trip`() = testApplication {
        enabledApp()
        val creator = TestUsers.seed(uniqueEmail("gql-data"), "pw")
        val (clientId, key) = TestIntegrationClients.service.create("gql-data-${UUID.randomUUID()}", creator)
        val marker = UUID.randomUUID().toString().substring(0, 8)
        val blueprintIdentifier = "gql-bp-$marker"
        val entityIdentifier = "gql-ent-$marker"
        try {
            val blueprint = TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = blueprintIdentifier,
                    title = "GraphQL blueprint",
                    schema = BlueprintSchema(
                        properties = mapOf("precise" to PropertyDefinition(type = "number", title = "Precise")),
                    ),
                ),
                creator,
            )
            val precise = "12345678901234567890.12345678901234567890"
            val entity = TestEntities.service.create(
                EntityRequest(
                    blueprint = blueprintIdentifier,
                    identifier = entityIdentifier,
                    title = "GraphQL entity",
                    properties = buildJsonObject { put("precise", Json.parseToJsonElement(precise)) },
                ),
                creator,
            )
            val storedPrecise = entity.properties["precise"]!!.jsonPrimitive.content

            val introspection = jsonClient().graphql(key, IntrospectionQuery.INTROSPECTION_QUERY).body<JsonObject>()
            assertNull(introspection["errors"], introspection["errors"].toString())

            val response = jsonClient().graphql(
                key,
                """query ReadPort {
                  __schema { queryType { name } }
                  first: entity(id: "${entity.id}") { id blueprint identifier properties createdBy createdAt }
                  again: entity(id: "${entity.id}") { title }
                  missing: entity(id: "4294967295") { id }
                  blueprint(id: "${blueprint.id}") { id identifier }
                  missingBlueprint: blueprint(id: "4294967295") { id }
                  blueprints(page: 1, pageSize: 1) { items { id schema hierarchyRelations } page pageSize total }
                  entities(page: 1, pageSize: 20, blueprint: "$blueprintIdentifier", q: "$entityIdentifier") {
                    items { id findings { code } } total
                  }
                  errors(blueprints: ["$blueprintIdentifier"]) {
                    checkedEntities checkedBlueprints
                    entities(page: 1, pageSize: 1) { items { id } total }
                    blueprints(page: 1, pageSize: 1) { items { id } total }
                  }
                }""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val root = response.body<JsonObject>()
            assertNull(root["errors"], root["errors"].toString())
            val data = root["data"]!!.jsonObject
            assertEquals("Query", data["__schema"]!!.jsonObject["queryType"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(entity.id.toString(), data["first"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals(
                storedPrecise,
                data["first"]!!.jsonObject["properties"]!!.jsonObject["precise"]!!.jsonPrimitive.content,
            )
            assertEquals("GraphQL entity", data["again"]!!.jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(kotlinx.serialization.json.JsonNull, data["missing"])
            assertEquals(blueprint.id.toString(), data["blueprint"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals(kotlinx.serialization.json.JsonNull, data["missingBlueprint"])
            assertEquals("1", data["entities"]!!.jsonObject["total"]!!.jsonPrimitive.content)
            assertTrue(data["errors"]!!.jsonObject["entities"]!!.jsonObject["items"]!!.jsonArray.isEmpty())
            assertNotNull(blueprint)
        } finally {
            TestEntities.remove(entityIdentifier)
            TestBlueprints.remove(blueprintIdentifier)
            TestIntegrationClients.service.revoke(clientId)
        }
    }

    @Test
    fun `source depth and weighted complexity limits return bounded transport or GraphQL errors`() = testApplication {
        enabledApp()
        val creator = TestUsers.seed(uniqueEmail("gql-limits"), "pw")
        val (_, key) = TestIntegrationClients.service.create("gql-limits-${UUID.randomUUID()}", creator)
        val plain = jsonClient()

        val oversized = plain.graphql(key, "#".repeat(64 * 1024 + 1))
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        assertEquals("GraphQL query exceeds the size limit", oversized.body<ProblemDetail>().detail)

        val costly = (1..5).joinToString(prefix = "{", postfix = "}") { index ->
            "e$index: errors { checkedEntities }"
        }
        val costlyBody = plain.graphql(key, costly).body<JsonObject>()
        assertTrue(costlyBody["errors"]?.jsonArray?.isNotEmpty() == true)

        val nestedType = (1..14).fold("name") { nested, _ -> "ofType { $nested }" }
        val tooDeep = "{ __type(name: \"Entity\") { fields { type { $nestedType } } } }"
        val deepBody = plain.graphql(key, tooDeep).body<JsonObject>()
        assertTrue(deepBody["errors"]?.jsonArray?.isNotEmpty() == true)

        for (query in listOf(
            "{ entity(id: \"-1\") { id } }",
            "{ entity(id: \"4294967296\") { id } }",
            "{ entities(page: 0) { total } }",
            "{ entities(pageSize: 101) { total } }",
            "mutation { entity(id: \"1\") { id } }",
        )) {
            val body = plain.graphql(key, query).body<JsonObject>()
            assertTrue(body["errors"]?.jsonArray?.isNotEmpty() == true, query)
        }
        val unknown = plain.graphql(key, "{ entities(blueprint: \"does-not-exist\") { items { id } total } }")
            .body<JsonObject>()["data"]!!.jsonObject["entities"]!!.jsonObject
        assertEquals("0", unknown["total"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a stalled authenticated body times out and releases admission`() = testApplication {
        enabledApp()
        val creator = TestUsers.seed(uniqueEmail("gql-timeout"), "pw")
        val (_, key) = TestIntegrationClients.service.create("gql-timeout-${UUID.randomUUID()}", creator)
        val plain = jsonClient()
        val response = withTimeout(8_000) {
            plain.post("/integration/graphql") {
                header(HttpHeaders.Authorization, "Bearer $key")
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.Json
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeStringUtf8("{\"query\":")
                        channel.flush()
                        delay(10_000)
                    }
                })
            }
        }
        assertEquals(HttpStatusCode.RequestTimeout, response.status)
        assertEquals(HttpStatusCode.OK, plain.graphql(key, "{ __typename }").status)
    }
}
