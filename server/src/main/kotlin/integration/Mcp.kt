package ch.nokillswit.integration

import ch.nokillswit.plugins.respondProblem
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

internal const val MCP_SERVER_NAME = "toadie"

/** The tool-catalogue version — bump when a tool's shape/behavior changes, never on a patch fix. */
internal const val MCP_SERVER_VERSION = "1"

/** One deadline over receive + tool execution (a bulk import can run long; there is no separate parse phase to bound alone). */
internal const val MCP_REQUEST_TIMEOUT_MILLIS = 30_000L
internal const val MCP_MAX_BODY_BYTES = 4L * 1024 * 1024

/**
 * The MCP (Model Context Protocol) server over stateless Streamable HTTP (2.15.0, `POST
 * /integration/mcp`) — one [Server] + one [StreamableHttpServerTransport] PER REQUEST, no session
 * persisted across requests (`setSessionIdGenerator(null)`, the stateless posture: every call is
 * a fresh `initialize`). Authenticated exactly like [respondIntegrationSchema]/
 * [respondIntegrationGraphQL] — [integrationCaller] then [requireRateAllowance], both under the
 * existing [EXECUTION_TIMEOUT_MILLIS] admission deadline — then the whole receive+tool-run gets
 * its own, longer [MCP_REQUEST_TIMEOUT_MILLIS] budget, since a bulk entity import is a legitimate
 * slow tool call this endpoint must not starve. All ten tools ([registerReadTools],
 * [registerWriteTools]) are registered for every key regardless of scope, so `tools/list` always
 * shows the same catalogue; a `read`-scope key calling a write tool is refused inside `guarded`
 * (`FORBIDDEN`, audited `integration.scope_denied` — `.claude/docs/authorization.md` "Machine
 * integration clients").
 */
internal suspend fun ApplicationCall.respondIntegrationMcp(
    clients: IntegrationClientService,
    limits: IntegrationLimits,
    retainedLedger: IntegrationRetainedLedger,
    services: IntegrationServices,
) {
    val call = this
    val principal = withTimeoutOrNull(EXECUTION_TIMEOUT_MILLIS) {
        call.integrationCaller(clients).also { call.requireRateAllowance(limits, it) }
    }
    if (principal == null) {
        call.respondProblem(HttpStatusCode.RequestTimeout, "Integration request timed out")
        return
    }
    retainedLedger.open().use { retained ->
        val transport = StreamableHttpServerTransport(
            StreamableHttpServerTransport.Configuration(enableJsonResponse = true, maxRequestBodySize = MCP_MAX_BODY_BYTES),
        ).also { it.setSessionIdGenerator(null) }
        val server = Server(
            Implementation(name = MCP_SERVER_NAME, version = MCP_SERVER_VERSION),
            ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        val context = McpToolContext(principal, services, retained, limits)
        server.registerReadTools(context)
        server.registerWriteTools(context)
        val session = server.createSession(transport)
        try {
            val handled = withTimeoutOrNull(MCP_REQUEST_TIMEOUT_MILLIS) { transport.handleRequest(null, call) }
            if (handled == null) {
                call.respondProblem(HttpStatusCode.RequestTimeout, "Integration request timed out")
            }
        } finally {
            session.close()
        }
    }
}

/**
 * Encode the SDK's JSON-response-mode replies with ITS serializer, not the application's (2.15.1).
 * [StreamableHttpServerTransport] reads the request body itself but answers JSON-response mode
 * through Ktor's `call.respond(message)`, i.e. through ContentNegotiation — and the application-wide
 * `json()` writes every unset optional as an explicit `null` (`"resources":null`, `"title":null`,
 * `"_meta":null`, `"$schema":null`), which the official TypeScript MCP client's schemas reject
 * (`invalid_union` on the first `initialize` response: Claude Code could not connect at all).
 * Ktor forbids a second, route-scoped ContentNegotiation beside the application-level one, so this
 * route intercepts its own send pipeline in the FIRST phase and turns a [JSONRPCMessage] (or a
 * batch of them) into finished [TextContent] via [McpJson] (`explicitNulls = false` — what the
 * SDK's own SSE path already uses) before ContentNegotiation's Transform-phase interceptor runs.
 * Everything else on this route (problem details, bare status codes) passes through untouched.
 */
internal fun Route.encodeMcpResponsesWithMcpJson() {
    // `Route` is the builder interface; the pipelines live on the concrete node every `route {}` creates.
    val node = this as? RoutingNode ?: error("the MCP route must be a RoutingNode")
    node.sendPipeline.intercept(ApplicationSendPipeline.Before) { subject ->
        val encoded: JsonElement? = when (subject) {
            is JSONRPCMessage -> McpJson.encodeToJsonElement(JSONRPCMessage.serializer(), subject)
            is List<*> -> subject.filterIsInstance<JSONRPCMessage>()
                .takeIf { it.size == subject.size && it.isNotEmpty() }
                ?.let { batch -> JsonArray(batch.map { McpJson.encodeToJsonElement(JSONRPCMessage.serializer(), it) }) }
            else -> null
        }
        if (encoded != null) proceedWith(TextContent(encoded.toString(), ContentType.Application.Json))
    }
}
