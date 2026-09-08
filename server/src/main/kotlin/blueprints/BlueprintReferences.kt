package ch.nokillswit.blueprints

/**
 * Pure, DB-free helpers over a decoded [BlueprintDefinition] — no service, no transaction.
 * [BlueprintService] uses these under its table lock to check target existence and to
 * cascade an identifier rename onto every OTHER blueprint's targets.
 */

/**
 * The identifiers this blueprint's definition TARGETS: relation targets ∪ aggregation
 * targets. Mirror paths and ownership paths name RELATION ids (this blueprint's own, not
 * another blueprint's identifier), so they never change on a rename.
 */
fun blueprintTargets(definition: BlueprintDefinition): Set<String> =
    definition.relations.values.map { it.target }.toSet() +
        definition.aggregationProperties.values.map { it.target }.toSet()

/** Rewrites every relation/aggregation target equal to [old] to [new] (byte-exact match). */
fun withTargetRenamed(definition: BlueprintDefinition, old: String, new: String): BlueprintDefinition = definition.copy(
    relations = definition.relations.mapValues { (_, relation) ->
        if (relation.target == old) relation.copy(target = new) else relation
    },
    aggregationProperties = definition.aggregationProperties.mapValues { (_, aggregation) ->
        if (aggregation.target == old) aggregation.copy(target = new) else aggregation
    },
)
