package ch.nokillswit.blueprints

import io.ktor.server.plugins.BadRequestException

/**
 * Phase 4 of Toadie's move from Backstage's fixed System Model to Port.io's ontology
 * (`.claude/docs/port-data-model.md` "System blueprints"): `_team` and `_user` are Port's own
 * system blueprints, seeded by `V31__system_blueprints.sql` (the ONE documented exception to
 * "no migration seeds blueprints") and protected by [BlueprintService] — no delete, no
 * identifier rename, and no removal/retyping of their BASE shape. Everything else (extra
 * properties/relations, titles, `hierarchyRelation`, `ownership`) is an ordinary admin edit.
 */
const val SYSTEM_TEAM_BLUEPRINT = "_team"
const val SYSTEM_USER_BLUEPRINT = "_user"

/** Identifiers starting with this prefix are reserved — `POST` rejects them with `400`. */
const val SYSTEM_PREFIX = "_"

fun isSystemIdentifier(identifier: String): Boolean = identifier.startsWith(SYSTEM_PREFIX)

/** `_team`'s base shape: no properties, a single optional self-relation naming the entity hierarchy's parent link. */
private val SYSTEM_TEAM_BASE = BlueprintDefinition(
    schema = BlueprintSchema(),
    relations = mapOf(
        "parent" to RelationDefinition(title = "Parent team", target = SYSTEM_TEAM_BLUEPRINT, required = false, many = false),
    ),
)

/** `_user`'s base shape: a required `email` string, and a many-valued optional relation to `_team`. */
private val SYSTEM_USER_BASE = BlueprintDefinition(
    schema = BlueprintSchema(
        properties = mapOf(
            "email" to PropertyDefinition(type = "string", title = "Email", format = "email"),
        ),
        required = listOf("email"),
    ),
    relations = mapOf(
        "team" to RelationDefinition(title = "Team", target = SYSTEM_TEAM_BLUEPRINT, required = false, many = true),
    ),
)

/**
 * The base shape every system blueprint must keep, byte-identical to what `V31` seeds — a test
 * pins that the migrated rows decode to exactly these values.
 */
val SYSTEM_BLUEPRINT_BASES: Map<String, BlueprintDefinition> = mapOf(
    SYSTEM_TEAM_BLUEPRINT to SYSTEM_TEAM_BASE,
    SYSTEM_USER_BLUEPRINT to SYSTEM_USER_BASE,
)

/**
 * Enforced by [BlueprintService.update] whenever the row being replaced is system: the
 * identifier must not change (a rename would strand every entity/relation naming it), every
 * base property must remain present with the same `type`/`format`, `schema.required` must
 * still cover the base's required properties, and every base relation must remain present with
 * the same `target`/`many`. Titles, descriptions, extra properties/relations/mirror/
 * calculation/aggregation fields, `ownership`, and `hierarchyRelation` are free to change.
 */
fun validateSystemExtension(identifier: String, request: BlueprintRequest) {
    val base = SYSTEM_BLUEPRINT_BASES[identifier] ?: return
    if (request.identifier != identifier) {
        throw BadRequestException("System blueprint '$identifier' cannot be renamed")
    }
    base.schema.properties.forEach { (id, baseProperty) ->
        val property = request.schema.properties[id]
            ?: throw BadRequestException("System blueprint '$identifier' requires property '$id'")
        if (property.type != baseProperty.type || property.format != baseProperty.format) {
            val formatSuffix = baseProperty.format?.let { " and format '$it'" }.orEmpty()
            throw BadRequestException("System blueprint '$identifier' property '$id' must keep type '${baseProperty.type}'$formatSuffix")
        }
    }
    base.schema.required.forEach {
        if (it !in request.schema.required) {
            throw BadRequestException("System blueprint '$identifier' requires '$it' to stay in schema.required")
        }
    }
    base.relations.forEach { (id, baseRelation) ->
        val relation = request.relations[id]
            ?: throw BadRequestException("System blueprint '$identifier' requires relation '$id'")
        if (relation.target != baseRelation.target || relation.many != baseRelation.many) {
            throw BadRequestException(
                "System blueprint '$identifier' relation '$id' must keep target '${baseRelation.target}' and many=${baseRelation.many}",
            )
        }
    }
}
