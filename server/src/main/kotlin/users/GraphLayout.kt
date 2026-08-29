package ch.nokillswit.users

import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The layout-mode whitelist for the Graph page's per-user document (V19) — stored as the
 * wire string, no CHECK (the SUPPORTED_LANGUAGES idiom): `auto` = the dagre layout computed
 * client-side on every render, `manual` = the user's dragged positions below apply.
 */
val GRAPH_LAYOUT_MODES: Set<String> = setOf("auto", "manual")

/** Ceiling on stored positions — a workspace has far fewer entities than this. */
const val MAX_GRAPH_POSITIONS = 1000

/** Position keys are node ids (`kind:namespace/name`); entity names cap well below this. */
internal const val MAX_GRAPH_POSITION_KEY_LENGTH = 400

/** One node's canvas position (React Flow coordinates — any finite doubles). */
@Serializable
data class GraphPosition(val x: Double, val y: Double)

/**
 * The whole per-user Graph layout (GET/PUT /users/{id}/graph-layout): the mode plus every
 * manually dragged node's position, keyed by node id (`kind:namespace/name`). The PUT is a
 * wholesale replace — the CLIENT merges (it holds the full map from GET), so positions of
 * currently filtered-out nodes survive a save. The defaults double as the response for a
 * user who never saved (no row = auto mode, nothing dragged).
 */
@Serializable
data class GraphLayoutDocument(
    val mode: String = "auto",
    val positions: Map<String, GraphPosition> = emptyMap(),
)

/** Route- and service-enforced payload rules (finiteness comes free — JSON has no NaN). */
fun validateGraphLayout(doc: GraphLayoutDocument) {
    if (doc.mode !in GRAPH_LAYOUT_MODES) {
        throw BadRequestException("Unsupported layout mode (supported: ${GRAPH_LAYOUT_MODES.joinToString(", ")})")
    }
    if (doc.positions.size > MAX_GRAPH_POSITIONS) {
        throw BadRequestException("Too many stored positions (the limit is $MAX_GRAPH_POSITIONS)")
    }
    doc.positions.keys.forEach { key ->
        if (key.isBlank() || key.length > MAX_GRAPH_POSITION_KEY_LENGTH || key.any { it.isISOControl() }) {
            throw BadRequestException(
                "Position keys must be non-blank, at most $MAX_GRAPH_POSITION_KEY_LENGTH characters, " +
                    "and contain no control characters",
            )
        }
    }
}
