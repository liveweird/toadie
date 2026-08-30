package ch.nokillswit.catalog

import kotlinx.serialization.Serializable

/**
 * The YAML round-trip's server halves. YAML itself stays a CLIENT concern (the standing
 * decision): export ships structured documents the SPA renders/joins with its tested
 * generator; import receives documents the SPA parsed from YAML and stores them with the
 * ordinary validation/creation machinery — each document independently (report & skip).
 */
const val MAX_IMPORT_FILES = 200

@Serializable
data class ExportResponse(
    /** The active documents, ordered by (namespace, name) for a deterministic file. */
    val files: List<CatalogFile>,
)

@Serializable
data class ImportRequest(
    val files: List<CatalogFile>,
    /**
     * The URL the batch was fetched from (the import page's fetch-from-URL flow) — every
     * stored row gets it as its source reference AND starts synced (the content IS the repo
     * copy at import time). Omitted for pasted/uploaded batches. One URL for the whole
     * request on purpose: a multi-document catalog-info.yaml is ONE repo file.
     */
    val sourceUrl: String? = null,
)

@Serializable
enum class ImportResultStatus {
    /** Stored; `fileId` points at the new file. */
    CREATED,

    /**
     * Stored DESPITE soft findings (unresolved references or registry violations) — import
     * always waives them so a flawed batch can land and be fixed incrementally. `fileId`
     * points at the new file; `message` lists the findings; the Errors report tracks them.
     */
    CREATED_WITH_FINDINGS,

    /** Failed the structural descriptor-format validation or namespace resolution; `message` carries the rule. */
    INVALID,

    /** An active file already holds this identity — nothing overwritten (report & skip). */
    CONFLICT,

    /** An unexpected storage failure for this document; the rest of the batch proceeded. */
    ERROR,
}

@Serializable
data class ImportFileResult(
    /** The document's position in the submitted batch (0-based). */
    val index: Int,
    val kind: String,
    val namespace: String,
    val name: String,
    val status: ImportResultStatus,
    val fileId: UInt? = null,
    val message: String? = null,
)

@Serializable
data class ImportResponse(
    val results: List<ImportFileResult>,
)
