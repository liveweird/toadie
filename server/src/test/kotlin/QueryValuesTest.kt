package ch.nokillswit

import ch.nokillswit.entities.QueryCandidate
import ch.nokillswit.entityquery.ComparisonOp
import ch.nokillswit.entityquery.Expr
import ch.nokillswit.entityquery.Literal
import ch.nokillswit.entityquery.Operand
import ch.nokillswit.entityquery.Span
import ch.nokillswit.entityquery.StringOperator
import ch.nokillswit.entityquery.Truth
import ch.nokillswit.entityquery.compare
import ch.nokillswit.entityquery.evaluate
import ch.nokillswit.entityquery.inList
import ch.nokillswit.entityquery.isNull
import ch.nokillswit.entityquery.stringOp
import ch.nokillswit.entityquery.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure coverage of `entityquery/QueryValues.kt` (PR1 P1.6): Cypher's three-valued logic over
 * `JsonElement?`, and the WHERE [evaluate] recursion over a hand-built AST. No database.
 */
class QueryValuesTest {

    private val span = Span(1, 1, 1, 1)

    private fun candidate(identifier: String = "id1", properties: JsonObject = JsonObject(emptyMap())) =
        QueryCandidate(identifier, "Title", "bp", null, emptyList(), 0, 0, properties)

    private fun value(v: JsonElement) = Operand.Value(Literal(v, span), span)

    private fun prop(variable: String, key: String) = Operand.Property(variable, key, span)

    // --- Kleene tables -----------------------------------------------------------------------

    @Test
    fun `Kleene AND table`() {
        val t = Truth.TRUE
        val f = Truth.FALSE
        val u = Truth.UNKNOWN
        assertEquals(t, t and t)
        assertEquals(f, t and f)
        assertEquals(u, t and u)
        assertEquals(f, f and t)
        assertEquals(f, f and f)
        assertEquals(f, f and u)
        assertEquals(u, u and t)
        assertEquals(f, u and f)
        assertEquals(u, u and u)
    }

    @Test
    fun `Kleene OR table`() {
        val t = Truth.TRUE
        val f = Truth.FALSE
        val u = Truth.UNKNOWN
        assertEquals(t, t or t)
        assertEquals(t, t or f)
        assertEquals(t, t or u)
        assertEquals(t, f or t)
        assertEquals(f, f or f)
        assertEquals(u, f or u)
        assertEquals(t, u or t)
        assertEquals(u, u or f)
        assertEquals(u, u or u)
    }

    @Test
    fun `Kleene NOT table`() {
        assertEquals(Truth.FALSE, Truth.TRUE.not())
        assertEquals(Truth.TRUE, Truth.FALSE.not())
        assertEquals(Truth.UNKNOWN, Truth.UNKNOWN.not())
    }

    // --- compare -------------------------------------------------------------------------------

    @Test
    fun `equal and not equal are structural over primitives, arrays and objects`() {
        assertEquals(Truth.TRUE, compare(ComparisonOp.EQUAL, JsonPrimitive(1), JsonPrimitive(1)))
        assertEquals(Truth.FALSE, compare(ComparisonOp.EQUAL, JsonPrimitive(1), JsonPrimitive(2)))
        // Numbers are widened before the equality check — otherwise 1 <= 1.0 and 1 >= 1.0 but 1 <> 1.0.
        assertEquals(Truth.TRUE, compare(ComparisonOp.EQUAL, JsonPrimitive(1), JsonPrimitive(1.0)))
        assertEquals(Truth.FALSE, compare(ComparisonOp.NOT_EQUAL, JsonPrimitive(1), JsonPrimitive(1.0)))
        assertEquals(Truth.FALSE, compare(ComparisonOp.EQUAL, JsonPrimitive(1), JsonPrimitive("1")))
        assertEquals(Truth.TRUE, compare(ComparisonOp.NOT_EQUAL, JsonPrimitive("a"), JsonPrimitive("b")))
        val arrayA = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
        val arrayB = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
        assertEquals(Truth.TRUE, compare(ComparisonOp.EQUAL, arrayA, arrayB))
        val objA = buildJsonObject { put("x", 1) }
        val objB = buildJsonObject { put("x", 1) }
        assertEquals(Truth.TRUE, compare(ComparisonOp.EQUAL, objA, objB))
    }

    @Test
    fun `ordering compares numbers as doubles`() {
        assertEquals(Truth.TRUE, compare(ComparisonOp.LESS, JsonPrimitive(1), JsonPrimitive(2.5)))
        assertEquals(Truth.TRUE, compare(ComparisonOp.GREATER_OR_EQUAL, JsonPrimitive(2.5), JsonPrimitive(2.5)))
        assertEquals(Truth.FALSE, compare(ComparisonOp.GREATER, JsonPrimitive(1), JsonPrimitive(2)))
    }

    @Test
    fun `ordering compares strings lexicographically`() {
        assertEquals(Truth.TRUE, compare(ComparisonOp.LESS, JsonPrimitive("a"), JsonPrimitive("b")))
        assertEquals(Truth.FALSE, compare(ComparisonOp.GREATER, JsonPrimitive("a"), JsonPrimitive("b")))
        assertEquals(Truth.TRUE, compare(ComparisonOp.LESS_OR_EQUAL, JsonPrimitive("a"), JsonPrimitive("a")))
    }

    @Test
    fun `ordering compares booleans false less than true`() {
        assertEquals(Truth.TRUE, compare(ComparisonOp.LESS, JsonPrimitive(false), JsonPrimitive(true)))
        assertEquals(Truth.FALSE, compare(ComparisonOp.GREATER, JsonPrimitive(false), JsonPrimitive(true)))
        assertEquals(Truth.TRUE, compare(ComparisonOp.LESS_OR_EQUAL, JsonPrimitive(false), JsonPrimitive(false)))
    }

    @Test
    fun `mixed or unordered type pairs answer UNKNOWN, never a thrown exception`() {
        assertEquals(Truth.UNKNOWN, compare(ComparisonOp.LESS, JsonPrimitive(1), JsonPrimitive("a")))
        assertEquals(Truth.UNKNOWN, compare(ComparisonOp.GREATER, JsonPrimitive(true), JsonPrimitive(1)))
        assertEquals(Truth.UNKNOWN, compare(ComparisonOp.LESS, JsonArray(emptyList()), JsonPrimitive(1)))
    }

    @Test
    fun `null, JSON null or absent on either side of a comparison is UNKNOWN`() {
        assertEquals(Truth.UNKNOWN, compare(ComparisonOp.EQUAL, null, JsonPrimitive(1)))
        assertEquals(Truth.UNKNOWN, compare(ComparisonOp.EQUAL, JsonPrimitive(1), null))
        assertEquals(Truth.UNKNOWN, compare(ComparisonOp.EQUAL, JsonNull, JsonPrimitive(1)))
        assertEquals(Truth.UNKNOWN, compare(ComparisonOp.LESS, null, null))
    }

    // --- inList --------------------------------------------------------------------------------

    @Test
    fun `IN checks membership with the list on either side`() {
        val list = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        assertEquals(Truth.TRUE, inList(JsonPrimitive("a"), list))
        assertEquals(Truth.FALSE, inList(JsonPrimitive("z"), list))
        // The natural `'kotlin' IN s.languages` spelling: the property is the array, on the LEFT.
        assertEquals(Truth.TRUE, inList(list, JsonPrimitive("a")))
        assertEquals(Truth.FALSE, inList(list, JsonPrimitive("z")))
    }

    @Test
    fun `IN answers UNKNOWN when absent on either side or neither side is a list`() {
        assertEquals(Truth.UNKNOWN, inList(null, JsonArray(emptyList())))
        assertEquals(Truth.UNKNOWN, inList(JsonPrimitive("a"), null))
        assertEquals(Truth.UNKNOWN, inList(JsonPrimitive("a"), JsonPrimitive("b")))
    }

    // --- stringOp ------------------------------------------------------------------------------

    @Test
    fun `string operators are byte-exact and string-only`() {
        assertEquals(Truth.TRUE, stringOp(StringOperator.CONTAINS, JsonPrimitive("hello world"), JsonPrimitive("wor")))
        assertEquals(Truth.TRUE, stringOp(StringOperator.STARTS_WITH, JsonPrimitive("hello"), JsonPrimitive("he")))
        assertEquals(Truth.TRUE, stringOp(StringOperator.ENDS_WITH, JsonPrimitive("hello"), JsonPrimitive("lo")))
        assertEquals(Truth.FALSE, stringOp(StringOperator.CONTAINS, JsonPrimitive("hello"), JsonPrimitive("Hello")))
        assertEquals(Truth.UNKNOWN, stringOp(StringOperator.CONTAINS, JsonPrimitive(1), JsonPrimitive("1")))
        assertEquals(Truth.UNKNOWN, stringOp(StringOperator.CONTAINS, null, JsonPrimitive("x")))
    }

    // --- isNull / truthy -------------------------------------------------------------------------

    @Test
    fun `isNull covers absence and the JSON null literal only`() {
        assertTrue(isNull(null))
        assertTrue(isNull(JsonNull))
        assertFalse(isNull(JsonPrimitive("")))
        assertFalse(isNull(JsonPrimitive(0)))
    }

    @Test
    fun `truthy is TRUE only for the boolean literal true, FALSE only for false`() {
        assertEquals(Truth.TRUE, truthy(JsonPrimitive(true)))
        assertEquals(Truth.FALSE, truthy(JsonPrimitive(false)))
        assertEquals(Truth.UNKNOWN, truthy(JsonPrimitive("true")))
        assertEquals(Truth.UNKNOWN, truthy(JsonPrimitive(1)))
        assertEquals(Truth.UNKNOWN, truthy(null))
        assertEquals(Truth.UNKNOWN, truthy(JsonNull))
    }

    // --- evaluate over a small expression tree ---------------------------------------------------

    @Test
    fun `evaluate resolves operands off a candidateOf lookup, unbound variables resolve to null`() {
        val bound = candidate(properties = buildJsonObject { put("age", 30); put("active", true) })
        val candidateOf: (String) -> QueryCandidate? = { v -> if (v == "a") bound else null }

        // a.age = 30 AND a.active -> TRUE and TRUE -> TRUE
        val left = Expr.Compare(ComparisonOp.EQUAL, prop("a", "age"), value(JsonPrimitive(30)), span)
        val right = Expr.Truthy(prop("a", "active"), span)
        assertEquals(Truth.TRUE, evaluate(Expr.And(left, right, span), candidateOf))

        // b.age = 30 -> UNKNOWN (b is unbound); OR'd with TRUE -> TRUE; AND'd with TRUE -> TRUE
        val unboundCompare = Expr.Compare(ComparisonOp.EQUAL, prop("b", "age"), value(JsonPrimitive(30)), span)
        val orExpr = Expr.Or(unboundCompare, right, span)
        assertEquals(Truth.TRUE, evaluate(orExpr, candidateOf))
        assertEquals(Truth.UNKNOWN, evaluate(unboundCompare, candidateOf))

        // NOT (a.age = 30) -> FALSE
        assertEquals(Truth.FALSE, evaluate(Expr.Not(left, span), candidateOf))

        // a.age IN [10, 30] -> TRUE
        val inExpr = Expr.In(prop("a", "age"), value(JsonArray(listOf(JsonPrimitive(10), JsonPrimitive(30)))), span)
        assertEquals(Truth.TRUE, evaluate(inExpr, candidateOf))

        // a.missing IS NULL -> TRUE (always decidable, never UNKNOWN)
        val isNullExpr = Expr.IsNull(prop("a", "missing"), negated = false, span = span)
        assertEquals(Truth.TRUE, evaluate(isNullExpr, candidateOf))
        val isNotNullExpr = Expr.IsNull(prop("a", "age"), negated = true, span = span)
        assertEquals(Truth.TRUE, evaluate(isNotNullExpr, candidateOf))

        // a.$identifier CONTAINS 'id' -> TRUE (meta resolution shares AggregationQuery's candidateValue)
        val stringOpExpr = Expr.StringOp(StringOperator.CONTAINS, prop("a", "\$identifier"), value(JsonPrimitive("id")), span)
        assertEquals(Truth.TRUE, evaluate(stringOpExpr, candidateOf))
    }
}
