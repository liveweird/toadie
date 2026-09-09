package ch.nokillswit.entities

import ch.nokillswit.blueprints.BlueprintDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure, DB-free helpers over a decoded [EntityDocument] + its owning [BlueprintDefinition] — no
 * service, no transaction. [EntityService] uses these under its table lock to check target
 * existence and to cascade an identifier rename onto every OTHER entity's targets — the
 * `blueprints/BlueprintReferences.kt` precedent, one level down.
 */

/** Every (targetBlueprintIdentifier, targetEntityIdentifier) this document's relations name. */
fun entityTargets(document: EntityDocument, definition: BlueprintDefinition): Set<Pair<String, String>> {
    val targets = mutableSetOf<Pair<String, String>>()
    document.relations.forEach { (id, value) ->
        val relationDef = definition.relations[id] ?: return@forEach
        stringValues(value).forEach { targets += relationDef.target to it }
    }
    return targets
}

private fun stringValues(value: JsonElement): List<String> = when (value) {
    is JsonPrimitive -> if (value.isString) listOf(value.content) else emptyList()
    is JsonArray -> value.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.content }
    else -> emptyList()
}

/**
 * Rewrites every relation naming [old] within [targetBlueprint] to [new] (byte-exact match) —
 * only relations whose OWN definition targets [targetBlueprint] are touched, so a rename can
 * never bleed into a same-named entity of a different blueprint.
 */
fun withEntityTargetRenamed(
    document: EntityDocument,
    definition: BlueprintDefinition,
    targetBlueprint: String,
    old: String,
    new: String,
): EntityDocument {
    val relations = JsonObject(
        document.relations.mapValues { (id, value) ->
            val relationDef = definition.relations[id]
            if (relationDef == null || relationDef.target != targetBlueprint) value else renamedRelationValue(value, old, new)
        },
    )
    return document.copy(relations = relations)
}

private fun renamedRelationValue(value: JsonElement, old: String, new: String): JsonElement = when (value) {
    is JsonPrimitive -> if (value.isString && value.content == old) JsonPrimitive(new) else value
    is JsonArray -> JsonArray(value.map { renamedElement(it, old, new) })
    else -> value
}

private fun renamedElement(element: JsonElement, old: String, new: String): JsonElement =
    if (element is JsonPrimitive && element.isString && element.content == old) JsonPrimitive(new) else element
