package ch.nokillswit.infra.db

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.LikeEscapeOp
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.stringParam

/**
 * Case- and diacritics-insensitive contains-match: renders
 * `LOWER(public.unaccent(col)) LIKE LOWER(public.unaccent(?)) ESCAPE '\'`, so "zolw" matches
 * "Żółw" and vice versa. Both sides fold through PG's unaccent (extension enabled in V4) — the
 * rules can't drift between query and stored text (ł→l, ß→ss, æ→ae, …), and unaccent never
 * touches ASCII `% _ \`, so [containsPattern]'s escaping survives the folding. unaccent runs
 * BEFORE LOWER on purpose: unaccent maps to same-case ASCII base letters, which LOWER folds
 * correctly under any DB locale — in a C-locale database (the postgres:18-alpine default),
 * `LOWER('Ż')` alone would leave the letter uppercase and uppercase-diacritic input would
 * silently stop matching. Every per-column substring filter MUST use this — never hand-roll
 * `lowerCase() like`.
 */
fun Expression<out String?>.containsNormalized(raw: String): Op<Boolean> {
    val pattern = containsPattern(raw)
    return LikeEscapeOp(
        LowerCase(unaccent(this)),
        LowerCase(unaccent(stringParam(pattern.pattern))),
        like = true,
        escapeChar = pattern.escapeChar,
    )
}

// Schema-qualified so resolution never depends on search_path. Accepts nullable string
// expressions too: a NULL value never LIKE-matches, which is exactly what a substring filter
// over an optional column should do.
private fun unaccent(expr: Expression<*>): CustomFunction<String> =
    CustomFunction("public.unaccent", TextColumnType(), expr)

/**
 * Case-insensitive contains-match pattern with SQL LIKE metacharacters escaped — the escaping
 * is correctness-sensitive and must not drift. Used only via [containsNormalized]; internal so
 * no filter site can bypass the diacritics folding.
 */
internal fun containsPattern(raw: String): LikePattern {
    val escaped = raw.lowercase()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return LikePattern("%$escaped%", escapeChar = '\\')
}

/**
 * Exact membership test against a JSON string ARRAY nested inside a TEXT column holding a
 * JSON document: renders `jsonb_exists(CAST(col AS jsonb) #> '{a,b}', ?)` — the value is a
 * bound parameter, never interpolated, and `jsonb_exists` (the function form of jsonb's `?`
 * operator) matches whole array elements only, so no LIKE-style false positives from other
 * strings in the document. The cast is a per-row seq-scan cost — fine at this scale; the day
 * a filter here needs an index is the day the field gets a denormalized column instead.
 * [path] segments are compile-time constants by contract (guarded — they land inside a SQL
 * literal).
 */
fun Expression<String>.jsonArrayContains(path: List<String>, value: String): Op<Boolean> {
    require(path.isNotEmpty() && path.all { it.matches(Regex("[A-Za-z0-9_]+")) }) {
        "jsonArrayContains path segments must be simple identifiers"
    }
    return object : Op<Boolean>() {
        // Chained single-arg appends on purpose — the vararg overload is absent from the
        // QueryBuilder this resolves against in a cold (Docker) build.
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append("jsonb_exists(CAST(")
            queryBuilder.append(this@jsonArrayContains)
            queryBuilder.append(" AS jsonb) #> '{${path.joinToString(",")}}', ")
            queryBuilder.append(stringParam(value))
            queryBuilder.append(")")
        }
    }
}

/**
 * Case-folded exact match against a scalar STRING field nested inside a TEXT column holding a
 * JSON document: renders `LOWER(CAST(col AS jsonb) #>> '{a,b}') = ?` with the value bound
 * lowercased. An absent field extracts to SQL NULL, which never equals — exactly what a filter
 * over an optional field should do (encodeDefaults=false stores absent fields as absent).
 * Same seq-scan/denormalization trade-off as [jsonArrayContains]; [path] segments are
 * compile-time constants by contract (guarded — they land inside a SQL literal).
 */
fun Expression<String>.jsonTextEqualsFolded(path: List<String>, value: String): Op<Boolean> {
    requireSimplePath(path)
    return object : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append("LOWER(CAST(")
            queryBuilder.append(this@jsonTextEqualsFolded)
            queryBuilder.append(" AS jsonb) #>> '{${path.joinToString(",")}}') = ")
            queryBuilder.append(stringParam(value.lowercase()))
        }
    }
}

/**
 * Key-presence test against a JSON OBJECT nested inside a TEXT column: `jsonb_exists` on an
 * object checks key membership (the same function [jsonArrayContains] uses for array
 * elements), so this is the same rendering under an honest name. The KEY is a bound
 * parameter — label keys legally carry `.`/`-`/`/` and must never travel as path segments.
 */
fun Expression<String>.jsonObjectHasKey(path: List<String>, key: String): Op<Boolean> =
    jsonArrayContains(path, key)

/**
 * Any-of match of the value stored under a caller-supplied KEY of a JSON object: renders
 * `LOWER((CAST(col AS jsonb) #> '{a,b}') ->> ?) IN (?, …)` — the key AND every value bound,
 * values folded on both sides. A missing object or key extracts to SQL NULL → no match.
 */
fun Expression<String>.jsonObjectValueIn(path: List<String>, key: String, values: List<String>): Op<Boolean> {
    requireSimplePath(path)
    require(values.isNotEmpty()) { "jsonObjectValueIn needs at least one value" }
    return object : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append("LOWER((CAST(")
            queryBuilder.append(this@jsonObjectValueIn)
            queryBuilder.append(" AS jsonb) #> '{${path.joinToString(",")}}') ->> ")
            queryBuilder.append(stringParam(key))
            queryBuilder.append(") IN (")
            values.forEachIndexed { index, value ->
                if (index > 0) queryBuilder.append(", ")
                queryBuilder.append(stringParam(value.lowercase()))
            }
            queryBuilder.append(")")
        }
    }
}

private fun requireSimplePath(path: List<String>) {
    require(path.isNotEmpty() && path.all { it.matches(Regex("[A-Za-z0-9_]+")) }) {
        "JSON path segments must be simple identifiers"
    }
}

/**
 * Post-commit read-back guard: the create/transition already committed (Location set, audit
 * emitted), so a missing re-read is a server-side anomaly (500 via [error]), never a client
 * 404. Reads `X.read(id).orVanished("CatalogFile", id)`; transitions pass a phase like
 * "after opening".
 */
fun <T> T?.orVanished(resource: String, id: Any, phase: String = "between create and re-read"): T =
    this ?: error("$resource $id vanished $phase")
