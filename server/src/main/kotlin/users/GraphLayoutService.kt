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
 * The per-user Graph-page layout store (V19): one row per user holding the whole
 * [GraphLayoutDocument] — mode as a plain column, positions as a JSON object in TEXT (the
 * labels/tags precedent). Hard-delete by design (the user_disabled_features exception):
 * pure view state replaced wholesale on every save, no history worth keeping.
 */
class GraphLayoutService(private val database: R2dbcDatabase) {
    object GraphLayouts : Table("graph_layouts") {
        val userId = reference("user_id", UserService.Users)
        val mode = varchar("mode", length = 10).default("auto")
        val positions = text("positions").default("{}")
        val updatedAt = long("updated_at")
        override val primaryKey = PrimaryKey(userId)
    }

    private val json = Json

    /** The stored document, or the default (auto mode, nothing dragged) when never saved. */
    suspend fun read(userId: UInt): GraphLayoutDocument = suspendTransaction(database) {
        GraphLayouts.selectAll()
            .where { GraphLayouts.userId eq userId }
            .singleOrNull()
            ?.let {
                GraphLayoutDocument(
                    mode = it[GraphLayouts.mode],
                    positions = json.decodeFromString(it[GraphLayouts.positions]),
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
        GraphLayouts.upsert {
            it[GraphLayouts.userId] = userId
            it[mode] = doc.mode
            it[positions] = json.encodeToString(doc.positions)
            it[updatedAt] = System.currentTimeMillis()
        }
    }
}
