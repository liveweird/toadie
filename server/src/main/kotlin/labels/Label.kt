package ch.nokillswit.labels

import ch.nokillswit.catalog.MAX_ENTITY_PART_LENGTH
import ch.nokillswit.catalog.MAX_KEY_PREFIX_LENGTH
import ch.nokillswit.catalog.canonicalizedKinds
import ch.nokillswit.catalog.validateAllowedKinds
import ch.nokillswit.catalog.validateKey
import ch.nokillswit.catalog.validateNamePart
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The ADMIN-curated label registry's wire shapes. One label = one allowed `metadata.labels`
 * key with its CLOSED value list and the entity kinds it may be applied to — the registry is
 * the whitelist every catalog-file write's labels are checked against (strictly, no
 * grandfathering; an empty registry means no file may carry labels). Key and value grammar
 * come from the descriptor format (`catalog/CatalogFileValidation.kt` owns the rules).
 */
@Serializable
data class LabelResponse(
    val id: UInt,
    val key: String,
    val values: List<String>,
    val kinds: List<String>,
)

@Serializable
data class LabelList(val items: List<LabelResponse>)

@Serializable
data class LabelRequest(
    val key: String,
    val values: List<String>,
    val kinds: List<String>,
)

const val MAX_LABELS = 200
const val MAX_LABEL_VALUES = 100
const val MAX_LABEL_KEY_LENGTH = MAX_KEY_PREFIX_LENGTH + 1 + MAX_ENTITY_PART_LENGTH

/**
 * Trims the payload's scalars and normalizes a case-variant kind to its canonical casing
 * (`api` → `API`) plus the canonical [SUPPORTED_KINDS] order. Keys and values keep their
 * case — the grammar allows uppercase name parts, and silently rewriting what the admin
 * typed would mask a mistake (the sanitizer convention).
 */
fun sanitizedLabelRequest(request: LabelRequest): LabelRequest = LabelRequest(
    key = request.key.trim(),
    values = request.values.map { it.trim() },
    kinds = canonicalizedKinds(request.kinds),
)

/** The registry's validation rules — enforced by the route AND re-checked by the service. */
fun validateLabelRequest(request: LabelRequest) {
    validateKey(request.key, "key")
    if (request.values.isEmpty()) throw BadRequestException("values must have at least one entry")
    if (request.values.size > MAX_LABEL_VALUES) {
        throw BadRequestException("values must have at most $MAX_LABEL_VALUES entries")
    }
    request.values.forEach { validateNamePart(it, "values entry '$it'") }
    // Case-folded duplicate detection: "Backend" next to "backend" would be a confusing twin.
    val foldedValues = request.values.map { it.lowercase() }
    if (foldedValues.size != foldedValues.toSet().size) {
        throw BadRequestException("values must not contain duplicates")
    }
    // No duplicate check needed: the sanitizer's canonical-order rebuild already dedupes
    // supported kinds (and duplicate UNKNOWN kinds die inside validateAllowedKinds).
    validateAllowedKinds(request.kinds)
}
