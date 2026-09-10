package ch.nokillswit.entities

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure, DB-free helpers over a decoded [EntityDocument] + its owning [BlueprintDefinition] — no
 * service, no transaction. [EntityService] uses these under its table lock to check target
 * existence and to cascade an identifier rename onto every OTHER entity's targets — the
 * `blueprints/BlueprintReferences.kt` precedent, one level down. [withTeamRenamed]/
 * [formatTargets]/[withFormatTargetRenamed] (Phase 4) extend the same rename-cascade shape to the
 * `team` column and `format: team|user` PROPERTY values, which are not relations at all.
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

/** Rewrites [old] to [new] wherever it appears in a `team` value (scalar or array), byte-exact — the `team` column's own rename shape. */
fun withTeamRenamed(team: JsonElement?, old: String, new: String): JsonElement? = when (team) {
    null -> null
    is JsonPrimitive -> if (team.isString && team.content == old) JsonPrimitive(new) else team
    is JsonArray -> JsonArray(team.map { if (it is JsonPrimitive && it.isString && it.content == old) JsonPrimitive(new) else it })
    else -> team
}

/**
 * Every value a `format: [format]` (`team` | `user`) property of [document] names, scalar or
 * array-item — the properties-side counterpart of [entityTargets], since these are not relations.
 */
fun formatTargets(document: EntityDocument, definition: BlueprintDefinition, format: String): Set<String> {
    val targets = mutableSetOf<String>()
    document.properties.forEach { (id, value) ->
        val propertyDef = definition.schema.properties[id] ?: return@forEach
        targets += formatValues(propertyDef, value, format)
    }
    return targets
}

private fun formatValues(def: PropertyDefinition, value: JsonElement, format: String): List<String> = when {
    def.type == "string" && def.format == format && value is JsonPrimitive && value.isString -> listOf(value.content)
    def.type == "array" && def.items?.format == format && value is JsonArray ->
        value.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.content }
    else -> emptyList()
}

/** Rewrites [old] to [new] wherever it appears in a `format: [format]` property value (scalar or array item) of [document]. */
fun withFormatTargetRenamed(
    document: EntityDocument,
    definition: BlueprintDefinition,
    format: String,
    old: String,
    new: String,
): EntityDocument {
    val properties = JsonObject(
        document.properties.mapValues { (id, value) ->
            val propertyDef = definition.schema.properties[id]
            if (propertyDef == null) value else renamedFormatValue(propertyDef, value, format, old, new)
        },
    )
    return document.copy(properties = properties)
}

private fun renamedFormatValue(def: PropertyDefinition, value: JsonElement, format: String, old: String, new: String): JsonElement = when {
    def.type == "string" && def.format == format && value is JsonPrimitive && value.isString && value.content == old -> JsonPrimitive(new)
    def.type == "array" && def.items?.format == format && value is JsonArray -> JsonArray(value.map { renamedElement(it, old, new) })
    else -> value
}
