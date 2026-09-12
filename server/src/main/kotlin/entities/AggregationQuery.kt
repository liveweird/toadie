package ch.nokillswit.entities

import ch.nokillswit.blueprints.AggregationQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Phase 5 (`.claude/docs/port-data-model.md` "Aggregation properties"): Port's search-rule
 * syntax (`combinator` + `rules`), evaluated over one candidate entity of an aggregation's
 * `target` blueprint. Pure, DB-free.
 */

/** Defensive recursion cap for a nested `combinator`+`rules` rule. */
const val MAX_QUERY_DEPTH = 10

/** One candidate entity as [matchesQuery] needs it — the meta-properties plus its STORED properties. */
data class QueryCandidate(
    val identifier: String,
    val title: String,
    val blueprint: String,
    val icon: String?,
    val team: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val properties: JsonObject,
)

/**
 * `null` query → every [candidate] matches. `and` requires every rule (empty rules → true); `or`
 * requires at least one (empty rules → false); any other combinator → false. [depth] guards
 * against a pathological nesting of `combinator`+`rules` rules.
 */
fun matchesQuery(query: AggregationQuery?, candidate: QueryCandidate, depth: Int = 0): Boolean {
    if (query == null) return true
    if (depth > MAX_QUERY_DEPTH) return false
    return when (query.combinator) {
        "and" -> query.rules.all { matchesRule(it, candidate, depth) }
        "or" -> query.rules.any { matchesRule(it, candidate, depth) }
        else -> false
    }
}

private fun matchesRule(rule: JsonObject, candidate: QueryCandidate, depth: Int): Boolean {
    val property = (rule["property"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val combinator = (rule["combinator"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val nestedRules = rule["rules"] as? JsonArray
    if (property == null && combinator != null && nestedRules != null) {
        val nested = AggregationQuery(combinator = combinator, rules = nestedRules.filterIsInstance<JsonObject>())
        return matchesQuery(nested, candidate, depth + 1)
    }
    val operator = (rule["operator"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (property == null || operator == null) return false
    return evaluateOperator(operator, candidateValue(property, candidate), rule["value"])
}

/**
 * Meta-property + stored-property resolution off one [QueryCandidate] — shared with
 * `entityquery/QueryValues.kt`'s WHERE evaluator so the query language and aggregation rules
 * agree on exactly one meta vocabulary (`QUERY_META_PROPERTIES`).
 */
internal fun candidateValue(property: String, candidate: QueryCandidate): JsonElement? = when (property) {
    "\$identifier" -> JsonPrimitive(candidate.identifier)
    "\$title" -> JsonPrimitive(candidate.title)
    "\$blueprint" -> JsonPrimitive(candidate.blueprint)
    "\$icon" -> candidate.icon?.let { JsonPrimitive(it) }
    "\$createdAt" -> JsonPrimitive(candidate.createdAt)
    "\$updatedAt" -> JsonPrimitive(candidate.updatedAt)
    // Always an array (assumption): Port's own $team meta-property shape is not documented for
    // the query-rule context, and an entity's effective team is itself list-shaped here.
    "\$team" -> JsonArray(candidate.team.map { JsonPrimitive(it) })
    else -> candidate.properties[property]
}

private fun evaluateOperator(operator: String, actual: JsonElement?, expected: JsonElement?): Boolean = when (operator) {
    "=" -> structurallyEqual(actual, expected)
    "!=" -> !structurallyEqual(actual, expected)
    ">", "<", ">=", "<=" -> compareOperator(operator, actual, expected)
    "contains" -> containsOperator(actual, expected)
    "doesNotContain" -> !containsOperator(actual, expected)
    "in" -> membershipOperator(actual, expected)
    "notIn" -> !membershipOperator(actual, expected)
    "isEmpty" -> isEmptyValue(actual)
    "isNotEmpty" -> !isEmptyValue(actual)
    "containsAny" -> containsAnyOperator(actual, expected)
    else -> false
}

// "=" is structural (an absent actual is never equal to anything, including an explicit `null`
// rule value); "!=" is its exact negation, so an absent actual answers `true` there.
private fun structurallyEqual(actual: JsonElement?, expected: JsonElement?): Boolean = actual != null && actual == expected

private fun compareOperator(operator: String, actual: JsonElement?, expected: JsonElement?): Boolean {
    val cmp = numericCompare(actual, expected) ?: stringCompare(actual, expected) ?: return false
    return when (operator) {
        ">" -> cmp > 0
        "<" -> cmp < 0
        ">=" -> cmp >= 0
        "<=" -> cmp <= 0
        else -> false
    }
}

private fun numericCompare(actual: JsonElement?, expected: JsonElement?): Int? {
    val a = (actual as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
    val e = (expected as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
    return a.compareTo(e)
}

private fun stringCompare(actual: JsonElement?, expected: JsonElement?): Int? {
    val a = (actual as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val e = (expected as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return a.compareTo(e)
}

private fun containsOperator(actual: JsonElement?, expected: JsonElement?): Boolean = when (actual) {
    is JsonArray -> expected != null && actual.any { it == expected }
    is JsonPrimitive -> actual.isString && expected is JsonPrimitive && expected.isString && actual.content.contains(expected.content)
    else -> false
}

private fun membershipOperator(actual: JsonElement?, expected: JsonElement?): Boolean =
    expected is JsonArray && actual != null && expected.any { it == actual }

private fun isEmptyValue(actual: JsonElement?): Boolean = when (actual) {
    null, JsonNull -> true
    is JsonPrimitive -> actual.isString && actual.content.isEmpty()
    is JsonArray -> actual.isEmpty()
    is JsonObject -> actual.isEmpty()
}

/**
 * Treats a scalar [actual]/[expected] as a singleton list, then checks for any shared element
 * (**interpretation** — Port's docs do not spell out the multi-value shape).
 */
private fun containsAnyOperator(actual: JsonElement?, expected: JsonElement?): Boolean {
    val actualList = asList(actual) ?: return false
    val expectedList = asList(expected) ?: return false
    return actualList.any { a -> expectedList.any { it == a } }
}

private fun asList(value: JsonElement?): List<JsonElement>? = when (value) {
    null -> null
    is JsonArray -> value
    else -> listOf(value)
}
