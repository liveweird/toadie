package ch.nokillswit.integration

import ch.nokillswit.plugins.respondProblem
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.withTimeoutOrNull

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
 * slow tool call this endpoint must not starve. Read tools are registered for every scope; write
 * tools ([registerWriteTools]) only when the caller's key carries [IntegrationScope.WRITE]
 * (`.claude/docs/authorization.md` "Machine integration clients") — a read-scope caller sees them
 * neither in `tools/list` nor callable at all.
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
        val context = McpToolContext(principal, services, retained)
        server.registerReadTools(context)
        if (principal.scope == IntegrationScope.WRITE) {
            server.registerWriteTools(context)
        }
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
