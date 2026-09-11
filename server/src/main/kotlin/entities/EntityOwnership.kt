package ch.nokillswit.entities

import ch.nokillswit.blueprints.BlueprintDefinition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Port ownership (`.claude/docs/port-data-model.md` "Ownership"): a blueprint's `ownership.type`
 * is `Direct` (the entity's own `team` column is authoritative — the STORED value) or `Inherited`
 * (the effective team is computed at READ time from a related blueprint's Direct ownership,
 * walking `ownership.path` — NEVER stored). Pure, DB-free: [EntityService] supplies the row
 * snapshot ([RowLookup]) this walk needs, taken under its existing lock/read.
 */

/** The graph-edge relation id ownership edges carry (`entities/EntityGraph.kt`) — never a real blueprint relation. */
const val OWNERSHIP_RELATION_ID = "\$team"

/** Hop budget for [inheritedTeam] — a defensive cap against a pathological/cyclical ownership chain. */
const val MAX_OWNERSHIP_HOPS = 10

/** One entity row as the ownership walk needs it — blueprint-identity-independent. */
data class OwnedRow(val blueprint: String, val identifier: String, val document: EntityDocument, val team: JsonElement?)

/** Looks up one ACTIVE entity row by (blueprintIdentifier, entityIdentifier); null when it does not resolve. */
typealias RowLookup = (blueprint: String, identifier: String) -> OwnedRow?

fun isInherited(definition: BlueprintDefinition): Boolean = definition.ownership?.type == "Inherited"

/** One hop's outcome: keep walking a further-Inherited blueprint, land on a Direct/absent one, or give up. */
private sealed interface HopResult {
    data class Stop(val team: JsonElement?) : HopResult
    data class Continue(val definition: BlueprintDefinition, val document: EntityDocument) : HopResult
    data object GiveUp : HopResult
}

/** One [segment] of the walk, over [currentDefinition]/[currentDocument]'s OWN relation value — never re-checks the hop budget. */
private fun ownershipHop(
    segment: String,
    currentDefinition: BlueprintDefinition,
    currentDocument: EntityDocument,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    rowLookup: RowLookup,
): HopResult {
    val relation = currentDefinition.relations[segment] ?: return HopResult.GiveUp
    if (relation.many) return HopResult.GiveUp
    val targetIdentifier = (currentDocument.relations[segment] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return HopResult.GiveUp
    val targetRow = rowLookup(relation.target, targetIdentifier) ?: return HopResult.GiveUp
    val targetDefinition = blueprintsByIdentifier[relation.target] ?: return HopResult.GiveUp
    return if (isInherited(targetDefinition)) HopResult.Continue(targetDefinition, targetRow.document) else HopResult.Stop(targetRow.team)
}

/**
 * Walks [definition]'s `ownership.path` (a dot-chain of relation identifiers, the first naming
 * one of [definition]'s OWN relations) hop by hop over [document]'s relation values, stopping at
 * the first blueprint along the chain whose OWN ownership is Direct or absent and returning THAT
 * row's stored `team` — the effective inherited team. Absent (null) when: [definition] is not
 * Inherited, the path is blank, a hop's relation is unknown or `many`, a hop's value is missing/
 * not a single string, a hop's target row does not resolve, a hop's target blueprint is unknown,
 * the hop budget ([MAX_OWNERSHIP_HOPS]) is exhausted, or the path is exhausted while the landed
 * blueprint is still Inherited (a misconfigured chain — never guessed at).
 */
fun inheritedTeam(
    document: EntityDocument,
    definition: BlueprintDefinition,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    rowLookup: RowLookup,
): JsonElement? {
    if (!isInherited(definition)) return null
    val path = definition.ownership?.path?.split('.')?.filter { it.isNotBlank() }.orEmpty()
    if (path.isEmpty()) return null

    var currentDefinition = definition
    var currentDocument = document
    path.forEachIndexed { hop, segment ->
        if (hop >= MAX_OWNERSHIP_HOPS) return null
        when (val result = ownershipHop(segment, currentDefinition, currentDocument, blueprintsByIdentifier, rowLookup)) {
            is HopResult.Stop -> return result.team
            is HopResult.Continue -> {
                currentDefinition = result.definition
                currentDocument = result.document
            }
            HopResult.GiveUp -> return null
        }
    }
    return null
}

/** The team a caller should SHOW: the stored value for Direct/absent ownership, the computed one for Inherited. */
fun effectiveTeam(
    storedTeam: JsonElement?,
    document: EntityDocument,
    definition: BlueprintDefinition,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    rowLookup: RowLookup,
): JsonElement? = if (isInherited(definition)) {
    inheritedTeam(document, definition, blueprintsByIdentifier, rowLookup)
} else {
    storedTeam
}

/**
 * One [segment] of the static walk (definitions only, no document/rowLookup): the target named,
 * and the next blueprint to continue from (null to stop after this one).
 */
private fun ownershipPathStep(
    currentDefinition: BlueprintDefinition,
    segment: String,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
): Pair<String, BlueprintDefinition?>? {
    val relation = currentDefinition.relations[segment] ?: return null
    if (relation.many) return null
    val targetDefinition = blueprintsByIdentifier[relation.target]
    val next = targetDefinition?.takeIf { isInherited(it) }
    return relation.target to next
}

/**
 * The blueprint identifiers [EntityService]'s snapshot must include active entities for, so
 * [inheritedTeam]'s walk never queries the database mid-transaction: every relation TARGET named
 * along [definition]'s `ownership.path`, resolved hop by hop as far as the declared
 * path/[blueprintsByIdentifier] allow — stopping early on an unknown/many/missing hop, the same
 * conditions [inheritedTeam] itself gives up on, so the snapshot never over- or under-loads.
 */
fun ownershipPathBlueprints(definition: BlueprintDefinition, blueprintsByIdentifier: Map<String, BlueprintDefinition>): Set<String> {
    if (!isInherited(definition)) return emptySet()
    val path = definition.ownership?.path?.split('.')?.filter { it.isNotBlank() }.orEmpty()
    if (path.isEmpty()) return emptySet()

    val targets = mutableSetOf<String>()
    var currentDefinition: BlueprintDefinition? = definition
    for (segment in path) {
        val definitionForHop = currentDefinition ?: break
        val step = ownershipPathStep(definitionForHop, segment, blueprintsByIdentifier)
        step?.let { (target, next) ->
            targets += target
            currentDefinition = next
        } ?: run { currentDefinition = null }
    }
    return targets
}
