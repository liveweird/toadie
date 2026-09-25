package ch.nokillswit.integration

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.entities.EntityFinding
import ch.nokillswit.entities.EntityInvalidException
import ch.nokillswit.entities.EntityReferencedException
import ch.nokillswit.infra.paging.DEFAULT_PAGE_SIZE
import ch.nokillswit.infra.paging.MAX_PAGE_SIZE
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.plugins.isUniqueViolation
import io.ktor.server.plugins.BadRequestException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * The per-call context every tool handler closes over: the authenticated
 * [IntegrationClientPrincipal] (its `scope` gates the WRITE tools inside [guarded], since all ten
 * tools are now registered for every key), the shared domain [IntegrationServices], the SAME
 * [IntegrationRetainedLedger.Reservation] the whole MCP request holds open (`integration/Mcp.kt`)
 * — one reservation covers every tool call made within one `POST /integration/mcp` request,
 * exactly like one GraphQL request's reservation covers every root field — and [limits], the
 * SAME per-client rate bucket the request-level admission already drew from once. A JSON-RPC
 * BATCH dispatches its calls concurrently on the SDK transport, so [requestLock] serializes every
 * call within one POST to one at a time, and [callCount] charges [limits] for every call after
 * the first (M1 — the first call is already covered by the request's own admission check).
 */
internal class McpToolContext(
    val principal: IntegrationClientPrincipal,
    val services: IntegrationServices,
    val retained: IntegrationRetainedLedger.Reservation,
    val limits: IntegrationLimits = IntegrationLimits(),
) {
    private val requestLock = Mutex()
    private val callCount = AtomicInteger(0)

    internal suspend fun <T> withRequestLock(block: suspend () -> T): T = requestLock.withLock { block() }

    internal fun isFirstCallInRequest(): Boolean = callCount.getAndIncrement() == 0
}

private val mcpLogger = LoggerFactory.getLogger("ch.nokillswit.integration.mcp")

/** One deadline per READ tool call — a slow read must not consume the whole request's [MCP_REQUEST_TIMEOUT_MILLIS] budget. */
internal const val MCP_READ_TOOL_TIMEOUT_MILLIS = EXECUTION_TIMEOUT_MILLIS

/**
 * The ONE outcome mapper every tool handler runs through: serializes every call made within one
 * MCP POST to one at a time ([McpToolContext.withRequestLock] — a JSON-RPC batch dispatches its
 * calls concurrently on the SDK transport, so without this a batch would multiply work under the
 * request's single admission permit, M1); charges [McpToolContext.limits] for every call AFTER
 * the first in the request (the first is already covered by the request-level admission check),
 * answering `RATE_LIMITED` once the per-client bucket is exhausted; refuses a WRITE tool
 * (`write = true`) for a caller whose key does not carry [IntegrationScope.WRITE] with
 * `FORBIDDEN`, audited `integration.scope_denied` (L1 — all ten tools are registered for every
 * key, so this is where the scope gate actually lives); maps a domain exception to a structured
 * [CallToolResult] (never a JSON-RPC protocol error — a rejected write is a normal MCP tool
 * failure, not a broken call); enforces the per-result response-memory budget against the
 * request's shared [McpToolContext.retained] reservation; and audits exactly once as
 * `integration.mcp_call`. [CancellationException] (other than a per-tool [TimeoutCancellationException])
 * always propagates unswallowed — the request's own deadline, or caller disconnect, must still
 * cancel the coroutine.
 */
// The MCP tool boundary — every failure must answer a CallToolResult, never crash the session
// (the EntityImport.kt/UrlFetch.kt best-effort-boundary precedent, `.claude/docs/testing.md`).
@Suppress("TooGenericExceptionCaught")
internal suspend fun McpToolContext.guarded(
    tool: String,
    write: Boolean = false,
    block: suspend () -> CallToolResult,
): CallToolResult = withRequestLock {
    val rateLimited = !isFirstCallInRequest() && !limits.allow(principal.clientId)
    val scopeDenied = write && principal.scope != IntegrationScope.WRITE
    var result = when {
        rateLimited -> {
            audit("integration.rate_limited", "clientId" to principal.clientId.toLong(), "clientName" to principal.name)
            toolError("RATE_LIMITED", "Integration client rate limit exceeded — retry later")
        }
        scopeDenied -> {
            audit(
                "integration.scope_denied",
                "clientId" to principal.clientId.toLong(),
                "clientName" to principal.name,
                "tool" to tool,
            )
            toolError("FORBIDDEN", "This key has read scope; write tools require a write-scope key")
        }
        else -> try {
            block()
        } catch (e: TimeoutCancellationException) {
            toolError("TIMEOUT", e.message ?: "Tool call timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: EntityInvalidException) {
            toolError("INVALID", e.message ?: "Invalid entity", buildJsonObject { put("findings", findingsJson(e.findings)) })
        } catch (e: EntityReferencedException) {
            toolError(
                "CONFLICT",
                e.message ?: "Entity is referenced",
                buildJsonObject {
                    put("target", e.target)
                    put("referrers", JsonArray(e.referrers.map(::JsonPrimitive)))
                },
            )
        } catch (e: BadRequestException) {
            toolError("BAD_REQUEST", e.message ?: "Bad request")
        } catch (e: NotFoundException) {
            toolError("NOT_FOUND", e.message ?: "Not found")
        } catch (e: TooManyRequestsException) {
            toolError("BUDGET_EXCEEDED", e.message ?: "Budget exceeded — retry shortly")
        } catch (e: Exception) {
            if (e.isUniqueViolation()) {
                toolError("CONFLICT", "Conflicting write")
            } else {
                mcpLogger.warn("mcp tool call failed ({}): {}", tool, e.javaClass.name)
                toolError("INTERNAL", "Internal error")
            }
        }
    }
    if (result.isError != true) {
        result = chargeOrRefuse(result)
    }
    audit(
        "integration.mcp_call",
        "clientId" to principal.clientId.toLong(),
        "clientName" to principal.name,
        "tool" to tool,
        "ok" to (result.isError != true),
    )
    result
}

/** Renders [EntityFinding]s as plain JSON objects — no serializer import needed for one small shape; shared with `McpReadTools.kt`. */
internal fun findingsJson(findings: List<EntityFinding>): JsonArray = JsonArray(
    findings.map { finding ->
        buildJsonObject {
            put("code", finding.code)
            put("field", finding.field)
            put("message", finding.message)
        }
    },
)

/** Refuses an oversized encoded result outright, then charges the request's shared response-memory reservation. */
private fun McpToolContext.chargeOrRefuse(result: CallToolResult): CallToolResult {
    val structured = result.structuredContent ?: JsonObject(emptyMap())
    if (structured.toString().utf8Size() > MAX_RESPONSE_BYTES) {
        return toolError("BUDGET_EXCEEDED", "Result exceeds the response size limit")
    }
    return try {
        retained.charge(structured)
        result
    } catch (e: BadRequestException) {
        toolError("BUDGET_EXCEEDED", e.message ?: "Result exceeds the response size limit")
    } catch (e: TooManyRequestsException) {
        toolError("BUDGET_EXCEEDED", e.message ?: "Integration response memory is busy — retry shortly")
    }
}

// ---------------------------------------------------------------------------------------------
// Argument decoding — every tool reads its `arguments` JsonObject through these, never by hand.
// ---------------------------------------------------------------------------------------------

private fun CallToolRequest.rawArg(name: String): JsonElement? = arguments?.get(name)

internal fun CallToolRequest.argString(name: String, required: Boolean = false): String? {
    val value = rawArg(name) ?: if (required) throw BadRequestException("$name is required") else return null
    val primitive = value as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw BadRequestException("$name must be a string")
    return primitive.content
}

internal fun CallToolRequest.requireString(name: String): String = checkNotNull(argString(name, required = true))

internal fun CallToolRequest.argInt(name: String, default: Int, min: Int, max: Int): Int {
    val value = rawArg(name) ?: return default
    val parsed = (value as? JsonPrimitive)?.content?.toIntOrNull() ?: throw BadRequestException("$name must be an integer")
    if (parsed !in min..max) throw BadRequestException("$name must be between $min and $max")
    return parsed
}

internal fun CallToolRequest.argBool(name: String, default: Boolean = false): Boolean {
    val value = rawArg(name) ?: return default
    return (value as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        ?: throw BadRequestException("$name must be a boolean")
}

internal fun CallToolRequest.argObject(name: String, required: Boolean = false): JsonObject? {
    val value = rawArg(name) ?: if (required) throw BadRequestException("$name is required") else return null
    return value as? JsonObject ?: throw BadRequestException("$name must be an object")
}

internal fun CallToolRequest.requireObject(name: String): JsonObject = checkNotNull(argObject(name, required = true))

internal fun CallToolRequest.argObjects(name: String, min: Int, max: Int): List<JsonObject> {
    val value = rawArg(name) ?: throw BadRequestException("$name is required")
    val array = value as? JsonArray ?: throw BadRequestException("$name must be an array")
    if (array.size !in min..max) throw BadRequestException("$name must contain between $min and $max items")
    return array.map { it as? JsonObject ?: throw BadRequestException("$name items must be objects") }
}

/** One-based `page`/`pageSize` — the `Fetchers.kt` `DataFetchingEnvironment.pageRequest()` idiom, over tool arguments. */
internal fun CallToolRequest.mcpPageRequest(sort: List<SortField>): PageRequest {
    val page = argInt("page", default = 1, min = 1, max = Int.MAX_VALUE)
    val pageSize = argInt("pageSize", default = DEFAULT_PAGE_SIZE, min = 1, max = MAX_PAGE_SIZE)
    return PageRequest(page, pageSize, sort)
}

/** [ch.nokillswit.entities.EntityService.ontologyErrors]/`Fetchers.kt`'s `inMemoryPage` idiom, over a plain in-memory list. */
internal fun <T> mcpInMemoryPage(items: List<T>, page: Int, pageSize: Int): Pair<List<T>, Long> {
    val offset = ((page - 1).toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val end = (offset.toLong() + pageSize).coerceAtMost(items.size.toLong()).toInt()
    val pageItems = if (offset >= items.size) emptyList() else items.subList(offset, end)
    return pageItems to items.size.toLong()
}
