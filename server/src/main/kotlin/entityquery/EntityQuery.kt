package ch.nokillswit.entityquery

import kotlinx.serialization.Serializable

/**
 * Port migration phase 7 (v2.0.0, `.claude/docs/entity-query-language.md`): the **entity query
 * language** — a read-only, openCypher-shaped subset (`MATCH` / `OPTIONAL MATCH` / `WHERE` /
 * `RETURN` / `LIMIT`) evaluated IN MEMORY over the active entity workspace. The matched
 * entities become the SHOWN set of the Entity graph / Entity hierarchy canvases, intersected
 * with the ordinary `blueprint`/`q`/`team` filters.
 *
 * This file holds the vocabulary every other file in the package compiles against: the caps,
 * the diagnostic codes, the wire [QueryDiagnostic], and [QueryException] — the parser's and
 * validator's ONE failure shape. Parsing (`QueryParser.kt`), validation (`QueryValidator.kt`)
 * and evaluation (`QueryEvaluator.kt`) are pure — no database, no Ktor — so their tests run
 * without Docker.
 */

/** Query text ceiling — declared as `maxLength` on the `query` parameter and the check body. */
const val MAX_QUERY_LENGTH = 2000

/** Node patterns across every MATCH / OPTIONAL MATCH clause. */
const val MAX_QUERY_NODE_PATTERNS = 32

/** Distinct node + edge variables in one query. */
const val MAX_QUERY_VARIABLES = 32

/**
 * Variable-length upper bound (`*` alone = `1..10`; a larger explicit bound is `RANGE_INVALID`).
 * Deliberately its own constant, equal to `MAX_COMPUTED_HOPS`: the two limits happen to agree
 * today, and neither should silently move the other.
 */
const val MAX_QUERY_HOPS = 10

/** Intermediate join rows (bindings) an evaluation may hold before it is refused (`BINDING_LIMIT`). */
const val MAX_QUERY_BINDINGS = 100_000

/** Evaluation budget bounds (`entityQuery.deadlineMillis`, the `computed.jq.deadlineMillis` idiom). */
const val MAX_ENTITY_QUERY_DEADLINE_MILLIS = 60_000L
const val DEFAULT_ENTITY_QUERY_DEADLINE_MILLIS = 2_000L

/** The ownership pseudo edge type: an entity's EFFECTIVE `$team` values → the `_team` entities. */
const val OWNERSHIP_EDGE_TYPE = "\$team"

/**
 * Meta-property names an operand may address after the dot (`v.$identifier`): Port's own
 * spelling, the SAME keys `entities/AggregationQuery.kt`'s value lookup resolves, so one
 * vocabulary serves aggregation rules and queries alike.
 */
val QUERY_META_PROPERTIES: Set<String> =
    setOf("\$identifier", "\$title", "\$blueprint", "\$team", "\$icon", "\$createdAt", "\$updatedAt")

/**
 * Stable diagnostic codes — the wire contract the SPA switches on. Every code is an ERROR:
 * a query is either accepted whole or refused, the strict posture of every registry check.
 */
object QueryDiagnosticCodes {
    /** Lexical or grammatical failure (unterminated string, unexpected token, …). */
    const val SYNTAX = "SYNTAX"

    /** A Cypher feature outside the accepted subset, named in the message. */
    const val UNSUPPORTED = "UNSUPPORTED"

    const val UNKNOWN_LABEL = "UNKNOWN_LABEL"
    const val UNKNOWN_RELATION = "UNKNOWN_RELATION"
    const val UNKNOWN_PROPERTY = "UNKNOWN_PROPERTY"
    const val UNKNOWN_VARIABLE = "UNKNOWN_VARIABLE"
    const val DUPLICATE_VARIABLE = "DUPLICATE_VARIABLE"

    /** An edge variable used in WHERE/RETURN — relations carry no properties in Toadie. */
    const val RELATIONSHIP_VARIABLE_REFERENCE = "RELATIONSHIP_VARIABLE_REFERENCE"
    const val RANGE_INVALID = "RANGE_INVALID"
    const val LIMIT_INVALID = "LIMIT_INVALID"

    /** Plain MATCH patterns that share no node variable — a cartesian product, refused. */
    const val DISCONNECTED_PATTERN = "DISCONNECTED_PATTERN"
    const val TOO_MANY_PATTERNS = "TOO_MANY_PATTERNS"
    const val TOO_MANY_VARIABLES = "TOO_MANY_VARIABLES"
    const val QUERY_TOO_LONG = "QUERY_TOO_LONG"

    /** Evaluation refused: the deadline passed (`entityQuery.deadlineMillis`). No position. */
    const val DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"

    /** Evaluation refused: more than [MAX_QUERY_BINDINGS] intermediate rows. No position. */
    const val BINDING_LIMIT = "BINDING_LIMIT"
}

/**
 * One problem with a query, positioned in the SOURCE text (1-based line/column, end exclusive)
 * when it has a position — evaluation-time refusals carry none. [suggestion] is the nearest
 * known name for the UNKNOWN_* codes ("did you mean …"), absent otherwise. Rendered by the SPA
 * both as an editor lint marker and as a list row; the SAME shape rides the graph GET's `400`
 * problem body (`EntityQueryProblem`) and the `/query/check` `200` payload.
 */
@Serializable
data class QueryDiagnostic(
    val code: String,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null,
    val suggestion: String? = null,
)

/**
 * The parser's and validator's failure: one or more [diagnostics]. The parser stops at its
 * FIRST syntax error (a recursive-descent parser cannot resynchronize meaningfully), the
 * validator reports EVERY finding it can. Never thrown by evaluation — the budget has its own
 * exception, mapped by the service.
 */
class QueryException(val diagnostics: List<QueryDiagnostic>) : RuntimeException(
    diagnostics.joinToString("; ") { d ->
        if (d.line != null) "${d.line}:${d.column} ${d.message}" else d.message
    },
) {
    constructor(diagnostic: QueryDiagnostic) : this(listOf(diagnostic))
}
