package ch.nokillswit.catalog

import kotlinx.serialization.Serializable

/**
 * Cross-checking: resolving the entity references stored files make against the workspace.
 * Pure logic — the service supplies the rows, the routes the transport. Semantics follow
 * `.claude/docs/backstage-descriptor-format.md`: per-field default kinds, an omitted namespace
 * defaults to the REFERENCING file's own namespace, and kind/namespace/name all match
 * case-insensitively. Findings never block saves — files legitimately reference entities that
 * arrive later (the Backstage model).
 */
@Serializable
enum class CrossCheckStatus {
    /** The reference should resolve to a stored file of its kind but no active one matches. */
    MISSING,

    /** A dependsOn/dependencyOf entry without a kind — Backstage cannot ingest those. */
    KIND_REQUIRED,

    /** The target kind is not stored in Toadie (Location, Template, custom kinds). */
    UNVERIFIABLE,
}

/** One problematic reference inside one document (the ad-hoc check's shape). */
@Serializable
data class DocumentCheckFinding(
    val field: String,
    val reference: String,
    val status: CrossCheckStatus,
)

@Serializable
data class DocumentCheckReport(
    val findings: List<DocumentCheckFinding>,
)

/** One problematic reference in the workspace report, tagged with its source file. */
@Serializable
data class CrossCheckFinding(
    val fileId: UInt,
    val fileName: String,
    val fileNamespace: String,
    val field: String,
    val reference: String,
    val status: CrossCheckStatus,
)

@Serializable
data class CrossCheckReport(
    val findings: List<CrossCheckFinding>,
    val checkedFiles: Int,
    val checkedReferences: Int,
)

/** What the checker needs to know about one stored file. */
data class CrossCheckSource(
    val id: UInt,
    val file: CatalogFile,
)

internal const val COMPONENT_KIND = "component"

/** The lowercased kinds the editor stores — references to them are RESOLVABLE. */
internal val STORED_KINDS: Set<String> = SUPPORTED_KINDS.map { it.lowercase() }.toSet()

// Per-field default kinds (the descriptor reference's context rules). dependsOn/dependencyOf
// map to null ON PURPOSE: Backstage gives them no default, so a kind-less entry is an error.
internal val REF_FIELD_DEFAULT_KINDS: Map<String, String?> = mapOf(
    "spec.owner" to "group",
    "spec.system" to "system",
    "spec.subcomponentOf" to COMPONENT_KIND,
    "spec.providesApis" to "api",
    "spec.consumesApis" to "api",
    "spec.dependsOn" to null,
    "spec.dependencyOf" to null,
    "spec.parent" to "group",
    "spec.children" to "group",
    "spec.members" to "user",
    "spec.memberOf" to "group",
    "spec.domain" to "domain",
    "spec.subdomainOf" to "domain",
)

/** A lowercased identity a reference can resolve to. */
data class EntityIdentity(val kind: String, val namespace: String, val name: String)

fun identityOf(file: CatalogFile) = EntityIdentity(
    kind = file.kind.lowercase(),
    namespace = file.metadata.namespace.lowercase(),
    name = file.metadata.name.lowercase(),
)

internal data class ParsedRef(val kind: String?, val namespace: String?, val name: String)

// Lenient on purpose: the ad-hoc check sees in-progress documents whose refs the form is
// already flagging — an unparsable ref is skipped here, never a 400.
internal fun parseRef(raw: String): ParsedRef? {
    val colon = raw.indexOf(':')
    if (colon != raw.lastIndexOf(':')) return null
    val kind = if (colon >= 0) raw.substring(0, colon) else null
    val rest = if (colon >= 0) raw.substring(colon + 1) else raw
    val slash = rest.indexOf('/')
    if (slash != rest.lastIndexOf('/')) return null
    val namespace = if (slash >= 0) rest.substring(0, slash) else null
    val name = if (slash >= 0) rest.substring(slash + 1) else rest
    if (kind?.isEmpty() == true || namespace?.isEmpty() == true || name.isEmpty()) return null
    return ParsedRef(kind, namespace, name)
}

// Kind-independent on purpose: validation guarantees a stored document carries only its
// kind's fields, so enumerating the whole superset is both simpler and correct (and for the
// ad-hoc check, a mid-edit foreign field still gets a useful verdict).
internal fun EntitySpec.refFields(): List<Pair<String, List<String>>> = listOf(
    "spec.owner" to listOfNotNull(owner),
    "spec.system" to listOfNotNull(system),
    "spec.subcomponentOf" to listOfNotNull(subcomponentOf),
    "spec.providesApis" to providesApis,
    "spec.consumesApis" to consumesApis,
    "spec.dependsOn" to dependsOn,
    "spec.dependencyOf" to dependencyOf,
    "spec.parent" to listOfNotNull(parent),
    "spec.children" to children.orEmpty(),
    "spec.members" to members,
    "spec.memberOf" to memberOf.orEmpty(),
    "spec.domain" to listOfNotNull(domain),
    "spec.subdomainOf" to listOfNotNull(subdomainOf),
)

/**
 * Checks one document's references against the given identity set. [referenceCounter] (when
 * provided) is incremented once per non-blank reference encountered, parsable or not.
 */
fun checkDocument(
    file: CatalogFile,
    identities: Set<EntityIdentity>,
    referenceCounter: ((Int) -> Unit)? = null,
): List<DocumentCheckFinding> {
    val sourceNamespace = file.metadata.namespace.lowercase().ifEmpty { DEFAULT_NAMESPACE }
    val findings = mutableListOf<DocumentCheckFinding>()
    var seen = 0
    for ((field, refs) in file.spec.refFields()) {
        val defaultKind = REF_FIELD_DEFAULT_KINDS.getValue(field)
        for (raw in refs.filter { it.isNotBlank() }) {
            seen++
            statusOf(raw, defaultKind, sourceNamespace, identities)?.let {
                findings += DocumentCheckFinding(field, raw, it)
            }
        }
    }
    referenceCounter?.invoke(seen)
    return findings
}

// The per-reference verdict; null = resolved (or unparsable — the form flags those itself).
private fun statusOf(
    raw: String,
    defaultKind: String?,
    sourceNamespace: String,
    identities: Set<EntityIdentity>,
): CrossCheckStatus? {
    val parsed = parseRef(raw) ?: return null
    val kind = parsed.kind?.lowercase() ?: defaultKind ?: return CrossCheckStatus.KIND_REQUIRED
    if (kind !in STORED_KINDS) return CrossCheckStatus.UNVERIFIABLE
    val target = EntityIdentity(
        kind = kind,
        namespace = parsed.namespace?.lowercase() ?: sourceNamespace,
        name = parsed.name.lowercase(),
    )
    return if (target in identities) null else CrossCheckStatus.MISSING
}

/** The workspace report: every active file's references resolved against every active file. */
fun crossCheckAll(sources: List<CrossCheckSource>): CrossCheckReport {
    val identities = sources.map { identityOf(it.file) }.toSet()
    var checkedReferences = 0
    val findings = sources.flatMap { source ->
        checkDocument(source.file, identities) { checkedReferences += it }.map {
            CrossCheckFinding(
                fileId = source.id,
                fileName = source.file.metadata.name,
                fileNamespace = source.file.metadata.namespace,
                field = it.field,
                reference = it.reference,
                status = it.status,
            )
        }
    }
    return CrossCheckReport(
        findings = findings,
        checkedFiles = sources.size,
        checkedReferences = checkedReferences,
    )
}
