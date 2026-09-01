package ch.nokillswit.catalog

import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.jsonArrayContains
import ch.nokillswit.infra.db.jsonObjectHasKey
import ch.nokillswit.infra.db.jsonObjectValueIn
import ch.nokillswit.infra.db.jsonTextEqualsFolded
import java.text.Normalizer
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or

// The catalog-file filter set, shared by the list endpoint (SQL, via
// [buildCatalogFilePredicate]) and the graph endpoint (in-memory over decoded sources, via
// [matches]). The two implementations of the ONE semantics live side by side in this file on
// purpose — a filter added to one and not the other is a bug, and the list-vs-graph parity
// test pins them together.

data class CatalogFileListFilter(
    val name: String? = null,
    val namespace: String? = null,
    /** Canonical-cased kinds, any-of/IN (the route validates each against SUPPORTED_KINDS). */
    val kinds: List<String> = emptyList(),
    /** Exact tag membership against metadata.tags (folded — tags are stored lowercase). */
    val tag: String? = null,
    /** Case-folded exact match against spec.type. */
    val type: String? = null,
    /** Case-folded exact match against spec.lifecycle. */
    val lifecycle: String? = null,
    /**
     * The RESOLVED owner target: a file matches when its stored spec.owner — whatever short
     * form it uses — resolves to this identity under the descriptor defaulting rules
     * (default kind group, default namespace = the FILE's namespace).
     */
    val owner: EntityIdentity? = null,
    /** metadata.labels key presence (byte-exact — the registry forbids case-twin keys). */
    val label: String? = null,
    /** Any-of match over [label]'s value, case-folded (the API-LIST-004 IN param). */
    val labelValues: List<String> = emptyList(),
)

/**
 * Parses the `owner` query param into the identity it targets — the same lenient ref
 * grammar as resolution ([parseRef]), with spec.owner's defaults: kind `group`, namespace
 * `default` (the param has no source file whose namespace could serve as context).
 * Null = unparsable → the route 400s (the value comes from a dropdown of stored entities,
 * so garbage is a client bug, not a no-match).
 */
internal fun ownerFilterTarget(raw: String): EntityIdentity? =
    resolveTarget(raw, defaultKind = REF_FIELD_DEFAULT_KINDS.getValue("spec.owner"), sourceNamespace = DEFAULT_NAMESPACE)

/** The list endpoint's SQL rendering of the filter (ANDed; the caller adds active()). */
internal fun buildCatalogFilePredicate(filter: CatalogFileListFilter): Op<Boolean> {
    val files = CatalogFileService.CatalogFiles
    var op: Op<Boolean> = Op.TRUE
    filter.name?.takeIf { it.isNotBlank() }?.let {
        op = op and (files.name.containsNormalized(it))
    }
    filter.namespace?.takeIf { it.isNotBlank() }?.let {
        // Stored namespaces are lowercase (sanitizedCatalogFile) — fold the filter too.
        op = op and (files.namespace eq it.lowercase())
    }
    if (filter.kinds.isNotEmpty()) {
        op = op and (files.kind inList filter.kinds)
    }
    filter.tag?.takeIf { it.isNotBlank() }?.let {
        // Exact membership inside the content JSON (tags are stored lowercase — fold).
        op = op and files.content.jsonArrayContains(listOf("metadata", "tags"), it.lowercase())
    }
    filter.type?.takeIf { it.isNotBlank() }?.let {
        op = op and files.content.jsonTextEqualsFolded(listOf("spec", "type"), it)
    }
    filter.lifecycle?.takeIf { it.isNotBlank() }?.let {
        op = op and files.content.jsonTextEqualsFolded(listOf("spec", "lifecycle"), it)
    }
    filter.owner?.let { op = op and ownerPredicate(it) }
    filter.label?.let {
        op = op and files.content.jsonObjectHasKey(listOf("metadata", "labels"), it)
    }
    if (filter.label != null && filter.labelValues.isNotEmpty()) {
        op = op and files.content.jsonObjectValueIn(listOf("metadata", "labels"), filter.label, filter.labelValues)
    }
    return op
}

/**
 * The stored spec.owner spellings that RESOLVE to [target] (mirrors [resolveTarget]'s
 * defaulting in SQL): the full `kind:ns/name` always; `ns/name` only when the target kind is
 * owner's default (group); and the namespace-less forms `kind:name` / bare `name` only for
 * files whose OWN namespace is the target's (that is what a namespace-less ref defaults to).
 */
private fun ownerPredicate(target: EntityIdentity): Op<Boolean> {
    val files = CatalogFileService.CatalogFiles
    val path = listOf("spec", "owner")
    val ownerDefaultKind = REF_FIELD_DEFAULT_KINDS.getValue("spec.owner")
    var op = files.content.jsonTextEqualsFolded(path, "${target.kind}:${target.namespace}/${target.name}")
    if (target.kind == ownerDefaultKind) {
        op = op or files.content.jsonTextEqualsFolded(path, "${target.namespace}/${target.name}")
    }
    var namespaceLess = files.content.jsonTextEqualsFolded(path, "${target.kind}:${target.name}")
    if (target.kind == ownerDefaultKind) {
        namespaceLess = namespaceLess or files.content.jsonTextEqualsFolded(path, target.name)
    }
    return op or ((files.namespace eq target.namespace) and namespaceLess)
}

/**
 * The graph endpoint's in-memory rendering of the SAME semantics, applied to decoded sources
 * (the graph loads and decodes every active file anyway, so filters stay pure Kotlin there).
 */
internal fun CatalogFileListFilter.matches(file: CatalogFile): Boolean =
    matchesName(file) &&
        (namespace?.takeIf { it.isNotBlank() }?.let { file.metadata.namespace.lowercase() == it.lowercase() } ?: true) &&
        (kinds.isEmpty() || file.kind in kinds) &&
        (tag?.takeIf { it.isNotBlank() }?.let { it.lowercase() in file.metadata.tags } ?: true) &&
        (type?.takeIf { it.isNotBlank() }?.let { file.spec.type?.lowercase() == it.lowercase() } ?: true) &&
        (lifecycle?.takeIf { it.isNotBlank() }?.let { file.spec.lifecycle?.lowercase() == it.lowercase() } ?: true) &&
        matchesOwner(file) &&
        (label?.let { it in file.metadata.labels } ?: true) &&
        matchesLabelValues(file)

/**
 * Whether the graph may draw a referenced entity that no stored file provides (a MISSING node).
 * Every slot a reference IDENTITY carries is judged — kind, namespace and name — so the pills
 * and the name box mean the same thing here as everywhere else. The CONTENT filters (tag, type,
 * lifecycle, owner, label) have no document to read and never hide it: dropping a dangling
 * reference because a tag filter is on would hide exactly what is worth seeing.
 */
internal fun CatalogFileListFilter.allowsVirtualTarget(identity: EntityIdentity): Boolean =
    // `kinds` holds canonical casing (`API`), an identity is lowercase throughout.
    (kinds.isEmpty() || kinds.any { it.equals(identity.kind, ignoreCase = true) }) &&
        (namespace?.takeIf { it.isNotBlank() }?.let { identity.namespace == it.lowercase() } ?: true) &&
        // The list's own substring rule, so the name box narrows both alike.
        (name?.takeIf { it.isNotBlank() }?.let { foldForMatch(identity.name).contains(foldForMatch(it)) } ?: true)

private fun CatalogFileListFilter.matchesName(file: CatalogFile): Boolean =
    name?.takeIf { it.isNotBlank() }?.let { foldForMatch(file.metadata.name).contains(foldForMatch(it)) } ?: true

private fun CatalogFileListFilter.matchesOwner(file: CatalogFile): Boolean {
    val target = owner ?: return true
    val stored = file.spec.owner ?: return false
    val ownerDefaultKind = REF_FIELD_DEFAULT_KINDS.getValue("spec.owner")
    return resolveTarget(stored, ownerDefaultKind, file.metadata.namespace.lowercase()) == target
}

private fun CatalogFileListFilter.matchesLabelValues(file: CatalogFile): Boolean {
    if (label == null || labelValues.isEmpty()) return true
    val stored = file.metadata.labels[label] ?: return false
    return labelValues.any { it.equals(stored, ignoreCase = true) }
}

// NFD decomposition misses letters with no combining mark (ł is L-with-stroke), so those get
// explicit mappings — the same table as the SPA's foldDiacritics (web/src/utils/text.ts).
// Approximate vs PG's unaccent (which backs the list's SQL name filter): the common European
// cases agree, exotic-diacritic tails may diverge between list and graph — accepted.
private val COMBINING_MARKS = Regex("\\p{M}+")
private val NON_DECOMPOSING = mapOf('ł' to "l", 'đ' to "d", 'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss")

internal fun foldForMatch(raw: String): String {
    val stripped = COMBINING_MARKS.replace(Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD), "")
    return buildString(stripped.length) {
        for (c in stripped) {
            val mapped = NON_DECOMPOSING[c]
            if (mapped != null) append(mapped) else append(c)
        }
    }
}
