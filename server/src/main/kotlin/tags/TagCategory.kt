package ch.nokillswit.tags

import ch.nokillswit.catalog.MAX_ENTITY_PART_LENGTH
import ch.nokillswit.catalog.canonicalizedKinds
import ch.nokillswit.catalog.validateAllowedKinds
import ch.nokillswit.catalog.validateTagValue
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The ADMIN-curated tag categories' wire shapes — an INTERNAL Toadie concept (not in the
 * Backstage schema): one category = a display name, its >=1 tag values, and the entity kinds
 * those tags may be applied to. The registry is the whitelist every catalog-file write's
 * `metadata.tags` is checked against (strictly, no grandfathering; an empty registry means
 * no file may carry tags), and each tag belongs to exactly ONE category (cross-category
 * clashes are the service's 409). Tag grammar comes from the descriptor format
 * (`catalog/CatalogFileValidation.kt` owns the rule); the category NAME is Toadie's own —
 * a free single-line display name.
 */
@Serializable
data class TagCategoryResponse(
    val id: UInt,
    val name: String,
    val tags: List<String>,
    val kinds: List<String>,
)

@Serializable
data class TagCategoryList(val items: List<TagCategoryResponse>)

@Serializable
data class TagCategoryRequest(
    val name: String,
    val tags: List<String>,
    val kinds: List<String>,
)

const val MAX_TAG_CATEGORIES = 200
const val MAX_CATEGORY_TAGS = 100
const val MAX_CATEGORY_NAME_LENGTH = MAX_ENTITY_PART_LENGTH

/**
 * Trims the payload's scalars (the name via the control-character-rejecting single-line
 * sanitizer) and normalizes kinds to canonical casing and [ch.nokillswit.catalog.SUPPORTED_KINDS]
 * order. Tags keep their exact (trimmed) form — the grammar is lowercase-only anyway, and
 * silently rewriting what the admin typed would mask a mistake (the sanitizer convention).
 */
fun sanitizedTagCategoryRequest(request: TagCategoryRequest): TagCategoryRequest = TagCategoryRequest(
    name = sanitizeSingleLine(request.name, "name"),
    tags = request.tags.map { it.trim() },
    kinds = canonicalizedKinds(request.kinds),
)

/** The registry's validation rules — enforced by the route AND re-checked by the service. */
fun validateTagCategoryRequest(request: TagCategoryRequest) {
    if (request.name.isEmpty() || request.name.length > MAX_CATEGORY_NAME_LENGTH) {
        throw BadRequestException("name must be 1-$MAX_CATEGORY_NAME_LENGTH characters")
    }
    if (request.tags.isEmpty()) throw BadRequestException("tags must have at least one entry")
    if (request.tags.size > MAX_CATEGORY_TAGS) {
        throw BadRequestException("tags must have at most $MAX_CATEGORY_TAGS entries")
    }
    request.tags.forEach { validateTagValue(it, "tags entry '$it'") }
    // Case-folded duplicate detection (defensive — the grammar is lowercase-only already).
    val folded = request.tags.map { it.lowercase() }
    if (folded.size != folded.toSet().size) {
        throw BadRequestException("tags must not contain duplicates")
    }
    validateAllowedKinds(request.kinds)
}
