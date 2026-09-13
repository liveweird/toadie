package ch.nokillswit.entityquery

import ch.nokillswit.entities.QueryCandidate
import ch.nokillswit.entities.candidateValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Cypher's three-valued logic (`.claude/docs/entity-query-language.md`, PR1 P1.6) over stored
 * `properties`/meta values (`JsonElement?` — `null` means the property/meta/variable did not
 * resolve, e.g. an unbound variable or a missing key). Pure, DB-free: [evaluate] is the WHERE
 * evaluator `QueryEvaluator.kt` calls per candidate binding.
 */

/** TRUE/FALSE/UNKNOWN — Kleene logic, never a plain [Boolean]: a WHERE clause keeps a binding iff TRUE. */
internal enum class Truth {
    TRUE,
    FALSE,
    UNKNOWN,
    ;

    /** Kleene AND: FALSE dominates (even over UNKNOWN), else UNKNOWN dominates, else TRUE. */
    infix fun and(other: Truth): Truth = when {
        this == FALSE || other == FALSE -> FALSE
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> TRUE
    }

    /** Kleene OR: TRUE dominates (even over UNKNOWN), else UNKNOWN dominates, else FALSE. */
    infix fun or(other: Truth): Truth = when {
        this == TRUE || other == TRUE -> TRUE
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> FALSE
    }

    /** Kleene NOT: UNKNOWN stays UNKNOWN. */
    fun not(): Truth = when (this) {
        TRUE -> FALSE
        FALSE -> TRUE
        UNKNOWN -> UNKNOWN
    }
}

private fun boolTruth(value: Boolean): Truth = if (value) Truth.TRUE else Truth.FALSE

/** `null` (absent) or the JSON `null` literal — either counts as "did not resolve". */
internal fun isNull(value: JsonElement?): Boolean = value == null || value is JsonNull

private fun jsonString(value: JsonElement): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun jsonNumber(value: JsonElement): Double? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** A JSON boolean LITERAL only — a quoted `"true"` string is not a boolean here. */
private fun jsonBoolean(value: JsonElement): Boolean? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private fun booleanOrdering(left: Boolean, right: Boolean): Int = when {
    left == right -> 0
    !left -> -1
    else -> 1
}

/** Numbers compare as [Double], strings lexicographically, booleans `false < true`; any other/mixed pairing is unordered. */
private fun orderingCompare(left: JsonElement, right: JsonElement): Int? {
    jsonNumber(left)?.let { l -> jsonNumber(right)?.let { r -> return l.compareTo(r) } }
    jsonString(left)?.let { l -> jsonString(right)?.let { r -> return l.compareTo(r) } }
    jsonBoolean(left)?.let { l -> jsonBoolean(right)?.let { r -> return booleanOrdering(l, r) } }
    return null
}

/**
 * Two numbers are equal when their DOUBLE values are (`1 = 1.0`, the same widening the ordering
 * operators use — otherwise `a <= b`, `a >= b` and `a <> b` could all hold at once); everything
 * else is [JsonElement.equals] — byte-exact strings, order-sensitive arrays, key-set objects.
 */
private fun structurallyEqual(left: JsonElement, right: JsonElement): Boolean {
    val l = jsonNumber(left)
    val r = jsonNumber(right)
    if (l != null && r != null) return l == r
    return left == right
}

/**
 * `EQUAL`/`NOT_EQUAL` are structural via [structurallyEqual] (numbers widened to Double first,
 * the rest the same shape `entities/AggregationQuery.kt`'s `structurallyEqual` checks). Ordering operators
 * answer UNKNOWN for any pairing [orderingCompare] cannot resolve (including a number vs. a
 * string). Absence on either side is UNKNOWN throughout — never FALSE.
 */
internal fun compare(op: ComparisonOp, left: JsonElement?, right: JsonElement?): Truth {
    val l = left?.takeUnless { it is JsonNull } ?: return Truth.UNKNOWN
    val r = right?.takeUnless { it is JsonNull } ?: return Truth.UNKNOWN
    if (op == ComparisonOp.EQUAL) return boolTruth(structurallyEqual(l, r))
    if (op == ComparisonOp.NOT_EQUAL) return boolTruth(!structurallyEqual(l, r))
    val cmp = orderingCompare(l, r) ?: return Truth.UNKNOWN
    return boolTruth(
        when (op) {
            ComparisonOp.LESS -> cmp < 0
            ComparisonOp.LESS_OR_EQUAL -> cmp <= 0
            ComparisonOp.GREATER -> cmp > 0
            ComparisonOp.GREATER_OR_EQUAL -> cmp >= 0
        },
    )
}

/**
 * `left IN right`: a [JsonArray] on the right checks membership of [left]; a [JsonArray] on the
 * left against a scalar right checks the other way (`'kotlin' IN s.languages`'s natural
 * spelling — either side may be the list). Neither side an array, or either side absent, is
 * UNKNOWN.
 */
internal fun inList(left: JsonElement?, right: JsonElement?): Truth {
    val l = left?.takeUnless { it is JsonNull } ?: return Truth.UNKNOWN
    val r = right?.takeUnless { it is JsonNull } ?: return Truth.UNKNOWN
    return when {
        r is JsonArray -> boolTruth(r.any { it == l })
        l is JsonArray -> boolTruth(l.any { it == r })
        else -> Truth.UNKNOWN
    }
}

/**
 * `CONTAINS`/`STARTS WITH`/`ENDS WITH` — both sides must be JSON strings, compared byte-exact;
 * UNKNOWN when either exceeds [MAX_STRING_OPERAND_CHARS]/[MAX_STRING_NEEDLE_CHARS].
 */
internal fun stringOp(op: StringOperator, left: JsonElement?, right: JsonElement?): Truth {
    val l = left?.takeUnless { it is JsonNull }?.let { jsonString(it) } ?: return Truth.UNKNOWN
    val r = right?.takeUnless { it is JsonNull }?.let { jsonString(it) } ?: return Truth.UNKNOWN
    // Bounded per-candidate work (the budget only observes the clock BETWEEN candidates).
    if (l.length > MAX_STRING_OPERAND_CHARS || r.length > MAX_STRING_NEEDLE_CHARS) return Truth.UNKNOWN
    return boolTruth(
        when (op) {
            StringOperator.CONTAINS -> l.contains(r)
            StringOperator.STARTS_WITH -> l.startsWith(r)
            StringOperator.ENDS_WITH -> l.endsWith(r)
        },
    )
}

/** A bare operand as a predicate: TRUE only for the JSON boolean literal `true`, FALSE only for `false`. */
internal fun truthy(value: JsonElement?): Truth {
    val primitive = value as? JsonPrimitive ?: return Truth.UNKNOWN
    return when (jsonBoolean(primitive)) {
        true -> Truth.TRUE
        false -> Truth.FALSE
        null -> Truth.UNKNOWN
    }
}

/**
 * `variable.key` resolved off whatever [candidateOf] currently binds that variable to — an
 * unbound [Operand.Property] variable (no active binding, e.g. inside an unmatched OPTIONAL
 * MATCH extension) resolves to `null`, same as a missing property. [Operand.Value] is already
 * in JSON shape.
 */
internal fun resolveOperand(operand: Operand, candidateOf: (variable: String) -> QueryCandidate?): JsonElement? =
    when (operand) {
        is Operand.Property -> candidateOf(operand.variable)?.let { candidateValue(operand.key, it) }
        is Operand.Value -> operand.literal.value
    }

/**
 * The full recursive WHERE evaluator over one binding (`candidateOf` resolves a bound
 * variable's current [QueryCandidate]): `And`/`Or`/`Not` are Kleene ([Truth.and]/[Truth.or]/
 * [Truth.not]); [Expr.IsNull] is always decidable — TRUE or FALSE, never UNKNOWN, unlike a
 * comparison against a possibly-absent value.
 */
internal fun evaluate(expr: Expr, candidateOf: (variable: String) -> QueryCandidate?): Truth = when (expr) {
    is Expr.And -> evaluate(expr.left, candidateOf) and evaluate(expr.right, candidateOf)
    is Expr.Or -> evaluate(expr.left, candidateOf) or evaluate(expr.right, candidateOf)
    is Expr.Not -> evaluate(expr.operand, candidateOf).not()
    is Expr.Compare -> compare(expr.op, resolveOperand(expr.left, candidateOf), resolveOperand(expr.right, candidateOf))
    is Expr.In -> inList(resolveOperand(expr.left, candidateOf), resolveOperand(expr.right, candidateOf))
    is Expr.StringOp -> stringOp(expr.op, resolveOperand(expr.left, candidateOf), resolveOperand(expr.right, candidateOf))
    is Expr.IsNull -> {
        val absent = isNull(resolveOperand(expr.operand, candidateOf))
        boolTruth(if (expr.negated) !absent else absent)
    }
    is Expr.Truthy -> truthy(resolveOperand(expr.operand, candidateOf))
}
