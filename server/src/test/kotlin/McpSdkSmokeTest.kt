package ch.nokillswit

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Groundwork smoke test for the release 2.15.0 MCP endpoint (`io.modelcontextprotocol:kotlin-sdk-server`
 * 0.15.0, `gradle/libs.versions.toml`). Not a feature test — it pins the exact package layout and
 * constructor/method shapes an implementer will build the real MCP route against, since the SDK's
 * public surface (types live under `io.modelcontextprotocol.kotlin.sdk.types`, everything else under
 * `io.modelcontextprotocol.kotlin.sdk.server`) is easy to get wrong from memory. If this file stops
 * compiling after a version bump, the SDK's shape changed — update the callers here AND wherever the
 * real MCP feature ends up using them, in the same commit. Docker-free (no database, no Ktor server).
 */
class McpSdkSmokeTest {
    @Test
    fun `MCP SDK server, tool registration, and streamable HTTP transport wire up as expected`() {
        val server = Server(
            Implementation(name = "smoke", version = "1"),
            ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )

        server.addTool(
            name = "echo",
            description = "Echoes the given text back",
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = listOf("text")),
            toolAnnotations = ToolAnnotations(readOnlyHint = true),
        ) {
            CallToolResult(
                content = listOf(TextContent("echo")),
                structuredContent = buildJsonObject { put("text", JsonPrimitive("echo")) },
            )
        }

        assertTrue(server.tools.containsKey("echo"), "the registered tool should be visible on the server")

        // 0.15.0 rejects a duplicate tool name outright (FeatureRegistry.add) — proof the registration
        // path is wired up, not just that the constructor accepted arguments.
        assertFailsWith<IllegalArgumentException> {
            server.addTool(
                name = "echo",
                description = "duplicate",
                inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
            ) { CallToolResult(content = emptyList()) }
        }

        val transport = StreamableHttpServerTransport(
            StreamableHttpServerTransport.Configuration(
                enableJsonResponse = true,
                maxRequestBodySize = 4L * 1024 * 1024,
            ),
        )
        transport.setSessionIdGenerator(null)

        assertTrue(transport.sessionId == null, "a fresh stateless transport has no session id yet")
    }
}
