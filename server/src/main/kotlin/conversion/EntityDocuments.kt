package ch.nokillswit.conversion

import ch.nokillswit.catalog.CatalogFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The wire shapes of the Backstage -> Port conversion (`POST …/conversion-mappings/{id}/entity-documents`,
 * phase 4): a PURE, unaudited computation. [EntityDocumentsResponse.documents] are exactly the
 * documents `POST /api/v1/entities/import` accepts (blueprint, identifier, title, icon?, team?,
 * properties, relations — an INVALID or SKIPPED row has none), [EntityDocumentsResponse.rows]
 * report what happened to every submitted file, one row each, in request order. The no-`null`
 * wire posture of [CatalogDocumentsResponse] applies (the route encodes with `blueprintJson`).
 */
@Serializable
data class EntityDocumentsRequest(
    /** The Backstage documents, at most `MAX_IMPORT_DOCUMENTS`. */
    val files: List<CatalogFile>,
    /** Merge each document over the stored entity of the same (blueprint, identifier) — the default; off = the bare document. */
    val merge: Boolean = true,
)

@Serializable
data class EntityDocumentRow(
    /** Position in the request's `files` (and in [EntityDocumentsResponse.rows]). */
    val index: Int,
    val status: ConversionRowStatus,
    /** The submitted document's identity as the engine read it (kind as submitted, namespace lowercased, blank resolved to the default). */
    val kind: String,
    val namespace: String,
    val name: String,
    /** The blueprint the document converts to; absent on a SKIPPED row and on one refused before a pair was chosen. */
    val blueprint: String? = null,
    /** The Port identifier (the Backstage `metadata.name`); absent exactly when [blueprint] is. */
    val identifier: String? = null,
    /** The stored entity the document was merged over; absent without `merge` or a match. */
    val existingId: UInt? = null,
    val findings: List<ConversionFinding>,
    /** Backstage fields the document carries that no rule of its pair addresses (`spec.dependencyOf`, `metadata.labels.<key>`, ...). */
    val unmapped: List<String>,
)

@Serializable
data class EntityDocumentsResponse(
    val documents: List<JsonObject>,
    val rows: List<EntityDocumentRow>,
)
