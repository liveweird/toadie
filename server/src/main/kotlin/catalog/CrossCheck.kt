package ch.nokillswit.catalog

import kotlinx.serialization.Serializable

/**
 * Cross-checking: resolving the entity references stored files make against the workspace.
 * Pure logic — the service supplies the rows, the routes the transport. Semantics follow
 * `.claude/docs/backstage-descriptor-format.md`: per-field default kinds AND allowed target
 * kinds, an omitted namespace defaults to the REFERENCING file's own namespace, and
 * kind/namespace/name all match case-insensitively. This is ALSO the write-time enforcement
 * rulebook (CatalogFileService.requireResolvedReferences): every save must resolve every
 * reference, so findings here arise only from deletions (dangling refs) and imports whose
 * sibling documents failed — the cross-check report is the net for those.
 */
@Serializable
enum class CrossCheckStatus {
    /** The reference should resolve to a stored file of its kind but no active one matches. */
    MISSING,

    /** A dependsOn/dependencyOf entry without a kind — Backstage cannot ingest those. */
    KIND_REQUIRED,

    /** The reference names a kind the field does not allow (e.g. a Component in spec.owner). */
    WRONG_KIND,
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

/** The lowercased kinds the editor stores — references to them are RESOLVABLE. */
internal val STORED_KINDS: Set<String> = SUPPORTED_KINDS.map { it.lowercase() }.toSet()

// Per-field default kinds (the descriptor reference's context rules). dependsOn/dependencyOf
// map to null ON PURPOSE: Backstage gives them no default, so a kind-less entry is an error.
internal val REF_FIELD_DEFAULT_KINDS: Map<String, String?> = mapOf(
    "spec.owner" to "group",
    "spec.system" to "system",
    "spec.subcomponentOf" to "component",
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

// The kinds each field may TARGET (the descriptor reference's tables) — a reference naming
// any other kind is WRONG_KIND even when such an entity exists. Every allowed kind is a
// stored kind, so nothing is unverifiable: each reference either resolves or is a finding.
internal val REF_FIELD_ALLOWED_KINDS: Map<String, Set<String>> = mapOf(
    "spec.owner" to setOf("group", "user"),
    "spec.system" to setOf("system"),
    "spec.subcomponentOf" to setOf("component"),
    "spec.providesApis" to setOf("api"),
    "spec.consumesApis" to setOf("api"),
    "spec.dependsOn" to setOf("component", "resource"),
    "spec.dependencyOf" to setOf("component", "resource"),
    "spec.parent" to setOf("group"),
    "spec.children" to setOf("group"),
    "spec.members" to setOf("user"),
    "spec.memberOf" to setOf("group"),
    "spec.domain" to setOf("domain"),
    "spec.subdomainOf" to setOf("domain"),
)

/** A lowercased identity a reference can resolve to. */
data class EntityIdentity(val kind: String, val namespace: String, val name: String)

fun identityOf(file: CatalogFile) = EntityIdentity(
    kind = file.kind.lowercase(),
    namespace = file.metadata.namespace.lowercase(),
    name = file.metadata.name.lowercase(),
)

internal data class ParsedRef(val kind: String?, val namespace: String?, val name: String)

/**
 * Splits on the single allowed occurrence of [sep]: `(prefix?, rest)`, or null when [sep]
 * occurs more than once. The one splitter behind both the lenient [parseRef] and the strict
 * grammar validators in CatalogFileValidation.kt — the ref grammar lives in one place.
 */
internal fun splitRefOnce(value: String, sep: Char): Pair<String?, String>? {
    val first = value.indexOf(sep)
    if (first != value.lastIndexOf(sep)) return null
    return if (first >= 0) value.substring(0, first) to value.substring(first + 1) else null to value
}

// Lenient on purpose: the ad-hoc check sees in-progress documents whose refs the form is
// already flagging — an unparsable ref is skipped here, never a 400.
internal fun parseRef(raw: String): ParsedRef? {
    val (kind, rest) = splitRefOnce(raw, ':') ?: return null
    val (namespace, name) = splitRefOnce(rest, '/') ?: return null
    if (kind?.isEmpty() == true || namespace?.isEmpty() == true || name.isEmpty()) return null
    return ParsedRef(kind, namespace, name)
}

/**
 * Resolves one raw reference to the identity it targets — the ONE resolution rule shared by
 * the cross-check and the graph (contextual default kind, the referencing file's namespace as
 * the fallback, lowercased identity). Null = unparsable or kind-less.
 */
internal fun resolveTarget(raw: String, defaultKind: String?, sourceNamespace: String): EntityIdentity? {
    val parsed = parseRef(raw) ?: return null
    val kind = parsed.kind?.lowercase() ?: defaultKind ?: return null
    return EntityIdentity(
        kind = kind,
        namespace = parsed.namespace?.lowercase() ?: sourceNamespace,
        name = parsed.name.lowercase(),
    )
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

/** One document's verdicts plus how many non-blank references were seen (parsable or not). */
data class DocumentCheckResult(
    val findings: List<DocumentCheckFinding>,
    val referenceCount: Int,
)

/** Checks one document's references against the given identity set. */
fun checkDocument(file: CatalogFile, identities: Set<EntityIdentity>): DocumentCheckResult {
    val sourceNamespace = file.metadata.namespace.lowercase().ifEmpty { DEFAULT_NAMESPACE }
    val findings = mutableListOf<DocumentCheckFinding>()
    var seen = 0
    for ((field, refs) in file.spec.refFields()) {
        for (raw in refs.filter { it.isNotBlank() }) {
            seen++
            statusOf(raw, field, sourceNamespace, identities)?.let {
                findings += DocumentCheckFinding(field, raw, it)
            }
        }
    }
    return DocumentCheckResult(findings = findings, referenceCount = seen)
}

// The per-reference verdict; null = resolved (or unparsable — the form flags those itself,
// and the write path's grammar validation rejects them before enforcement runs).
private fun statusOf(
    raw: String,
    field: String,
    sourceNamespace: String,
    identities: Set<EntityIdentity>,
): CrossCheckStatus? {
    if (parseRef(raw) == null) return null
    val target = resolveTarget(raw, REF_FIELD_DEFAULT_KINDS.getValue(field), sourceNamespace)
        ?: return CrossCheckStatus.KIND_REQUIRED // parsable (checked above) but kind-less
    if (target.kind !in REF_FIELD_ALLOWED_KINDS.getValue(field)) return CrossCheckStatus.WRONG_KIND
    return if (target in identities) null else CrossCheckStatus.MISSING
}

/** The workspace report: every active file's references resolved against every active file. */
fun crossCheckAll(sources: List<CrossCheckSource>): CrossCheckReport {
    val identities = sources.map { identityOf(it.file) }.toSet()
    var checkedReferences = 0
    val findings = sources.flatMap { source ->
        val result = checkDocument(source.file, identities)
        checkedReferences += result.referenceCount
        result.findings.map {
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
