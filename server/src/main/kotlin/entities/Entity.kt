package ch.nokillswit.entities

import ch.nokillswit.infra.fetch.sanitizedSourceUrl
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.InvalidPayloadException
import ch.nokillswit.infra.validation.invalidPayloadJson
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Port migration phase 2 (`.claude/docs/port-data-model.md`): **entities** — instances of a
 * [ch.nokillswit.blueprints.BlueprintDefinition]. Every entity belongs to one blueprint (by ID,
 * so a blueprint rename never touches it), carries `properties` typed by that blueprint's
 * `schema`, and `relations` naming other entities of the target blueprints. Since phase 5
 * (v1.27.0, `entities/EntityComputed.kt`), a mirror/calculation/aggregation property id is never
 * accepted as WRITE input ([EntityFinding] code `COMPUTED_PROPERTY`) but IS evaluated at read
 * time and merged into every GET/list/create response's `properties` — an unresolvable value is
 * simply ABSENT there, never a finding.
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

// The WORKSPACE-wide budget of stored document + team bytes over active rows (2.4.0), enforced
// on create/replace/import under the V28 lock: ENTITY_READ_BUDGET_BYTES / 4, so a whole
// workspace fits a read with its decoded relations/team beside it. Sample data is ~16 KiB.
const val MAX_WORKSPACE_DOCUMENT_BYTES = 16L * 1024 * 1024

@Serializable
data class EntityRequest(
    val blueprint: String,
    val identifier: String,
    val title: String,
    val icon: String? = null,
    val team: JsonElement? = null,
    val properties: JsonObject = JsonObject(emptyMap()),
    val relations: JsonObject = JsonObject(emptyMap()),
    /**
     * The entity's source reference (2.9.0, the `catalog_files.source_url` twin, one level
     * down) — the https URL of its canonical remote copy. Flat on the request body: unlike
     * `catalog/CatalogFileWriteRequest`, an entity body IS the Port document plus `blueprint`,
     * so there is no separate envelope wrapper. Row state, never part of a bulk-import
     * DOCUMENT ([requireNoDocumentSourceUrl]) — set via the editor's Source fieldset, PUT
     * full-replace semantics (omitted/blank clears it), or stamped by [EntityService.import]'s
     * batch `sourceUrl`/`POST …/entities/{id}/sync`.
     */
    val sourceUrl: String? = null,
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
    InvalidPayloadException(findings.joinToString("; ") { "${it.field}: ${it.message}" }) {
    override fun problemJson(title: String, status: Int, instance: String?): String =
        invalidPayloadJson.encodeToString(
            EntityInvalidProblem.serializer(),
            EntityInvalidProblem(title = title, status = status, detail = message, instance = instance, findings = findings),
        )
}

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
    /** The entity's source reference; absent = none set. */
    val sourceUrl: String? = null,
    /** Epoch millis of the last HTTP→DB sync; 0 = never. */
    val lastSyncedAt: Long,
)

typealias EntityPageResponse = PageResponse<EntityResponse>

/** GET …/entities/{id}/sync — the sync state incl. the baseline document stored at the last sync. */
@Serializable
data class EntitySyncStateResponse(
    val sourceUrl: String? = null,
    /** Epoch millis; 0 = never synced. */
    val lastSyncedAt: Long,
    /** The document as stored at the last sync — the DB-vs-remote comparison baseline; absent = never. */
    val syncedDocument: EntityRequest? = null,
)

/** POST …/entities/{id}/sync — the remote copy, parsed/decoded client-side. */
@Serializable
data class SyncEntityRequest(val document: EntityRequest)

/**
 * The entity write path's column-write policy for the `sourceUrl`/`lastSyncedAt`/`synced_content`
 * envelope (2.9.0, the `catalog/CatalogFileService.kt` create/update precedent, one level down):
 * [FromRequest] is the ordinary create/PUT posture (the submitted `sourceUrl`, resetting the sync
 * stamp when it differs from the stored one); [Keep] leaves all three columns untouched (an
 * import row replaced without a batch `sourceUrl`, D3); [Synced] is a fetch-backed write (a
 * batch import with a `sourceUrl`, or `POST …/entities/{id}/sync`) — stamps the reference, the
 * sync timestamp, and the baseline document together.
 */
sealed interface SourceWrite {
    data object FromRequest : SourceWrite

    data object Keep : SourceWrite

    data class Synced(val sourceUrl: String) : SourceWrite
}

/**
 * A bulk-import DOCUMENT never carries `sourceUrl` itself — it is row state for the WHOLE
 * request ([EntityImportRequest.sourceUrl]), not a per-document member (`.claude/docs/
 * port-data-model.md` "Import and export"). Thrown before the row's ordinary validation so the
 * message is specific rather than a generic unknown-member decode failure.
 */
fun requireNoDocumentSourceUrl(request: EntityRequest) {
    if (request.sourceUrl != null) {
        throw BadRequestException("sourceUrl is row state set for the whole request, not a document member")
    }
}

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

/** Trims identifier/title/icon (control characters -> 400) and the source reference; never rewrites keys or values. */
fun sanitizedEntityRequest(request: EntityRequest): EntityRequest = request.copy(
    identifier = sanitizeSingleLine(request.identifier, "identifier"),
    title = sanitizeSingleLine(request.title, "title"),
    icon = request.icon?.trim(),
    sourceUrl = sanitizedSourceUrl(request.sourceUrl),
)
