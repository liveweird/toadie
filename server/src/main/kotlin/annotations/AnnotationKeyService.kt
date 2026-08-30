package ch.nokillswit.annotations

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

val AnnotationKeyServiceKey = AttributeKey<AnnotationKeyService>("AnnotationKeyService")

class AnnotationKeyService(private val database: R2dbcDatabase) {
    object AnnotationKeys : UIntIdTable("annotation_keys") {
        // Case-folded key uniqueness is enforced by the partial unique index
        // uq_annotation_keys_key_active (active rows only; V17), so a soft-deleted key is
        // reusable. Exposed table defs are query-only (not DDL), so no `.uniqueIndex()` here.
        val key = varchar("key", length = MAX_ANNOTATION_KEY_LENGTH)

        // JSON array in TEXT (the labels/V10 precedent): the kind list is replaced whole on
        // every save — no child-table reconcile.
        val allowedKinds = text("allowed_kinds")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private val json = Json

    private fun active(): Op<Boolean> = AnnotationKeys.markedAsDeleted eq false

    private fun rowToResponse(id: UInt, key: String, kindsJson: String) = AnnotationKeyResponse(
        id = id,
        key = key,
        kinds = json.decodeFromString(kindsJson),
    )

    /** Every active key, ordered case-insensitively (id as deterministic tiebreaker). */
    suspend fun list(): List<AnnotationKeyResponse> = suspendTransaction(database) {
        AnnotationKeys.selectAll()
            .where { active() }
            .orderBy(AnnotationKeys.key.lowerCase() to SortOrder.ASC, AnnotationKeys.id to SortOrder.ASC)
            .map { rowToResponse(it[AnnotationKeys.id].value, it[AnnotationKeys.key], it[AnnotationKeys.allowedKinds]) }
            .toList()
    }

    suspend fun create(request: AnnotationKeyRequest): UInt = suspendTransaction(database) {
        validateAnnotationKeyRequest(request) // re-checked service-side so direct callers stay guarded
        if (AnnotationKeys.selectAll().where { active() }.count() >= MAX_ANNOTATION_KEYS) {
            throw BadRequestException("The annotation-key registry is full ($MAX_ANNOTATION_KEYS keys)")
        }
        val newRecord = AnnotationKeys.insert {
            it[key] = request.key
            it[allowedKinds] = json.encodeToString(request.kinds)
        }
        newRecord[AnnotationKeys.id].value
    }

    /**
     * Whole-row replace, key rename included — a stored file carrying the old key simply
     * goes strict-invalid on its next save (the no-grandfathering rule). Returns the
     * affected-row count (0 → the route's 404).
     */
    suspend fun update(id: UInt, request: AnnotationKeyRequest): Int = suspendTransaction(database) {
        validateAnnotationKeyRequest(request) // re-checked service-side so direct callers stay guarded
        AnnotationKeys.update({ (AnnotationKeys.id eq id) and (AnnotationKeys.markedAsDeleted eq false) }) {
            it[key] = request.key
            it[allowedKinds] = json.encodeToString(request.kinds)
        }
    }

    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        AnnotationKeys.update({ (AnnotationKeys.id eq id) and (AnnotationKeys.markedAsDeleted eq false) }) {
            it[markedAsDeleted] = true
        }
    }
}
