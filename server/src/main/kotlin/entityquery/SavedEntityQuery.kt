package ch.nokillswit.entityquery

import ch.nokillswit.infra.validation.sanitizeMultiLine
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * Saved entity queries (phase 7, v2.1.0 — `.claude/docs/entity-query-language.md`): named,
 * saveable entity-query texts for the Entity graph / Entity hierarchy query bar — the
 * `lenses/Lens.kt` shape verbatim with the nine shared catalog filter slots replaced by ONE
 * query text. A PRIVATE saved query is visible only to its creator; a PUBLIC one is visible to
 * every authenticated user but stays creator-only mutable (ADMIN gets no special access, the
 * standing rule).
 *
 * The query text is validated STRUCTURALLY and must PARSE (syntax + the supported Cypher
 * subset) — schema validity (unknown blueprints/relations/properties) is deliberately NOT
 * checked at save time: blueprints change, and a stale saved query simply shows its
 * diagnostics the next time it is applied.
 */
enum class SavedEntityQueryVisibility { PRIVATE, PUBLIC }

@Serializable
data class SavedEntityQuery(
    val id: UInt,
    val name: String,
    val visibility: SavedEntityQueryVisibility,
    val query: String,
    val createdBy: UInt,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class SavedEntityQueryList(val items: List<SavedEntityQuery>)

@Serializable
data class SavedEntityQueryRequest(
    val name: String,
    val visibility: SavedEntityQueryVisibility,
    val query: String,
)

const val MAX_SAVED_ENTITY_QUERY_NAME_LENGTH = 100

/**
 * Trims the name (the `sanitizeSingleLine` convention — control characters → 400) and the
 * query text (`sanitizeMultiLine` — newlines/tabs allowed, other control characters → 400).
 */
fun sanitizedSavedEntityQueryRequest(request: SavedEntityQueryRequest): SavedEntityQueryRequest =
    SavedEntityQueryRequest(
        name = sanitizeSingleLine(request.name, "name"),
        visibility = request.visibility,
        query = sanitizeMultiLine(request.query, "query"),
    )

/**
 * The saved-query validation rules — enforced by the route AND re-checked by the service (the
 * lens idiom). A query that fails to parse throws [EntityQueryInvalidException] carrying the
 * full diagnostics list rather than a plain [BadRequestException] — the one findings-bearing
 * `400` shape this feature reuses from `entities/EntityService.kt`'s own query filter.
 */
fun validateSavedEntityQueryRequest(request: SavedEntityQueryRequest) {
    if (request.name.isEmpty()) throw BadRequestException("name must not be blank")
    if (request.name.length > MAX_SAVED_ENTITY_QUERY_NAME_LENGTH) {
        throw BadRequestException("name must be at most $MAX_SAVED_ENTITY_QUERY_NAME_LENGTH characters")
    }
    if (request.query.isEmpty()) throw BadRequestException("query must not be blank")
    if (request.query.length > MAX_QUERY_LENGTH) {
        throw BadRequestException("query must be at most $MAX_QUERY_LENGTH characters")
    }
    try {
        parseEntityQuery(request.query)
    } catch (cause: QueryException) {
        throw EntityQueryInvalidException(cause.diagnostics).apply { initCause(cause) }
    }
}
