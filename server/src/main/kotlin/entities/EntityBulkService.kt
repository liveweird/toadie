package ch.nokillswit.entities

import ch.nokillswit.infra.concurrency.ReadBudgetExceeded
import ch.nokillswit.infra.concurrency.ReadLedger
import ch.nokillswit.infra.concurrency.throwReadBudgetHttp
import ch.nokillswit.infra.fetch.SourceWrite
import io.ktor.server.plugins.BadRequestException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.entities.EntityBulk")

/**
 * The effectful half of the entity bulk change (`entities/EntityBulk.kt`; the pure classification is
 * `entities/EntityBulkPlan.kt`), declared as [EntityService] EXTENSIONS — the `entities/EntityImport.kt` idiom, and the service class
 * is near detekt's size cap. Both endpoints plan identically: the request-level rules first (no database), then
 * [EntityService.importSnapshot] (identities only, outside the read ledger by its documented exception) and the targets' STORED
 * documents ([readStoredEntities], never the computed view) under ONE [EntityService.readLedger] reservation that covers the read and
 * the planning and is released as soon as the plan exists (the decoded stored documents are garbage after it). A refused read is the
 * usual `400`/`429` ([throwReadBudgetHttp]). The dry-run stops there; the real run then holds a SECOND, smaller reservation — only the
 * planned [BulkRowPlan.Write] rows' after-documents, which stay alive for the write loop — and writes each one through the ordinary
 * strict [EntityService.update]: its own transaction under the V28 lock, its own history event (`origin=bulk`, `bulkId`) and its own
 * audit callback, reporting and skipping per row.
 */

/** The dry-run: the identical classification, storing, auditing and recording nothing. */
suspend fun EntityService.bulkChangeCheck(request: EntityBulkChangeRequest): EntityBulkChangeResponse =
    EntityBulkChangeResponse(bulkId = null, results = planBulk(request).second.map { it.row })

/** The real run: [onCommitted] fires once per row, right after that row's transaction committed (the route audits from it). */
internal suspend fun EntityService.bulkChange(
    request: EntityBulkChangeRequest,
    callerId: UInt,
    onCommitted: (EntityBulkCommit) -> Unit,
): EntityBulkChangeResponse {
    val (change, plans) = planBulk(request)
    val bulkId = UUID.randomUUID().toString()
    return holdWriteSet(plans).use {
        val results = plans.map { plan ->
            when (plan) {
                is BulkRowPlan.Skip -> plan.row
                is BulkRowPlan.Write -> writeBulkRow(plan, change, callerId, bulkId, onCommitted)
            }
        }
        EntityBulkChangeResponse(bulkId = bulkId, results = results)
    }
}

/**
 * The request-level rules, the blueprint, the targets' stored documents and the pure plan — under one reservation that is released
 * when this returns (only the plan's small rows and the after-documents of its writes outlive it).
 */
private suspend fun EntityService.planBulk(request: EntityBulkChangeRequest): Pair<ResolvedBulkChange, List<BulkRowPlan>> {
    requireBulkShape(request)
    val snapshot = importSnapshot()
    val blueprint = snapshot.blueprint(request.blueprint) ?: throw BadRequestException("Unknown blueprint")
    val change = resolveBulkChange(request, blueprint.definition)
    return readLedger.open().use { reservation ->
        val stored = try {
            readStoredEntities(request.entities.map { it.id }, snapshot, reservation)
        } catch (cause: ReadBudgetExceeded) {
            throwReadBudgetHttp(cause, "entity")
        }
        change to planEntityBulkChange(request.entities, stored, change, snapshot, workspaceDocumentBytes)
    }
}

/**
 * The reservation the write loop keeps: the planned writes' after-documents ([documentByteSize] x [ENTITY_SHOWN_ROW_RATIO], the
 * read's own ratio), booked all-or-nothing BEFORE the first write — a refusal is the usual `400`/`429` with nothing stored.
 */
private fun EntityService.holdWriteSet(plans: List<BulkRowPlan>): ReadLedger.Reservation {
    val bytes = plans.filterIsInstance<BulkRowPlan.Write>().sumOf { documentByteSize(it.request).toLong() }
    val reservation = readLedger.open()
    try {
        reservation.reserve(bytes * ENTITY_SHOWN_ROW_RATIO)
    } catch (cause: ReadBudgetExceeded) {
        reservation.close()
        throwReadBudgetHttp(cause, "entity")
    }
    return reservation
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
            goneRow(row.id)
        } else {
            committed = true
            row.copy(updatedAt = result.updatedAt ?: row.updatedAt)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: EntityStaleException) {
        staleRow(row, e.message ?: STALE_CHANGED)
    } catch (e: EntityInvalidException) {
        row.copy(status = EntityBulkStatus.INVALID, message = e.message, findings = e.findings)
    } catch (e: BadRequestException) {
        row.copy(status = EntityBulkStatus.INVALID, message = e.message)
    } catch (e: Exception) {
        // The entity id, the run and the exception CLASS only — never the document, the value or the message (they can carry content).
        log.warn("bulk change row failed: entityId={} bulkId={} cause={}", row.id, bulkId, e.javaClass.name)
        row.copy(status = EntityBulkStatus.ERROR, message = "Storage failed")
    }
    // Deliberately outside the storage classification above (the EntityImport.kt posture): the row IS committed, so a failing
    // callback must never turn it into an ERROR.
    if (committed) onCommitted(EntityBulkCommit(row.id, change.blueprint, plan.request.identifier, change.field.path, bulkId))
    return outcome
}

/** The row of an entity that vanished between the plan and its write: no identity and no stamp, the planner's own shape. */
private fun goneRow(id: UInt) = EntityBulkResult(id, status = EntityBulkStatus.STALE, message = NO_LONGER_ACTIVE)

/**
 * A write refused as STALE: nothing was stored, and the row carries the CURRENT stored stamp so a client retrying with
 * `result.updatedAt` is not STALE forever. The stamp is read best-effort in its own short transaction — a vanished entity is a
 * [goneRow], an unreadable stamp leaves `updatedAt` absent (the retry then goes unguarded or re-checks first).
 */
private suspend fun EntityService.staleRow(row: EntityBulkResult, message: String): EntityBulkResult {
    val read = runCatching { currentUpdatedAt(row.id) }
    read.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    if (read.isSuccess && read.getOrNull() == null) return goneRow(row.id)
    return EntityBulkResult(
        row.id, row.identifier, row.title, EntityBulkStatus.STALE, message, updatedAt = read.getOrNull(),
    )
}

/** The stored write-version stamp of the active entity [id], null when it is no longer active. */
private suspend fun EntityService.currentUpdatedAt(id: UInt): Long? = suspendTransaction(database) {
    EntityService.Entities.select(EntityService.Entities.updatedAt)
        .where { (EntityService.Entities.id eq id) and active() }
        .firstOrNull()
        ?.get(EntityService.Entities.updatedAt)
}
