package ch.nokillswit.catalog

import kotlinx.serialization.Serializable

/**
 * The rendered-together view: the stored entities and the reference edges between them as one
 * graph. Built on CrossCheck.kt's parsing/field machinery so resolution can never disagree
 * with the cross-check (same default kinds, contextual namespace, case-insensitive identity).
 */
@Serializable
enum class GraphNodeStatus {
    /** A stored (active) catalog file. */
    STORED,

    /** A referenced entity of a stored kind that no active file provides — MISSING, drawn. */
    MISSING,

    /** A referenced entity of a kind Toadie doesn't store (Location, Template, custom). */
    EXTERNAL,
}

@Serializable
data class GraphNode(
    /** The canonical lowercased identity `kind:namespace/name` — also the dedupe key. */
    val id: String,
    val kind: String,
    val namespace: String,
    val name: String,
    val title: String? = null,
    /** The backing file for STORED nodes; null for virtual (MISSING/EXTERNAL) nodes. */
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

private fun storedNode(source: CrossCheckSource): GraphNode {
    val identity = identityOf(source.file)
    return GraphNode(
        id = nodeId(identity),
        kind = identity.kind,
        namespace = identity.namespace,
        name = source.file.metadata.name,
        title = source.file.metadata.title,
        fileId = source.id,
        status = GraphNodeStatus.STORED,
    )
}

/**
 * Builds the graph for [sources] (the files whose references are expanded — e.g. one
 * namespace). Targets resolve against [allSources] (the whole active workspace), so a stored
 * file outside the rendered set still appears as a STORED node when something points at it —
 * an edge needs both ends. Every parsable, kind-carrying reference becomes an edge to a
 * STORED / MISSING / EXTERNAL node; kind-less dependsOn/dependencyOf entries are skipped
 * (they're cross-check errors, not drawable edges).
 */
fun buildGraph(sources: List<CrossCheckSource>, allSources: List<CrossCheckSource> = sources): CatalogGraph {
    val storedByIdentity = allSources.associateBy { identityOf(it.file) }
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
                // Kind-less refs resolve to null — cross-check errors, not drawable edges.
                val target = resolveTarget(raw, defaultKind, sourceIdentity.namespace) ?: continue
                val targetId = nodeId(target)
                nodes.getOrPut(targetId) { virtualOrForeignStoredNode(target, storedByIdentity) }
                edges += GraphEdge(sourceId = nodeId(sourceIdentity), targetId = targetId, field = field)
            }
        }
    }

    return CatalogGraph(nodes = nodes.values.toList(), edges = edges.toList())
}

// A referenced node that is not in the rendered set: a stored file outside it, a missing
// component, or an external-kind entity.
private fun virtualOrForeignStoredNode(
    target: EntityIdentity,
    storedByIdentity: Map<EntityIdentity, CrossCheckSource>,
): GraphNode {
    if (target.kind in STORED_KINDS) {
        storedByIdentity[target]?.let { return storedNode(it) }
    }
    return GraphNode(
        id = nodeId(target),
        kind = target.kind,
        namespace = target.namespace,
        name = target.name,
        status = if (target.kind in STORED_KINDS) GraphNodeStatus.MISSING else GraphNodeStatus.EXTERNAL,
    )
}
