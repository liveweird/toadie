package ch.nokillswit.blueprints

import ch.nokillswit.infra.importing.IMPORT_SCHEMA_MESSAGE
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.infra.importing.decodeDocument
import ch.nokillswit.infra.importing.orderWithDeferral
import ch.nokillswit.infra.importing.rawString
import ch.nokillswit.plugins.isUniqueViolation
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Bulk blueprint import (phase 6 of Toadie's Port data-model move, v1.28.0 — see
 * `.claude/docs/port-data-model.md` "Import and export"): `POST /api/v1/blueprints/import` +
 * `/import/check`, ADMIN only. A batch document is decoded PER ROW, so one malformed document is
 * that row's `INVALID`, never a whole-request `400`. [planBlueprintImport] is PURE and DB-free —
 * it is handed one snapshot of the registry and decides every row's fate, including the write
 * ORDER and what each document's first write must temporarily DROP (a forward reference or a
 * relation cycle within the batch) — [BlueprintService.import]/[BlueprintService.importCheck]
 * are its only effectful callers, reusing [BlueprintService.create]/[BlueprintService.update]
 * per row so every write still goes through the ordinary V27 lock and validation.
 */

@Serializable
data class BlueprintImportRequest(
    val documents: List<JsonObject>,
    val replaceExisting: Boolean = false,
)

@Serializable
data class BlueprintImportRow(
    val index: Int,
    val identifier: String? = null,
    val status: OntologyImportStatus,
    val id: UInt? = null,
    val message: String? = null,
)

@Serializable
data class BlueprintImportResponse(val results: List<BlueprintImportRow>)

/** One registry row as the planner needs it — the pure counterpart of [BlueprintService.list]. */
data class RegistryBlueprint(val id: UInt, val identifier: String, val isSystem: Boolean)

/** [planBlueprintImport]'s per-document outcome. */
sealed interface BlueprintPlanVerdict {
    data class Rejected(val row: BlueprintImportRow) : BlueprintPlanVerdict

    /**
     * A document that will be written: [pass1] is [request] with every reference to a batch
     * sibling not yet stored at this position ([deferred]) stripped — restored by a pass-2
     * full write when [deferred] is non-empty.
     */
    data class Store(
        val request: BlueprintRequest,
        val existingId: UInt?,
        val deferred: Set<String>,
        val pass1: BlueprintRequest,
    ) : BlueprintPlanVerdict
}

data class BlueprintImportPlan(val verdicts: List<BlueprintPlanVerdict>, val order: List<Int>)

private fun rejected(index: Int, identifier: String?, message: String?, status: OntologyImportStatus = OntologyImportStatus.INVALID) =
    BlueprintPlanVerdict.Rejected(BlueprintImportRow(index, identifier, status, message = message))

/** One document past decode/validate/system/duplicate — still subject to the fixpoint and ordering below. */
private data class Candidate(val index: Int, val identifier: String, val request: BlueprintRequest, val existingId: UInt?)

/** Steps 1-4: decode, structural validation, the system-identifier rule, in-batch duplicates, and the registry match. */
private fun classifyDocuments(
    documents: List<JsonObject>,
    registry: List<RegistryBlueprint>,
    replaceExisting: Boolean,
    knownHierarchies: Set<String>,
    verdicts: Array<BlueprintPlanVerdict?>,
): List<Candidate> {
    val registryByIdentifier = registry.associateBy { it.identifier.lowercase() }
    val systemIdentifiers = registry.filter { it.isSystem }.map { it.identifier.lowercase() }.toSet()
    val seen = mutableMapOf<String, Int>()
    val candidates = mutableListOf<Candidate>()

    documents.forEachIndexed { index, obj ->
        val decoded = decodeDocument<BlueprintRequest>(obj)
        if (decoded == null) {
            verdicts[index] = rejected(index, rawString(obj, "identifier"), IMPORT_SCHEMA_MESSAGE)
            return@forEachIndexed
        }
        val sanitized = sanitizedBlueprintRequest(decoded)
        val candidate =
            classifyOne(index, sanitized, registryByIdentifier, systemIdentifiers, seen, replaceExisting, knownHierarchies, verdicts)
        candidate?.let { candidates += it }
    }
    return candidates
}

@Suppress("ReturnCount") // guard-clause style, the repo's validation-throw idiom applied to classification instead
private fun classifyOne(
    index: Int,
    sanitized: BlueprintRequest,
    registryByIdentifier: Map<String, RegistryBlueprint>,
    systemIdentifiers: Set<String>,
    seen: MutableMap<String, Int>,
    replaceExisting: Boolean,
    knownHierarchies: Set<String>,
    verdicts: Array<BlueprintPlanVerdict?>,
): Candidate? {
    try {
        validateBlueprintRequest(sanitized)
    } catch (e: BadRequestException) {
        verdicts[index] = rejected(index, sanitized.identifier, e.message)
        return null
    }
    val unknownHierarchies = sanitized.hierarchyRelations?.keys?.filterNot { it in knownHierarchies }.orEmpty()
    if (unknownHierarchies.isNotEmpty()) {
        val message = "hierarchyRelations names an unknown hierarchy '${unknownHierarchies.first()}'"
        verdicts[index] = rejected(index, sanitized.identifier, message)
        return null
    }
    val identifierLower = sanitized.identifier.lowercase()
    if (isSystemIdentifier(sanitized.identifier) && identifierLower !in systemIdentifiers) {
        verdicts[index] = rejected(index, sanitized.identifier, "Identifier '${sanitized.identifier}' is reserved for system blueprints")
        return null
    }
    val firstSeen = seen[identifierLower]
    if (firstSeen != null) {
        verdicts[index] = rejected(index, sanitized.identifier, "Duplicate of document $firstSeen", OntologyImportStatus.CONFLICT)
        return null
    }
    seen[identifierLower] = index
    val existing = registryByIdentifier[identifierLower]
    if (existing == null) return Candidate(index, sanitized.identifier, sanitized, existingId = null)
    if (!replaceExisting) {
        verdicts[index] = BlueprintPlanVerdict.Rejected(
            BlueprintImportRow(
                index, sanitized.identifier, OntologyImportStatus.EXISTS,
                id = existing.id, message = "already exists — enable Replace existing to overwrite",
            ),
        )
        return null
    }
    if (existing.isSystem) {
        try {
            validateSystemExtension(existing.identifier, sanitized)
        } catch (e: BadRequestException) {
            verdicts[index] = rejected(index, sanitized.identifier, e.message)
            return null
        }
    }
    return Candidate(index, sanitized.identifier, sanitized, existingId = existing.id)
}

/** Step 5: the unknown-target / registry-cap fixpoint — iterated until a full pass rejects nothing further. */
private fun fixpointTargetsAndCap(
    initial: List<Candidate>,
    registry: List<RegistryBlueprint>,
    verdicts: Array<BlueprintPlanVerdict?>,
): List<Candidate> {
    var remaining = initial
    var changed = true
    while (changed) {
        changed = false
        remaining = rejectUnknownTargets(remaining, registry, verdicts) { changed = true }
        remaining = rejectOverCap(remaining, registry, verdicts) { changed = true }
    }
    return remaining
}

private fun rejectUnknownTargets(
    remaining: List<Candidate>,
    registry: List<RegistryBlueprint>,
    verdicts: Array<BlueprintPlanVerdict?>,
    onReject: () -> Unit,
): List<Candidate> {
    val known = registry.map { it.identifier }.toSet() + remaining.map { it.identifier }.toSet()
    val survivors = mutableListOf<Candidate>()
    for (c in remaining) {
        val unknown = blueprintTargets(c.request.toDefinition()).filterNot { it == c.identifier || it in known }
        if (unknown.isEmpty()) {
            survivors += c
        } else {
            verdicts[c.index] = rejected(c.index, c.identifier, "Unknown relation/aggregation target(s): ${unknown.joinToString()}")
            onReject()
        }
    }
    return survivors
}

private fun rejectOverCap(
    remaining: List<Candidate>,
    registry: List<RegistryBlueprint>,
    verdicts: Array<BlueprintPlanVerdict?>,
    onReject: () -> Unit,
): List<Candidate> {
    val survivors = mutableListOf<Candidate>()
    var creates = 0
    for (c in remaining) {
        if (c.existingId == null) {
            creates++
            if (registry.size + creates > MAX_BLUEPRINTS) {
                verdicts[c.index] = rejected(c.index, c.identifier, "The blueprint registry is full ($MAX_BLUEPRINTS blueprints)")
                onReject()
                continue
            }
        }
        survivors += c
    }
    return survivors
}

/**
 * Steps 6-7: Kahn ordering over batch-create targets (the mechanical loop and its input-order
 * tie-break live in `infra/importing/ImportBatch.kt`'s [orderWithDeferral], shared with
 * `entities/EntityImport.kt`'s `attemptOrdering`) — a stall always DEFERS the lowest remaining
 * index's unmet targets to a pass-2 write (never rejects, unlike the entity planner's
 * mandatory-reference-cycle check).
 */
private fun orderCandidates(remaining: List<Candidate>): BlueprintImportPlan {
    val byIndex = remaining.associateBy { it.index }
    val storeIdentifiers = remaining.filter { it.existingId == null }.map { it.identifier }.toSet()
    val identifierToIndex = remaining.associate { it.identifier to it.index }
    val dependencies: Map<Int, List<String>> = remaining.associate { c ->
        val targets = blueprintTargets(c.request.toDefinition()).filterNot { it == c.identifier }
        c.index to targets.filter { it in storeIdentifiers }
    }

    val batch = orderWithDeferral(remaining.map { it.index }, dependencies, identifierToIndex)
    val storeByIndex: Map<Int, BlueprintPlanVerdict.Store> = batch.order.associateWith { idx ->
        val c = byIndex.getValue(idx)
        val deferred = batch.deferredByIndex[idx].orEmpty()
        BlueprintPlanVerdict.Store(c.request, c.existingId, deferred, withDeferredStripped(c.request, deferred))
    }
    return BlueprintImportPlan(batch.order.map { storeByIndex.getValue(it) }, batch.order)
}

private fun firstSegment(path: String): String = path.substringBefore('.')

/** Drops every reference to a [deferred] identifier — the pass-1 write's temporary shape. */
private fun withDeferredStripped(request: BlueprintRequest, deferred: Set<String>): BlueprintRequest {
    if (deferred.isEmpty()) return request
    val droppedRelations = request.relations.filterValues { it.target in deferred }.keys
    val relations = request.relations - droppedRelations
    val aggregationProperties = request.aggregationProperties.filterValues { it.target !in deferred }
    val mirrorProperties = request.mirrorProperties.filterValues { firstSegment(it.path) !in droppedRelations }
    val ownership = request.ownership?.takeUnless {
        it.type == "Inherited" && firstSegment(it.path.orEmpty()) in droppedRelations
    }
    // hierarchyRelations values are relation KEYS of this SAME row (never another blueprint's
    // identity), so a deferred entry is any whose relation VALUE got dropped above.
    val hierarchyRelations = request.hierarchyRelations?.filterValues { it !in droppedRelations }?.ifEmpty { null }
    return request.copy(
        relations = relations,
        aggregationProperties = aggregationProperties,
        mirrorProperties = mirrorProperties,
        ownership = ownership,
        hierarchyRelations = hierarchyRelations,
    )
}

/**
 * The batch classification: steps 1-4 (decode/validate/system/duplicate/registry-match — now
 * also rejecting a `hierarchyRelations` key outside [knownHierarchies], the identical rule
 * [BlueprintService.create]/[BlueprintService.update] enforce at write time, so the dry-run and
 * the real run classify a document the same way), then the step-5 fixpoint (unknown targets,
 * the registry cap), then steps 6-7 (Kahn order + deferral). Every document ends with exactly
 * one verdict — a rejection, or a [BlueprintPlanVerdict.Store] in [BlueprintImportPlan.order]'s
 * write order.
 */
fun planBlueprintImport(
    documents: List<JsonObject>,
    registry: List<RegistryBlueprint>,
    replaceExisting: Boolean,
    knownHierarchies: Set<String> = emptySet(),
): BlueprintImportPlan {
    val verdicts = arrayOfNulls<BlueprintPlanVerdict>(documents.size)
    val candidates = classifyDocuments(documents, registry, replaceExisting, knownHierarchies, verdicts)
    val remaining = fixpointTargetsAndCap(candidates, registry, verdicts)
    val stored = orderCandidates(remaining)
    stored.order.forEachIndexed { position, idx -> verdicts[idx] = stored.verdicts[position] }
    return BlueprintImportPlan(verdicts.map { it ?: error("blueprint import document left unclassified") }, stored.order)
}

// ---------------------------------------------------------------------------------------------
// Effectful: BlueprintService.import / importCheck — the planner's only callers.
// ---------------------------------------------------------------------------------------------

private const val BLUEPRINT_STORAGE_FAILED = "Storage failed"

/**
 * The real run: registry snapshot → plan → pass 1 in [BlueprintImportPlan.order] (reusing
 * [BlueprintService.create]/[BlueprintService.update] per row, so every write still runs under
 * the ordinary V27 table lock) → pass 2 for every stored row with deferred parts. Cancellation
 * always rethrows — a gone client must stop the batch, the `catalog/CatalogFileImport.kt` rule.
 */
suspend fun BlueprintService.import(documents: List<JsonObject>, callerId: UInt, replaceExisting: Boolean): List<BlueprintImportRow> {
    val registry = list().map { RegistryBlueprint(it.id, it.identifier, it.system) }
    val plan = planBlueprintImport(documents, registry, replaceExisting, knownHierarchies())
    val rows = arrayOfNulls<BlueprintImportRow>(documents.size)
    plan.verdicts.forEachIndexed { idx, verdict -> if (verdict is BlueprintPlanVerdict.Rejected) rows[idx] = verdict.row }

    val storedIds = mutableMapOf<Int, UInt>()
    for (idx in plan.order) {
        val verdict = plan.verdicts[idx] as? BlueprintPlanVerdict.Store ?: continue
        rows[idx] = writeBlueprintRow(idx, verdict, callerId, storedIds)
    }
    for (idx in plan.order) {
        val verdict = plan.verdicts[idx] as? BlueprintPlanVerdict.Store ?: continue
        if (verdict.deferred.isEmpty()) continue
        val id = storedIds[idx] ?: continue
        rows[idx] = pass2Blueprint(id, verdict, rows[idx] ?: error("blueprint import row $idx missing after pass 1"))
    }
    return rows.map { it ?: error("blueprint import row left unset") }
}

// Per-document isolation: one row's unexpected failure is reported as ERROR and never fails siblings (report & skip).
@Suppress("TooGenericExceptionCaught")
private suspend fun BlueprintService.writeBlueprintRow(
    index: Int,
    verdict: BlueprintPlanVerdict.Store,
    callerId: UInt,
    storedIds: MutableMap<Int, UInt>,
): BlueprintImportRow {
    val identifier = verdict.request.identifier
    return try {
        if (verdict.existingId == null) {
            val created = create(verdict.pass1, callerId)
            storedIds[index] = created.id
            BlueprintImportRow(index, identifier, OntologyImportStatus.CREATED, id = created.id)
        } else {
            val result = update(verdict.existingId, verdict.pass1)
            if (result.affected == 0) {
                BlueprintImportRow(index, identifier, OntologyImportStatus.ERROR, message = "Blueprint vanished")
            } else {
                storedIds[index] = verdict.existingId
                BlueprintImportRow(index, identifier, OntologyImportStatus.UPDATED, id = verdict.existingId)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: BadRequestException) {
        BlueprintImportRow(index, identifier, OntologyImportStatus.INVALID, message = e.message)
    } catch (e: Exception) {
        if (e.isUniqueViolation()) {
            BlueprintImportRow(index, identifier, OntologyImportStatus.EXISTS, message = "created concurrently")
        } else {
            BlueprintImportRow(index, identifier, OntologyImportStatus.ERROR, message = BLUEPRINT_STORAGE_FAILED)
        }
    }
}

/**
 * Restores a stored row's deferred targets with a second full write. A failure here is only a
 * concurrent-change residual (the pre-flight fixpoint already proved the FULL document resolves
 * against the batch) — reported `ERROR` WITH the row's id, never silently left `CREATED`/`UPDATED`.
 */
private suspend fun BlueprintService.pass2Blueprint(
    id: UInt,
    verdict: BlueprintPlanVerdict.Store,
    previousRow: BlueprintImportRow,
): BlueprintImportRow =
    try {
        update(id, verdict.request)
        previousRow
    } catch (e: CancellationException) {
        throw e
    } catch (e: BadRequestException) {
        previousRow.copy(status = OntologyImportStatus.ERROR, id = id, message = "Stored without its deferred targets: ${e.message}")
    }

/** The dry-run: the identical classification, storing nothing — `Store` verdicts predict CREATED/UPDATED. */
suspend fun BlueprintService.importCheck(documents: List<JsonObject>, replaceExisting: Boolean): List<BlueprintImportRow> {
    val registry = list().map { RegistryBlueprint(it.id, it.identifier, it.system) }
    val plan = planBlueprintImport(documents, registry, replaceExisting, knownHierarchies())
    return plan.verdicts.mapIndexed { idx, verdict ->
        when (verdict) {
            is BlueprintPlanVerdict.Rejected -> verdict.row
            is BlueprintPlanVerdict.Store -> BlueprintImportRow(
                idx,
                verdict.request.identifier,
                if (verdict.existingId != null) OntologyImportStatus.UPDATED else OntologyImportStatus.CREATED,
                id = verdict.existingId,
            )
        }
    }
}
