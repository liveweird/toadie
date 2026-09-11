package ch.nokillswit

import ch.nokillswit.entities.JqEvaluator
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Phase 5 (`.claude/docs/port-data-model.md` "Computed properties"): pure coverage of
 * `entities/JqCalculation.kt` — the jq bridge behind `calculationProperties`. No database.
 */
class JqCalculationTest {

    @Test
    fun `evaluate reads a plain value`() {
        val evaluator = JqEvaluator()
        assertEquals(JsonPrimitive(5), evaluator.evaluate(".a", buildJsonObject { put("a", 5) }))
    }

    @Test
    fun `evaluate interpolates strings`() {
        val evaluator = JqEvaluator()
        val input = buildJsonObject {
            put("a", "x")
            put("b", "y")
        }
        assertEquals(JsonPrimitive("x-y"), evaluator.evaluate("\"\\(.a)-\\(.b)\"", input))
    }

    @Test
    fun `a JSON null result is absent`() {
        val evaluator = JqEvaluator()
        assertNull(evaluator.evaluate(".missing", buildJsonObject { }))
        assertNull(evaluator.evaluate("null", buildJsonObject { }))
    }

    @Test
    fun `empty produces no output and is absent`() {
        assertNull(JqEvaluator().evaluate("empty", buildJsonObject { }))
    }

    @Test
    fun `the first value of a comma expression wins`() {
        val evaluator = JqEvaluator()
        val input = buildJsonObject {
            put("a", 1)
            put("b", 2)
        }
        assertEquals(JsonPrimitive(1), evaluator.evaluate(".a, .b", input))
    }

    @Test
    fun `range aborts at its first value instead of iterating a billion times`() {
        val result = JqEvaluator().evaluate("range(1e9)", buildJsonObject { })
        assertNotNull(result)
        assertEquals(0.0, (result as JsonPrimitive).double)
    }

    @Test
    fun `a compile error is absent`() {
        assertNull(JqEvaluator().evaluate("{{{", buildJsonObject { }))
    }

    @Test
    fun `a user error call is absent`() {
        assertNull(JqEvaluator().evaluate("error(\"boom\")", buildJsonObject { }))
    }

    @Test
    fun `a type error - dot-b on a number - is absent`() {
        assertNull(JqEvaluator().evaluate(".a.b", buildJsonObject { put("a", 5) }))
    }

    @Test
    fun `infinite recursion is absent, not a crash`() {
        assertNull(JqEvaluator().evaluate("def f: f; f", buildJsonObject { }))
    }

    @Test
    fun `env and ENV are shadowed even though the process environment is non-empty`() {
        assertNotNull(System.getenv("PATH"), "test sanity: the JVM process must carry a PATH")
        val evaluator = JqEvaluator()
        assertNull(evaluator.evaluate("env.PATH", buildJsonObject { }))
        assertNull(evaluator.evaluate("\$ENV.PATH", buildJsonObject { }))
    }

    @Test
    fun `include fails closed since no module loader is ever installed`() {
        assertNull(JqEvaluator().evaluate("include \"x\"; .", buildJsonObject { }))
    }

    @Test
    fun `the same expression compiles once regardless of how many times it is evaluated`() {
        val evaluator = JqEvaluator()
        evaluator.evaluate(".a", buildJsonObject { put("a", 1) })
        evaluator.evaluate(".a", buildJsonObject { put("a", 2) })
        assertEquals(1, evaluator.cacheSize)
        evaluator.evaluate(".b", buildJsonObject { put("b", 1) })
        assertEquals(2, evaluator.cacheSize)
    }

    @Test
    fun `an oversize result is absent, never truncated`() {
        assertNull(JqEvaluator().evaluate("\"x\" * 70000", buildJsonObject { }))
    }
}
