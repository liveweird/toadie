package ch.nokillswit.entityquery

import ch.nokillswit.blueprints.BlueprintTiers
import ch.nokillswit.blueprints.MAX_TIER
import ch.nokillswit.entities.candidateValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The query-only fill-in tier metas (2.18.0, `.claude/docs/entity-query-language.md`): `n.$tier`
 * (the blueprint's tier), `n.$fillTier` (the highest tier an entity has fully filled) and
 * `r.$tier` (a relationship's tier on its source blueprint). Tiers are a visual hint and change
 * no logic, so these live ONLY here — never in `QUERY_META_PROPERTIES`, which aggregation rules
 * and mirror terminals share. Pure: no database, no Ktor.
 */

/**
 * A stored property counts as filled when present and not JSON `null`, a blank string, `[]` or
 * `{}` — `false` and `0` ARE filled values.
 */
internal fun isFilledProperty(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonArray -> value.isNotEmpty()
    is JsonObject -> value.isNotEmpty()
    is JsonPrimitive -> !value.isString || value.content.isNotBlank()
}

/**
 * A relation value counts as filled when it is a non-blank string, or an array with at least one
 * non-blank string element. Whether the target resolves is the findings' job, not the tier's.
 */
internal fun isFilledRelation(value: JsonElement?): Boolean = when (value) {
    is JsonPrimitive -> value.isString && value.content.isNotBlank()
    is JsonArray -> value.any { isFilledRelation(it as? JsonPrimitive) }
    else -> false
}

/**
 * The highest T in 0..[MAX_TIER] such that every tiered property and relation of tier <= T is
 * filled; `null` when [tiers] names no tiered property or relation (untiered is not complete).
 * Tiers that carry no field count as satisfied, so the answer is one below the lowest tier of an
 * unfilled field, or [MAX_TIER] when everything is filled. [properties] is called AT MOST once
 * and only when a property is tiered — a blueprint with only tiered relations never decodes (or
 * charges for) the row's document.
 */
internal fun computeFillTier(tiers: BlueprintTiers, relations: JsonObject, properties: () -> JsonObject): Int? {
    if (tiers.properties.isEmpty() && tiers.relations.isEmpty()) return null
    val unfilledRelationTiers = tiers.relations.filterKeys { !isFilledRelation(relations[it]) }.values
    val unfilledPropertyTiers = if (tiers.properties.isEmpty()) {
        emptyList()
    } else {
        val document = properties()
        tiers.properties.filterKeys { !isFilledProperty(document[it]) }.values
    }
    val lowestUnfilled = (unfilledRelationTiers + unfilledPropertyTiers).minOrNull()
    return if (lowestUnfilled == null) MAX_TIER else lowestUnfilled - 1
}

/**
 * One traversed single-hop relationship, as `r.$tier` reads it: the [source] entity the relation
 * value lives on and the CONCRETE [relationKey] on its blueprint (a hierarchy virtual edge type
 * is already resolved to the blueprint's relation; `null` for the `$team` ownership pseudo edge).
 */
internal data class EdgeRef(val source: QueryRow, val relationKey: String?) {
    /** The relation's tier on the SOURCE blueprint; `null` when unset or for an ownership edge. */
    val tier: Int? get() = relationKey?.let { source.blueprint.tiers.relations[it] }
}

/** The one stage between a node variable's `.key` and its value: the two tier metas, else [candidateValue]. */
internal fun nodeValue(key: String, row: QueryRow): JsonElement? = when (key) {
    TIER_META -> row.blueprint.tiers.blueprint?.let { JsonPrimitive(it) }
    FILL_TIER_META -> row.fillTier?.let { JsonPrimitive(it) }
    else -> candidateValue(key, row.candidate)
}
