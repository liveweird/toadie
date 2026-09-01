package ch.nokillswit.users

import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The layout-mode whitelist for the Graph page's per-user document (V19) — stored as the
 * wire string, no CHECK (the SUPPORTED_LANGUAGES idiom): `auto` = the dagre layout computed
 * client-side on every render, `manual` = the user's dragged positions below apply.
 */
val GRAPH_LAYOUT_MODES: Set<String> = setOf("auto", "manual")

/**
 * Ceiling on stored positions AND on collapsed ids — one limit for both, since each entry of
 * either is one node; a workspace has far fewer entities than this.
 */
const val MAX_GRAPH_POSITIONS = 1000

/** Position keys and collapsed ids are node ids (`kind:namespace/name`); names cap well below this. */
internal const val MAX_GRAPH_POSITION_KEY_LENGTH = 400

/** One node's canvas position (React Flow coordinates — any finite doubles). */
@Serializable
data class GraphPosition(val x: Double, val y: Double)

/**
 * The whole per-user Graph layout (GET/PUT /users/{id}/graph-layout): the mode, every
 * manually dragged node's position keyed by node id (`kind:namespace/name`), and (V24) the
 * ids of the nodes the user COLLAPSED — folded so their containment descendants are hidden
 * and stand in for them on the canvas. The PUT is a wholesale replace — the CLIENT merges (it
 * holds the full document from GET), so positions and collapsed ids of currently filtered-out
 * nodes survive a save. The defaults double as the response for a user who never saved (no
 * row = auto mode, nothing dragged, nothing collapsed).
 */
@Serializable
data class GraphLayoutDocument(
    val mode: String = "auto",
    val positions: Map<String, GraphPosition> = emptyMap(),
    val collapsed: List<String> = emptyList(),
)

/** Route- and service-enforced payload rules (finiteness comes free — JSON has no NaN). */
fun validateGraphLayout(doc: GraphLayoutDocument) {
    if (doc.mode !in GRAPH_LAYOUT_MODES) {
        throw BadRequestException("Unsupported layout mode (supported: ${GRAPH_LAYOUT_MODES.joinToString(", ")})")
    }
    if (doc.positions.size > MAX_GRAPH_POSITIONS) {
        throw BadRequestException("Too many stored positions (the limit is $MAX_GRAPH_POSITIONS)")
    }
    if (doc.collapsed.size > MAX_GRAPH_POSITIONS) {
        throw BadRequestException("Too many collapsed nodes (the limit is $MAX_GRAPH_POSITIONS)")
    }
    doc.positions.keys.forEach { requireNodeKey(it, "Position keys") }
    doc.collapsed.forEach { requireNodeKey(it, "Collapsed node ids") }
}

// The one grammar for anything keyed by a node id — positions and collapsed ids alike.
private fun requireNodeKey(key: String, what: String) {
    if (key.isBlank() || key.length > MAX_GRAPH_POSITION_KEY_LENGTH || key.any { it.isISOControl() }) {
        throw BadRequestException(
            "$what must be non-blank, at most $MAX_GRAPH_POSITION_KEY_LENGTH characters, " +
                "and contain no control characters",
        )
    }
}
