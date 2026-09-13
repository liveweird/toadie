package ch.nokillswit.entityquery

import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val SavedEntityQueryServiceKey = AttributeKey<SavedEntityQueryService>("SavedEntityQueryService")

/**
 * A mutation's authorization verdict, decided inside the mutation's own transaction — the
 * `lenses/LensService.kt` [ch.nokillswit.lenses.LensMutationResult] shape verbatim: an absent,
 * soft-deleted, or foreign PRIVATE saved query is uniformly [NOT_FOUND] (a private query's
 * existence is itself the secret), while a foreign PUBLIC one — visible in everyone's list
 * anyway — gets the honest [FORBIDDEN_PUBLIC] → 403.
 */
enum class SavedEntityQueryMutationResult { OK, NOT_FOUND, FORBIDDEN_PUBLIC }

class SavedEntityQueryService(private val database: R2dbcDatabase) {
    object EntityQueries : UIntIdTable("entity_queries") {
        // Per-owner case-folded name uniqueness is enforced by the partial unique index
        // uq_entity_queries_owner_name_active (active rows only; V35), so a soft-deleted row
        // frees its name. Exposed table defs are query-only (not DDL), so no `.uniqueIndex()`.
        val name = varchar("name", length = MAX_SAVED_ENTITY_QUERY_NAME_LENGTH)
        val visibility = varchar("visibility", length = 10)
        val query = text("query")
        val createdBy = reference("created_by", UserService.Users)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private fun active(): Op<Boolean> = EntityQueries.markedAsDeleted eq false

    // The sanctioned cross-feature table read (see persistence.md): the creator's display
    // fields must come from the same transaction as the saved-query rows, so the users table
    // is joined directly instead of calling UserService (which would open a second transaction).
    private fun joined() = EntityQueries.join(
        UserService.Users,
        JoinType.INNER,
        onColumn = EntityQueries.createdBy,
        otherColumn = UserService.Users.id,
    )

    private fun ResultRow.toResponse() = SavedEntityQuery(
        id = this[EntityQueries.id].value,
        name = this[EntityQueries.name],
        visibility = SavedEntityQueryVisibility.valueOf(this[EntityQueries.visibility]),
        query = this[EntityQueries.query],
        createdBy = this[EntityQueries.createdBy].value,
        creatorName = this[UserService.Users.name],
        creatorDeleted = this[UserService.Users.markedAsDeleted],
        createdAt = this[EntityQueries.createdAt],
        updatedAt = this[EntityQueries.updatedAt],
    )

    /**
     * Every saved query the caller may see: their own (both visibilities) plus everyone's
     * PUBLIC ones — name-ordered case-insensitively (id as deterministic tiebreaker), unpaged
     * (the labels/lenses posture: a personal-plus-curated scale).
     */
    suspend fun list(callerId: UInt): List<SavedEntityQuery> = suspendTransaction(database) {
        joined().selectAll()
            .where {
                active() and
                    (
                        (EntityQueries.visibility eq SavedEntityQueryVisibility.PUBLIC.name) or
                            (EntityQueries.createdBy eq callerId)
                        )
            }
            .orderBy(EntityQueries.name.lowerCase() to SortOrder.ASC, EntityQueries.id to SortOrder.ASC)
            .map { it.toResponse() }
            .toList()
    }

    suspend fun create(request: SavedEntityQueryRequest, callerId: UInt): SavedEntityQuery =
        suspendTransaction(database) {
            // Re-checked service-side so direct callers stay guarded.
            validateSavedEntityQueryRequest(request)
            val now = System.currentTimeMillis()
            val id = EntityQueries.insert {
                it[name] = request.name
                it[visibility] = request.visibility.name
                it[query] = request.query
                it[createdBy] = callerId
                it[createdAt] = now
                it[updatedAt] = now
            }[EntityQueries.id].value
            joined().selectAll().where { EntityQueries.id eq id }.map { it.toResponse() }.singleOrNull()
                ?: error("saved entity query $id vanished between insert and read-back")
        }

    /**
     * Whole-row replace (name, visibility, AND query text — overwrite, rename, and the
     * visibility flip are all this one PUT). Creator-only; see [SavedEntityQueryMutationResult]
     * for the disclosure split.
     */
    suspend fun update(id: UInt, request: SavedEntityQueryRequest, callerId: UInt): SavedEntityQueryMutationResult =
        suspendTransaction(database) {
            // Verdict BEFORE validation — 403/404 wins over 400 (the convention everywhere);
            // the route deliberately does NOT pre-validate the PUT for the same reason.
            val verdict = mutationVerdict(id, callerId)
            if (verdict != SavedEntityQueryMutationResult.OK) return@suspendTransaction verdict
            validateSavedEntityQueryRequest(request)
            EntityQueries.update({ (EntityQueries.id eq id) and active() }) {
                it[name] = request.name
                it[visibility] = request.visibility.name
                it[query] = request.query
                it[updatedAt] = System.currentTimeMillis()
            }
            SavedEntityQueryMutationResult.OK
        }

    suspend fun delete(id: UInt, callerId: UInt): SavedEntityQueryMutationResult = suspendTransaction(database) {
        val verdict = mutationVerdict(id, callerId)
        if (verdict != SavedEntityQueryMutationResult.OK) return@suspendTransaction verdict
        EntityQueries.update({ (EntityQueries.id eq id) and active() }) { it[markedAsDeleted] = true }
        SavedEntityQueryMutationResult.OK
    }

    /** One read deciding the mutation's fate — see [SavedEntityQueryMutationResult] for the split. */
    private suspend fun mutationVerdict(id: UInt, callerId: UInt): SavedEntityQueryMutationResult {
        val row = EntityQueries.selectAll()
            .where { (EntityQueries.id eq id) and active() }
            .map { it[EntityQueries.createdBy].value to it[EntityQueries.visibility] }
            .singleOrNull()
        return when {
            row == null -> SavedEntityQueryMutationResult.NOT_FOUND
            row.first == callerId -> SavedEntityQueryMutationResult.OK
            row.second == SavedEntityQueryVisibility.PUBLIC.name -> SavedEntityQueryMutationResult.FORBIDDEN_PUBLIC
            else -> SavedEntityQueryMutationResult.NOT_FOUND
        }
    }
}
