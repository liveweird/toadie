package ch.nokillswit.infra.db

import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The shared shape of the per-record audit-event tables (today only `catalog_file_events`,
 * V23) — ported from Lettuce, where seven `*_events` tables ride it: an FK to the owning
 * record, the acting user, a creation timestamp, and a STRUCTURED event (kind + JSON params)
 * the SPA localizes. Feature packages declare
 * `object XEvents : EventLogTable("x_events", "x_id", XTable)` and keep their typed
 * create/list wrappers; the mechanics live once here.
 *
 * Two deliberate departures from Lettuce's copy. Its opt-in `commentColumn` hook (an
 * encrypted free-text column on `goal_events`) is left behind: Toadie's events never store
 * free text at all — the diff records the FACT that a free-text field changed, never its
 * value (`catalog/CatalogFileEvents.kt`). And [listFor] is PAGED, because a catalog file's
 * event count is unbounded (repo sync alone mints one per run) and
 * `api-guidelines/API-GUIDELINES.md` admits the unpaged `{items}` wrapper only for an
 * intrinsically tiny set.
 */
abstract class EventLogTable(
    name: String,
    ownerColumn: String,
    ownerTable: IdTable<UInt>,
) : UIntIdTable(name) {
    val ownerId: Column<EntityID<UInt>> = reference(ownerColumn, ownerTable)
    val userId = reference("user_id", UserService.Users)
    val timestamp = long("created_at")
    // Structured event so the SPA can localize it: the kind plus a JSON params map.
    val eventType = varchar("event_type", EVENT_TYPE_LENGTH)
    val params = text("params")
}

private const val EVENT_TYPE_LENGTH = 40

/** The sortable whitelist every events endpoint declares (an event has nothing else to sort on). */
val EVENT_LOG_SORT_FIELDS: Set<String> = setOf("id", "timestamp")

/**
 * Newest first, with the id tiebreaker DESCENDING too: one mutation mints its events inside a
 * single millisecond, so only the id ordering makes them read as a true reversal of mint order.
 */
val EVENT_LOG_DEFAULT_SORT: List<SortField> =
    listOf(SortField("timestamp", descending = true), SortField("id", descending = true))

/** One raw event row with the acting user resolved — feature wrappers map it to their typed response. */
data class EventLogRow(
    val id: UInt,
    val ownerId: UInt,
    val userId: UInt,
    val userName: String,
    val timestamp: Long,
    val type: String,
    val params: Map<String, String>,
)

/** One page of a record's history plus the pre-pagination total (the list-endpoint envelope). */
data class EventLogPage(val items: List<EventLogRow>, val total: Long)

class EventLog(private val database: R2dbcDatabase, private val table: EventLogTable) {

    // The sanctioned cross-feature table read (see persistence.md): the acting user's display
    // fields must come from the same transaction as the event rows, so the users table is
    // joined directly instead of calling UserService (a second transaction).
    private fun joined() = table.join(
        UserService.Users,
        JoinType.INNER,
        onColumn = table.userId,
        otherColumn = UserService.Users.id,
    )

    private val sortableColumns: Map<String, Column<*>> =
        mapOf("id" to table.id, "timestamp" to table.timestamp)

    /**
     * Inserts an audit event. The timestamp is set here, never taken from a caller. Rows are
     * immutable: there is no update or delete, and none is wanted — an event outlives its
     * (soft-deleted) record.
     */
    suspend fun create(
        ownerId: UInt,
        actingUserId: UInt,
        type: String,
        eventParams: Map<String, String>,
    ): UInt = suspendTransaction(database) {
        table.insert {
            it[table.ownerId] = ownerId
            it[table.userId] = actingUserId
            it[table.timestamp] = System.currentTimeMillis()
            it[table.eventType] = type
            it[table.params] = encodeParams(eventParams)
        }[table.id].value
    }

    /** The record's history page with acting user names; count + rows on one predicate, one transaction. */
    suspend fun listFor(ownerId: UInt, paging: PageRequest): EventLogPage = suspendTransaction(database) {
        val total = joined().selectAll().where { table.ownerId eq ownerId }.count()
        val rows = joined().selectAll()
            .where { table.ownerId eq ownerId }
            .applyPaging(paging, sortableColumns)
            .map { row ->
                EventLogRow(
                    id = row[table.id].value,
                    ownerId = ownerId,
                    userId = row[table.userId].value,
                    userName = row[UserService.Users.name],
                    timestamp = row[table.timestamp],
                    type = row[table.eventType],
                    params = decodeParams(row[table.params]),
                )
            }
            .toList()
        EventLogPage(items = rows, total = total)
    }
}
