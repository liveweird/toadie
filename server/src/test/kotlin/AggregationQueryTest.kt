package ch.nokillswit

import ch.nokillswit.blueprints.AggregationQuery
import ch.nokillswit.entities.QueryCandidate
import ch.nokillswit.entities.matchesQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 5 (`.claude/docs/port-data-model.md` "Aggregation properties"): pure coverage of
 * `entities/AggregationQuery.kt` — Port's search-rule syntax evaluated over one candidate
 * entity. No database.
 */
class AggregationQueryTest {

    private fun candidate(
        identifier: String = "id1",
        title: String = "Title",
        blueprint: String = "bp",
        icon: String? = null,
        team: List<String> = emptyList(),
        createdAt: Long = 100,
        updatedAt: Long = 200,
        properties: JsonObject = JsonObject(emptyMap()),
    ) = QueryCandidate(identifier, title, blueprint, icon, team, createdAt, updatedAt, properties)

    private fun rule(property: String, operator: String, value: JsonElement? = null) = buildJsonObject {
        put("property", property)
        put("operator", operator)
        value?.let { put("value", it) }
    }

    @Test
    fun `no query matches everything`() {
        assertTrue(matchesQuery(null, candidate()))
    }

    @Test
    fun `and requires every rule, or requires at least one, empty rules per combinator default`() {
        val c = candidate(properties = buildJsonObject { put("x", 1) })
        assertTrue(matchesQuery(AggregationQuery(combinator = "and", rules = emptyList()), c))
        assertFalse(matchesQuery(AggregationQuery(combinator = "or", rules = emptyList()), c))
        assertFalse(matchesQuery(AggregationQuery(combinator = "bogus", rules = emptyList()), c))

        val trueRule = rule("x", "=", JsonPrimitive(1))
        val falseRule = rule("x", "=", JsonPrimitive(2))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(trueRule, trueRule)), c))
        assertFalse(matchesQuery(AggregationQuery("and", listOf(trueRule, falseRule)), c))
        assertTrue(matchesQuery(AggregationQuery("or", listOf(falseRule, trueRule)), c))
        assertFalse(matchesQuery(AggregationQuery("or", listOf(falseRule, falseRule)), c))
    }

    @Test
    fun `a nested combinator+rules entry recurses`() {
        val c = candidate(properties = buildJsonObject { put("x", 1); put("y", 2) })
        val nested = buildJsonObject {
            put("combinator", "or")
            put("rules", JsonArray(listOf(rule("x", "=", JsonPrimitive(9)), rule("y", "=", JsonPrimitive(2)))))
        }
        assertTrue(matchesQuery(AggregationQuery("and", listOf(nested)), c))
    }

    @Test
    fun `depth beyond the cap answers false`() {
        fun nestedAt(depth: Int): JsonObject = if (depth == 0) {
            rule("x", "=", JsonPrimitive(1))
        } else {
            buildJsonObject {
                put("combinator", "and")
                put("rules", JsonArray(listOf(nestedAt(depth - 1))))
            }
        }
        val c = candidate(properties = buildJsonObject { put("x", 1) })
        assertTrue(matchesQuery(AggregationQuery("and", listOf(nestedAt(9))), c))
        assertFalse(matchesQuery(AggregationQuery("and", listOf(nestedAt(12))), c))
    }

    @Test
    fun `a rule missing property or operator answers false`() {
        val c = candidate()
        assertFalse(matchesQuery(AggregationQuery("and", listOf(buildJsonObject { put("operator", "=") })), c))
        assertFalse(matchesQuery(AggregationQuery("and", listOf(buildJsonObject { put("property", "x") })), c))
    }

    @Test
    fun `meta-property lookups resolve off the candidate`() {
        val c = candidate(
            identifier = "svc1", title = "Service One", blueprint = "service", icon = "Gear",
            createdAt = 10, updatedAt = 20, team = listOf("payments"),
        )
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("\$identifier", "=", JsonPrimitive("svc1")))), c))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("\$title", "=", JsonPrimitive("Service One")))), c))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("\$blueprint", "=", JsonPrimitive("service")))), c))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("\$icon", "=", JsonPrimitive("Gear")))), c))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("\$createdAt", "=", JsonPrimitive(10)))), c))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("\$updatedAt", "=", JsonPrimitive(20)))), c))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("\$team", "contains", JsonPrimitive("payments")))), c))
    }

    @Test
    fun `equals is structural and absent never equals anything`() {
        val c = candidate()
        assertFalse(matchesQuery(AggregationQuery("and", listOf(rule("missing", "=", JsonPrimitive("x")))), c))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("missing", "!=", JsonPrimitive("x")))), c))
    }

    @Test
    fun `numeric and lexicographic comparisons`() {
        val numeric = candidate(properties = buildJsonObject { put("n", 5) })
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("n", ">", JsonPrimitive(3)))), numeric))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("n", "<", JsonPrimitive(10)))), numeric))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("n", ">=", JsonPrimitive(5)))), numeric))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("n", "<=", JsonPrimitive(5)))), numeric))

        val lexical = candidate(properties = buildJsonObject { put("s", "banana") })
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("s", ">", JsonPrimitive("apple")))), lexical))
        assertFalse(matchesQuery(AggregationQuery("and", listOf(rule("s", "<", JsonPrimitive("apple")))), lexical))

        val mixed = candidate(properties = buildJsonObject { put("n", 5) })
        assertFalse(matchesQuery(AggregationQuery("and", listOf(rule("n", ">", JsonPrimitive("x")))), mixed))
    }

    @Test
    fun `contains and doesNotContain - substring or array element`() {
        val string = candidate(properties = buildJsonObject { put("s", "hello world") })
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("s", "contains", JsonPrimitive("world")))), string))
        assertFalse(matchesQuery(AggregationQuery("and", listOf(rule("s", "doesNotContain", JsonPrimitive("world")))), string))

        val array = candidate(properties = buildJsonObject { put("tags", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))) })
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("tags", "contains", JsonPrimitive("b")))), array))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("tags", "doesNotContain", JsonPrimitive("z")))), array))
    }

    @Test
    fun `in and notIn require the rule value to be an array`() {
        val c = candidate(properties = buildJsonObject { put("s", "b") })
        val ab = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("s", "in", ab))), c))
        assertFalse(matchesQuery(AggregationQuery("and", listOf(rule("s", "notIn", ab))), c))
        assertFalse(matchesQuery(AggregationQuery("and", listOf(rule("s", "in", JsonPrimitive("b")))), c))
    }

    @Test
    fun `isEmpty and isNotEmpty cover absent, blank, and empty containers`() {
        val empty =
            candidate(properties = buildJsonObject { put("s", ""); put("arr", JsonArray(emptyList())); put("obj", JsonObject(emptyMap())) })
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("missing", "isEmpty"))), empty))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("s", "isEmpty"))), empty))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("arr", "isEmpty"))), empty))
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("obj", "isEmpty"))), empty))

        val nonEmpty = candidate(properties = buildJsonObject { put("s", "x") })
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("s", "isNotEmpty"))), nonEmpty))
        assertFalse(matchesQuery(AggregationQuery("and", listOf(rule("s", "isEmpty"))), nonEmpty))
    }

    @Test
    fun `containsAny treats a scalar as a singleton on both sides`() {
        val scalar = candidate(properties = buildJsonObject { put("s", "b") })
        assertTrue(
            matchesQuery(
                AggregationQuery("and", listOf(rule("s", "containsAny", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))))),
                scalar,
            ),
        )
        assertFalse(
            matchesQuery(
                AggregationQuery("and", listOf(rule("s", "containsAny", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("c")))))),
                scalar,
            ),
        )

        val array = candidate(properties = buildJsonObject { put("tags", JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y")))) })
        assertTrue(matchesQuery(AggregationQuery("and", listOf(rule("tags", "containsAny", JsonPrimitive("y")))), array))
    }

    @Test
    fun `an unknown operator answers false`() {
        val c = candidate(properties = buildJsonObject { put("x", 1) })
        assertFalse(matchesQuery(AggregationQuery("and", listOf(rule("x", "bogus", JsonPrimitive(1)))), c))
    }
}
