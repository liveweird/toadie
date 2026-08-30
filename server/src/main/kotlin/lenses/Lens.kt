package ch.nokillswit.lenses

import ch.nokillswit.catalog.SUPPORTED_KINDS
import ch.nokillswit.catalog.canonicalizedKinds
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * Lenses: named, saveable snapshots of the shared catalog filter set (the nine slots the
 * Hierarchy/Files/Graph/Errors views declare). A PRIVATE lens is visible only to its
 * creator; a PUBLIC lens is visible to every authenticated user but stays creator-only
 * mutable — the "ADMIN gets no special content access" rule applies here too.
 *
 * The filter payload is validated STRUCTURALLY only (lengths, control characters, the kind
 * whitelist, labelValue-requires-label — mirroring the list API's own param rules), never
 * against the registries: registry values drift, and a lens holding a since-removed tag
 * simply matches nothing until edited.
 */
enum class LensVisibility { PRIVATE, PUBLIC }

/** The nine shared filter slots, all optional — absent means "not filtered on". */
@Serializable
data class LensFilters(
    val name: String? = null,
    val namespace: String? = null,
    /** The visible-kinds set (any-of); absent = every kind visible. */
    val kind: List<String>? = null,
    val tag: String? = null,
    val type: String? = null,
    val lifecycle: String? = null,
    val owner: String? = null,
    val label: String? = null,
    val labelValue: List<String>? = null,
)

@Serializable
data class LensResponse(
    val id: UInt,
    val name: String,
    val visibility: LensVisibility,
    val filters: LensFilters,
    val createdBy: UInt,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class LensList(val items: List<LensResponse>)

@Serializable
data class LensRequest(
    val name: String,
    val visibility: LensVisibility,
    val filters: LensFilters,
)

const val MAX_LENS_NAME_LENGTH = 100
const val MAX_LENS_FILTER_VALUE_LENGTH = 255
const val MAX_LENS_LABEL_VALUES = 100

/**
 * Trims the name and every string filter value (control characters → 400, the
 * sanitizeSingleLine convention), normalizes empty strings/lists to absent, and rebuilds a
 * case-variant kind list into canonical casing and order (`api` → `API`).
 */
fun sanitizedLensRequest(request: LensRequest): LensRequest = LensRequest(
    name = sanitizeSingleLine(request.name, "name"),
    visibility = request.visibility,
    filters = LensFilters(
        name = sanitizedFilterValue(request.filters.name, "filters.name"),
        namespace = sanitizedFilterValue(request.filters.namespace, "filters.namespace"),
        kind = request.filters.kind?.let { canonicalizedKinds(it) }?.takeIf { it.isNotEmpty() },
        tag = sanitizedFilterValue(request.filters.tag, "filters.tag"),
        type = sanitizedFilterValue(request.filters.type, "filters.type"),
        lifecycle = sanitizedFilterValue(request.filters.lifecycle, "filters.lifecycle"),
        owner = sanitizedFilterValue(request.filters.owner, "filters.owner"),
        label = sanitizedFilterValue(request.filters.label, "filters.label"),
        labelValue = request.filters.labelValue
            ?.map { sanitizeSingleLine(it, "filters.labelValue entry") }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() },
    ),
)

private fun sanitizedFilterValue(value: String?, field: String): String? =
    value?.let { sanitizeSingleLine(it, field) }?.takeIf { it.isNotEmpty() }

/** The lens validation rules — enforced by the route AND re-checked by the service. */
fun validateLensRequest(request: LensRequest) {
    if (request.name.isEmpty()) throw BadRequestException("name must not be blank")
    if (request.name.length > MAX_LENS_NAME_LENGTH) {
        throw BadRequestException("name must be at most $MAX_LENS_NAME_LENGTH characters")
    }
    val filters = request.filters
    listOf(
        "filters.name" to filters.name,
        "filters.namespace" to filters.namespace,
        "filters.tag" to filters.tag,
        "filters.type" to filters.type,
        "filters.lifecycle" to filters.lifecycle,
        "filters.owner" to filters.owner,
        "filters.label" to filters.label,
    ).forEach { (field, value) ->
        if (value != null && value.length > MAX_LENS_FILTER_VALUE_LENGTH) {
            throw BadRequestException("$field must be at most $MAX_LENS_FILTER_VALUE_LENGTH characters")
        }
    }
    filters.kind?.forEach {
        if (it !in SUPPORTED_KINDS) {
            throw BadRequestException("Unknown kind: $it (allowed: ${SUPPORTED_KINDS.joinToString()})")
        }
    }
    filters.labelValue?.let { values ->
        // The list API's own rule: labelValue only travels alongside label.
        if (filters.label == null) throw BadRequestException("filters.labelValue requires filters.label")
        if (values.size > MAX_LENS_LABEL_VALUES) {
            throw BadRequestException("filters.labelValue must have at most $MAX_LENS_LABEL_VALUES entries")
        }
        values.forEach {
            if (it.length > MAX_LENS_FILTER_VALUE_LENGTH) {
                throw BadRequestException(
                    "filters.labelValue entries must be at most $MAX_LENS_FILTER_VALUE_LENGTH characters",
                )
            }
        }
    }
}
