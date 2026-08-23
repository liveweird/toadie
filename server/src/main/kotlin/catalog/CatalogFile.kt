package ch.nokillswit.catalog

import ch.nokillswit.infra.paging.PageResponse
import io.ktor.server.plugins.BadRequestException
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.Serializable

/**
 * One catalog-info.yaml document — a Backstage Component entity (the only kind the editor
 * supports yet; `kind` stays off the wire and fixed to `Component` until a second kind makes it
 * an additive field). Shape and validation rules mirror the descriptor reference
 * (`.claude/docs/backstage-descriptor-format.md`); references are validated by GRAMMAR only —
 * resolving them against other stored files is the future cross-check feature.
 */
@Serializable
data class CatalogFile(
    val metadata: CatalogFileMetadata,
    val spec: ComponentSpec,
)

@Serializable
data class CatalogFileMetadata(
    val name: String,
    // Folded to lowercase by [sanitizedCatalogFile] (Backstage renders namespaces lowercase).
    val namespace: String = DEFAULT_NAMESPACE,
    val title: String? = null,
    val description: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val links: List<CatalogLink> = emptyList(),
)

@Serializable
data class CatalogLink(
    val url: String,
    val title: String? = null,
    val icon: String? = null,
)

@Serializable
data class ComponentSpec(
    val type: String,
    val lifecycle: String,
    val owner: String,
    val system: String? = null,
    val subcomponentOf: String? = null,
    val providesApis: List<String> = emptyList(),
    val consumesApis: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val dependencyOf: List<String> = emptyList(),
)

const val DEFAULT_NAMESPACE = "default"

// Backstage's own limits where it defines them (63/253 — the Kubernetes-derived rules); the
// title/description/annotation-value/link-title caps and the collection caps are Toadie's own
// sanity ceilings, declared as maxLength/maxItems in the OpenAPI spec.
const val MAX_ENTITY_PART_LENGTH = 63
const val MAX_KEY_PREFIX_LENGTH = 253
const val MAX_TITLE_LENGTH = 200
const val MAX_DESCRIPTION_LENGTH = 2000
const val MAX_ANNOTATION_VALUE_LENGTH = 5000
const val MAX_LINK_TITLE_LENGTH = 100
const val MAX_TAGS = 50
const val MAX_MAP_ENTRIES = 50
const val MAX_LINKS = 20
const val MAX_REFS = 100

// [a-zA-Z0-9] runs joined by single [-_.] separators — metadata.name, label values, key name
// parts, and link icons all share this shape.
private val NAME_REGEX = Regex("[A-Za-z0-9]+(?:[-_.][A-Za-z0-9]+)*")

// Lowercase alphanumeric runs joined by single dashes (validated post-folding).
private val NAMESPACE_REGEX = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

// Tags: lowercase [a-z0-9:+#] runs joined by single dashes (e.g. "java", "c++", "csharp:v2").
private val TAG_REGEX = Regex("[a-z0-9:+#]+(?:-[a-z0-9:+#]+)*")

// Label/annotation key prefix: a lowercase domain name (dot-joined dash-separated labels).
private val KEY_PREFIX_REGEX = Regex("[a-z0-9]+(?:-[a-z0-9]+)*(?:\\.[a-z0-9]+(?:-[a-z0-9]+)*)*")

// Entity-reference kind: alphabetic head, alphanumeric tail (Component, API, my-org's kinds…).
private val KIND_REGEX = Regex("[A-Za-z][A-Za-z0-9]*")

// Annotations the catalog server writes itself — a creation UI must never emit them.
private val SERVER_WRITTEN_ANNOTATIONS = setOf(
    "backstage.io/managed-by-location",
    "backstage.io/managed-by-origin-location",
    "backstage.io/orphan",
)

/**
 * Trims every scalar field and list entry and folds `namespace` to lowercase (blank → the
 * `default` namespace), so validation and storage always see canonical text. Map keys/values
 * are validated as sent — silently rewriting a key the user typed would mask the mistake.
 */
fun sanitizedCatalogFile(file: CatalogFile): CatalogFile = file.copy(
    metadata = file.metadata.copy(
        name = file.metadata.name.trim(),
        namespace = file.metadata.namespace.trim().lowercase().ifEmpty { DEFAULT_NAMESPACE },
        title = file.metadata.title?.trim(),
        description = file.metadata.description?.trim(),
        tags = file.metadata.tags.map { it.trim() },
        links = file.metadata.links.map {
            it.copy(url = it.url.trim(), title = it.title?.trim(), icon = it.icon?.trim())
        },
    ),
    spec = file.spec.copy(
        type = file.spec.type.trim(),
        lifecycle = file.spec.lifecycle.trim(),
        owner = file.spec.owner.trim(),
        system = file.spec.system?.trim(),
        subcomponentOf = file.spec.subcomponentOf?.trim(),
        providesApis = file.spec.providesApis.map { it.trim() },
        consumesApis = file.spec.consumesApis.map { it.trim() },
        dependsOn = file.spec.dependsOn.map { it.trim() },
        dependencyOf = file.spec.dependencyOf.map { it.trim() },
    ),
)

/**
 * The single home of the catalog-file payload rules, enforced by the route (the API 400 path)
 * and re-checked by [CatalogFileService] (so direct service callers stay guarded) — one source,
 * no drift. Expects sanitized input ([sanitizedCatalogFile]).
 */
fun validateCatalogFile(file: CatalogFile) {
    validateMetadata(file.metadata)
    validateSpec(file.spec)
}

private fun validateMetadata(metadata: CatalogFileMetadata) {
    validateNamePart(metadata.name, "metadata.name")
    if (!NAMESPACE_REGEX.matches(metadata.namespace) || metadata.namespace.length > MAX_ENTITY_PART_LENGTH) {
        throw BadRequestException(
            "metadata.namespace must be 1-$MAX_ENTITY_PART_LENGTH lowercase alphanumeric characters " +
                "with single dash separators",
        )
    }
    metadata.title?.let {
        if (it.isEmpty() || it.length > MAX_TITLE_LENGTH) {
            throw BadRequestException("metadata.title must be 1-$MAX_TITLE_LENGTH characters")
        }
    }
    metadata.description?.let {
        if (it.isEmpty() || it.length > MAX_DESCRIPTION_LENGTH) {
            throw BadRequestException("metadata.description must be 1-$MAX_DESCRIPTION_LENGTH characters")
        }
    }
    validateTags(metadata.tags)
    validateLabels(metadata.labels)
    validateAnnotations(metadata.annotations)
    validateLinks(metadata.links)
}

private fun validateSpec(spec: ComponentSpec) {
    validateSingleWord(spec.type, "spec.type")
    validateSingleWord(spec.lifecycle, "spec.lifecycle")
    validateEntityRef(spec.owner, "spec.owner")
    spec.system?.let { validateEntityRef(it, "spec.system") }
    spec.subcomponentOf?.let { validateEntityRef(it, "spec.subcomponentOf") }
    validateRefArray(spec.providesApis, "spec.providesApis")
    validateRefArray(spec.consumesApis, "spec.consumesApis")
    validateRefArray(spec.dependsOn, "spec.dependsOn")
    validateRefArray(spec.dependencyOf, "spec.dependencyOf")
}

private fun validateNamePart(value: String, field: String) {
    if (value.length !in 1..MAX_ENTITY_PART_LENGTH || !NAME_REGEX.matches(value)) {
        throw BadRequestException(
            "$field must be 1-$MAX_ENTITY_PART_LENGTH alphanumeric characters " +
                "with single [-_.] separators",
        )
    }
}

private fun validateSingleWord(value: String, field: String) {
    if (value.isEmpty() || value.length > MAX_ENTITY_PART_LENGTH || value.any { it.isWhitespace() }) {
        throw BadRequestException("$field must be 1-$MAX_ENTITY_PART_LENGTH characters without whitespace")
    }
}

private fun validateTags(tags: List<String>) {
    if (tags.size > MAX_TAGS) throw BadRequestException("metadata.tags must have at most $MAX_TAGS entries")
    for (tag in tags) {
        if (tag.length !in 1..MAX_ENTITY_PART_LENGTH || !TAG_REGEX.matches(tag)) {
            throw BadRequestException(
                "metadata.tags entry '$tag' must be 1-$MAX_ENTITY_PART_LENGTH characters of " +
                    "[a-z0-9:+#] with single dash separators",
            )
        }
    }
}

private fun validateLabels(labels: Map<String, String>) {
    if (labels.size > MAX_MAP_ENTRIES) {
        throw BadRequestException("metadata.labels must have at most $MAX_MAP_ENTRIES entries")
    }
    for ((key, value) in labels) {
        validateKey(key, "metadata.labels")
        validateNamePart(value, "metadata.labels['$key'] value")
    }
}

private fun validateAnnotations(annotations: Map<String, String>) {
    if (annotations.size > MAX_MAP_ENTRIES) {
        throw BadRequestException("metadata.annotations must have at most $MAX_MAP_ENTRIES entries")
    }
    for ((key, value) in annotations) {
        validateKey(key, "metadata.annotations")
        if (key in SERVER_WRITTEN_ANNOTATIONS) {
            throw BadRequestException("metadata.annotations key '$key' is written by the catalog server itself")
        }
        if (value.length > MAX_ANNOTATION_VALUE_LENGTH) {
            throw BadRequestException(
                "metadata.annotations['$key'] value must be at most $MAX_ANNOTATION_VALUE_LENGTH characters",
            )
        }
    }
}

// A label/annotation key: an optional lowercase-domain prefix + '/', then a name-shaped part.
private fun validateKey(key: String, field: String) {
    val slash = key.indexOf('/')
    if (slash != key.lastIndexOf('/')) {
        throw BadRequestException("$field key '$key' must contain at most one '/'")
    }
    val prefix = if (slash >= 0) key.substring(0, slash) else null
    val name = if (slash >= 0) key.substring(slash + 1) else key
    prefix?.let {
        if (it.length !in 1..MAX_KEY_PREFIX_LENGTH || !KEY_PREFIX_REGEX.matches(it)) {
            throw BadRequestException(
                "$field key prefix '$it' must be a lowercase domain name of at most " +
                    "$MAX_KEY_PREFIX_LENGTH characters",
            )
        }
    }
    if (name.length !in 1..MAX_ENTITY_PART_LENGTH || !NAME_REGEX.matches(name)) {
        throw BadRequestException(
            "$field key name '$name' must be 1-$MAX_ENTITY_PART_LENGTH alphanumeric characters " +
                "with single [-_.] separators",
        )
    }
}

private fun validateLinks(links: List<CatalogLink>) {
    if (links.size > MAX_LINKS) throw BadRequestException("metadata.links must have at most $MAX_LINKS entries")
    for (link in links) {
        val absolute = try {
            URI(link.url).isAbsolute
        } catch (_: URISyntaxException) {
            false
        }
        if (link.url.isEmpty() || !absolute) {
            throw BadRequestException("metadata.links url '${link.url}' must be an absolute URI")
        }
        link.title?.let {
            if (it.isEmpty() || it.length > MAX_LINK_TITLE_LENGTH) {
                throw BadRequestException("metadata.links title must be 1-$MAX_LINK_TITLE_LENGTH characters")
            }
        }
        link.icon?.let { validateNamePart(it, "metadata.links icon") }
    }
}

private fun validateRefArray(refs: List<String>, field: String) {
    if (refs.size > MAX_REFS) throw BadRequestException("$field must have at most $MAX_REFS entries")
    for (ref in refs) validateEntityRef(ref, field)
}

/**
 * Validates the entity-reference grammar `[kind:][namespace/]name` (format only — dangling
 * references are the future cross-check feature's concern, not a 400 here).
 */
fun validateEntityRef(ref: String, field: String) {
    val colon = ref.indexOf(':')
    if (colon != ref.lastIndexOf(':')) {
        throw BadRequestException("$field reference '$ref' must contain at most one ':'")
    }
    val kind = if (colon >= 0) ref.substring(0, colon) else null
    val rest = if (colon >= 0) ref.substring(colon + 1) else ref
    kind?.let {
        if (it.length !in 1..MAX_ENTITY_PART_LENGTH || !KIND_REGEX.matches(it)) {
            throw BadRequestException("$field reference '$ref' has an invalid kind '$it'")
        }
    }
    val slash = rest.indexOf('/')
    if (slash != rest.lastIndexOf('/')) {
        throw BadRequestException("$field reference '$ref' must contain at most one '/'")
    }
    val namespace = if (slash >= 0) rest.substring(0, slash) else null
    val name = if (slash >= 0) rest.substring(slash + 1) else rest
    namespace?.let {
        // References match case-insensitively in Backstage, so accept any case here.
        if (it.length !in 1..MAX_ENTITY_PART_LENGTH || !NAMESPACE_REGEX.matches(it.lowercase())) {
            throw BadRequestException("$field reference '$ref' has an invalid namespace '$it'")
        }
    }
    if (name.length !in 1..MAX_ENTITY_PART_LENGTH || !NAME_REGEX.matches(name)) {
        throw BadRequestException("$field reference '$ref' has an invalid name '$name'")
    }
}

@Serializable
data class CatalogFileResponse(
    val id: UInt,
    val metadata: CatalogFileMetadata,
    val spec: ComponentSpec,
    val createdBy: UInt,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The flatter list-row shape: identity + the display fields, creator resolved via join. */
@Serializable
data class CatalogFileListItem(
    val id: UInt,
    val name: String,
    val namespace: String,
    val title: String?,
    val type: String,
    val lifecycle: String,
    val owner: String,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val updatedAt: Long,
)

typealias CatalogFilePageResponse = PageResponse<CatalogFileListItem>
