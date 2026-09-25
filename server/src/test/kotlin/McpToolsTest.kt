package ch.nokillswit

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.blueprints.BlueprintServiceKey
import ch.nokillswit.entities.EntityFinding
import ch.nokillswit.entities.EntityInvalidException
import ch.nokillswit.entities.EntityReferencedException
import ch.nokillswit.entities.EntityServiceKey
import ch.nokillswit.integration.IntegrationClientPrincipal
import ch.nokillswit.integration.IntegrationRetainedLedger
import ch.nokillswit.integration.IntegrationScope
import ch.nokillswit.integration.IntegrationServices
import ch.nokillswit.integration.McpToolContext
import ch.nokillswit.integration.argBool
import ch.nokillswit.integration.argInt
import ch.nokillswit.integration.argObject
import ch.nokillswit.integration.argObjects
import ch.nokillswit.integration.argString
import ch.nokillswit.integration.guarded
import ch.nokillswit.integration.mcpInMemoryPage
import ch.nokillswit.integration.mcpPageRequest
import ch.nokillswit.integration.requireObject
import ch.nokillswit.integration.requireString
import ch.nokillswit.integration.stringProp
import ch.nokillswit.integration.toolResult
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MCP tool plumbing (`integration/McpTools.kt`, `McpSchemas.kt`) below the route: the
 * argument decoders' every refusal, the in-memory pager's edges, and `guarded`'s exception →
 * tool-result table. `IntegrationMcpTest` drives the happy paths over the wire; this file pins the
 * branches a well-behaved client never exercises.
 */
class McpToolsTest {
    private fun request(vararg args: Pair<String, Any?>): CallToolRequest = CallToolRequest(
        CallToolRequestParams(
            name = "t",
            arguments = buildJsonObject {
                args.forEach { (k, v) ->
                    when (v) {
                        null -> put(k, JsonPrimitive(null as String?))
                        is String -> put(k, v)
                        is Int -> put(k, v)
                        is Boolean -> put(k, v)
                        is JsonObject -> put(k, v)
                        is JsonArray -> put(k, v)
                        else -> error("unsupported fixture value $v")
                    }
                }
            },
        ),
    )

    private fun noArgs() = CallToolRequest(CallToolRequestParams(name = "t", arguments = null))

    private fun badRequest(block: () -> Any?): String = assertFailsWith<BadRequestException> { block() }.message!!

    @Test
    fun `argString accepts strings and refuses everything else`() {
        assertEquals("x", request("a" to "x").argString("a"))
        assertNull(noArgs().argString("a"))
        assertNull(request("b" to "x").argString("a"))
        assertEquals("a is required", badRequest { request("b" to "x").argString("a", required = true) })
        assertEquals("a must be a string", badRequest { request("a" to 1).argString("a") })
        assertEquals("a must be a string", badRequest { request("a" to buildJsonObject {}).argString("a") })
        assertEquals("x", request("a" to "x").requireString("a"))
        assertEquals("a is required", badRequest { noArgs().requireString("a") })
    }

    @Test
    fun `argInt defaults, parses, and bounds`() {
        assertEquals(7, request().argInt("n", default = 7, min = 1, max = 10))
        assertEquals(3, request("n" to 3).argInt("n", default = 7, min = 1, max = 10))
        assertEquals(3, request("n" to "3").argInt("n", default = 7, min = 1, max = 10))
        assertEquals("n must be an integer", badRequest { request("n" to "three").argInt("n", 7, 1, 10) })
        assertEquals("n must be an integer", badRequest { request("n" to buildJsonObject {}).argInt("n", 7, 1, 10) })
        assertEquals("n must be between 1 and 10", badRequest { request("n" to 0).argInt("n", 7, 1, 10) })
        assertEquals("n must be between 1 and 10", badRequest { request("n" to 11).argInt("n", 7, 1, 10) })
    }

    @Test
    fun `argBool defaults and refuses non-booleans`() {
        assertEquals(false, request().argBool("b"))
        assertEquals(true, request().argBool("b", default = true))
        assertEquals(true, request("b" to true).argBool("b"))
        assertEquals(false, request("b" to "false").argBool("b"))
        assertEquals("b must be a boolean", badRequest { request("b" to "yes").argBool("b") })
        assertEquals("b must be a boolean", badRequest { request("b" to 1).argBool("b") })
        assertEquals("b must be a boolean", badRequest { request("b" to buildJsonObject {}).argBool("b") })
    }

    @Test
    fun `argObject and argObjects refuse wrong shapes and sizes`() {
        val obj = buildJsonObject { put("k", "v") }
        assertEquals(obj, request("o" to obj).argObject("o"))
        assertNull(request().argObject("o"))
        assertEquals("o is required", badRequest { request().argObject("o", required = true) })
        assertEquals("o must be an object", badRequest { request("o" to "s").argObject("o") })
        assertEquals("o must be an object", badRequest { request("o" to JsonArray(emptyList())).argObject("o") })
        assertEquals(obj, request("o" to obj).requireObject("o"))
        assertEquals("o is required", badRequest { noArgs().requireObject("o") })

        val docs = JsonArray(listOf(obj, obj))
        assertEquals(2, request("d" to docs).argObjects("d", 1, 5).size)
        assertEquals("d is required", badRequest { request().argObjects("d", 1, 5) })
        assertEquals("d must be an array", badRequest { request("d" to obj).argObjects("d", 1, 5) })
        assertEquals("d must contain between 1 and 5 items", badRequest { request("d" to JsonArray(emptyList())).argObjects("d", 1, 5) })
        assertEquals("d must contain between 1 and 1 items", badRequest { request("d" to docs).argObjects("d", 1, 1) })
        assertEquals("d items must be objects", badRequest { request("d" to JsonArray(listOf(JsonPrimitive("s")))).argObjects("d", 1, 5) })
    }

    @Test
    fun `page request and in-memory pager honour bounds and edges`() {
        val defaults = request().mcpPageRequest(emptyList())
        assertEquals(1, defaults.page)
        assertEquals(20, defaults.pageSize)
        assertEquals(2, request("page" to 2, "pageSize" to 5).mcpPageRequest(emptyList()).page)
        assertTrue(badRequest { request("pageSize" to 101).mcpPageRequest(emptyList()) }.startsWith("pageSize must be between"))
        assertTrue(badRequest { request("page" to 0).mcpPageRequest(emptyList()) }.startsWith("page must be between"))

        val items = (1..5).toList()
        assertEquals(listOf(1, 2) to 5L, mcpInMemoryPage(items, page = 1, pageSize = 2))
        assertEquals(listOf(5) to 5L, mcpInMemoryPage(items, page = 3, pageSize = 2))
        assertEquals(emptyList<Int>() to 5L, mcpInMemoryPage(items, page = 4, pageSize = 2))
        assertEquals(emptyList<Int>() to 0L, mcpInMemoryPage(emptyList<Int>(), page = 1, pageSize = 2))
    }

    @Test
    fun `stringProp carries an enum only when given one`() {
        assertNull(stringProp("d")["enum"])
        assertEquals(listOf("a", "b"), stringProp("d", listOf("a", "b"))["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    private fun code(result: CallToolResult): String = result.structuredContent!!["code"]!!.jsonPrimitive.content

    @Test
    fun `guarded maps every domain failure to a structured tool error and rethrows cancellation`() = testApplication {
        usePostgresTestcontainer()
        val services = IntegrationServices(application.attributes[BlueprintServiceKey], application.attributes[EntityServiceKey])
        val principal = IntegrationClientPrincipal(clientId = 1u, name = "t", scope = IntegrationScope.WRITE, serviceUserId = null)
        val capture = LogCapture("ch.nokillswit.audit")
        try {
            IntegrationRetainedLedger().open().use { retained ->
                val context = McpToolContext(principal, services, retained)
                val ok = context.guarded("t") { toolResult(buildJsonObject { put("k", "v") }) }
                assertEquals(null, ok.isError)
                assertEquals("v", ok.structuredContent!!["k"]!!.jsonPrimitive.content)

                val invalid = context.guarded("t") { throw EntityInvalidException(listOf(EntityFinding("X", "f", "m"))) }
                assertEquals("INVALID", code(invalid))
                assertEquals("X", invalid.structuredContent!!["findings"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)

                val referenced = context.guarded("t") { throw EntityReferencedException("bp/e", listOf("bp2/r")) }
                assertEquals("CONFLICT", code(referenced))
                assertEquals("bp2/r", referenced.structuredContent!!["referrers"]!!.jsonArray.single().jsonPrimitive.content)

                assertEquals("BAD_REQUEST", code(context.guarded("t") { throw BadRequestException("bad") }))
                assertEquals("NOT_FOUND", code(context.guarded("t") { throw NotFoundException("gone") }))
                assertEquals("BUDGET_EXCEEDED", code(context.guarded("t") { throw TooManyRequestsException("busy") }))
                assertEquals("INTERNAL", code(context.guarded("t") { throw IllegalStateException("boom") }))
                assertEquals("TIMEOUT", code(context.guarded("t") { withTimeout(1) { delay(10_000); toolResult(buildJsonObject {}) } }))
                assertFailsWith<CancellationException> { context.guarded("t") { throw CancellationException("caller went away") } }

                val huge = buildJsonObject { put("blob", "x".repeat(4 * 1024 * 1024 + 1)) }
                assertEquals("BUDGET_EXCEEDED", code(context.guarded("t") { toolResult(huge) }))
            }
            IntegrationRetainedLedger(capacity = 1).open().use { tiny ->
                val context = McpToolContext(principal, services, tiny)
                assertEquals("BUDGET_EXCEEDED", code(context.guarded("t") { toolResult(buildJsonObject { put("k", "v".repeat(64)) }) }))
            }
            val calls = capture.events.filter { it.message == "integration.mcp_call" }
            assertTrue(calls.any { it.hasKeyValue("ok", true) } && calls.any { it.hasKeyValue("ok", false) })
            assertTrue(calls.all { it.hasKeyValue("tool", "t") })
        } finally {
            capture.detach()
        }
    }
}
