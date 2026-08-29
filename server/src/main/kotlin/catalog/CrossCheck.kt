package ch.nokillswit.catalog

import kotlinx.serialization.Serializable

/**
 * Cross-checking: resolving the entity references stored files make against the workspace,
 * plus the registry checks (labels, annotation keys, tags, type, lifecycle) against a
 * caller-supplied snapshot. Pure logic — the service supplies the rows and the snapshot, the
 * routes the transport. Reference semantics follow `.claude/docs/backstage-descriptor-format.md`:
 * per-field default kinds AND allowed target kinds, an omitted namespace defaults to the
 * REFERENCING file's own namespace, and kind/namespace/name all match case-insensitively.
 * This is ALSO the write-time enforcement rulebook (CatalogFileService.softFindings): a save
 * with any finding is rejected UNLESS the caller waives it with `allowInvalid=true`, so the
 * cross-check report is the net for waived saves as well as deletions (dangling refs), imports
 * whose sibling documents failed, and registry rows removed after the fact.
 */
@Serializable
enum class CrossCheckStatus {
    /** The reference should resolve to a stored file of its kind but no active one matches. */
    MISSING,

    /** A dependsOn/dependencyOf entry without a kind — Backstage cannot ingest those. */
    KIND_REQUIRED,

    /** The reference names a kind the field does not allow (e.g. a Component in spec.owner). */
    WRONG_KIND,

    /**
     * The reference resolves to the referencing document itself (e.g. a Domain's
     * spec.subdomainOf naming that very Domain) — a Toadie rule beyond upstream Backstage.
     */
    SELF_REFERENCE,

    /** A metadata.labels entry the label registry rejects (unknown key, kind, or value). */
    LABEL_NOT_ALLOWED,

    /** A metadata.annotations key the annotation-key registry rejects (unknown key or kind). */
    ANNOTATION_NOT_ALLOWED,

    /** A metadata.tags entry the tag categories reject (unknown tag, or its category's kinds). */
    TAG_NOT_ALLOWED,

    /** A spec.type outside the kind's type dictionary (or the kind has no dictionary at all). */
    TYPE_NOT_ALLOWED,

    /** A spec.lifecycle outside the global lifecycles dictionary. */
    LIFECYCLE_NOT_ALLOWED,
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
    // The document's OWN identity — built from the same namespace fallback as the refs it
    // anchors (identityOf() has no blank->default fallback), so short-form self-refs match.
    val self = EntityIdentity(
        kind = file.kind.lowercase(),
        namespace = sourceNamespace,
        name = file.metadata.name.lowercase(),
    )
    val findings = mutableListOf<DocumentCheckFinding>()
    var seen = 0
    for ((field, refs) in file.spec.refFields()) {
        for (raw in refs.filter { it.isNotBlank() }) {
            seen++
            statusOf(raw, field, sourceNamespace, self, identities)?.let {
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
    self: EntityIdentity,
    identities: Set<EntityIdentity>,
): CrossCheckStatus? {
    if (parseRef(raw) == null) return null
    val target = resolveTarget(raw, REF_FIELD_DEFAULT_KINDS.getValue(field), sourceNamespace)
        ?: return CrossCheckStatus.KIND_REQUIRED // parsable (checked above) but kind-less
    if (target.kind !in REF_FIELD_ALLOWED_KINDS.getValue(field)) return CrossCheckStatus.WRONG_KIND
    // Before the membership test: an entity may never reference itself, saved or not.
    if (target == self) return CrossCheckStatus.SELF_REFERENCE
    return if (target in identities) null else CrossCheckStatus.MISSING
}

/**
 * The workspace report: every active file's references resolved against every active file,
 * plus every file's registry findings against [registries].
 */
fun crossCheckAll(sources: List<CrossCheckSource>, registries: RegistrySnapshot): CrossCheckReport {
    val identities = sources.map { identityOf(it.file) }.toSet()
    var checkedReferences = 0
    val findings = sources.flatMap { source ->
        val result = checkDocument(source.file, identities)
        checkedReferences += result.referenceCount
        val documentFindings = result.findings + registryFindings(source.file, registries).map { it.finding }
        documentFindings.map {
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

/**
 * A soft (waivable) finding plus its precise strict-mode message — the wire carries the
 * finding (per-area status), the aggregated 400 and the import result rows carry the message.
 */
data class SoftFinding(val finding: DocumentCheckFinding, val message: String)

/** The strict-mode message for a reference finding (registry findings carry their own). */
fun referenceFindingMessage(finding: DocumentCheckFinding): String {
    fun canonical(kind: String) = SUPPORTED_KINDS.first { it.lowercase() == kind }
    return when (finding.status) {
        CrossCheckStatus.MISSING ->
            "${finding.field} reference '${finding.reference}' does not resolve to a stored entity"
        CrossCheckStatus.WRONG_KIND ->
            "${finding.field} reference '${finding.reference}' must target " +
                REF_FIELD_ALLOWED_KINDS.getValue(finding.field).joinToString(" or ") { canonical(it) }
        CrossCheckStatus.KIND_REQUIRED ->
            "${finding.field} reference '${finding.reference}' needs an explicit kind"
        CrossCheckStatus.SELF_REFERENCE ->
            "${finding.field} reference '${finding.reference}' must not point at the entity itself"
        else -> error("not a reference finding: ${finding.status}")
    }
}

/**
 * The active registry rows the soft checks match against, snapshotted by the caller inside
 * its own transaction (CatalogFileService.loadRegistrySnapshot) so the pure checks below
 * stay DB-free and one report reads each registry once.
 */
data class RegistrySnapshot(
    /** label key -> (allowed kinds, allowed values) */
    val labels: Map<String, Pair<List<String>, List<String>>>,
    /** annotation key -> allowed kinds */
    val annotationKeys: Map<String, List<String>>,
    /** tag -> (owning category name, its allowed kinds) — categories are disjoint by rule */
    val tags: Map<String, Pair<String, List<String>>>,
    /** canonical kind -> its active type dictionary (an absent kind allows NO types) */
    val types: Map<String, List<String>>,
    /** the active lifecycle values (stored lowercase-folded) */
    val lifecycles: Set<String>,
)

/**
 * One document's registry findings — the same rules the strict save enforces, as findings:
 * every label/annotation/tag/type/lifecycle value must be allowed by the ADMIN-curated
 * registries for the file's kind (byte-exact matching, no grandfathering — the editor only
 * ever writes registered values verbatim, so a finding means the registry changed or the
 * document arrived via import/waiver). At most one finding per offending entry (the first
 * failing rule), mirroring the strict messages verbatim.
 */
fun registryFindings(file: CatalogFile, registries: RegistrySnapshot): List<SoftFinding> =
    labelFindings(file, registries) +
        annotationFindings(file, registries) +
        tagFindings(file, registries) +
        typeFindings(file, registries) +
        lifecycleFindings(file, registries)

private fun soft(field: String, value: String, status: CrossCheckStatus, message: String) =
    SoftFinding(DocumentCheckFinding(field = field, reference = value, status = status), message)

private fun labelFindings(file: CatalogFile, registries: RegistrySnapshot): List<SoftFinding> =
    file.metadata.labels.mapNotNull { (key, value) ->
        val (allowedKinds, allowedValues) = registries.labels[key]
            ?: return@mapNotNull soft(
                "metadata.labels", key, CrossCheckStatus.LABEL_NOT_ALLOWED,
                "metadata.labels key '$key' is not a defined label — define it on the Labels page",
            )
        when {
            file.kind !in allowedKinds -> soft(
                "metadata.labels", key, CrossCheckStatus.LABEL_NOT_ALLOWED,
                "Label '$key' cannot be applied to kind '${file.kind}' — adjust its kinds on the Labels page",
            )
            value !in allowedValues -> soft(
                "metadata.labels", "$key=$value", CrossCheckStatus.LABEL_NOT_ALLOWED,
                "Value '$value' is not allowed for label '$key' — adjust its values on the Labels page",
            )
            else -> null
        }
    }

private fun annotationFindings(file: CatalogFile, registries: RegistrySnapshot): List<SoftFinding> =
    file.metadata.annotations.keys.mapNotNull { key ->
        val allowedKinds = registries.annotationKeys[key]
            ?: return@mapNotNull soft(
                "metadata.annotations", key, CrossCheckStatus.ANNOTATION_NOT_ALLOWED,
                "metadata.annotations key '$key' is not a registered annotation key — define it on the Annotations page",
            )
        if (file.kind !in allowedKinds) {
            soft(
                "metadata.annotations", key, CrossCheckStatus.ANNOTATION_NOT_ALLOWED,
                "Annotation '$key' cannot be applied to kind '${file.kind}' — adjust its kinds on the Annotations page",
            )
        } else {
            null
        }
    }

private fun tagFindings(file: CatalogFile, registries: RegistrySnapshot): List<SoftFinding> =
    file.metadata.tags.mapNotNull { tag ->
        val (category, allowedKinds) = registries.tags[tag]
            ?: return@mapNotNull soft(
                "metadata.tags", tag, CrossCheckStatus.TAG_NOT_ALLOWED,
                "metadata.tags entry '$tag' is not a defined tag — define it on the Tags page",
            )
        if (file.kind !in allowedKinds) {
            soft(
                "metadata.tags", tag, CrossCheckStatus.TAG_NOT_ALLOWED,
                "Tag '$tag' (category '$category') cannot be applied to kind '${file.kind}'" +
                    " — adjust the category's kinds on the Tags page",
            )
        } else {
            null
        }
    }

private fun typeFindings(file: CatalogFile, registries: RegistrySnapshot): List<SoftFinding> {
    val type = file.spec.type
    if (type.isNullOrBlank()) return emptyList()
    val allowed = registries.types[file.kind]
        ?: return listOf(
            soft(
                "spec.type", type, CrossCheckStatus.TYPE_NOT_ALLOWED,
                "No types are defined for kind '${file.kind}' — define them on the Types page",
            ),
        )
    if (type in allowed) return emptyList()
    return listOf(
        soft(
            "spec.type", type, CrossCheckStatus.TYPE_NOT_ALLOWED,
            "spec.type '$type' is not an allowed type for kind '${file.kind}' — define it on the Types page",
        ),
    )
}

private fun lifecycleFindings(file: CatalogFile, registries: RegistrySnapshot): List<SoftFinding> {
    val lifecycle = file.spec.lifecycle
    if (lifecycle.isNullOrBlank() || lifecycle in registries.lifecycles) return emptyList()
    return listOf(
        soft(
            "spec.lifecycle", lifecycle, CrossCheckStatus.LIFECYCLE_NOT_ALLOWED,
            "spec.lifecycle '$lifecycle' is not an allowed lifecycle — define it on the Lifecycles page",
        ),
    )
}
