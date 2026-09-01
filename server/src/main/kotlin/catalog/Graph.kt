package ch.nokillswit.catalog

import kotlinx.serialization.Serializable

/**
 * The rendered-together view: the stored entities and the reference edges between them as one
 * graph. Built on Errors.kt's parsing/field machinery so resolution can never disagree
 * with the Errors report (same default kinds, contextual namespace, case-insensitive identity).
 */
@Serializable
enum class GraphNodeStatus {
    /** A stored (active) catalog file. */
    STORED,

    /** A referenced entity of a stored kind that no active file provides — MISSING, drawn. */
    MISSING,
}

@Serializable
data class GraphNode(
    /** The canonical lowercased identity `kind:namespace/name` — also the dedupe key. */
    val id: String,
    val kind: String,
    val namespace: String,
    val name: String,
    val title: String? = null,
    /**
     * The stored document's `spec.type` — what the graph node's second line shows. Null for a
     * User (its spec has no type), for a type-optional kind left blank, and for a MISSING
     * node, which has no document at all.
     */
    val type: String? = null,
    /** The stored document's `metadata.tags` — tooltip content; empty for a MISSING node. */
    val tags: List<String> = emptyList(),
    /** The backing file for STORED nodes; null for a MISSING one. */
    val fileId: UInt? = null,
    val status: GraphNodeStatus,
)

@Serializable
data class GraphEdge(
    val sourceId: String,
    val targetId: String,
    /** The spec field the reference lives in, e.g. `spec.dependsOn`. */
    val field: String,
)

@Serializable
data class CatalogGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
)

private fun nodeId(identity: EntityIdentity) = "${identity.kind}:${identity.namespace}/${identity.name}"

private fun storedNode(source: CatalogSource): GraphNode {
    val identity = identityOf(source.file)
    return GraphNode(
        id = nodeId(identity),
        kind = identity.kind,
        namespace = identity.namespace,
        name = source.file.metadata.name,
        title = source.file.metadata.title,
        type = source.file.spec.type,
        tags = source.file.metadata.tags,
        fileId = source.id,
        status = GraphNodeStatus.STORED,
    )
}

/**
 * Builds the graph over [sources] — the SHOWN entities, i.e. exactly the files the caller's
 * filter selected (the same set the Files list returns). A reference becomes an edge only when
 * its target is shown as well: **an edge needs both of its ends on screen**. A target that is
 * a stored file the filter excluded therefore draws nothing at all — which is precisely why
 * [allSources] (the whole active workspace) is still needed: without it, a hidden stored file
 * would be mislabelled MISSING.
 *
 * A target no active file provides is a MISSING entity. It carries no document, so only the
 * identity-borne slots can be judged — [showsVirtual] decides (the kind pill and the namespace
 * filter). A referenced kind Toadie doesn't store (Location, Template, custom) has no kind pill
 * that could ever select it, so it is never shown.
 *
 * Kind-less dependsOn/dependencyOf entries are skipped (report findings, not drawable edges).
 */
fun buildGraph(
    sources: List<CatalogSource>,
    allSources: List<CatalogSource> = sources,
    showsVirtual: (EntityIdentity) -> Boolean = { true },
): CatalogGraph {
    val storedIdentities = allSources.mapTo(HashSet()) { identityOf(it.file) }
    val nodes = LinkedHashMap<String, GraphNode>()
    val edges = LinkedHashSet<GraphEdge>()

    for (source in sources) {
        nodes[nodeId(identityOf(source.file))] = storedNode(source)
    }

    for (source in sources) {
        val sourceIdentity = identityOf(source.file)
        for ((field, refs) in source.file.spec.refFields()) {
            val defaultKind = REF_FIELD_DEFAULT_KINDS.getValue(field)
            for (raw in refs.filter { it.isNotBlank() }) {
                // Kind-less refs resolve to null — report findings, not drawable edges.
                val target = resolveTarget(raw, defaultKind, sourceIdentity.namespace) ?: continue
                val targetId = nodeId(target)
                // Already shown, or drawable as a MISSING entity the filter admits — otherwise
                // skip the reference entirely: no node means no edge. (Re-putting an existing
                // key leaves its insertion order alone.)
                val node = nodes[targetId] ?: shownMissingNode(target, storedIdentities, showsVirtual) ?: continue
                nodes[targetId] = node
                edges += GraphEdge(sourceId = nodeId(sourceIdentity), targetId = targetId, field = field)
            }
        }
    }

    return CatalogGraph(nodes = nodes.values.toList(), edges = edges.toList())
}

// The node for a referenced target that is not itself shown — null (no node, hence no edge)
// unless it is a MISSING entity the filter admits.
private fun shownMissingNode(
    target: EntityIdentity,
    storedIdentities: Set<EntityIdentity>,
    showsVirtual: (EntityIdentity) -> Boolean,
): GraphNode? {
    // Stored, but the filter hid it: hidden is not the same as absent, so it must NOT read as
    // MISSING — it simply leaves the canvas.
    if (target in storedIdentities) return null
    // A kind Toadie doesn't store has no kind pill, so nothing could ever select it.
    if (target.kind !in STORED_KINDS) return null
    if (!showsVirtual(target)) return null
    return GraphNode(
        id = nodeId(target),
        kind = target.kind,
        namespace = target.namespace,
        name = target.name,
        status = GraphNodeStatus.MISSING,
    )
}
