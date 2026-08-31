package ch.nokillswit.catalog

import ch.nokillswit.infra.db.EventLog
import ch.nokillswit.infra.db.EventLogTable
import ch.nokillswit.infra.paging.PageRequest
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

val CatalogFileEventServiceKey = AttributeKey<CatalogFileEventService>("CatalogFileEventService")

/** One page of a file's history plus the pre-pagination total (the list-endpoint envelope). */
data class CatalogFileEventListResult(
    val items: List<CatalogFileEventResponse>,
    val total: Long,
)

/**
 * The catalog file's audit trail — a thin wrapper over the shared [EventLog] mechanics
 * (`infra/db/EventLog.kt`); only the typed DTO mapping lives here. Events are minted as a
 * side-effect of the catalog mutations by their ROUTES (never by [CatalogFileService]), so
 * there is no create endpoint and nothing here ever updates or deletes a row.
 */
class CatalogFileEventService(database: R2dbcDatabase) {

    object CatalogFileEvents : EventLogTable(
        "catalog_file_events",
        "catalog_file_id",
        CatalogFileService.CatalogFiles,
    ) {
        /** Feature-named alias for direct DSL use (the same Column instance). */
        val catalogFileId get() = ownerId
    }

    private val log = EventLog(database, CatalogFileEvents)

    /** Appends one event. The timestamp is server-set; the descriptor carries type + params. */
    suspend fun record(catalogFileId: UInt, byUserId: UInt, descriptor: CatalogFileEventDescriptor): UInt =
        log.create(catalogFileId, byUserId, descriptor.type.name, descriptor.params)

    /** The file's history, newest first (id descending as the same-instant tiebreaker). */
    suspend fun listForFile(catalogFileId: UInt, paging: PageRequest): CatalogFileEventListResult {
        val page = log.listFor(catalogFileId, paging)
        return CatalogFileEventListResult(
            items = page.items.map {
                CatalogFileEventResponse(
                    id = it.id,
                    catalogFileId = it.ownerId,
                    userId = it.userId,
                    userName = it.userName,
                    timestamp = it.timestamp,
                    type = CatalogFileEventType.valueOf(it.type),
                    params = it.params,
                )
            },
            total = page.total,
        )
    }
}
