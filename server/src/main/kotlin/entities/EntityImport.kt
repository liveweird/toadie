package ch.nokillswit.entities

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.SYSTEM_USER_BLUEPRINT
import ch.nokillswit.infra.importing.IMPORT_SCHEMA_MESSAGE
import ch.nokillswit.infra.importing.ImportMutation
import ch.nokillswit.infra.importing.ImportMutationKind
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.infra.importing.decodeDocument
import ch.nokillswit.infra.importing.orderWithDeferral
import ch.nokillswit.infra.importing.rawString
import ch.nokillswit.plugins.isUniqueViolation
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bulk entity import (phase 6 of Toadie's Port data-model move, v1.28.0 — see
 * `.claude/docs/port-data-model.md` "Import and export"): `POST /api/v1/entities/import` +
 * `/import/check`, any authenticated user (the entity shared-workspace rule — no admin gate).
 * [planEntityImport] is PURE and DB-free; [EntityService.import]/[EntityService.importSnapshot]
 * are its only effectful callers, reusing [EntityService.create]/[EntityService.update] per row
 * so every write still runs under the ordinary two-table V28 lock protocol. The reference graph
 * a document may need ordering over is wider than a blueprint's: relation targets
 * ([entityTargets]), `team` values (always against `_team`), and `format: team|user` property
 * values ([formatTargets]) — all three sources, the same ones
 * `entities/EntityReferences.kt`/`entities/EntityOwnership.kt` already enumerate for the
 * rename cascade.
 */

@Serializable
data class EntityImportRequest(
    val documents: List<JsonObject>,
    val replaceExisting: Boolean = false,
)

@Serializable
data class EntityImportRow(
    val index: Int,
    val blueprint: String? = null,
    val identifier: String? = null,
    val status: OntologyImportStatus,
    val id: UInt? = null,
    val message: String? = null,
    val findings: List<EntityFinding>? = null,
)

@Serializable
data class EntityImportResponse(val results: List<EntityImportRow>)

/** One blueprint as the entity planner needs it — reads through [EntityService.importSnapshot]'s read seam. */
data class EntityImportBlueprint(val id: UInt, val identifier: String, val definition: BlueprintDefinition)

/**
 * One committed read of everything [planEntityImport] needs about the CURRENT workspace: the
 * active blueprint definitions, and every active entity's identity (keyed exactly, the
 * `EntityService` snapshot convention — target resolution stays byte-exact even though the
 * unique index itself folds case) plus how many active rows each blueprint already holds.
 */
class EntityImportSnapshot(
    val blueprints: List<EntityImportBlueprint>,
    identifiersByBlueprint: Map<UInt, List<Pair<String, UInt>>>,
    val total: Long,
    private val countsByBlueprint: Map<UInt, Long>,
    /** The workspace's CURRENT total of `octet_length(document) + octet_length(team)` over active rows (2.4.0). */
    val documentBytes: Long,
    /** Per-row byte size (by entity id), keyed the same way [total]/[countsByBlueprint] are — never the row's text. */
    private val bytesById: Map<UInt, Long>,
) {
    private val blueprintsByIdentifier: Map<String, EntityImportBlueprint> = blueprints.associateBy { it.identifier }
    private val exactByBlueprint: Map<UInt, Map<String, UInt>> = identifiersByBlueprint.mapValues { it.value.toMap() }
    private val foldedByBlueprint: Map<UInt, Map<String, UInt>> =
        identifiersByBlueprint.mapValues { (_, pairs) -> pairs.associate { it.first.lowercase() to it.second } }

    fun blueprint(identifier: String): EntityImportBlueprint? = blueprintsByIdentifier[identifier]

    /** Byte-exact target resolution — the `EntityService` snapshot's own rule, over registry rows only. */
    val targetExists: TargetExists = { targetBlueprint, entityId ->
        blueprintsByIdentifier[targetBlueprint]?.let { exactByBlueprint[it.id]?.containsKey(entityId) } == true
    }

    /** Case-insensitive within one blueprint — the partial-unique-index rule (`uq_entities_blueprint_identifier_active`). */
    fun existingId(blueprint: String, identifier: String): UInt? =
        blueprintsByIdentifier[blueprint]?.let { foldedByBlueprint[it.id]?.get(identifier.lowercase()) }

    fun countFor(blueprintId: UInt): Long = countsByBlueprint[blueprintId] ?: 0L

    /** The stored byte size an UPDATED row's own current document+team already contributes to [documentBytes]. */
    fun bytesFor(id: UInt): Long = bytesById[id] ?: 0L
}

/** [planEntityImport]'s per-document outcome — the `blueprints/BlueprintImport.kt` shape, one level down. */
sealed interface EntityPlanVerdict {
    data class Rejected(val row: EntityImportRow) : EntityPlanVerdict

    data class Store(
        val request: EntityRequest,
        val existingId: UInt?,
        val deferred: Set<String>,
        val pass1: EntityRequest,
    ) : EntityPlanVerdict
}

data class EntityImportPlan(val verdicts: List<EntityPlanVerdict>, val order: List<Int>)

private fun rejected(
    index: Int,
    blueprint: String?,
    identifier: String?,
    message: String?,
    status: OntologyImportStatus = OntologyImportStatus.INVALID,
) = EntityPlanVerdict.Rejected(EntityImportRow(index, blueprint, identifier, status, message = message))

/** One document past decode/validate/blueprint-lookup/duplicate/registry-match — still subject to the fixpoint and ordering below. */
private data class Candidate(
    val index: Int,
    val blueprintIdentifier: String,
    val blueprintId: UInt,
    val definition: BlueprintDefinition,
    val identifier: String,
    val request: EntityRequest,
    val existingId: UInt?,
)

/** Steps 1-4: decode, structural validation, the blueprint lookup, in-batch duplicates (per blueprint), and the registry match. */
private fun classifyDocuments(
    documents: List<JsonObject>,
    snapshot: EntityImportSnapshot,
    replaceExisting: Boolean,
    verdicts: Array<EntityPlanVerdict?>,
): List<Candidate> {
    val seen = mutableMapOf<Pair<String, String>, Int>()
    val candidates = mutableListOf<Candidate>()
    documents.forEachIndexed { index, obj ->
        val decoded = decodeDocument<EntityRequest>(obj)
        if (decoded == null) {
            verdicts[index] = rejected(index, rawString(obj, "blueprint"), rawString(obj, "identifier"), IMPORT_SCHEMA_MESSAGE)
            return@forEachIndexed
        }
        val sanitized = sanitizedEntityRequest(decoded)
        classifyOne(index, sanitized, snapshot, seen, replaceExisting, verdicts)?.let { candidates += it }
    }
    return candidates
}

@Suppress("ReturnCount") // guard-clause classification, the BlueprintImport.kt precedent
private fun classifyOne(
    index: Int,
    sanitized: EntityRequest,
    snapshot: EntityImportSnapshot,
    seen: MutableMap<Pair<String, String>, Int>,
    replaceExisting: Boolean,
    verdicts: Array<EntityPlanVerdict?>,
): Candidate? {
    try {
        validateEntityRequest(sanitized)
    } catch (e: BadRequestException) {
        verdicts[index] = rejected(index, sanitized.blueprint, sanitized.identifier, e.message)
        return null
    }
    val blueprint = snapshot.blueprint(sanitized.blueprint)
        ?: run {
            verdicts[index] = rejected(index, sanitized.blueprint, sanitized.identifier, "Unknown blueprint")
            return null
        }
    val key = sanitized.blueprint to sanitized.identifier.lowercase()
    val firstSeen = seen[key]
    if (firstSeen != null) {
        verdicts[index] =
            rejected(index, sanitized.blueprint, sanitized.identifier, "Duplicate of document $firstSeen", OntologyImportStatus.CONFLICT)
        return null
    }
    seen[key] = index
    val existingId = snapshot.existingId(sanitized.blueprint, sanitized.identifier)
    if (existingId != null && !replaceExisting) {
        verdicts[index] = EntityPlanVerdict.Rejected(
            EntityImportRow(
                index, sanitized.blueprint, sanitized.identifier, OntologyImportStatus.EXISTS,
                id = existingId, message = "already exists — enable Replace existing to overwrite",
            ),
        )
        return null
    }
    return Candidate(index, sanitized.blueprint, blueprint.id, blueprint.definition, sanitized.identifier, sanitized, existingId)
}

/** Every sibling reference a document's relations/team/format-properties name — the ordering graph's edges. */
private fun siblingReferences(document: EntityDocument, definition: BlueprintDefinition, team: JsonElement?): Set<Pair<String, String>> =
    entityTargets(document, definition) +
        teamValues(team).map { SYSTEM_TEAM_BLUEPRINT to it } +
        formatTargets(document, definition, "team").map { SYSTEM_TEAM_BLUEPRINT to it } +
        formatTargets(document, definition, "user").map { SYSTEM_USER_BLUEPRINT to it }

/** Step 6: the findings/cap fixpoint — iterated until a full pass rejects nothing further. */
private fun fixpointFindingsAndCap(
    initial: List<Candidate>,
    snapshot: EntityImportSnapshot,
    verdicts: Array<EntityPlanVerdict?>,
    workspaceDocumentBytes: Long,
): List<Candidate> {
    var remaining = initial
    var changed = true
    while (changed) {
        changed = false
        remaining = rejectFindings(remaining, snapshot, verdicts) { changed = true }
        remaining = rejectOverCap(remaining, snapshot, verdicts, workspaceDocumentBytes) { changed = true }
    }
    return remaining
}

private fun rejectFindings(
    remaining: List<Candidate>,
    snapshot: EntityImportSnapshot,
    verdicts: Array<EntityPlanVerdict?>,
    onReject: () -> Unit,
): List<Candidate> {
    val storeKeys: Map<String, Set<String>> =
        remaining.groupBy({ it.blueprintIdentifier }, { it.identifier }).mapValues { it.value.toSet() }
    val combined: TargetExists = { bp, id -> snapshot.targetExists(bp, id) || storeKeys[bp]?.contains(id) == true }
    val survivors = mutableListOf<Candidate>()
    for (c in remaining) {
        val findings = entityFindings(c.request.toDocument(), c.definition, combined, c.request.team)
        if (findings.isEmpty()) {
            survivors += c
        } else {
            verdicts[c.index] = EntityPlanVerdict.Rejected(
                EntityImportRow(
                    c.index, c.blueprintIdentifier, c.identifier, OntologyImportStatus.INVALID,
                    message = findings.joinToString("; ") { "${it.field}: ${it.message}" }, findings = findings,
                ),
            )
            onReject()
        }
    }
    return survivors
}

/**
 * The entity-count caps ([MAX_ENTITIES_TOTAL]/[MAX_ENTITIES_PER_BLUEPRINT], CREATE rows only —
 * an UPDATE never grows the registry) PLUS the workspace document-byte budget (2.4.0), which
 * applies to every Store candidate — CREATE and UPDATE alike, since a shrinking edit frees room
 * for a later create. [runningBytes] is a running delta over [snapshot.documentBytes] in
 * submission order (`documentByteSize(request) − snapshot.bytesFor(existingId)` per candidate),
 * mirroring the write path's `EntityService.checkDocumentByteBudget`.
 */
private fun rejectOverCap(
    remaining: List<Candidate>,
    snapshot: EntityImportSnapshot,
    verdicts: Array<EntityPlanVerdict?>,
    workspaceDocumentBytes: Long,
    onReject: () -> Unit,
): List<Candidate> {
    val survivors = mutableListOf<Candidate>()
    var totalCreates = 0
    val createsByBlueprint = mutableMapOf<UInt, Int>()
    var runningBytes = snapshot.documentBytes
    for (c in remaining) {
        val replacingBytes = c.existingId?.let { snapshot.bytesFor(it) } ?: 0L
        val projectedBytes = runningBytes - replacingBytes + documentByteSize(c.request)
        val message = if (c.existingId != null) {
            byteBudgetMessage(projectedBytes, workspaceDocumentBytes)
        } else {
            totalCreates++
            val bpCreates = (createsByBlueprint[c.blueprintId] ?: 0) + 1
            createsByBlueprint[c.blueprintId] = bpCreates
            countCapMessage(c, snapshot, totalCreates, bpCreates) ?: byteBudgetMessage(projectedBytes, workspaceDocumentBytes)
        }
        if (message == null) {
            survivors += c
            runningBytes = projectedBytes
        } else {
            verdicts[c.index] = rejected(c.index, c.blueprintIdentifier, c.identifier, message)
            onReject()
        }
    }
    return survivors
}

private fun countCapMessage(c: Candidate, snapshot: EntityImportSnapshot, totalCreates: Int, bpCreates: Int): String? = when {
    snapshot.total + totalCreates > MAX_ENTITIES_TOTAL -> "The entity registry is full ($MAX_ENTITIES_TOTAL entities)"
    snapshot.countFor(c.blueprintId) + bpCreates > MAX_ENTITIES_PER_BLUEPRINT ->
        "This blueprint's entities are full ($MAX_ENTITIES_PER_BLUEPRINT entities)"
    else -> null
}

private fun byteBudgetMessage(projectedBytes: Long, workspaceDocumentBytes: Long): String? =
    if (projectedBytes > workspaceDocumentBytes) "The entity workspace is full ($workspaceDocumentBytes bytes of documents)" else null

/** A reference that MAY NOT be dropped: a `required: true` relation, or a `format: team|user` property in `schema.required`. */
private fun mandatoryFieldNaming(c: Candidate, deferred: Set<Pair<String, String>>): String? {
    c.definition.relations.forEach { (id, relationDef) ->
        if (!relationDef.required) return@forEach
        val targets = scalarOrArrayStrings(c.request.relations[id])
        if (targets.any { relationDef.target to it in deferred }) return "relations.$id"
    }
    c.definition.schema.required.forEach { id ->
        val propertyDef = c.definition.schema.properties[id] ?: return@forEach
        val targetBlueprint = formatTargetBlueprint(propertyDef.format) ?: return@forEach
        val values = scalarOrArrayStrings(c.request.properties[id])
        if (values.any { targetBlueprint to it in deferred }) return "properties.$id"
    }
    return null
}

private fun formatTargetBlueprint(format: String?): String? = when (format) {
    "team" -> SYSTEM_TEAM_BLUEPRINT
    "user" -> SYSTEM_USER_BLUEPRINT
    else -> null
}

private fun scalarOrArrayStrings(value: JsonElement?): List<String> = when (value) {
    is JsonPrimitive -> if (value.isString) listOf(value.content) else emptyList()
    is JsonArray -> value.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.content }
    else -> emptyList()
}

/** One Kahn ordering attempt: `null` mandatory violation means success, ready for [withDeferredStripped]. */
private data class OrderingAttempt(
    val order: List<Int>,
    val deferredByIndex: Map<Int, Set<Pair<String, String>>>,
    val mandatoryViolation: Pair<Int, String>?,
)

/**
 * The mechanical loop and its input-order tie-break live in `infra/importing/ImportBatch.kt`'s
 * [orderWithDeferral], shared with `blueprints/BlueprintImport.kt`'s `orderCandidates`; this
 * function only collects the entity-shaped edges (relations/`team`/format-properties, via
 * [siblingReferences]) and supplies the ONE policy difference — a forced pick whose deferred set
 * would drop a MANDATORY reference ([mandatoryFieldNaming]) stops the sort outright instead of
 * deferring it to pass 2.
 */
private fun attemptOrdering(remaining: List<Candidate>): OrderingAttempt {
    val byIndex = remaining.associateBy { it.index }
    val createKeysByBlueprint: Map<String, Set<String>> =
        remaining.filter { it.existingId == null }.groupBy({ it.blueprintIdentifier }, { it.identifier }).mapValues { it.value.toSet() }
    val indexByKey: Map<Pair<String, String>, Int> = remaining.associate { (it.blueprintIdentifier to it.identifier) to it.index }
    val dependencies: Map<Int, List<Pair<String, String>>> = remaining.associate { c ->
        val refs = siblingReferences(c.request.toDocument(), c.definition, c.request.team)
        c.index to refs.filter { (bp, id) -> createKeysByBlueprint[bp]?.contains(id) == true }
    }

    val batch = orderWithDeferral(remaining.map { it.index }, dependencies, indexByKey) { idx, deferred ->
        mandatoryFieldNaming(byIndex.getValue(idx), deferred)
    }
    return OrderingAttempt(batch.order, batch.deferredByIndex, batch.stoppedAt)
}

/** Drops every relation/team/format-property value naming a [deferred] sibling — the pass-1 write's temporary shape. */
private fun withDeferredStripped(
    request: EntityRequest,
    definition: BlueprintDefinition,
    deferred: Set<Pair<String, String>>,
): EntityRequest {
    if (deferred.isEmpty()) return request
    val relations = JsonObject(
        request.relations.mapNotNull { (id, value) ->
            val relationDef = definition.relations[id] ?: return@mapNotNull id to value
            stripRelationValue(value, relationDef, deferred)?.let { id to it }
        }.toMap(),
    )
    val team = stripTeamValue(request.team, deferred)
    val properties = JsonObject(
        request.properties.mapNotNull { (id, value) ->
            val propertyDef = definition.schema.properties[id] ?: return@mapNotNull id to value
            stripPropertyValue(propertyDef, value, deferred)?.let { id to it }
        }.toMap(),
    )
    return request.copy(relations = relations, team = team, properties = properties)
}

private fun stripRelationValue(
    value: JsonElement,
    def: RelationDefinition,
    deferred: Set<Pair<String, String>>,
): JsonElement? = if (!def.many) {
    val content = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (content != null && def.target to content in deferred) null else value
} else {
    (value as? JsonArray)?.let { array -> JsonArray(array.filterNot { it.deferredStringMatches(def.target, deferred) }) } ?: value
}

private fun JsonElement.deferredStringMatches(targetBlueprint: String, deferred: Set<Pair<String, String>>): Boolean =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { targetBlueprint to it in deferred } == true

private fun stripTeamValue(team: JsonElement?, deferred: Set<Pair<String, String>>): JsonElement? = when (team) {
    null -> null
    is JsonPrimitive -> if (team.isString && SYSTEM_TEAM_BLUEPRINT to team.content in deferred) null else team
    is JsonArray -> JsonArray(team.filterNot { it.deferredStringMatches(SYSTEM_TEAM_BLUEPRINT, deferred) })
    else -> team
}

private fun stripPropertyValue(def: PropertyDefinition, value: JsonElement, deferred: Set<Pair<String, String>>): JsonElement? {
    val targetBlueprint = formatTargetBlueprint(def.format) ?: return value
    return when {
        def.type == "string" -> {
            val content = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (content != null && targetBlueprint to content in deferred) null else value
        }
        def.type == "array" && value is JsonArray -> JsonArray(value.filterNot { it.deferredStringMatches(targetBlueprint, deferred) })
        else -> value
    }
}

/**
 * The batch classification: steps 1-4 (decode/validate/blueprint/duplicate/registry-match), the
 * step-6 fixpoint (findings against `registry ∪ will-store`, the caps), then ordering with
 * deferral — a mandatory-reference cycle rejects its document and re-runs the fixpoint, since one
 * fewer candidate can change what other documents resolve against.
 */
fun planEntityImport(
    documents: List<JsonObject>,
    snapshot: EntityImportSnapshot,
    replaceExisting: Boolean,
    workspaceDocumentBytes: Long = MAX_WORKSPACE_DOCUMENT_BYTES,
): EntityImportPlan {
    val verdicts = arrayOfNulls<EntityPlanVerdict>(documents.size)
    var remaining = classifyDocuments(documents, snapshot, replaceExisting, verdicts)
    remaining = fixpointFindingsAndCap(remaining, snapshot, verdicts, workspaceDocumentBytes)

    while (true) {
        val attempt = attemptOrdering(remaining)
        val violation = attempt.mandatoryViolation
        if (violation == null) {
            val byIndex = remaining.associateBy { it.index }
            val storeByIndex: Map<Int, EntityPlanVerdict.Store> = attempt.order.associateWith { idx ->
                val c = byIndex.getValue(idx)
                val deferred = attempt.deferredByIndex[idx].orEmpty()
                val pass1 = withDeferredStripped(c.request, c.definition, deferred)
                EntityPlanVerdict.Store(c.request, c.existingId, deferred.map { "${it.first}/${it.second}" }.toSet(), pass1)
            }
            attempt.order.forEach { idx -> verdicts[idx] = storeByIndex.getValue(idx) }
            return EntityImportPlan(verdicts.map { it ?: error("entity import document left unclassified") }, attempt.order)
        }
        val (idx, field) = violation
        val c = remaining.first { it.index == idx }
        verdicts[idx] = rejected(idx, c.blueprintIdentifier, c.identifier, "Circular required reference '$field' within the batch")
        remaining = fixpointFindingsAndCap(remaining.filterNot { it.index == idx }, snapshot, verdicts, workspaceDocumentBytes)
    }
}

// ---------------------------------------------------------------------------------------------
// Effectful: EntityService.import / importCheck — the planner's only callers.
// ---------------------------------------------------------------------------------------------

private const val ENTITY_STORAGE_FAILED = "Storage failed"

// Per-document isolation, shared by both writer passes: rethrow cancellation, classify a
// pass-1 unique-violation race (no committed id) as EXISTS, and every pass-2 failure as an
// ERROR retaining its committed id — never let an unexpected failure escape the batch.
@Suppress("TooGenericExceptionCaught")
internal fun storageFailureRow(
    e: Exception,
    index: Int,
    blueprint: String?,
    identifier: String?,
    id: UInt? = null,
    messageFor: (String) -> String = { it },
): EntityImportRow = if (id == null && e.isUniqueViolation()) {
    EntityImportRow(index, blueprint, identifier, OntologyImportStatus.EXISTS, id = id, message = messageFor("created concurrently"))
} else {
    EntityImportRow(index, blueprint, identifier, OntologyImportStatus.ERROR, id = id, message = messageFor(ENTITY_STORAGE_FAILED))
}

/**
 * The real run: workspace snapshot → plan → pass 1 in [EntityImportPlan.order] (reusing
 * [EntityService.create]/[EntityService.update] per row, so every write still runs under the
 * ordinary two-table V28 lock) → pass 2 for rows with deferred references.
 */
suspend fun EntityService.import(
    documents: List<JsonObject>,
    callerId: UInt,
    replaceExisting: Boolean,
    onCommitted: (ImportMutation) -> Unit,
): List<EntityImportRow> {
    val plan = planEntityImport(documents, importSnapshot(), replaceExisting, workspaceDocumentBytes)
    val rows = arrayOfNulls<EntityImportRow>(documents.size)
    plan.verdicts.forEachIndexed { idx, verdict -> if (verdict is EntityPlanVerdict.Rejected) rows[idx] = verdict.row }

    val storedIds = mutableMapOf<Int, UInt>()
    for (idx in plan.order) {
        val verdict = plan.verdicts[idx] as? EntityPlanVerdict.Store ?: continue
        val write = writeEntityRow(idx, verdict, callerId, storedIds)
        rows[idx] = write.row
        // Keep committed mutation facts separate from pass 2's final verdict. The callback is
        // deliberately outside storage classification and runs exactly once per pass-1 write.
        write.mutation?.let(onCommitted)
    }
    for (idx in plan.order) {
        val verdict = plan.verdicts[idx] as? EntityPlanVerdict.Store ?: continue
        if (verdict.deferred.isEmpty()) continue
        val id = storedIds[idx] ?: continue
        rows[idx] = pass2Entity(id, verdict, rows[idx] ?: error("entity import row $idx missing after pass 1"))
    }
    return rows.map { it ?: error("entity import row left unset") }
}

private data class EntityPass1Write(val row: EntityImportRow, val mutation: ImportMutation? = null)

// Per-document isolation: one row's unexpected failure is reported as ERROR and never fails siblings (report & skip).
@Suppress("TooGenericExceptionCaught")
private suspend fun EntityService.writeEntityRow(
    index: Int,
    verdict: EntityPlanVerdict.Store,
    callerId: UInt,
    storedIds: MutableMap<Int, UInt>,
): EntityPass1Write {
    val blueprint = verdict.request.blueprint
    val identifier = verdict.request.identifier
    return try {
        if (verdict.existingId == null) {
            val created = create(verdict.pass1, callerId)
            storedIds[index] = created.id
            EntityPass1Write(
                EntityImportRow(index, blueprint, identifier, OntologyImportStatus.CREATED, id = created.id),
                ImportMutation(ImportMutationKind.CREATED, created.id, identifier, blueprint = blueprint),
            )
        } else {
            val result = update(verdict.existingId, verdict.pass1)
            if (result.affected == 0) {
                EntityPass1Write(EntityImportRow(index, blueprint, identifier, OntologyImportStatus.ERROR, message = "Entity vanished"))
            } else {
                storedIds[index] = verdict.existingId
                EntityPass1Write(
                    EntityImportRow(index, blueprint, identifier, OntologyImportStatus.UPDATED, id = verdict.existingId),
                    ImportMutation(ImportMutationKind.UPDATED, verdict.existingId, identifier, blueprint = blueprint),
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: EntityInvalidException) {
        EntityPass1Write(
            EntityImportRow(index, blueprint, identifier, OntologyImportStatus.INVALID, message = e.message, findings = e.findings),
        )
    } catch (e: BadRequestException) {
        EntityPass1Write(EntityImportRow(index, blueprint, identifier, OntologyImportStatus.INVALID, message = e.message))
    } catch (e: Exception) {
        EntityPass1Write(storageFailureRow(e, index, blueprint, identifier))
    }
}

// Per-document isolation, the writeEntityRow precedent: an unexpected pass-2 storage failure is
// reported as ERROR (via storageFailureRow) rather than escaping and failing the whole batch.
@Suppress("TooGenericExceptionCaught")
private suspend fun EntityService.pass2Entity(id: UInt, verdict: EntityPlanVerdict.Store, previousRow: EntityImportRow): EntityImportRow =
    try {
        val result = update(id, verdict.request)
        if (result.affected == 0) {
            previousRow.copy(
                status = OntologyImportStatus.ERROR,
                id = id,
                message = "Stored without its deferred references: Entity vanished",
            )
        } else {
            previousRow
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: EntityInvalidException) {
        previousRow.copy(
            status = OntologyImportStatus.ERROR, id = id, findings = e.findings,
            message = "Stored without its deferred references: ${e.message}",
        )
    } catch (e: BadRequestException) {
        previousRow.copy(status = OntologyImportStatus.ERROR, id = id, message = "Stored without its deferred references: ${e.message}")
    } catch (e: Exception) {
        storageFailureRow(e, previousRow.index, previousRow.blueprint, previousRow.identifier, id) {
            "Stored without its deferred references: $it"
        }
    }

/** The dry-run: the identical classification, storing nothing. */
suspend fun EntityService.importCheck(documents: List<JsonObject>, replaceExisting: Boolean): List<EntityImportRow> {
    val plan = planEntityImport(documents, importSnapshot(), replaceExisting, workspaceDocumentBytes)
    return plan.verdicts.mapIndexed { idx, verdict ->
        when (verdict) {
            is EntityPlanVerdict.Rejected -> verdict.row
            is EntityPlanVerdict.Store -> EntityImportRow(
                idx,
                verdict.request.blueprint,
                verdict.request.identifier,
                if (verdict.existingId != null) OntologyImportStatus.UPDATED else OntologyImportStatus.CREATED,
                id = verdict.existingId,
            )
        }
    }
}
