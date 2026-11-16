package ch.nokillswit.entities

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.infra.importing.requireBatchSize
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Bulk change of ONE field across entities of ONE blueprint (2.38.0, `.claude/docs/port-data-model.md`
 * "Bulk change"): `POST /api/v1/entities/bulk-change` + `/check`, any authenticated user (the entity
 * shared-workspace rule — no admin gate). The wire types and the request-level rules live here; the
 * PURE per-row planner is `entities/EntityBulkPlan.kt`; the effectful orchestration (the read ledger,
 * the writes) is `entities/EntityBulkService.kt`. There is deliberately no bulk-log table: the run's
 * server-minted `bulkId` rides each row's history event and audit line only.
 */

/** The request member naming the targets — the 200-entry cap is the ontology imports' (`MAX_IMPORT_DOCUMENTS`), one request, no paging. */
private const val BULK_FIELD_ENTITIES = "entities"

/** `before`/`after` ride a result only up to this many encoded characters each; beyond it both are withheld (`valuesOmitted`). */
internal const val MAX_BULK_VALUE_CHARS = 2_000

@Serializable
enum class EntityBulkOperation { SET, CLEAR, ADD, REMOVE }

@Serializable
enum class EntityBulkStatus { UPDATED, UNCHANGED, INVALID, STALE, ERROR }

/** One target: the entity [id] and, when the client read it, the `updatedAt` it saw (a different stored stamp makes the row `STALE`). */
@Serializable
data class EntityBulkTarget(val id: UInt, val updatedAt: Long? = null)

@Serializable
data class EntityBulkChangeRequest(
    val blueprint: String,
    /** `properties.<id>`, `relations.<id>` or `team` — the findings/history path grammar. */
    val field: String,
    val operation: EntityBulkOperation,
    /** SET: the new value (any JSON). ADD/REMOVE: a non-empty array of items. CLEAR: absent. */
    val value: JsonElement? = null,
    val entities: List<EntityBulkTarget>,
)

@Serializable
data class EntityBulkResult(
    val id: UInt,
    val identifier: String? = null,
    val title: String? = null,
    val status: EntityBulkStatus,
    val message: String? = null,
    val findings: List<EntityFinding>? = null,
    /** The stored write-version stamp after this row's outcome (the new one for `UPDATED`); absent when the entity is gone. */
    val updatedAt: Long? = null,
    val before: JsonElement? = null,
    val after: JsonElement? = null,
    /** True when `before`/`after` exceeded [MAX_BULK_VALUE_CHARS] and were withheld. */
    val valuesOmitted: Boolean? = null,
)

@Serializable
data class EntityBulkChangeResponse(
    /** The run's identifier (real run only) — also the `bulkId` param of each written row's history event and its audit line. */
    val bulkId: String? = null,
    val results: List<EntityBulkResult>,
)

/** One committed row, handed to the route's audit callback right after its transaction committed. */
internal data class EntityBulkCommit(val id: UInt, val blueprint: String, val identifier: String, val field: String, val bulkId: String)

/** Which part of the entity a [ResolvedBulkField] addresses. */
internal enum class BulkSection { PROPERTIES, RELATIONS, TEAM }

/** The field a bulk change addresses, resolved against the blueprint: [listLike] fields accept ADD/REMOVE. */
internal data class ResolvedBulkField(val path: String, val section: BulkSection, val key: String?, val listLike: Boolean)

/** A request that passed every request-level rule, with its field resolved against the blueprint's CURRENT definition. */
internal data class ResolvedBulkChange(
    val blueprint: String,
    val definition: BlueprintDefinition,
    val field: ResolvedBulkField,
    val operation: EntityBulkOperation,
    val value: JsonElement?,
)

/**
 * The request-SHAPE rules that need no blueprint: 1..200 targets, distinct ids, no negative stamp, the operation/value pairing
 * of SET and CLEAR and a bounded value. Run by the service for both endpoints, AFTER the route's guard and `receive` (401 wins).
 */
internal fun requireBulkShape(request: EntityBulkChangeRequest) {
    if (request.entities.isEmpty()) throw BadRequestException("$BULK_FIELD_ENTITIES must have at least 1 entry")
    requireBatchSize(request.entities.size, BULK_FIELD_ENTITIES)
    if (request.entities.map { it.id }.toSet().size != request.entities.size) {
        throw BadRequestException("$BULK_FIELD_ENTITIES must not name the same entity twice")
    }
    if (request.entities.any { (it.updatedAt ?: 0L) < 0 }) throw BadRequestException("updatedAt must not be negative")
    val value = request.value
    when (request.operation) {
        EntityBulkOperation.SET -> if (value == null) throw BadRequestException("SET needs a value")
        EntityBulkOperation.CLEAR -> if (value != null) throw BadRequestException("CLEAR takes no value")
        EntityBulkOperation.ADD, EntityBulkOperation.REMOVE ->
            if (value !is JsonArray || value.isEmpty()) throw BadRequestException("${request.operation} needs a non-empty array of items")
    }
    if (value != null && blueprintJson.encodeToString(value).toByteArray(Charsets.UTF_8).size > MAX_ENTITY_DOCUMENT_BYTES) {
        throw BadRequestException("value must be at most $MAX_ENTITY_DOCUMENT_BYTES bytes")
    }
}

/**
 * The field against [definition]: declared (a computed property and an Inherited blueprint's `team` are never writable), and the
 * operation fitting it (ADD/REMOVE only on a list — an `array` property, a `many` relation, `team`). Every refusal is a request-level
 * `400`: the same verdict for every row, so it is never a per-row status.
 */
internal fun resolveBulkChange(request: EntityBulkChangeRequest, definition: BlueprintDefinition): ResolvedBulkChange {
    val field = resolveBulkField(request.field, definition)
    val listOnly = request.operation == EntityBulkOperation.ADD || request.operation == EntityBulkOperation.REMOVE
    if (listOnly && !field.listLike) {
        throw BadRequestException("${request.operation} only applies to a list field; '${field.path}' is not one")
    }
    return ResolvedBulkChange(request.blueprint, definition, field, request.operation, request.value)
}

private fun resolveBulkField(path: String, definition: BlueprintDefinition): ResolvedBulkField = when {
    path == "team" -> {
        if (isInherited(definition)) {
            throw BadRequestException("team is computed on this blueprint (Inherited ownership) and cannot be changed")
        }
        ResolvedBulkField(path, BulkSection.TEAM, null, listLike = true)
    }
    path.startsWith("properties.") -> {
        val id = path.removePrefix("properties.")
        val declared = definition.schema.properties[id]
        if (declared == null) {
            val computed = id in computedPropertyIds(definition)
            throw BadRequestException(
                if (computed) "'$id' is a computed property and cannot be changed" else "'$id' is not a declared property",
            )
        }
        ResolvedBulkField(path, BulkSection.PROPERTIES, id, listLike = declared.type == "array")
    }
    path.startsWith("relations.") -> {
        val id = path.removePrefix("relations.")
        val declared = definition.relations[id] ?: throw BadRequestException("'$id' is not a declared relation")
        ResolvedBulkField(path, BulkSection.RELATIONS, id, listLike = declared.many)
    }
    else -> throw BadRequestException("field must be 'team', 'properties.<id>' or 'relations.<id>'")
}

/** The field's STORED value on [entity] (explicit null is the same absence as a missing key), null when absent. */
internal fun StoredEntity.fieldValue(field: ResolvedBulkField): JsonElement? =
    when (field.section) {
        BulkSection.PROPERTIES -> properties[field.key]
        BulkSection.RELATIONS -> relations[field.key]
        BulkSection.TEAM -> team
    }.takeUnless { it is JsonNull }

/**
 * The field's value after [operation] ([value] per the request), null = the key is dropped. SET replaces, CLEAR drops; ADD appends
 * the items not yet present (set-like, order-preserving, the value's own duplicates collapsed) and leaves the field untouched when
 * there is nothing to add; REMOVE drops the listed items and drops the key when none is left (untouched when none matched). A
 * non-list stored value (a team stored as a bare string) counts as the one-item list it is.
 */
internal fun applyBulkOperation(existing: JsonElement?, operation: EntityBulkOperation, value: JsonElement?): JsonElement? {
    val items: List<JsonElement> = (value as? JsonArray).orEmpty()
    val current = when (existing) {
        null -> emptyList()
        is JsonArray -> existing.toList()
        else -> listOf(existing)
    }
    return when (operation) {
        EntityBulkOperation.SET -> value
        EntityBulkOperation.CLEAR -> null
        EntityBulkOperation.ADD -> {
            val added = items.distinct().filterNot { it in current }
            if (added.isEmpty()) existing else JsonArray(current + added)
        }
        EntityBulkOperation.REMOVE -> {
            val remaining = current.filterNot { it in items }
            when {
                remaining.size == current.size -> existing
                remaining.isEmpty() -> null
                else -> JsonArray(remaining)
            }
        }
    }
}

/** [entity] with the field set to [newValue] (null drops the key) as the PUT-shaped request the ordinary strict write takes. */
internal fun StoredEntity.withField(field: ResolvedBulkField, newValue: JsonElement?): EntityRequest {
    fun JsonObject.with(key: String): JsonObject =
        JsonObject(toMutableMap().also { if (newValue == null) it.remove(key) else it[key] = newValue })
    return EntityRequest(
        blueprint = blueprint,
        identifier = identifier,
        title = title,
        icon = icon,
        team = if (field.section == BulkSection.TEAM) newValue else team,
        properties = if (field.section == BulkSection.PROPERTIES) properties.with(field.key.orEmpty()) else properties,
        relations = if (field.section == BulkSection.RELATIONS) relations.with(field.key.orEmpty()) else relations,
    )
}
