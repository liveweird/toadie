package ch.nokillswit.catalog

import io.ktor.server.plugins.BadRequestException
import java.net.URI
import java.net.URISyntaxException

/**
 * The descriptor-format rulebook for [CatalogFile]: the sanitizer, the per-kind spec table,
 * and every validator — split from the wire DTOs because the two halves change for different
 * reasons (this file tracks `.claude/docs/backstage-descriptor-format.md`; the DTO file tracks
 * the API). [validateCatalogFile] stays the single source, enforced by the route AND re-checked
 * by the service.
 */
// Backstage's own limits where it defines them (63/253 — the Kubernetes-derived rules); the
// title/description/annotation-value/link-title/definition/profile caps and the collection caps
// are Toadie's own sanity ceilings, declared as maxLength/maxItems in the OpenAPI spec.
const val MAX_ENTITY_PART_LENGTH = 63
const val MAX_KEY_PREFIX_LENGTH = 253
const val MAX_TITLE_LENGTH = 200
const val MAX_DESCRIPTION_LENGTH = 2000
const val MAX_ANNOTATION_VALUE_LENGTH = 5000
const val MAX_LINK_TITLE_LENGTH = 100
const val MAX_DEFINITION_LENGTH = 100_000
const val MAX_PROFILE_EMAIL_LENGTH = 254
const val MAX_TAGS = 50
const val MAX_MAP_ENTRIES = 50
const val MAX_LINKS = 20
const val MAX_REFS = 100

// [a-zA-Z0-9] runs joined by single [-_.] separators — metadata.name, label values, key name
// parts, and link icons all share this shape.
private val NAME_REGEX = Regex("[A-Za-z0-9]+(?:[-_.][A-Za-z0-9]+)*")

// Lowercase alphanumeric runs joined by single dashes (validated post-folding). Internal:
// the namespaces dictionary (dictionaries/Dictionary.kt) validates its values against the
// same grammar — the descriptor format owns the rule, the dictionary borrows it.
internal val NAMESPACE_REGEX = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

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
 * Trims every scalar field and list entry, folds `namespace` to lowercase (blank → the
 * `default` namespace), and canonicalizes a case-variant supported kind (`api` → `API`).
 * Map keys/values are validated as sent — silently rewriting a key the user typed would mask
 * the mistake.
 */
fun sanitizedCatalogFile(file: CatalogFile): CatalogFile = file.copy(
    kind = SUPPORTED_KINDS.firstOrNull { it.equals(file.kind.trim(), ignoreCase = true) } ?: file.kind.trim(),
    metadata = file.metadata.copy(
        name = file.metadata.name.trim(),
        // Blank survives sanitization: it means "the ADMIN-flagged default namespace", which
        // only the service can resolve (a DB read) — see CatalogFileService.resolveNamespace.
        namespace = file.metadata.namespace.trim().lowercase(),
        title = file.metadata.title?.trim(),
        description = file.metadata.description?.trim(),
        tags = file.metadata.tags.map { it.trim() },
        links = file.metadata.links.map {
            it.copy(url = it.url.trim(), title = it.title?.trim(), icon = it.icon?.trim())
        },
    ),
    spec = file.spec.copy(
        type = file.spec.type?.trim(),
        lifecycle = file.spec.lifecycle?.trim(),
        owner = file.spec.owner?.trim(),
        system = file.spec.system?.trim(),
        subcomponentOf = file.spec.subcomponentOf?.trim(),
        providesApis = file.spec.providesApis.map { it.trim() },
        consumesApis = file.spec.consumesApis.map { it.trim() },
        dependsOn = file.spec.dependsOn.map { it.trim() },
        dependencyOf = file.spec.dependencyOf.map { it.trim() },
        definition = file.spec.definition?.trim(),
        profile = file.spec.profile?.let {
            it.copy(displayName = it.displayName?.trim(), email = it.email?.trim(), picture = it.picture?.trim())
        },
        parent = file.spec.parent?.trim(),
        children = file.spec.children?.map { it.trim() },
        members = file.spec.members.map { it.trim() },
        memberOf = file.spec.memberOf?.map { it.trim() },
        domain = file.spec.domain?.trim(),
        subdomainOf = file.spec.subdomainOf?.trim(),
    ),
)

// ─── The per-kind spec table ────────────────────────────────────────────────────────────────

/** One spec field: how to detect it's present and how to validate it when it is. */
private class SpecField(
    val name: String,
    val isPresent: (EntitySpec) -> Boolean,
    val validate: (EntitySpec) -> Unit,
)

private fun scalar(name: String, get: (EntitySpec) -> String?, validate: (String) -> Unit) =
    SpecField(name, { get(it) != null }, { get(it)?.let(validate) })

private fun refList(name: String, get: (EntitySpec) -> List<String>?) =
    SpecField(
        name,
        { !get(it).isNullOrEmpty() },
        { spec -> get(spec)?.let { validateRefArray(it, "spec.$name") } },
    )

private val SPEC_FIELDS: List<SpecField> = listOf(
    scalar("type", { it.type }) { validateSingleWord(it, "spec.type") },
    scalar("lifecycle", { it.lifecycle }) { validateSingleWord(it, "spec.lifecycle") },
    scalar("owner", { it.owner }) { validateEntityRef(it, "spec.owner") },
    scalar("system", { it.system }) { validateEntityRef(it, "spec.system") },
    scalar("subcomponentOf", { it.subcomponentOf }) { validateEntityRef(it, "spec.subcomponentOf") },
    refList("providesApis") { it.providesApis },
    refList("consumesApis") { it.consumesApis },
    refList("dependsOn") { it.dependsOn },
    refList("dependencyOf") { it.dependencyOf },
    scalar("definition", { it.definition }) { validateDefinition(it) },
    SpecField("profile", { it.profile != null }, { it.profile?.let(::validateProfile) }),
    scalar("parent", { it.parent }) { validateEntityRef(it, "spec.parent") },
    refList("children") { it.children },
    refList("members") { it.members },
    refList("memberOf") { it.memberOf },
    scalar("domain", { it.domain }) { validateEntityRef(it, "spec.domain") },
    scalar("subdomainOf", { it.subdomainOf }) { validateEntityRef(it, "spec.subdomainOf") },
)

// Which spec fields each kind may carry, and which it must (the descriptor reference's rules).
// children/memberOf requiredness means PRESENT — an empty list satisfies it (checked apart,
// since refList presence means non-empty).
private val ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
    "Component" to setOf(
        "type", "lifecycle", "owner", "system", "subcomponentOf",
        "providesApis", "consumesApis", "dependsOn", "dependencyOf",
    ),
    "API" to setOf("type", "lifecycle", "owner", "system", "definition"),
    "System" to setOf("owner", "domain", "type"),
    "Domain" to setOf("owner", "subdomainOf", "type"),
    "Resource" to setOf("type", "owner", "system", "dependsOn", "dependencyOf"),
    "Group" to setOf("type", "profile", "parent", "children", "members"),
    "User" to setOf("profile", "memberOf"),
)

private val REQUIRED_FIELDS: Map<String, Set<String>> = mapOf(
    "Component" to setOf("type", "lifecycle", "owner"),
    "API" to setOf("type", "lifecycle", "owner", "definition"),
    "System" to setOf("owner"),
    "Domain" to setOf("owner"),
    "Resource" to setOf("type", "owner"),
    "Group" to setOf("type"),
    "User" to emptySet(),
)

/**
 * The single home of the catalog-file payload rules, enforced by the route (the API 400 path)
 * and re-checked by [CatalogFileService] (so direct service callers stay guarded) — one source,
 * no drift. Expects sanitized input ([sanitizedCatalogFile]).
 */
fun validateCatalogFile(file: CatalogFile) {
    if (file.kind !in SUPPORTED_KINDS) {
        throw BadRequestException("kind must be one of ${SUPPORTED_KINDS.joinToString()}")
    }
    validateMetadata(file.metadata)
    validateSpec(file.kind, file.spec)
}

private fun validateSpec(kind: String, spec: EntitySpec) {
    val allowed = ALLOWED_FIELDS.getValue(kind)
    val required = REQUIRED_FIELDS.getValue(kind)
    for (field in SPEC_FIELDS) {
        val present = field.isPresent(spec)
        if (present && field.name !in allowed) {
            throw BadRequestException("spec.${field.name} does not apply to kind $kind")
        }
        if (!present && field.name in required) {
            throw BadRequestException("spec.${field.name} is required for kind $kind")
        }
        if (present) field.validate(spec)
    }
    // Backstage requires these PRESENT (an empty list is fine) — presence, not non-emptiness.
    if (kind == "Group" && spec.children == null) {
        throw BadRequestException("spec.children is required for kind Group (an empty list is allowed)")
    }
    if (kind == "User" && spec.memberOf == null) {
        throw BadRequestException("spec.memberOf is required for kind User (an empty list is allowed)")
    }
}

/** The recurring "$field must be 1-$max characters" rule — one thrower for all six users. */
private fun requireLength(value: String, field: String, max: Int) {
    if (value.isEmpty() || value.length > max) {
        throw BadRequestException("$field must be 1-$max characters")
    }
}

private fun validateDefinition(definition: String) =
    requireLength(definition, "spec.definition", MAX_DEFINITION_LENGTH)

private fun validateProfile(profile: EntityProfile) {
    profile.displayName?.let { requireLength(it, "spec.profile.displayName", MAX_TITLE_LENGTH) }
    profile.email?.let { requireLength(it, "spec.profile.email", MAX_PROFILE_EMAIL_LENGTH) }
    profile.picture?.let {
        if (!isAbsoluteUri(it)) {
            throw BadRequestException("spec.profile.picture must be an absolute URI")
        }
    }
}

private fun isAbsoluteUri(value: String): Boolean = try {
    value.isNotEmpty() && URI(value).isAbsolute
} catch (_: URISyntaxException) {
    false
}

// ─── Metadata rules (kind-independent) ──────────────────────────────────────────────────────

private fun validateMetadata(metadata: CatalogFileMetadata) {
    validateNamePart(metadata.name, "metadata.name")
    // Blank is legal here: it means the flagged default namespace, resolved (and its
    // existence enforced) by the service at write time — validation stays pure.
    if (metadata.namespace.isNotEmpty() &&
        (!NAMESPACE_REGEX.matches(metadata.namespace) || metadata.namespace.length > MAX_ENTITY_PART_LENGTH)
    ) {
        throw BadRequestException(
            "metadata.namespace must be 1-$MAX_ENTITY_PART_LENGTH lowercase alphanumeric characters " +
                "with single dash separators",
        )
    }
    metadata.title?.let { requireLength(it, "metadata.title", MAX_TITLE_LENGTH) }
    metadata.description?.let { requireLength(it, "metadata.description", MAX_DESCRIPTION_LENGTH) }
    validateTags(metadata.tags)
    validateLabels(metadata.labels)
    validateAnnotations(metadata.annotations)
    validateLinks(metadata.links)
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
    val (prefix, name) = splitRefOnce(key, '/')
        ?: throw BadRequestException("$field key '$key' must contain at most one '/'")
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
        if (link.url.isEmpty() || !isAbsoluteUri(link.url)) {
            throw BadRequestException("metadata.links url '${link.url}' must be an absolute URI")
        }
        link.title?.let { requireLength(it, "metadata.links title", MAX_LINK_TITLE_LENGTH) }
        link.icon?.let { validateNamePart(it, "metadata.links icon") }
    }
}

private fun validateRefArray(refs: List<String>, field: String) {
    if (refs.size > MAX_REFS) throw BadRequestException("$field must have at most $MAX_REFS entries")
    for (ref in refs) validateEntityRef(ref, field)
}

/**
 * Validates the entity-reference grammar `[kind:][namespace/]name` (format only — dangling
 * references are the cross-check feature's concern, not a 400 here). Splits with the same
 * [splitRefOnce] the lenient `parseRef` uses, so the strict and lenient parses of the grammar
 * can never disagree on structure.
 */
fun validateEntityRef(ref: String, field: String) {
    val (kind, rest) = splitRefOnce(ref, ':')
        ?: throw BadRequestException("$field reference '$ref' must contain at most one ':'")
    kind?.let {
        if (it.length !in 1..MAX_ENTITY_PART_LENGTH || !KIND_REGEX.matches(it)) {
            throw BadRequestException("$field reference '$ref' has an invalid kind '$it'")
        }
    }
    val (namespace, name) = splitRefOnce(rest, '/')
        ?: throw BadRequestException("$field reference '$ref' must contain at most one '/'")
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
