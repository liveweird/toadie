package ch.nokillswit.integration

import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val MAX_GRAPHQL_JSON_DEPTH = 64
internal const val MAX_GRAPHQL_JSON_NODES = 20_000

/** Rejects structurally hostile JSON before kotlinx serialization builds a recursive tree.
 * Full syntax and DTO validation still belongs to the strict serializer after this scan. */
internal fun validateGraphQLBodyStructure(text: String) {
    var depth = 0
    var nodes = 1
    var inString = false
    var escaped = false
    text.forEach { character ->
        if (inString) {
            if (escaped) {
                escaped = false
                return@forEach
            }
            if (character == '\\') {
                escaped = true
                return@forEach
            }
            if (character == '"') inString = false
            return@forEach
        }
        when (character) {
            '"' -> inString = true
            '{', '[' -> {
                depth += 1
                nodes += 1
            }
            '}', ']' -> depth -= 1
            ',' -> nodes += 1
        }
        if (depth > MAX_GRAPHQL_JSON_DEPTH) {
            throw io.ktor.server.plugins.BadRequestException("JSON nesting is too deep")
        }
        if (nodes > MAX_GRAPHQL_JSON_NODES) {
            throw io.ktor.server.plugins.BadRequestException("JSON has too many values")
        }
    }
}

@Serializable
data class GraphQLHttpRequest(
    val query: String,
    val operationName: String? = null,
    val variables: JsonObject? = null,
)

fun JsonElement.toAnyValue(): Any? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        content == "true" -> true
        content == "false" -> false
        else -> content.toBigIntegerOrNull() ?: content.toBigDecimalOrNull() ?: content
    }
    is JsonArray -> map { it.toAnyValue() }
    is JsonObject -> mapValues { (_, value) -> value.toAnyValue() }
}

/** Encodes graphql-java's response tree with a strict UTF-8 ceiling. The writer stops growing
 * at the ceiling instead of first materializing a potentially much larger JSON string/tree. */
internal fun boundedGraphQLJson(value: Any?, maxBytes: Int = MAX_RESPONSE_BYTES): String? {
    val writer = BoundedJsonWriter(maxBytes)
    return if (writer.write(value)) writer.result() else null
}

private class BoundedJsonWriter(maxBytes: Int) {
    private val output = StringBuilder(minOf(maxBytes, 16 * 1024))
    private val maximum = maxBytes
    private var bytes = 0

    fun result(): String = output.toString()

    fun write(value: Any?): Boolean = when (value) {
        null -> append("null")
        is JsonElement -> writeJson(value)
        is String -> append(JsonPrimitive(value).toString())
        is Char -> append(JsonPrimitive(value.toString()).toString())
        is Boolean, is Byte, is Short, is Int, is Long, is BigInteger -> append(value.toString())
        is Float -> append(value.toString().takeIf { value.isFinite() } ?: "null")
        is Double -> append(value.toString().takeIf { value.isFinite() } ?: "null")
        is BigDecimal -> append(value.toPlainString())
        is Map<*, *> -> writeMap(value)
        is Iterable<*> -> writeIterable(value)
        is Array<*> -> writeIterable(value.asIterable())
        else -> append(JsonPrimitive(value.toString()).toString())
    }

    private fun writeJson(value: JsonElement): Boolean = when (value) {
        JsonNull -> append("null")
        is JsonPrimitive -> append(value.toString())
        is JsonArray -> writeIterable(value)
        is JsonObject -> writeMap(value)
    }

    private fun writeMap(value: Map<*, *>): Boolean {
        if (!append("{")) return false
        value.entries.forEachIndexed { index, entry ->
            if (index > 0 && !append(",")) return false
            if (!append(JsonPrimitive(entry.key.toString()).toString()) || !append(":")) return false
            if (!write(entry.value)) return false
        }
        return append("}")
    }

    private fun writeIterable(value: Iterable<*>): Boolean {
        if (!append("[")) return false
        value.forEachIndexed { index, child ->
            if (index > 0 && !append(",")) return false
            if (!write(child)) return false
        }
        return append("]")
    }

    private fun append(value: String): Boolean {
        val added = value.toByteArray(Charsets.UTF_8).size
        if (bytes + added > maximum) return false
        output.append(value)
        bytes += added
        return true
    }
}
