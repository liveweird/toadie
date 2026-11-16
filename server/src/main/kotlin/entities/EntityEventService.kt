package ch.nokillswit.entities

import ch.nokillswit.infra.db.EventLog
import ch.nokillswit.infra.db.EventLogTable
import ch.nokillswit.infra.paging.PageRequest
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

val EntityEventServiceKey = AttributeKey<EntityEventService>("EntityEventService")

/** One page of an entity's history plus the pre-pagination total (the list-endpoint envelope). */
data class EntityEventListResult(
    val items: List<EntityEventResponse>,
    val total: Long,
)

/**
 * The entity's audit trail — a thin wrapper over the shared [EventLog] mechanics
 * (`infra/db/EventLog.kt`), the `catalog/CatalogFileEventService.kt` twin; only the typed DTO
 * mapping lives here. Entity writes append through [recordInTransaction], so each event commits
 * or rolls back with its owning write (`.claude/docs/persistence.md` "Entity mutations and
 * product history"). Nothing here ever updates or deletes a row.
 */
class EntityEventService(database: R2dbcDatabase) {

    object EntityEvents : EventLogTable("entity_events", "entity_id", EntityService.Entities) {
        /** Feature-named alias for direct DSL use (the same Column instance). */
        val entityId get() = ownerId
    }

    private val log = EventLog(database, EntityEvents)

    /** Appends one event in the entity write's current transaction. */
    internal suspend fun recordInTransaction(
        entityId: UInt,
        byUserId: UInt,
        descriptor: EntityEventDescriptor,
    ): UInt = log.createInTransaction(entityId, byUserId, descriptor.type.name, descriptor.params)

    /** The entity's history, newest first (id descending as the same-instant tiebreaker). */
    suspend fun listForEntity(entityId: UInt, paging: PageRequest): EntityEventListResult {
        val page = log.listFor(entityId, paging)
        return EntityEventListResult(
            items = page.items.map {
                EntityEventResponse(
                    id = it.id,
                    entityId = it.ownerId,
                    userId = it.userId,
                    userName = it.userName,
                    userDeleted = it.userDeleted,
                    userServiceAccount = it.userServiceAccount,
                    timestamp = it.timestamp,
                    type = EntityEventType.valueOf(it.type),
                    params = it.params,
                )
            },
            total = page.total,
        )
    }
}
