package ch.nokillswit.types

import ch.nokillswit.catalog.SUPPORTED_KINDS
import ch.nokillswit.catalog.TYPE_BEARING_KINDS
import ch.nokillswit.catalog.validateSingleWord
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The ADMIN-curated per-kind type dictionaries' wire shapes — an INTERNAL Toadie constraint
 * on the open `spec.type` field (Backstage leaves it a free string): one row = ONE kind's
 * list of allowed types. The dictionaries are INDEPENDENT — the same value may be allowed
 * for several kinds (unlike tags' one-category-per-tag rule, there is no cross-row check).
 * The registry is the whitelist every catalog-file write's `spec.type` is checked against
 * (strictly, no grandfathering; a kind with no active row allows NO types — for
 * required-type kinds that means no file of that kind can be saved until the list exists).
 * Type values follow the exact spec.type rule (`validateSingleWord` — catalog owns it), so
 * a registered type is always saveable. Only [TYPE_BEARING_KINDS] may hold a dictionary —
 * User's spec has no type field.
 */
@Serializable
data class EntityTypesResponse(
    val id: UInt,
    val kind: String,
    val types: List<String>,
)

@Serializable
data class EntityTypesList(val items: List<EntityTypesResponse>)

@Serializable
data class EntityTypesRequest(
    val kind: String,
    val types: List<String>,
)

const val MAX_KIND_TYPES = 100

/**
 * Trims the payload's scalars and normalizes the kind to canonical casing (an unknown kind
 * passes through trimmed for [validateEntityTypesRequest] to reject by name). Types keep
 * their exact (trimmed) form — silently rewriting what the admin typed would mask a mistake
 * (the sanitizer convention).
 */
fun sanitizedEntityTypesRequest(request: EntityTypesRequest): EntityTypesRequest = EntityTypesRequest(
    kind = SUPPORTED_KINDS.firstOrNull { it.equals(request.kind.trim(), ignoreCase = true) }
        ?: request.kind.trim(),
    types = request.types.map { it.trim() },
)

/** The registry's validation rules — enforced by the route AND re-checked by the service. */
fun validateEntityTypesRequest(request: EntityTypesRequest) {
    if (request.kind !in TYPE_BEARING_KINDS) {
        throw BadRequestException(
            "kind must be one of ${TYPE_BEARING_KINDS.joinToString()} (kinds whose spec carries a type)",
        )
    }
    if (request.types.isEmpty()) throw BadRequestException("types must have at least one entry")
    if (request.types.size > MAX_KIND_TYPES) {
        throw BadRequestException("types must have at most $MAX_KIND_TYPES entries")
    }
    request.types.forEach { validateSingleWord(it, "types entry '$it'") }
    // Case-folded duplicate detection — "Service" beside "service" would be a trap.
    val folded = request.types.map { it.lowercase() }
    if (folded.size != folded.toSet().size) {
        throw BadRequestException("types must not contain duplicates")
    }
}
