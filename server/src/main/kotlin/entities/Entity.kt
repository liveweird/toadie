package ch.nokillswit.entities

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Port migration phase 2 (`.claude/docs/port-data-model.md`): **entities** — instances of a
 * [ch.nokillswit.blueprints.BlueprintDefinition]. Every entity belongs to one blueprint (by ID,
 * so a blueprint rename never touches it), carries `properties` typed by that blueprint's
 * `schema`, and `relations` naming other entities of the target blueprints. Nothing evaluates
 * mirror/calculation/aggregation properties (Port computes those); they are never accepted as
 * input keys ([EntityFinding] code `COMPUTED_PROPERTY`).
 *
 * Wire shape mirrors `blueprints/Blueprint.kt`: a typed skeleton for identity fields, raw
 * [JsonObject]/[JsonElement] for the genuinely open `properties`/`relations`/`team` trees.
 * Both the STORED encoding ([EntityDocument], the `entities.document` TEXT column) and every
 * HTTP response go through [ch.nokillswit.blueprints.blueprintJson] (`explicitNulls = false`)
 * so unset optionals are ABSENT, never explicit `null` — the same Port-shape round trip
 * phase 1 established, reused verbatim rather than declaring a second identical `Json` instance.
 */
const val MAX_ENTITIES_PER_BLUEPRINT = 2000
const val MAX_ENTITIES_TOTAL = 10_000
const val MAX_ENTITY_IDENTIFIER_LENGTH = 200
const val MAX_ENTITY_TITLE_LENGTH = 200
const val MAX_ENTITY_ICON_LENGTH = 100
const val MAX_ENTITY_TEAM_ENTRIES = 50
const val MAX_ENTITY_TEAM_LENGTH = 100

// The whole stored {properties, relations} document, serialized — a sanity ceiling independent
// of the per-field caps (the blueprints.definition precedent).
const val MAX_ENTITY_DOCUMENT_BYTES = 256 * 1024

@Serializable
data class EntityRequest(
    val blueprint: String,
    val identifier: String,
    val title: String,
    val icon: String? = null,
    val team: JsonElement? = null,
    val properties: JsonObject = JsonObject(emptyMap()),
    val relations: JsonObject = JsonObject(emptyMap()),
)

/** The non-identity fields as the stored document (`entities.document`). */
@Serializable
data class EntityDocument(val properties: JsonObject, val relations: JsonObject)

/** One rule-table violation; `field` is `properties.<id>`, `relations.<id>`, or `team`. */
@Serializable
data class EntityFinding(val code: String, val field: String, val message: String)

/**
 * Thrown by [EntityService.create]/[EntityService.update] when [entityFindings] is non-empty —
 * the aggregated strict-save `400`. Carries the FULL list so `plugins/ErrorHandling.kt` can
 * surface it as an [EntityInvalidProblem] extension member on the RFC 7807 body (never on any
 * OTHER 400 — shape-rule rejections stay a plain [ch.nokillswit.plugins.ProblemDetail]).
 */
class EntityInvalidException(val findings: List<EntityFinding>) :
    RuntimeException(findings.joinToString("; ") { "${it.field}: ${it.message}" })

/**
 * [ch.nokillswit.plugins.ProblemDetail] plus the full [EntityFinding] list — the entity create/
 * replace `400` body only (`EntityInvalidProblem` in the OpenAPI contract), so the SPA can paint
 * per-field errors after a rejected save without re-parsing `detail`.
 */
@Serializable
data class EntityInvalidProblem(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val findings: List<EntityFinding>,
)

@Serializable
data class EntityResponse(
    val id: UInt,
    val blueprint: String,
    val blueprintId: UInt,
    val identifier: String,
    val title: String,
    val icon: String? = null,
    val team: JsonElement? = null,
    val properties: JsonObject,
    val relations: JsonObject,
    val findings: List<EntityFinding>,
    val createdBy: UInt,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias EntityPageResponse = PageResponse<EntityResponse>

/**
 * The request's `properties`/`relations` as the stored document: an explicit JSON `null` value
 * means "unset" (Port's relation convention, extended to properties for the same reason a
 * non-required `null` should never trip `TYPE_MISMATCH`) and is dropped rather than stored, so
 * "absent" and "explicitly cleared" collapse to one stored shape.
 */
fun EntityRequest.toDocument(): EntityDocument = EntityDocument(
    properties = JsonObject(properties.filterValues { it != JsonNull }),
    relations = JsonObject(relations.filterValues { it != JsonNull }),
)

/** Trims identifier/title/icon (control characters -> 400); never rewrites keys or values. */
fun sanitizedEntityRequest(request: EntityRequest): EntityRequest = request.copy(
    identifier = sanitizeSingleLine(request.identifier, "identifier"),
    title = sanitizeSingleLine(request.title, "title"),
    icon = request.icon?.trim(),
)
