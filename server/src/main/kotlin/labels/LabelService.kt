package ch.nokillswit.labels

import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val LabelServiceKey = AttributeKey<LabelService>("LabelService")

class LabelService(private val database: R2dbcDatabase) {
    object Labels : UIntIdTable("labels") {
        // Case-folded key uniqueness is enforced by the partial unique index
        // uq_labels_key_active (active rows only; V10), so a soft-deleted label frees its
        // key. Exposed table defs are query-only (not DDL), so no `.uniqueIndex()` here.
        val key = varchar("key", length = MAX_LABEL_KEY_LENGTH)

        // JSON arrays in TEXT (the catalog_files.content precedent): a label's value/kind
        // lists are replaced whole on every save — no child-table reconcile.
        val allowedKinds = text("allowed_kinds")
        val allowedValues = text("allowed_values")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private val json = Json

    private fun active(): Op<Boolean> = Labels.markedAsDeleted eq false

    private fun rowToResponse(id: UInt, key: String, kindsJson: String, valuesJson: String) = LabelResponse(
        id = id,
        key = key,
        values = json.decodeFromString(valuesJson),
        kinds = json.decodeFromString(kindsJson),
    )

    /** Every active label, key-ordered case-insensitively (id as deterministic tiebreaker). */
    suspend fun list(): List<LabelResponse> = suspendTransaction(database) {
        Labels.selectAll()
            .where { active() }
            .orderBy(Labels.key.lowerCase() to SortOrder.ASC, Labels.id to SortOrder.ASC)
            .map { rowToResponse(it[Labels.id].value, it[Labels.key], it[Labels.allowedKinds], it[Labels.allowedValues]) }
            .toList()
    }

    suspend fun create(request: LabelRequest): UInt = suspendTransaction(database) {
        validateLabelRequest(request) // re-checked service-side so direct callers stay guarded
        if (Labels.selectAll().where { active() }.count() >= MAX_LABELS) {
            throw BadRequestException("The label registry is full ($MAX_LABELS labels)")
        }
        val newRecord = Labels.insert {
            it[key] = request.key
            it[allowedKinds] = json.encodeToString(request.kinds)
            it[allowedValues] = json.encodeToString(request.values)
        }
        newRecord[Labels.id].value
    }

    /**
     * Whole-label replace, key rename included — a stored file carrying the old key simply
     * goes strict-invalid on its next save (the no-grandfathering rule). Returns the
     * affected-row count (0 → the route's 404).
     */
    suspend fun update(id: UInt, request: LabelRequest): Int = suspendTransaction(database) {
        validateLabelRequest(request) // re-checked service-side so direct callers stay guarded
        Labels.update({ (Labels.id eq id) and (Labels.markedAsDeleted eq false) }) {
            it[key] = request.key
            it[allowedKinds] = json.encodeToString(request.kinds)
            it[allowedValues] = json.encodeToString(request.values)
        }
    }

    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        Labels.update({ (Labels.id eq id) and (Labels.markedAsDeleted eq false) }) {
            it[markedAsDeleted] = true
        }
    }
}
