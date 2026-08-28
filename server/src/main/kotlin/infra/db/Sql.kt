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
 * Post-commit read-back guard: the create/transition already committed (Location set, audit
 * emitted), so a missing re-read is a server-side anomaly (500 via [error]), never a client
 * 404. Reads `X.read(id).orVanished("CatalogFile", id)`; transitions pass a phase like
 * "after opening".
 */
fun <T> T?.orVanished(resource: String, id: Any, phase: String = "between create and re-read"): T =
    this ?: error("$resource $id vanished $phase")
