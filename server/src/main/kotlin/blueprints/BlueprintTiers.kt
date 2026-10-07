package ch.nokillswit.blueprints

import io.ktor.server.plugins.BadRequestException

/**
 * Fill-in tiers (2.18.0, V44): a Toadie-only 1-4 priority hint stored BESIDE the Port document
 * like `hierarchyRelations` (`.claude/docs/port-data-model.md` "Tiers"). Tiers change NO logic —
 * no validation, finding or computed value ever reads them. The [BlueprintTiers] DTO lives in
 * `Blueprint.kt` with the other wire shapes; every rule lives here.
 */
const val MIN_TIER = 1
const val MAX_TIER = 4

/** True when [this] carries no tier at any level. */
internal fun BlueprintTiers.isEmpty(): Boolean = blueprint == null && properties.isEmpty() && relations.isEmpty()

/** All-empty folds to `null` (absent on the wire), the "omitted means unset" convention of every optional collection here. */
internal fun sanitizedTiers(tiers: BlueprintTiers?): BlueprintTiers? = tiers?.takeUnless { it.isEmpty() }

private fun requireTier(value: Int, field: String) {
    if (value !in MIN_TIER..MAX_TIER) throw BadRequestException("$field must be an integer between $MIN_TIER and $MAX_TIER")
}

/**
 * Shape-only validation (400): every value an integer in 1..4, `tiers.properties` keys name
 * keys of THIS request's `schema.properties` (a computed property is nobody's to fill in, so
 * mirror/calculation/aggregation ids are rejected), `tiers.relations` keys name keys of its
 * `relations`. A dangling key is a client typo, exactly as for `hierarchyRelations`; maps the
 * SERVER carries over (sync, import pass 1) are pruned with [prunedTo] instead, never refused.
 */
internal fun validateTiers(request: BlueprintRequest) {
    val tiers = request.tiers ?: return
    tiers.blueprint?.let { requireTier(it, "tiers.blueprint") }
    tiers.properties.forEach { (key, value) ->
        if (key !in request.schema.properties) {
            throw BadRequestException("tiers.properties.$key must name a schema property of this blueprint")
        }
        requireTier(value, "tiers.properties.$key")
    }
    tiers.relations.forEach { (key, value) ->
        if (key !in request.relations) {
            throw BadRequestException("tiers.relations.$key must name a relation of this blueprint")
        }
        requireTier(value, "tiers.relations.$key")
    }
}

/** [this] restricted to the given keys; `null` when nothing is left. */
internal fun BlueprintTiers.prunedTo(propertyKeys: Set<String>, relationKeys: Set<String>): BlueprintTiers? =
    sanitizedTiers(copy(properties = properties.filterKeys { it in propertyKeys }, relations = relations.filterKeys { it in relationKeys }))

/** The `blueprints.tiers` column's JSON-object-in-TEXT codec (V44); `"{}"` is the unset value. */
internal fun decodeTiers(raw: String): BlueprintTiers? =
    sanitizedTiers(blueprintJson.decodeFromString<BlueprintTiers>(raw))

internal fun encodeTiers(tiers: BlueprintTiers?): String =
    sanitizedTiers(tiers)?.let { blueprintJson.encodeToString(it) } ?: "{}"
