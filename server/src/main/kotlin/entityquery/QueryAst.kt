package ch.nokillswit.entityquery

import kotlinx.serialization.json.JsonElement

/**
 * The entity query language's abstract syntax — what `parseEntityQuery` (`QueryParser.kt`)
 * produces, `validateEntityQuery` (`QueryValidator.kt`) annotates with diagnostics, and the
 * `QueryExecutor` (`QueryEvaluator.kt`) evaluates. Grammar and semantics:
 * `.claude/docs/entity-query-language.md`.
 *
 * Every node carries a [Span] into the source text so a diagnostic can point at it. Names are
 * kept EXACTLY as written (after backtick unquoting): the validator/evaluator fold labels and
 * hierarchy ids, while relation keys and property ids stay byte-exact (Port maps are
 * case-sensitive).
 */

/** 1-based line/column; [endLine]/[endColumn] exclusive. */
data class Span(val line: Int, val column: Int, val endLine: Int, val endColumn: Int) {
    /** The smallest span covering both. */
    fun until(other: Span): Span = Span(line, column, other.endLine, other.endColumn)
}

/** The whole query: `MATCH…+ (OPTIONAL MATCH…)* RETURN… (LIMIT n)?`. */
data class Query(
    val matches: List<MatchClause>,
    val optionals: List<MatchClause>,
    val returns: Returns,
    val limit: Int?,
    val span: Span,
)

/** One `MATCH` or `OPTIONAL MATCH` clause: comma-separated patterns plus an optional `WHERE`. */
data class MatchClause(
    val patterns: List<Pattern>,
    val where: Expr?,
    val optional: Boolean,
    val span: Span,
)

/** A chain `node (edge node)*` — [edges] has exactly `nodes.size - 1` entries. */
data class Pattern(val nodes: List<NodePattern>, val edges: List<EdgePattern>, val span: Span)

/** `(v:label|label2 {key: literal, …})` — every part optional. */
data class NodePattern(
    val variable: String?,
    /** Blueprint identifiers as written; empty = any blueprint. */
    val labels: List<String>,
    /** Inline equality predicates; keys are property ids or meta names (`$identifier`). */
    val properties: Map<String, Literal>,
    val span: Span,
)

enum class EdgeDirection { OUT, IN, UNDIRECTED }

/**
 * `-[r:type|type2 *min..max]->` — [types] are relation keys, hierarchy ids or
 * [OWNERSHIP_EDGE_TYPE], as written; empty = any relation plus ownership. [range] is null for a
 * single hop; `*` alone parses to `1..MAX_QUERY_HOPS`.
 */
data class EdgePattern(
    val variable: String?,
    val types: List<String>,
    val direction: EdgeDirection,
    val range: IntRange?,
    val span: Span,
)

/** `RETURN *` or `RETURN v1, v2`. `DISTINCT` is accepted and dropped (results are sets anyway). */
sealed interface Returns {
    val span: Span

    data class All(override val span: Span) : Returns

    data class Variables(val names: List<String>, override val span: Span) : Returns
}

/** WHERE expressions. */
sealed interface Expr {
    val span: Span

    data class And(val left: Expr, val right: Expr, override val span: Span) : Expr

    data class Or(val left: Expr, val right: Expr, override val span: Span) : Expr

    data class Not(val operand: Expr, override val span: Span) : Expr

    /** `left <op> right` for `=`, `<>`/`!=` (normalized to [ComparisonOp.NOT_EQUAL]), `<`, `<=`, `>`, `>=`. */
    data class Compare(val op: ComparisonOp, val left: Operand, val right: Operand, override val span: Span) : Expr

    /** `left IN right` — either side may be a list literal or an array-valued property. */
    data class In(val left: Operand, val right: Operand, override val span: Span) : Expr

    /** `left CONTAINS | STARTS WITH | ENDS WITH right` — string-only. */
    data class StringOp(val op: StringOperator, val left: Operand, val right: Operand, override val span: Span) : Expr

    /** `operand IS NULL` / `IS NOT NULL` ([negated]). */
    data class IsNull(val operand: Operand, val negated: Boolean, override val span: Span) : Expr

    /** A bare operand as a predicate — TRUE only for JSON `true`. */
    data class Truthy(val operand: Operand, override val span: Span) : Expr
}

enum class ComparisonOp { EQUAL, NOT_EQUAL, LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL }

enum class StringOperator { CONTAINS, STARTS_WITH, ENDS_WITH }

/** An expression leaf. */
sealed interface Operand {
    val span: Span

    /** `variable.key` — [key] a property id or a meta name (`$team`). */
    data class Property(val variable: String, val key: String, override val span: Span) : Operand

    data class Value(val literal: Literal, override val span: Span) : Operand
}

/**
 * A literal, already in JSON shape so comparison against stored `properties` needs no second
 * type system: strings, numbers, booleans, `null`, and lists of literals.
 */
data class Literal(val value: JsonElement, val span: Span)
