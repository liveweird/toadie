package ch.nokillswit.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    /**
     * Off (default): an identity an active file already holds is a `CONFLICT` and nothing is
     * overwritten. On: that file is REPLACED in place through the ordinary replace path — the
     * ontology imports' `replaceExisting`, one level over — reported `UPDATED`,
     * `UPDATED_WITH_FINDINGS` or `UNCHANGED`. See `.claude/docs/persistence.md` "Catalog import".
     */
    val replaceExisting: Boolean = false,
    /**
     * Optional per-file write-version expectations (2.29.0), by `files` index: a positive `revision`
     * is the stored counterpart's `revision` (a conversion row's `existingRevision`), `0` means
     * "expect NO stored counterpart" (each entry may also pin the counterpart's `fileId`). A file whose
     * counterpart changed, was replaced by another file, vanished or appeared is refused as `STALE`;
     * a file without an entry is unguarded, exactly as before. See
     * `.claude/docs/persistence.md` "Import expectations (entities and catalog files)".
     */
    val expected: List<ExpectedFileState> = emptyList(),
)

/**
 * One [ImportRequest.expected] entry: the `revision` file [index] expects of its stored counterpart (0 = none).
 * A revision restarts at 1 for every row, so [fileId] — the counterpart's id, a conversion row's `existingId` —
 * pins WHICH file the revision belongs to: a file deleted and re-created (or renamed into the identity) at an
 * equal revision is a different file, hence STALE. Optional; only valid beside a positive revision.
 */
@Serializable
data class ExpectedFileState(val index: Int, val revision: Long, val fileId: UInt? = null)

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

    /**
     * `replaceExisting`: an active file with this identity was replaced in place (the ordinary
     * replace path — history event, revision bump); `fileId` points at it. Soft findings are
     * waived exactly as for CREATED_WITH_FINDINGS.
     */
    UPDATED,

    /** [UPDATED] DESPITE soft findings (waived); `message` lists them. */
    UPDATED_WITH_FINDINGS,

    /**
     * `replaceExisting`: the active file already held this exact document and source reference,
     * so nothing changed in the document. Without a batch `sourceUrl` that is the ordinary
     * replace's no-op (no history event, no revision bump, no stamp change); WITH one the row is
     * still a repo sync and records the always-recorded SYNCED event, bumps the revision and
     * re-stamps the sync state, exactly as `POST /files/{id}/sync` does for a matching copy.
     * `fileId` points at the file; `message` still lists any soft findings it carries.
     */
    UNCHANGED,

    /** Failed the structural descriptor-format validation or namespace resolution; `message` carries the rule. */
    INVALID,

    /** An active file already holds this identity — nothing overwritten (report & skip). */
    CONFLICT,

    /** An unexpected storage failure for this document; the rest of the batch proceeded. */
    ERROR,

    /**
     * The request's `expected` revision for this file no longer holds: its stored counterpart
     * changed, vanished or appeared since the conversion that produced the document. Nothing is
     * stored (no history event, no revision bump); `fileId` names the counterpart on the real run
     * when one exists, and stays null on the dry-run like every prediction.
     */
    STALE,
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
    /** The soft findings a stored/replaced row waived — server-side only (the route's audit count), never serialized. */
    @Transient val waivedFindings: Int = 0,
)

@Serializable
data class ImportResponse(
    val results: List<ImportFileResult>,
)
