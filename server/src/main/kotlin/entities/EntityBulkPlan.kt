package ch.nokillswit.entities

import ch.nokillswit.blueprints.blueprintJson
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.JsonElement

/**
 * The PURE, DB-free planner of the entity bulk change (`entities/EntityBulk.kt`) — the `classifyOne` of
 * `EntityImport.kt` for this operation, ONE classification shared by the dry-run and the real run (the real run writes
 * exactly the rows this plan marks [BulkRowPlan.Write] and re-checks each atomically under the write lock).
 *
 * Per row, in this order (the first that applies decides): 1 no longer an active entity -> STALE; 2 of another blueprint
 * -> INVALID; 3 the client's `updatedAt` differs from the stamp read with the document -> STALE; 4 the operation changes
 * nothing the history would record -> UNCHANGED (no write at all: a PUT always moves `updated_at`); 5 the request shape
 * rules fail -> INVALID; 6 [entityFindings] over the WHOLE after-document -> INVALID with the findings; 7 the running
 * workspace byte budget would be exceeded -> INVALID; 8 otherwise UPDATED.
 */
internal sealed interface BulkRowPlan {
    val row: EntityBulkResult

    /** Nothing to write: the verdict is final (UNCHANGED, INVALID or STALE). */
    data class Skip(override val row: EntityBulkResult) : BulkRowPlan

    /**
     * The row to write: [request] is the full after-document, [expectedUpdatedAt] the stamp read with the document,
     * [projectedBytes] the running workspace byte budget after it.
     */
    data class Write(
        override val row: EntityBulkResult,
        val request: EntityRequest,
        val expectedUpdatedAt: Long,
        val projectedBytes: Long,
    ) : BulkRowPlan
}

private const val NO_LONGER_ACTIVE = "the entity no longer exists"

/** [targets] in request order against what was [stored] (only the still-active ones are present), judged under [change]. */
internal fun planEntityBulkChange(
    targets: List<EntityBulkTarget>,
    stored: Map<UInt, StoredEntity>,
    change: ResolvedBulkChange,
    snapshot: EntityImportSnapshot,
    workspaceDocumentBytes: Long,
): List<BulkRowPlan> {
    var runningBytes = snapshot.documentBytes
    return targets.map { target ->
        val entity = stored[target.id]
        val plan = if (entity == null) {
            BulkRowPlan.Skip(EntityBulkResult(target.id, status = EntityBulkStatus.STALE, message = NO_LONGER_ACTIVE))
        } else {
            classifyBulkRow(target, entity, change, snapshot, runningBytes, workspaceDocumentBytes)
        }
        if (plan is BulkRowPlan.Write) runningBytes = plan.projectedBytes
        plan
    }
}

@Suppress("ReturnCount") // guard-clause classification, the EntityImport.kt `classifyOne` precedent
private fun classifyBulkRow(
    target: EntityBulkTarget,
    entity: StoredEntity,
    change: ResolvedBulkChange,
    snapshot: EntityImportSnapshot,
    runningBytes: Long,
    workspaceDocumentBytes: Long,
): BulkRowPlan {
    val verdict = RowVerdicts(entity)
    if (entity.blueprint != change.blueprint) {
        return verdict.skip(EntityBulkStatus.INVALID, "the entity belongs to blueprint '${entity.blueprint}', not '${change.blueprint}'")
    }
    if (target.updatedAt != null && target.updatedAt != entity.updatedAt) {
        return verdict.skip(EntityBulkStatus.STALE, STALE_CHANGED)
    }
    val before = entity.fieldValue(change.field)
    val after = applyBulkOperation(before, change.operation, change.value)
    val afterRequest = entity.withField(change.field, after)
    val values = valuesOf(before, after)
    if (entityChanges(entity.eventState(), afterRequest.toEventState(null), change.definition).isEmpty) {
        return verdict.skip(EntityBulkStatus.UNCHANGED, null, values)
    }
    try {
        validateEntityRequest(afterRequest)
    } catch (e: BadRequestException) {
        return verdict.skip(EntityBulkStatus.INVALID, e.message, values)
    }
    val findings = entityFindings(afterRequest.toDocument(), change.definition, snapshot.targetExists, afterRequest.team)
    if (findings.isNotEmpty()) {
        val message = findings.joinToString("; ") { "${it.field}: ${it.message}" }
        return verdict.skip(EntityBulkStatus.INVALID, message, values, findings)
    }
    val projected = runningBytes - snapshot.bytesFor(entity.id) + documentByteSize(afterRequest)
    byteBudgetMessage(projected, workspaceDocumentBytes)?.let { return verdict.skip(EntityBulkStatus.INVALID, it, values) }
    return BulkRowPlan.Write(
        verdict.result(EntityBulkStatus.UPDATED, null, values), afterRequest, entity.updatedAt, projected,
    )
}

/** The stored fields the history diff reads (the source reference is never touched by a bulk write, so it never differs). */
private fun StoredEntity.eventState() =
    EntityEventState(identifier, title, icon, team, EntityDocument(properties, relations), sourceUrl = null)

/** The `before`/`after` pair a result shows: the field's values, or neither plus the flag when either is over [MAX_BULK_VALUE_CHARS]. */
internal data class BulkValues(val before: JsonElement?, val after: JsonElement?, val omitted: Boolean)

internal fun valuesOf(before: JsonElement?, after: JsonElement?): BulkValues {
    val tooLong = listOfNotNull(before, after).any { blueprintJson.encodeToString(it).length > MAX_BULK_VALUE_CHARS }
    return if (tooLong) BulkValues(null, null, omitted = true) else BulkValues(before, after, omitted = false)
}

/** Builds one entity's [EntityBulkResult]s (identity from the stored row, its stamp as read). */
private class RowVerdicts(private val entity: StoredEntity) {
    fun result(
        status: EntityBulkStatus,
        message: String?,
        values: BulkValues? = null,
        findings: List<EntityFinding>? = null,
    ) = EntityBulkResult(
        id = entity.id,
        identifier = entity.identifier,
        title = entity.title,
        status = status,
        message = message,
        findings = findings,
        updatedAt = entity.updatedAt,
        before = values?.before,
        after = values?.after,
        valuesOmitted = values?.omitted?.takeIf { it },
    )

    fun skip(status: EntityBulkStatus, message: String?, values: BulkValues? = null, findings: List<EntityFinding>? = null) =
        BulkRowPlan.Skip(result(status, message, values, findings))
}
