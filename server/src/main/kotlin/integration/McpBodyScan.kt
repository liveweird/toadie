package ch.nokillswit.integration

/**
 * Upper bound on the JSON values (every scalar, object and array; object keys excluded) one MCP
 * request body may hold (2.15.2). A parsed `JsonElement` costs ~70 B per value, so the cap bounds one
 * request's tree to ~7 MiB (x4 admission permits) while staying far above a legitimate 200-document
 * `import_entities` call. Without it a 4 MiB `[0,0,0,...]` builds a ~154 MiB tree before any size check.
 */
internal const val MCP_MAX_JSON_VALUES = 100_000

/**
 * Upper bound on JSON nesting depth in one MCP request body (2.15.2). kotlinx parses deep documents
 * without overflowing (heap-based past 200 levels), but the recursive encoders downstream of a tool call
 * (`encodeToString` on a stored document) would — 512 is far beyond any Port document and cheap to hold.
 */
internal const val MCP_MAX_JSON_DEPTH = 512

/** The verdict of [scanMcpBody]. */
internal sealed interface McpBodyScan {
    /** Within both caps — or not something this scan can make sense of (the SDK's parse answers that). */
    data object Ok : McpBodyScan

    /** The top-level array holds more than [MCP_MAX_BATCH_SIZE] elements. */
    data object TooManyMessages : McpBodyScan

    /** The body holds more than [MCP_MAX_JSON_VALUES] JSON values. */
    data object TooManyValues : McpBodyScan

    /** The body nests deeper than [MCP_MAX_JSON_DEPTH] levels. */
    data object TooDeep : McpBodyScan
}

/**
 * Pure, allocation-free streaming scan over the RAW request bytes, run BEFORE any JSON tree exists
 * and before the SDK transport is built. It tracks nesting depth and string state, counts the top-level
 * array's elements, every JSON value and the nesting depth, and stops the moment any cap is exceeded. Malformed JSON is
 * not its business: anything it cannot make sense of answers [McpBodyScan.Ok] and the SDK's own parse
 * answers the 400. Structural bytes are ASCII and never occur inside a UTF-8 multi-byte sequence, so the
 * scan works on bytes without decoding.
 */
internal fun scanMcpBody(bytes: ByteArray): McpBodyScan = McpBodyScanner().scan(bytes)

private class McpBodyScanner {
    private var depth = 0
    private var values = 0
    private var messages = 0
    private var topIsArray = false
    private var inString = false
    private var escaped = false
    private var inScalar = false
    private var lastWasString = false

    fun scan(bytes: ByteArray): McpBodyScan {
        for (byte in bytes) {
            val verdict = step(byte.toInt().toChar())
            if (verdict != null) return verdict
        }
        return McpBodyScan.Ok
    }

    /** Null = keep scanning; otherwise the final verdict. */
    private fun step(c: Char): McpBodyScan? = if (inString) stepInString(c) else stepOutside(c)

    private fun stepInString(c: Char): McpBodyScan? {
        when {
            escaped -> escaped = false
            c == '\\' -> escaped = true
            c == '"' -> {
                inString = false
                lastWasString = true
            }
        }
        return null
    }

    private fun stepOutside(c: Char): McpBodyScan? = when (c) {
        ' ', '\t', '\n', '\r' -> {
            inScalar = false
            null
        }
        ',' -> separator()
        ':' -> keyEnd()
        ']', '}' -> close()
        '[', '{' -> open(c)
        '"' -> {
            separator()
            inString = true
            startValue()
        }
        else -> if (inScalar) null else {
            separator()
            inScalar = true
            startValue()
        }
    }

    private fun separator(): McpBodyScan? {
        inScalar = false
        lastWasString = false
        return null
    }

    /** A string just closed before this colon was an object key, not a value. */
    private fun keyEnd(): McpBodyScan? {
        if (lastWasString) values--
        return separator()
    }

    /** A stray closer means malformed input: not this scan's verdict. */
    private fun close(): McpBodyScan? {
        separator()
        depth--
        return if (depth < 0) McpBodyScan.Ok else null
    }

    private fun open(c: Char): McpBodyScan? {
        separator()
        if (c == '[' && depth == 0 && values == 0) topIsArray = true
        val verdict = startValue()
        depth++
        return verdict ?: if (depth > MCP_MAX_JSON_DEPTH) McpBodyScan.TooDeep else null
    }

    private fun startValue(): McpBodyScan? {
        values++
        if (topIsArray && depth == 1) messages++
        return when {
            messages > MCP_MAX_BATCH_SIZE -> McpBodyScan.TooManyMessages
            values > MCP_MAX_JSON_VALUES -> McpBodyScan.TooManyValues
            else -> null
        }
    }
}
