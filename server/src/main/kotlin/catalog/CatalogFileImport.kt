package ch.nokillswit.catalog

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
    } catch (e: Exception) {
        return Preflight.Rejected(
            rowBase(index, sanitized).copy(status = ImportResultStatus.ERROR, message = e.message ?: "Storage failed"),
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
        )
    }

/**
 * Report & skip: each document imports independently — [preflight] (a rejection becomes its
 * INVALID/ERROR row) → create (an identity clash — the partial unique index's 23505 —
 * becomes CONFLICT; any other storage failure ERROR). Import ALWAYS waives the soft checks
 * (`allowInvalid`): a document with unresolved references or registry findings still stores,
 * reported as CREATED_WITH_FINDINGS with the finding messages — the point of the round trip
 * is getting the batch IN so errors can be fixed incrementally (the Errors report tracks
 * them). Only structural validation and namespace resolution still skip a document as
 * INVALID. Nothing rethrows except cancellation, so the batch always runs to completion and
 * the result rows ARE the outcome. The route emits the audit events for the stored rows
 * (the repo convention: audits live route-side).
 */
suspend fun CatalogFileService.import(
    files: List<CatalogFile>,
    createdByUserId: UInt,
    // The fetch-from-URL flow's source: every stored row gets this reference AND starts
    // synced (the content IS the repo copy at import time — an import from a URL is a
    // sync). A pasted/uploaded batch passes null and the rows read "no source".
    sourceUrl: String? = null,
): List<ImportFileResult> {
    // The batch universe: sibling documents resolve against each other ORDER-INDEPENDENTLY
    // (a real export's entities are interdependent — the round trip must survive). Only
    // documents that sanitize, validate, and namespace-resolve contribute an identity.
    // Documented residual: a document referencing a sibling that later fails to STORE
    // (identity conflict) keeps its dangling reference — the same class as a
    // deletion-created dangling ref, and the Errors report catches it.
    val batchIdentities = batchIdentities(files)
    return files.mapIndexed { index, raw -> importOne(index, raw, createdByUserId, batchIdentities, sourceUrl) }
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
suspend fun CatalogFileService.importCheck(files: List<CatalogFile>): List<ImportFileResult> {
    val batchIdentities = batchIdentities(files)
    return withImportSnapshot { active, registries, resolve ->
        // The running seen-set predicts INTRA-batch duplicates: the real run stores the
        // first occurrence and 23505-CONFLICTs the second — mirror that ordering.
        val seen = mutableSetOf<EntityIdentity>()
        files.mapIndexed { index, raw ->
            checkOne(index, raw, batchIdentities, active, registries, seen, resolve)
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
): ImportFileResult {
    val stored = when (val outcome = preflight(index, raw, resolve)) {
        is Preflight.Rejected -> return outcome.row
        is Preflight.Ready -> outcome.file
    }
    val identity = identityOf(stored)
    if (identity in active || !seen.add(identity)) {
        return rowBase(index, stored).copy(status = ImportResultStatus.CONFLICT, message = IMPORT_CONFLICT_MESSAGE)
    }
    val findings = checkDocument(stored, active + batchIdentities).findings
        .map { SoftFinding(it, referenceFindingMessage(it)) } +
        registryFindings(stored, registries)
    return storedRow(rowBase(index, stored), findings, fileId = null)
}

private suspend fun CatalogFileService.importOne(
    index: Int,
    raw: CatalogFile,
    createdByUserId: UInt,
    batchIdentities: Set<EntityIdentity>,
    sourceUrl: String?,
): ImportFileResult {
    val file = when (val outcome = preflight(index, raw) { resolveNamespace(it) }) {
        is Preflight.Rejected -> return outcome.row
        is Preflight.Ready -> outcome.file
    }
    val base = rowBase(index, file)
    return try {
        val saved = create(
            file,
            createdByUserId,
            batchIdentities,
            allowInvalid = true,
            sourceUrl = sourceUrl,
            markSynced = true,
        )
        storedRow(base, saved.waived, fileId = saved.id)
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
            base.copy(status = ImportResultStatus.ERROR, message = e.message ?: "Storage failed")
        }
    }
}
