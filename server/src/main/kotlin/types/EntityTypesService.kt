package ch.nokillswit.types

import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val EntityTypesServiceKey = AttributeKey<EntityTypesService>("EntityTypesService")

class EntityTypesService(private val database: R2dbcDatabase) {
    object EntityTypes : UIntIdTable("entity_types") {
        // Kind uniqueness is enforced by the partial unique index uq_entity_types_kind_active
        // (active rows only; V14 — no LOWER(): kinds are stored canonical), so a soft-deleted
        // dictionary frees its kind. Exposed table defs are query-only (not DDL).
        val kind = varchar("kind", length = 63)

        // JSON array in TEXT (the labels/V10 precedent): the list is replaced whole on every
        // save — no child-table reconcile. The dictionaries are INDEPENDENT (no cross-row
        // uniqueness — unlike tags' one-category-per-tag rule).
        val types = text("types")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private val json = Json

    private fun active(): Op<Boolean> = EntityTypes.markedAsDeleted eq false

    private fun rowToResponse(id: UInt, kind: String, typesJson: String) = EntityTypesResponse(
        id = id,
        kind = kind,
        types = json.decodeFromString(typesJson),
    )

    /** Every active dictionary, kind-ordered alphabetically (id as deterministic tiebreaker). */
    suspend fun list(): List<EntityTypesResponse> = suspendTransaction(database) {
        EntityTypes.selectAll()
            .where { active() }
            .orderBy(EntityTypes.kind to SortOrder.ASC, EntityTypes.id to SortOrder.ASC)
            .map { rowToResponse(it[EntityTypes.id].value, it[EntityTypes.kind], it[EntityTypes.types]) }
            .toList()
    }

    suspend fun read(id: UInt): EntityTypesResponse? = suspendTransaction(database) {
        EntityTypes.selectAll()
            .where { (EntityTypes.id eq id) and active() }
            .map { rowToResponse(it[EntityTypes.id].value, it[EntityTypes.kind], it[EntityTypes.types]) }
            .singleOrNull()
    }

    /**
     * A kind already holding an active dictionary raises the partial unique index's 23505 →
     * the central 409 (there are at most six rows — no service-side pre-check needed).
     */
    suspend fun create(request: EntityTypesRequest): UInt = suspendTransaction(database) {
        validateEntityTypesRequest(request) // re-checked service-side so direct callers stay guarded
        val newRecord = EntityTypes.insert {
            it[kind] = request.kind
            it[types] = json.encodeToString(request.types)
        }
        newRecord[EntityTypes.id].value
    }

    /**
     * Whole-dictionary replace, kind change included (moving the list to another kind clashes
     * with that kind's active row → 409) — a stored file carrying a removed type simply goes
     * strict-invalid on its next save (the no-grandfathering rule). Returns the affected-row
     * count (0 → the route's 404).
     */
    suspend fun update(id: UInt, request: EntityTypesRequest): Int = suspendTransaction(database) {
        validateEntityTypesRequest(request) // re-checked service-side so direct callers stay guarded
        EntityTypes.update({ (EntityTypes.id eq id) and (EntityTypes.markedAsDeleted eq false) }) {
            it[kind] = request.kind
            it[types] = json.encodeToString(request.types)
        }
    }

    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        EntityTypes.update({ (EntityTypes.id eq id) and (EntityTypes.markedAsDeleted eq false) }) {
            it[markedAsDeleted] = true
        }
    }
}
