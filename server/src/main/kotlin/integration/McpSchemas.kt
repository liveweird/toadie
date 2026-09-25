package ch.nokillswit.integration

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure MCP tool-schema/result builders (2.15.0) — no service, no database. One tiny function per
 * JSON Schema shape, the `PropertyValidation.kt` one-function-per-rule idiom, so
 * `McpReadTools.kt`/`McpWriteTools.kt` read as a plain list of `objectSchema(...)` calls.
 */

internal fun objectSchema(properties: Map<String, JsonObject>, required: List<String> = emptyList()): ToolSchema =
    ToolSchema(properties = JsonObject(properties), required = required)

internal fun stringProp(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
}

internal fun intProp(description: String, min: Int, max: Int): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", min)
    put("maximum", max)
}

internal fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal fun objectProp(description: String): JsonObject = buildJsonObject {
    put("type", "object")
    put("description", description)
}

internal fun arrayOfObjectsProp(description: String, minItems: Int, maxItems: Int): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject { put("type", "object") })
    put("minItems", minItems)
    put("maxItems", maxItems)
}

/** A successful tool call — [structured] doubles as the human-readable text content (a JsonObject's
 *  kotlinx.serialization `toString()` already renders valid JSON). */
internal fun toolResult(structured: JsonObject): CallToolResult =
    CallToolResult(content = listOf(TextContent(structured.toString())), structuredContent = structured)

/** A tool-level failure (`isError = true`) — never a JSON-RPC protocol error, so the caller sees
 *  a machine-readable [code] plus a human [message] and any [extra] structured detail. */
internal fun toolError(code: String, message: String, extra: JsonObject = JsonObject(emptyMap())): CallToolResult {
    val structured = buildJsonObject {
        put("code", code)
        put("message", message)
        extra.forEach { (key, value) -> put(key, value) }
    }
    return CallToolResult(content = listOf(TextContent(structured.toString())), structuredContent = structured, isError = true)
}
