package ch.nokillswit.integration

import ch.nokillswit.plugins.respondProblem
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.io.IOException

internal const val MCP_SERVER_NAME = "toadie"

/** The tool-catalogue version — bump when a tool's shape/behavior changes, never on a patch fix. */
internal const val MCP_SERVER_VERSION = "1"

/** One deadline over receive + tool execution (a bulk import can run long; there is no separate parse phase to bound alone). */
internal const val MCP_REQUEST_TIMEOUT_MILLIS = 30_000L
internal const val MCP_MAX_BODY_BYTES = 4L * 1024 * 1024

/**
 * The most messages one JSON-RPC batch may carry (2.15.2). The SDK transport answers `initialize`,
 * `tools/list` and `ping` ITSELF, outside [guarded] — so no ledger charge, no rate-bucket draw, no
 * request lock — and buffers every reply whole in its `pendingJsonResponses` before re-encoding
 * the lot; a 4 MiB array of ~90k bare `tools/list` requests would exhaust a 256 MiB heap on any
 * valid key. [admitMcpBody] therefore counts the array's elements (see [scanMcpBody]) BEFORE any JSON tree
 * or transport exists and refuses an over-long batch with a 400, dispatching nothing.
 */
internal const val MCP_MAX_BATCH_SIZE = 64

/**
 * The MCP (Model Context Protocol) server over stateless Streamable HTTP (2.15.0, `POST
 * /integration/mcp`) — one [Server] + one [StreamableHttpServerTransport] PER REQUEST, no session
 * persisted across requests (`setSessionIdGenerator(null)`, the stateless posture: every call is
 * a fresh `initialize`). The route authenticates FIRST (`authenticateIntegration` in Integration.kt:
 * [integrationCaller] then [requireRateAllowance] under the [EXECUTION_TIMEOUT_MILLIS] deadline,
 * before any admission permit) and hands the principal in; the whole receive+tool-run then gets
 * its own, longer [MCP_REQUEST_TIMEOUT_MILLIS] budget, since a bulk entity import is a legitimate
 * slow tool call this endpoint must not starve. All ten tools ([registerReadTools],
 * [registerWriteTools]) are registered for every key regardless of scope, so `tools/list` always
 * shows the same catalogue; a `read`-scope key calling a write tool is refused inside `guarded`
 * (`FORBIDDEN`, audited `integration.scope_denied` — `.claude/docs/authorization.md` "Machine
 * integration clients").
 */
internal suspend fun ApplicationCall.respondIntegrationMcp(
    principal: IntegrationClientPrincipal,
    limits: IntegrationLimits,
    retainedLedger: IntegrationRetainedLedger,
    services: IntegrationServices,
) {
    val call = this
    retainedLedger.open().use { retained ->
        var session: ServerSession? = null
        try {
            val handled = withTimeoutOrNull(MCP_REQUEST_TIMEOUT_MILLIS) {
                if (call.admitMcpBody()) {
                    val transport = StreamableHttpServerTransport(
                        StreamableHttpServerTransport.Configuration(enableJsonResponse = true, maxRequestBodySize = MCP_MAX_BODY_BYTES),
                    ).also { it.setSessionIdGenerator(null) }
                    session = buildMcpServer(McpToolContext(principal, services, retained, limits)).createSession(transport)
                    transport.handleRequest(null, call)
                }
                true
            }
            if (handled == null) {
                call.respondProblem(HttpStatusCode.RequestTimeout, "Integration request timed out")
            }
        } finally {
            session?.close()
        }
    }
}

private fun buildMcpServer(context: McpToolContext): Server {
    val server = Server(
        Implementation(name = MCP_SERVER_NAME, version = MCP_SERVER_VERSION),
        ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )
    server.registerReadTools(context)
    server.registerWriteTools(context)
    return server
}

private val McpPreReadBodyKey = AttributeKey<ByteArray>("ToadieMcpPreReadBody")

/**
 * The FIRST thing after authentication (2.15.2): reads the raw body once from the engine channel,
 * bounded at [MCP_MAX_BODY_BYTES] + 1, runs [scanMcpBody] over the bytes and, on any refusal, answers
 * the `application/problem+json` itself and returns false — no transport, no tool, nothing else read.
 * Otherwise the bytes are stored on the call for [replayMcpPreReadBody] to hand the transport.
 */
private suspend fun ApplicationCall.admitMcpBody(): Boolean {
    val bytes = try {
        request.receiveChannel().readRemaining(MCP_MAX_BODY_BYTES + 1).readByteArray()
    } catch (e: IOException) {
        // An aborted upload is the client's doing, not a server fault: answer 400 instead of the logged 500.
        respondProblem(HttpStatusCode.BadRequest, "MCP request body could not be read: ${e.javaClass.simpleName}")
        return false
    }
    val refusal = when {
        bytes.size > MCP_MAX_BODY_BYTES ->
            HttpStatusCode.PayloadTooLarge to "MCP request body exceeds ${MCP_MAX_BODY_BYTES / (1024 * 1024)} MiB"
        else -> when (scanMcpBody(bytes)) {
            McpBodyScan.TooManyMessages -> HttpStatusCode.BadRequest to "MCP batch exceeds $MCP_MAX_BATCH_SIZE messages"
            McpBodyScan.TooManyValues -> HttpStatusCode.BadRequest to "MCP request exceeds $MCP_MAX_JSON_VALUES JSON values"
            McpBodyScan.TooDeep -> HttpStatusCode.BadRequest to "MCP request nests deeper than $MCP_MAX_JSON_DEPTH levels"
            McpBodyScan.Ok -> null
        }
    }
    if (refusal != null) {
        respondProblem(refusal.first, refusal.second)
        return false
    }
    attributes.put(McpPreReadBodyKey, bytes)
    return true
}

/**
 * Hands the SDK transport the exact bytes [admitMcpBody] already consumed: a route-scoped receive
 * interceptor (the receive-side twin of [encodeMcpResponsesWithMcpJson]) replaces the engine channel
 * with the pre-read buffer, so the transport's own `receiveChannel()` replays them. Deliberately NOT
 * Ktor's `DoubleReceive`, whose cache keeps copying an oversized body into memory after the refusal.
 */
internal fun Route.replayMcpPreReadBody() {
    val node = this as? RoutingNode ?: error("the MCP route must be a RoutingNode")
    node.receivePipeline.intercept(ApplicationReceivePipeline.Before) {
        call.attributes.getOrNull(McpPreReadBodyKey)?.let {
            call.attributes.remove(McpPreReadBodyKey) // one replay; frees the buffer for the rest of the tool work
            proceedWith(ByteReadChannel(it))
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
