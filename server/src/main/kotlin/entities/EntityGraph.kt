package ch.nokillswit.entities

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Port migration phase 3 (`.claude/docs/port-data-model.md`): the entity relation graph behind
 * `GET /api/v1/entities/graph`, the `entities/EntityService.kt`-scale counterpart of
 * `catalog/Graph.kt`. Pure, DB-free: [EntityService.graph] loads only the rows its filter
 * shows and hands them to [buildEntityGraph] — an edge needs both ends shown, so a hidden row
 * can never contribute a node or an edge, and (assumption A1) a relation value naming an
 * entity outside the shown set is simply dropped rather than drawn MISSING. Phase 4 adds
 * ownership edges (`entities/EntityOwnership.kt`) over the SAME both-ends rule.
 */

@Serializable
data class EntityGraphNode(
    val id: String,
    val entityId: UInt,
    val blueprint: String,
    val blueprintTitle: String,
    val identifier: String,
    val title: String,
    val icon: String? = null,
    val findings: Int,
)

@Serializable
data class EntityGraphEdge(
    val sourceId: String,
    val targetId: String,
    /** [OWNERSHIP_RELATION_ID] (`"$team"`) for an ownership edge; a declared relation id otherwise. */
    val relation: String,
    /** True when [relation] is the SOURCE blueprint's own `hierarchyRelation` (V29); always false for an ownership edge. */
    val hierarchy: Boolean,
    /** True for a Phase 4 ownership edge (source entity -> its effective team); false for a declared relation edge. */
    val ownership: Boolean,
)

@Serializable
data class EntityGraph(
    val nodes: List<EntityGraphNode>,
    val edges: List<EntityGraphEdge>,
)

/** One entity row as the pure builder needs it — no service/DB types leak in. */
data class EntityGraphSource(
    val id: UInt,
    val blueprintId: UInt,
    val identifier: String,
    val title: String,
    val icon: String?,
    val document: EntityDocument,
    /** The EFFECTIVE team (`entities/EntityOwnership.kt`'s `effectiveTeam`, already resolved by the caller) — empty when unowned. */
    val team: List<String> = emptyList(),
)

/** One blueprint as the pure builder needs it. */
data class GraphBlueprint(
    val identifier: String,
    val title: String,
    val definition: BlueprintDefinition,
    val hierarchyRelation: String?,
)

/**
 * `"<blueprint>|<identifier>"` — `|` is outside both the blueprint-identifier and entity-
 * identifier charsets, so exactly one `|` ever appears and the split point is unambiguous.
 * At most 301 characters (100 + 1 + 200), no control characters — satisfies `requireNodeKey`
 * (`users/GraphLayout.kt`), and `graphFold`'s `${source}|${target}|${field}` client-side merge
 * key stays collision-free (four `|` there vs. one here).
 */
fun entityNodeId(blueprint: String, identifier: String): String = "$blueprint|$identifier"

private fun relationValues(value: JsonElement): List<String> = when (value) {
    is JsonPrimitive -> if (value.isString) listOf(value.content) else emptyList()
    is JsonArray -> value.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.content }
    else -> emptyList()
}

/**
 * One edge per relation VALUE (array values expanded), deduped via the edge's own equality;
 * a target not among [rows] is dropped — never a MISSING node (assumption A1). Nodes keep
 * [rows]' order. [findings] is a closure so the caller controls how (and whether) a row's
 * finding COUNT is computed — the route needs it, the pure builder does not care how.
 */
fun buildEntityGraph(
    rows: List<EntityGraphSource>,
    blueprintsById: Map<UInt, GraphBlueprint>,
    findings: (EntityGraphSource) -> Int,
): EntityGraph {
    val nodeIdByEntityId = rows.associate { row ->
        row.id to entityNodeId(blueprintsById.getValue(row.blueprintId).identifier, row.identifier)
    }
    val rowByBlueprintAndIdentifier = rows.associateBy { row ->
        blueprintsById.getValue(row.blueprintId).identifier to row.identifier
    }

    val nodes = rows.map { row ->
        val blueprint = blueprintsById.getValue(row.blueprintId)
        EntityGraphNode(
            id = nodeIdByEntityId.getValue(row.id),
            entityId = row.id,
            blueprint = blueprint.identifier,
            blueprintTitle = blueprint.title,
            identifier = row.identifier,
            title = row.title,
            icon = row.icon,
            findings = findings(row),
        )
    }

    val edges = LinkedHashSet<EntityGraphEdge>()
    for (row in rows) {
        val blueprint = blueprintsById.getValue(row.blueprintId)
        val sourceId = nodeIdByEntityId.getValue(row.id)
        row.document.relations.forEach { (relationId, value) ->
            val relationDef = blueprint.definition.relations[relationId] ?: return@forEach
            relationValues(value).forEach { targetIdentifier ->
                val targetRow = rowByBlueprintAndIdentifier[relationDef.target to targetIdentifier] ?: return@forEach
                edges += EntityGraphEdge(
                    sourceId = sourceId,
                    targetId = nodeIdByEntityId.getValue(targetRow.id),
                    relation = relationId,
                    hierarchy = relationId == blueprint.hierarchyRelation,
                    ownership = false,
                )
            }
        }
    }
    // Ownership edges (Phase 4): one per effective team value, to `_team|<id>` — same both-ends
    // rule (a team not among the shown rows contributes no edge, never MISSING).
    for (row in rows) {
        val sourceId = nodeIdByEntityId.getValue(row.id)
        row.team.forEach { teamIdentifier ->
            val targetRow = rowByBlueprintAndIdentifier[SYSTEM_TEAM_BLUEPRINT to teamIdentifier] ?: return@forEach
            edges += EntityGraphEdge(
                sourceId = sourceId,
                targetId = nodeIdByEntityId.getValue(targetRow.id),
                relation = OWNERSHIP_RELATION_ID,
                hierarchy = false,
                ownership = true,
            )
        }
    }
    return EntityGraph(nodes = nodes, edges = edges.toList())
}
