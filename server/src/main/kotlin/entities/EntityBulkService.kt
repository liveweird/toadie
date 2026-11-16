package ch.nokillswit.entities

import ch.nokillswit.infra.concurrency.ReadBudgetExceeded
import ch.nokillswit.infra.concurrency.throwReadBudgetHttp
import ch.nokillswit.infra.fetch.SourceWrite
import io.ktor.server.plugins.BadRequestException
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * The effectful half of the entity bulk change (`entities/EntityBulk.kt`; the pure classification is
 * `entities/EntityBulkPlan.kt`), declared as [EntityService] EXTENSIONS — the `entities/EntityImport.kt` idiom, and the service class
 * is near detekt's size cap. Both endpoints plan identically: [EntityService.importSnapshot] (identities only, outside the read ledger
 * by its documented exception) and the targets' STORED documents ([readStoredEntities], never the computed view) under ONE
 * [EntityService.readLedger] reservation, held until the response is built because the decoded documents stay alive until then.
 * A refused read is the usual `400`/`429` ([throwReadBudgetHttp]). The dry-run stops there; the real run then writes each
 * [BulkRowPlan.Write] through the ordinary strict [EntityService.update] — its own transaction under the V28 lock, its own history
 * event (`origin=bulk`, `bulkId`) and its own audit callback — reporting and skipping per row.
 */

/** The dry-run: the identical classification, storing, auditing and recording nothing. */
suspend fun EntityService.bulkChangeCheck(request: EntityBulkChangeRequest): EntityBulkChangeResponse =
    withBulkPlan(request) { _, plans -> EntityBulkChangeResponse(bulkId = null, results = plans.map { it.row }) }

/** The real run: [onCommitted] fires once per row, right after that row's transaction committed (the route audits from it). */
internal suspend fun EntityService.bulkChange(
    request: EntityBulkChangeRequest,
    callerId: UInt,
    onCommitted: (EntityBulkCommit) -> Unit,
): EntityBulkChangeResponse = withBulkPlan(request) { change, plans ->
    val bulkId = UUID.randomUUID().toString()
    val results = plans.map { plan ->
        when (plan) {
            is BulkRowPlan.Skip -> plan.row
            is BulkRowPlan.Write -> writeBulkRow(plan, change, callerId, bulkId, onCommitted)
        }
    }
    EntityBulkChangeResponse(bulkId = bulkId, results = results)
}

private suspend fun <T> EntityService.withBulkPlan(
    request: EntityBulkChangeRequest,
    block: suspend (ResolvedBulkChange, List<BulkRowPlan>) -> T,
): T {
    requireBulkShape(request)
    val snapshot = importSnapshot()
    val blueprint = snapshot.blueprint(request.blueprint) ?: throw BadRequestException("Unknown blueprint")
    val change = resolveBulkChange(request, blueprint.definition)
    return readLedger.open().use { reservation ->
        try {
            val stored = readStoredEntities(request.entities.map { it.id }, snapshot, reservation)
            block(change, planEntityBulkChange(request.entities, stored, change, snapshot, workspaceDocumentBytes))
        } catch (cause: ReadBudgetExceeded) {
            throwReadBudgetHttp(cause, "entity")
        }
    }
}

// Per-row isolation, the EntityImport.kt `writeEntityRow` catch ladder: cancellation propagates, a changed/vanished row is STALE, a
// refused write INVALID, anything else ERROR — one row never fails its siblings.
@Suppress("TooGenericExceptionCaught")
private suspend fun EntityService.writeBulkRow(
    plan: BulkRowPlan.Write,
    change: ResolvedBulkChange,
    callerId: UInt,
    bulkId: String,
    onCommitted: (EntityBulkCommit) -> Unit,
): EntityBulkResult {
    val row = plan.row
    var committed = false
    val outcome = try {
        val result = update(
            row.id, plan.request, callerId, SourceWrite.Keep, plan.expectedUpdatedAt, EntityWriteKind.BULK, bulkId,
        )
        if (result.affected == 0) {
            row.copy(status = EntityBulkStatus.STALE, message = "the entity no longer exists")
        } else {
            committed = true
            row.copy(updatedAt = result.updatedAt ?: row.updatedAt)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: EntityStaleException) {
        row.copy(status = EntityBulkStatus.STALE, message = e.message ?: STALE_CHANGED)
    } catch (e: EntityInvalidException) {
        row.copy(status = EntityBulkStatus.INVALID, message = e.message, findings = e.findings)
    } catch (e: BadRequestException) {
        row.copy(status = EntityBulkStatus.INVALID, message = e.message)
    } catch (_: Exception) {
        row.copy(status = EntityBulkStatus.ERROR, message = "Storage failed")
    }
    // Deliberately outside the storage classification above (the EntityImport.kt posture): the row IS committed, so a failing
    // callback must never turn it into an ERROR.
    if (committed) onCommitted(EntityBulkCommit(row.id, change.blueprint, plan.request.identifier, change.field.path, bulkId))
    return outcome
}
