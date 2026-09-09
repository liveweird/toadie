package ch.nokillswit.users

import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert

val GraphLayoutServiceKey = AttributeKey<GraphLayoutService>("GraphLayoutService")

/**
 * Port migration phase 3 (`.claude/docs/port-data-model.md`): the Entity graph's own layout
 * store is a SECOND, independent instance of this same service over `entity_graph_layouts`
 * (V30) — so a manual layout/collapse edit on `/entity-graph` never collides with the
 * Backstage `/graph` page's saved layout for the same user.
 */
val EntityGraphLayoutServiceKey = AttributeKey<GraphLayoutService>("EntityGraphLayoutService")

/**
 * The column shape shared by every per-user Graph-layout table (V19+V24's `graph_layouts` and
 * V30's `entity_graph_layouts`): mode as a plain column, positions as a JSON object and the
 * collapsed ids as a JSON array, both in TEXT (the labels/tags precedent).
 */
abstract class GraphLayoutTable(name: String) : Table(name) {
    val userId = reference("user_id", UserService.Users)
    val mode = varchar("mode", length = 10).default("auto")
    val positions = text("positions").default("{}")
    val collapsed = text("collapsed").default("[]")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

/**
 * The per-user Graph-page layout store (V19): one row per user holding the whole
 * [GraphLayoutDocument]. Hard-delete by design (the user_disabled_features exception): pure
 * view state replaced wholesale on every save, no history worth keeping. Parameterized over
 * [table] so a second page's layout (the Entity graph's, V30) reuses this service unchanged
 * against its own, independent table — [GraphLayouts] and [EntityGraphLayouts] below.
 */
class GraphLayoutService(private val database: R2dbcDatabase, private val table: GraphLayoutTable) {
    object GraphLayouts : GraphLayoutTable("graph_layouts")
    object EntityGraphLayouts : GraphLayoutTable("entity_graph_layouts")

    private val json = Json

    /** The stored document, or the default (auto mode, nothing dragged) when never saved. */
    suspend fun read(userId: UInt): GraphLayoutDocument = suspendTransaction(database) {
        table.selectAll()
            .where { table.userId eq userId }
            .singleOrNull()
            ?.let {
                GraphLayoutDocument(
                    mode = it[table.mode],
                    positions = json.decodeFromString(it[table.positions]),
                    collapsed = json.decodeFromString(it[table.collapsed]),
                )
            }
            ?: GraphLayoutDocument()
    }

    /**
     * Wholesale replace (upsert — a drag stop and a mode switch may race from one client,
     * so update-then-insert would risk a duplicate-key 409). Idempotent; validated by the
     * route AND here so direct callers stay guarded.
     */
    suspend fun replace(userId: UInt, doc: GraphLayoutDocument): Unit = suspendTransaction(database) {
        validateGraphLayout(doc)
        table.upsert {
            it[table.userId] = userId
            it[table.mode] = doc.mode
            it[table.positions] = json.encodeToString(doc.positions)
            it[table.collapsed] = json.encodeToString(doc.collapsed)
            it[table.updatedAt] = System.currentTimeMillis()
        }
    }
}
