package ch.nokillswit.catalog

import ch.nokillswit.infra.fetch.SourceWrite
import ch.nokillswit.infra.fetch.sanitizedSourceUrl
import ch.nokillswit.plugins.isUniqueViolation
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.CancellationException

/**
 * The import pipeline — the CatalogFileService feature split into its own file (the batch
 * orchestration shares nothing with the CRUD beyond `create()` and the dry-run snapshot
 * seam). The real run and the dry-run go through ONE shared classification ([preflight] +
 * [storedRow]), so the "semantically equal" promise between `POST …/import` and
 * `POST …/import/check` is enforced by structure, not maintained by hand.
 */

/** A per-document pre-flight outcome: the resolvable document, or its rejection row. */
private sealed interface Preflight {
    data class Ready(val file: CatalogFile) : Preflight
    data class Rejected(val row: ImportFileResult) : Preflight
}

private const val STORAGE_FAILED_MESSAGE = "Storage failed"

private fun rowBase(index: Int, f: CatalogFile) = ImportFileResult(
    index = index,
    kind = f.kind,
    namespace = f.metadata.namespace,
    name = f.metadata.name,
    status = ImportResultStatus.ERROR,
)

/**
 * Sanitize → structural validation → namespace resolution, each failure classifying the row:
 * a rule rejection (the validator's 400, blank-without-default, an undefined namespace) is
 * INVALID with its message; a storage-level resolution failure is ERROR (cancellation always
 * rethrows — a gone client must stop the batch). The resolved document reports the CONCRETE
 * namespace a blank one lands in.
 */
private suspend fun preflight(
    index: Int,
    raw: CatalogFile,
    resolve: suspend (CatalogFile) -> CatalogFile,
): Preflight {
    val sanitized = sanitizedCatalogFile(raw)
    try {
        validateCatalogFile(sanitized)
    } catch (e: BadRequestException) {
        return Preflight.Rejected(
            rowBase(index, sanitized).copy(status = ImportResultStatus.INVALID, message = e.message),
        )
    }
    val stored = try {
        resolve(sanitized)
    } catch (e: CancellationException) {
        throw e
    } catch (e: BadRequestException) {
        return Preflight.Rejected(
            rowBase(index, sanitized).copy(status = ImportResultStatus.INVALID, message = e.message),
        )
    } catch (_: Exception) {
        return Preflight.Rejected(
            rowBase(index, sanitized).copy(status = ImportResultStatus.ERROR, message = STORAGE_FAILED_MESSAGE),
        )
    }
    return Preflight.Ready(stored)
}

/** CREATED vs CREATED_WITH_FINDINGS (message = the waived findings) — shared by both runs;
 *  the dry-run passes a null [fileId] (predictions store nothing). */
private fun storedRow(base: ImportFileResult, findings: List<SoftFinding>, fileId: UInt?): ImportFileResult =
    if (findings.isEmpty()) {
        base.copy(status = ImportResultStatus.CREATED, fileId = fileId)
    } else {
        base.copy(
            status = ImportResultStatus.CREATED_WITH_FINDINGS,
            fileId = fileId,
            message = findings.joinToString("; ") { it.message },
            waivedFindings = findings.size,
        )
    }

/**
 * The `replaceExisting` twin of [storedRow], also shared by both runs: UNCHANGED when the replace
 * records no field change (the message still lists the soft findings the file carries, so a
 * re-import of a flawed batch keeps reporting them) — under a batch `sourceUrl` that row is still a
 * recorded repo sync, see [CatalogFileService.update] — UPDATED / UPDATED_WITH_FINDINGS otherwise.
 * [fileId] is the replaced file (null on the dry-run's predictions).
 */
private fun replacedRow(
    base: ImportFileResult,
    findings: List<SoftFinding>,
    fileId: UInt?,
    unchanged: Boolean,
): ImportFileResult {
    val status = when {
        unchanged -> ImportResultStatus.UNCHANGED
        findings.isEmpty() -> ImportResultStatus.UPDATED
        else -> ImportResultStatus.UPDATED_WITH_FINDINGS
    }
    return base.copy(
        status = status,
        fileId = fileId,
        message = findings.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.message },
        waivedFindings = findings.size,
    )
}

/**
 * The source-reference posture of a replaced row — the ontology imports' D3 rule: a batch WITH
 * a URL moves/re-stamps every row (an import from a URL is a sync); without one the existing
 * reference and sync state are KEPT. [sourceUrl] is the sanitized batch URL.
 */
private fun replaceSourceWrite(sourceUrl: String?): SourceWrite =
    if (sourceUrl != null) SourceWrite.Synced(sourceUrl) else SourceWrite.Keep

/**
 * Report & skip: each document imports independently — [preflight] (a rejection becomes its
 * INVALID/ERROR row) → create (an identity clash — the partial unique index's 23505 —
 * becomes CONFLICT; any other storage failure ERROR). Import ALWAYS waives the soft checks
 * (`allowInvalid`): a document with unresolved references or registry findings still stores,
 * reported as CREATED_WITH_FINDINGS with the finding messages — the point of the round trip
 * is getting the batch IN so errors can be fixed incrementally (the Errors report tracks
 * them). Only structural validation and namespace resolution still skip a document as
 * INVALID. With [replaceExisting] an identity an ACTIVE file already holds is replaced in
 * place instead of conflicting — through the ordinary [CatalogFileService.update] (its history
 * event, revision bump and no-op suppression), never a delete-and-create — reported UPDATED /
 * UPDATED_WITH_FINDINGS / UNCHANGED; a soft-deleted twin is invisible to the lookup, so that
 * identity is CREATED; a second occurrence of an identity the batch already stored/replaced is
 * CONFLICT either way. Nothing rethrows except cancellation, so the batch always runs to completion and
 * the result rows ARE the outcome. Each successful create includes its product-history event
 * in the row transaction; security audits remain route-side.
 */
suspend fun CatalogFileService.import(
    files: List<CatalogFile>,
    createdByUserId: UInt,
    // The fetch-from-URL flow's source: every stored row gets this reference AND starts
    // synced (the content IS the repo copy at import time — an import from a URL is a
    // sync). A pasted/uploaded batch passes null and the rows read "no source".
    sourceUrl: String? = null,
    replaceExisting: Boolean = false,
    // Test seam: the replace lookup, so a regression can make the matched file vanish between
    // the lookup and the locked update (the create fallback) without a flaky race.
    activeIdLookup: suspend (CatalogFile) -> UInt? = { activeIdOf(it) },
): List<ImportFileResult> {
    // The batch universe: sibling documents resolve against each other ORDER-INDEPENDENTLY
    // (a real export's entities are interdependent — the round trip must survive). Only
    // documents that sanitize, validate, and namespace-resolve contribute an identity.
    // Documented residual: a document referencing a sibling that later fails to STORE
    // (identity conflict) keeps its dangling reference — the same class as a
    // deletion-created dangling ref, and the Errors report catches it.
    val batchIdentities = batchIdentities(files)
    // Identities this batch already stored/replaced: a repeat must not replace the row again
    // (the create path's 23505 catches it by itself, a replace would not).
    val handled = mutableSetOf<EntityIdentity>()
    return files.mapIndexed { index, raw ->
        importOne(index, raw, createdByUserId, batchIdentities, sourceUrl, replaceExisting, handled, activeIdLookup)
    }
}

/** The identities every batch document may reference (shared by import and its dry-run). */
private suspend fun CatalogFileService.batchIdentities(files: List<CatalogFile>): Set<EntityIdentity> =
    files.mapNotNull { raw ->
        val sanitized = sanitizedCatalogFile(raw)
        try {
            validateCatalogFile(sanitized)
            identityOf(resolveNamespace(sanitized))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // the document will produce its own INVALID/ERROR row
        }
    }.toSet()

/**
 * The import's DRY-RUN (`POST …/import/check`): the identical classification — the shared
 * [preflight], identity conflicts against the workspace AND within the batch, soft findings
 * — with the insert replaced by prediction. Nothing is stored, nothing is audited; every
 * row's `fileId` stays null and the statuses read as predictions (CREATED = would be
 * created). One snapshot transaction (the errors-report shape) — cheaper than the real run,
 * semantically equal; the result is a snapshot, so a concurrent write can change the real
 * outcome.
 */
suspend fun CatalogFileService.importCheck(
    files: List<CatalogFile>,
    replaceExisting: Boolean = false,
    // The route's already-sanitized batch URL. Only the replace prediction reads it (does it
    // move an existing reference / turn the row into a sync?).
    sourceUrl: String? = null,
): List<ImportFileResult> {
    val batchIdentities = batchIdentities(files)
    val sourceWrite = replaceSourceWrite(sanitizedSourceUrl(sourceUrl))
    return withImportSnapshot { active, registries, resolve, predict ->
        // The running seen-set predicts INTRA-batch duplicates: the real run stores the
        // first occurrence and 23505-CONFLICTs the second — mirror that ordering.
        val seen = mutableSetOf<EntityIdentity>()
        files.mapIndexed { index, raw ->
            checkOne(index, raw, batchIdentities, active, registries, seen, resolve) { file ->
                if (replaceExisting) predict(file, sourceWrite) else null
            }
        }
    }
}

private suspend fun checkOne(
    index: Int,
    raw: CatalogFile,
    batchIdentities: Set<EntityIdentity>,
    active: Set<EntityIdentity>,
    registries: RegistrySnapshot,
    seen: MutableSet<EntityIdentity>,
    resolve: suspend (CatalogFile) -> CatalogFile,
    // Non-null only under replaceExisting: the would-it-change verdict for an existing identity.
    predictReplace: suspend (CatalogFile) -> ReplacePrediction?,
): ImportFileResult {
    val stored = when (val outcome = preflight(index, raw, resolve)) {
        is Preflight.Rejected -> return outcome.row
        is Preflight.Ready -> outcome.file
    }
    val identity = identityOf(stored)
    // An in-batch repeat is a CONFLICT before any replace prediction is paid for.
    if (!seen.add(identity)) {
        return rowBase(index, stored).copy(status = ImportResultStatus.CONFLICT, message = IMPORT_CONFLICT_MESSAGE)
    }
    val replacement = if (identity in active) predictReplace(stored) else null
    // `identity in active` without a replacement verdict = replaceExisting is off: the clash stands.
    if (identity in active && replacement == null) {
        return rowBase(index, stored).copy(status = ImportResultStatus.CONFLICT, message = IMPORT_CONFLICT_MESSAGE)
    }
    val findings = checkDocument(stored, active + batchIdentities).findings
        .map { SoftFinding(it, referenceFindingMessage(it)) } +
        registryFindings(stored, registries)
    return if (replacement != null) {
        replacedRow(
            rowBase(index, stored),
            findings,
            fileId = null,
            unchanged = replacement == ReplacePrediction.UNCHANGED,
        )
    } else {
        storedRow(rowBase(index, stored), findings, fileId = null)
    }
}

// Per-document isolation: one row's unexpected failure is reported as ERROR and never fails siblings (report & skip).
@Suppress("TooGenericExceptionCaught")
private suspend fun CatalogFileService.importOne(
    index: Int,
    raw: CatalogFile,
    createdByUserId: UInt,
    batchIdentities: Set<EntityIdentity>,
    sourceUrl: String?,
    replaceExisting: Boolean,
    handled: MutableSet<EntityIdentity>,
    activeIdLookup: suspend (CatalogFile) -> UInt?,
): ImportFileResult {
    val file = when (val outcome = preflight(index, raw) { resolveNamespace(it) }) {
        is Preflight.Rejected -> return outcome.row
        is Preflight.Ready -> outcome.file
    }
    val base = rowBase(index, file)
    val identity = identityOf(file)
    if (identity in handled) {
        return base.copy(status = ImportResultStatus.CONFLICT, message = IMPORT_CONFLICT_MESSAGE)
    }
    return try {
        // replaceExisting: an ACTIVE twin is replaced through the ordinary update (its own
        // transaction, history event and no-op rule). Should the twin vanish between the lookup
        // and the locked update (rows == 0), the identity is free and the document is created.
        val replaced = if (replaceExisting) {
            replaceExistingFile(file, createdByUserId, batchIdentities, sourceUrl, activeIdLookup)
        } else {
            null
        }
        val row = replaced?.let { (id, result) ->
            replacedRow(base, result.waived, fileId = id, unchanged = result.changes.isEmpty())
        } ?: run {
            val saved = create(
                file,
                createdByUserId,
                batchIdentities,
                allowInvalid = true,
                sourceUrl = sourceUrl,
                markSynced = true,
                viaImport = true,
            )
            storedRow(base, saved.waived, fileId = saved.id)
        }
        handled += identity
        row
    } catch (e: CancellationException) {
        // Cancellation is not a per-document failure — a gone client must stop the batch.
        throw e
    } catch (e: BadRequestException) {
        // create()'s own rule rejections (the namespace resolution re-check) are INVALID
        // rows, exactly like the pre-flight validator's.
        base.copy(status = ImportResultStatus.INVALID, message = e.message)
    } catch (e: Exception) {
        if (e.isUniqueViolation()) {
            base.copy(status = ImportResultStatus.CONFLICT, message = IMPORT_CONFLICT_MESSAGE)
        } else {
            base.copy(status = ImportResultStatus.ERROR, message = STORAGE_FAILED_MESSAGE)
        }
    }
}

/** The replaced file's id and the update's result, or null when no active file holds the identity (any more). */
private suspend fun CatalogFileService.replaceExistingFile(
    file: CatalogFile,
    actingUserId: UInt,
    batchIdentities: Set<EntityIdentity>,
    sourceUrl: String?,
    activeIdLookup: suspend (CatalogFile) -> UInt?,
): Pair<UInt, CatalogFileUpdateResult>? {
    val id = activeIdLookup(file) ?: return null
    val result = update(
        id,
        file,
        actingUserId,
        allowInvalid = true,
        extraIdentities = batchIdentities,
        sourceWrite = replaceSourceWrite(sourceUrl),
    )
    return if (result.rows > 0) id to result else null
}
