package ch.nokillswit

import ch.nokillswit.integration.MCP_MAX_BATCH_SIZE
import ch.nokillswit.integration.MCP_MAX_JSON_DEPTH
import ch.nokillswit.integration.MCP_MAX_JSON_VALUES
import ch.nokillswit.integration.McpBodyScan
import ch.nokillswit.integration.scanMcpBody
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure tests (no Docker) of the allocation-free streaming scan that bounds an MCP body before any JSON tree exists. */
class McpBodyScanTest {
    private fun scan(text: String) = scanMcpBody(text.encodeToByteArray())

    private fun toolsCall(n: Int) =
        """{"jsonrpc":"2.0","id":$n,"method":"tools/call","params":{"name":"get_ontology_revision","arguments":{}}}"""

    private fun batch(count: Int) = List(count) { toolsCall(it) }.joinToString(",", "[", "]")

    @Test
    fun `exactly the batch cap of tools call messages is ok`() {
        assertEquals(McpBodyScan.Ok, scan(batch(MCP_MAX_BATCH_SIZE)))
    }

    @Test
    fun `one message over the batch cap is too many messages`() {
        assertEquals(McpBodyScan.TooManyMessages, scan(batch(MCP_MAX_BATCH_SIZE + 1)))
    }

    @Test
    fun `a 4 MiB array of zeros is refused as too many messages`() {
        val body = "[" + "0,".repeat(2 * 1024 * 1024 - 1) + "0]"
        assertEquals(McpBodyScan.TooManyMessages, scanMcpBody(body.encodeToByteArray()))
    }

    @Test
    fun `the scan stops early at the first cap and never reads the tail`() {
        val garbage = "\u0000\"\\{[[[".repeat(1000)
        assertEquals(McpBodyScan.TooManyMessages, scan("[" + "0,".repeat(MCP_MAX_BATCH_SIZE + 1) + garbage))
        val values = """{"a":[""" + "0,".repeat(MCP_MAX_JSON_VALUES + 1) + garbage
        assertEquals(McpBodyScan.TooManyValues, scan(values))
    }

    @Test
    fun `a single object is ok`() {
        assertEquals(McpBodyScan.Ok, scan(toolsCall(1)))
    }

    @Test
    fun `a tools call with a huge arguments array is too many values`() {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"x","arguments":{"a":[""" +
            "0,".repeat(150_000) + "0]}}}"
        assertEquals(McpBodyScan.TooManyValues, scan(body))
    }

    @Test
    fun `a legitimate import sized body is ok`() {
        val docs = List(200) { """{"identifier":"e$it","title":"t","properties":{"a":1,"b":"x","c":[1,2,3]},"relations":{}}""" }
        assertEquals(McpBodyScan.Ok, scan(docs.joinToString(",", """{"params":{"arguments":{"documents":[""", "]}}}")))
    }

    @Test
    fun `brackets braces quotes and escapes inside strings are not structure`() {
        val tricky = """{"s":"[[[[ {{{{ ] } \" \\\" ]]]","k":["a]","\\"]}"""
        assertEquals(McpBodyScan.Ok, scan(tricky))
        val manyBrackets = "[" + "\"[[[[[]]]]]\",".repeat(MCP_MAX_BATCH_SIZE - 1) + "\"{\\\"}\"]"
        assertEquals(McpBodyScan.Ok, scan(manyBrackets))
        assertEquals(McpBodyScan.Ok, scan("[" + "\"x[\",".repeat(MCP_MAX_BATCH_SIZE - 1) + "\"y]\"]"))
    }

    @Test
    fun `object keys are not counted as values`() {
        val keys = (0 until MCP_MAX_JSON_VALUES - 10).joinToString(",", "{", "}") { "\"k$it\":0" }
        assertEquals(McpBodyScan.Ok, scan(keys))
    }

    @Test
    fun `nesting beyond the depth cap is refused early`() {
        val deep = "[".repeat(MCP_MAX_JSON_DEPTH + 1) + "this tail is never reached"
        assertEquals(McpBodyScan.TooDeep, scan(deep))
        assertEquals(McpBodyScan.Ok, scan("[".repeat(MCP_MAX_JSON_DEPTH) + "]".repeat(MCP_MAX_JSON_DEPTH)))
    }

    @Test
    fun `nested arrays inside messages count toward values not messages`() {
        val nested = "[" + """{"a":[[1,2],[3,4],[5,6]]},""".repeat(MCP_MAX_BATCH_SIZE - 1) + """{"a":[[1,2]]}]"""
        assertEquals(McpBodyScan.Ok, scan(nested))
        val manyNested = """[{"a":[""" + "[0],".repeat(MCP_MAX_JSON_VALUES) + """[0]]}]"""
        assertEquals(McpBodyScan.TooManyValues, scan(manyNested))
    }

    @Test
    fun `empty and empty array bodies are ok`() {
        assertEquals(McpBodyScan.Ok, scan(""))
        assertEquals(McpBodyScan.Ok, scan("[]"))
        assertEquals(McpBodyScan.Ok, scan("  [ ] "))
    }

    @Test
    fun `malformed input is not this scans verdict`() {
        assertEquals(McpBodyScan.Ok, scan("]]]"))
        assertEquals(McpBodyScan.Ok, scan("""[{"jsonrpc":"2.0","id":1,"method":"""))
        assertEquals(McpBodyScan.Ok, scan("\"unterminated"))
    }
}
