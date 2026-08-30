package ch.nokillswit.lenses

import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
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

val LensServiceKey = AttributeKey<LensService>("LensService")

/**
 * A mutation's authorization verdict, decided inside the mutation's own transaction. The
 * split backs the deliberate hybrid disclosure policy (see `.claude/docs/authorization.md`):
 * an absent, soft-deleted, or FOREIGN PRIVATE lens is uniformly [NOT_FOUND] (a private
 * lens's existence is itself the secret), while a foreign PUBLIC lens — visible in
 * everyone's list anyway — gets the honest [FORBIDDEN_PUBLIC] → 403.
 */
enum class LensMutationResult { OK, NOT_FOUND, FORBIDDEN_PUBLIC }

class LensService(private val database: R2dbcDatabase) {
    object Lenses : UIntIdTable("lenses") {
        // Per-owner case-folded name uniqueness is enforced by the partial unique index
        // uq_lenses_owner_name_active (active rows only; V20), so a soft-deleted lens frees
        // its name. Exposed table defs are query-only (not DDL), so no `.uniqueIndex()` here.
        val name = varchar("name", length = MAX_LENS_NAME_LENGTH)
        val visibility = varchar("visibility", length = 10)
        // The nine shared filter slots as one JSON object in TEXT (the catalog_files.content
        // precedent) — replaced whole on every save.
        val filters = text("filters")
        val createdBy = reference("created_by", UserService.Users)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private val json = Json

    private fun active(): Op<Boolean> = Lenses.markedAsDeleted eq false

    // The sanctioned cross-feature table read (see persistence.md): the creator's display
    // fields must come from the same transaction as the lens rows, so the users table is
    // joined directly instead of calling UserService (which would open a second transaction).
    private fun joined() = Lenses.join(
        UserService.Users,
        JoinType.INNER,
        onColumn = Lenses.createdBy,
        otherColumn = UserService.Users.id,
    )

    private fun ResultRow.toResponse() = LensResponse(
        id = this[Lenses.id].value,
        name = this[Lenses.name],
        visibility = LensVisibility.valueOf(this[Lenses.visibility]),
        filters = json.decodeFromString(this[Lenses.filters]),
        createdBy = this[Lenses.createdBy].value,
        creatorName = this[UserService.Users.name],
        creatorDeleted = this[UserService.Users.markedAsDeleted],
        createdAt = this[Lenses.createdAt],
        updatedAt = this[Lenses.updatedAt],
    )

    /**
     * Every lens the caller may see: their own (both visibilities) plus everyone's PUBLIC
     * ones — name-ordered case-insensitively (id as deterministic tiebreaker), unpaged (the
     * labels posture: a personal-plus-curated scale).
     */
    suspend fun list(callerId: UInt): List<LensResponse> = suspendTransaction(database) {
        joined().selectAll()
            .where {
                active() and
                    ((Lenses.visibility eq LensVisibility.PUBLIC.name) or (Lenses.createdBy eq callerId))
            }
            .orderBy(Lenses.name.lowerCase() to SortOrder.ASC, Lenses.id to SortOrder.ASC)
            .map { it.toResponse() }
            .toList()
    }

    suspend fun create(request: LensRequest, callerId: UInt): LensResponse = suspendTransaction(database) {
        validateLensRequest(request) // re-checked service-side so direct callers stay guarded
        val now = System.currentTimeMillis()
        val id = Lenses.insert {
            it[name] = request.name
            it[visibility] = request.visibility.name
            it[filters] = json.encodeToString(request.filters)
            it[createdBy] = callerId
            it[createdAt] = now
            it[updatedAt] = now
        }[Lenses.id].value
        joined().selectAll().where { Lenses.id eq id }.map { it.toResponse() }.singleOrNull()
            ?: error("lens $id vanished between insert and read-back")
    }

    /**
     * Whole-lens replace (name, visibility, AND filter payload — overwrite, rename, and the
     * visibility flip are all this one PUT). Creator-only; see [LensMutationResult] for the
     * disclosure split.
     */
    suspend fun update(id: UInt, request: LensRequest, callerId: UInt): LensMutationResult =
        suspendTransaction(database) {
            // Verdict BEFORE validation — 403/404 wins over 400 (the convention everywhere);
            // the route deliberately does NOT pre-validate the PUT for the same reason.
            val verdict = mutationVerdict(id, callerId)
            if (verdict != LensMutationResult.OK) return@suspendTransaction verdict
            validateLensRequest(request)
            Lenses.update({ (Lenses.id eq id) and active() }) {
                it[name] = request.name
                it[visibility] = request.visibility.name
                it[filters] = json.encodeToString(request.filters)
                it[updatedAt] = System.currentTimeMillis()
            }
            LensMutationResult.OK
        }

    suspend fun delete(id: UInt, callerId: UInt): LensMutationResult = suspendTransaction(database) {
        val verdict = mutationVerdict(id, callerId)
        if (verdict != LensMutationResult.OK) return@suspendTransaction verdict
        Lenses.update({ (Lenses.id eq id) and active() }) { it[markedAsDeleted] = true }
        LensMutationResult.OK
    }

    /** One read deciding the mutation's fate — see [LensMutationResult] for the split. */
    private suspend fun mutationVerdict(id: UInt, callerId: UInt): LensMutationResult {
        val row = Lenses.selectAll()
            .where { (Lenses.id eq id) and active() }
            .map { it[Lenses.createdBy].value to it[Lenses.visibility] }
            .singleOrNull()
        return when {
            row == null -> LensMutationResult.NOT_FOUND
            row.first == callerId -> LensMutationResult.OK
            row.second == LensVisibility.PUBLIC.name -> LensMutationResult.FORBIDDEN_PUBLIC
            else -> LensMutationResult.NOT_FOUND
        }
    }
}
