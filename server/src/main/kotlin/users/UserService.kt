package ch.nokillswit.users

import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val UserServiceKey = AttributeKey<UserService>("UserService")

data class UserListFilter(
    val name: String? = null,
    val email: String? = null,
    val role: UserRole? = null,
)

data class UserListResult(
    val items: List<UserResponse>,
    val total: Long,
)

// Deliberately no "role": the wire shape is a set, and a set has no order (the Lettuce rule).
private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to UserService.Users.id,
    "name" to UserService.Users.name,
    "email" to UserService.Users.email,
)

class UserService(val database: R2dbcDatabase) {
    object Users : UIntIdTable() {
        val name = varchar("name", length = 50)
        // Uniqueness is enforced by a partial unique index (active rows only) in migration V1,
        // so a soft-deleted user frees its email. Exposed table defs are query-only (not DDL),
        // so this column carries no `.uniqueIndex()`.
        val email = varchar("email", length = 254)
        val passwordHash = varchar("password_hash", length = 255)
        // Single-column role storage (CHECK in V1); UserRole.USER is the baseline.
        val role = varchar("role", length = 20).default(UserRole.USER.name)
        val markedAsDeleted = bool("marked_as_deleted").default(false)
        val passwordChangedAt = long("password_changed_at").default(0)
    }

    suspend fun create(user: User): UInt = suspendTransaction(database) {
        val newRecord = Users.insert {
            it[name] = user.name
            it[email] = user.email
            it[passwordHash] = user.passwordHash
            it[role] = user.role.name
        }
        newRecord[Users.id].value
    }

    suspend fun read(id: UInt): User? = suspendTransaction(database) {
        Users.selectAll()
            .where { (Users.id eq id) and active() }
            .toList()
            .singleOrNull()
            ?.toUser()
    }

    suspend fun findWithIdByEmail(email: String): Pair<UInt, User>? = suspendTransaction(database) {
        // Stored emails are canonical; folding the argument too is defense-in-depth so a
        // caller that skipped canonicalEmail still matches.
        Users.selectAll()
            .where { (Users.email eq canonicalEmail(email)) and active() }
            .toList()
            .singleOrNull()
            ?.let { it[Users.id].value to it.toUser() }
    }

    suspend fun updatePassword(id: UInt, passwordHash: String): Int = suspendTransaction(database) {
        Users.update({ (Users.id eq id) and active() }) {
            it[this.passwordHash] = passwordHash
            // Invalidates outstanding refresh tokens: /refresh rejects iat < passwordChangedAt.
            it[passwordChangedAt] = System.currentTimeMillis()
        }
    }

    suspend fun list(filter: UserListFilter, paging: PageRequest): UserListResult =
        suspendTransaction(database) {
            val predicate: Op<Boolean> = buildPredicate(filter) and active()
            val total = Users.selectAll().where { predicate }.count()
            val rows = Users.selectAll()
                .where { predicate }
                .applyPaging(paging, SORTABLE_COLUMNS)
                .map { it.toUser().toResponse(it[Users.id].value) }
                .toList()
            UserListResult(items = rows, total = total)
        }

    /** Updates the identity fields + role; password and passwordChangedAt stay untouched. */
    suspend fun update(id: UInt, name: String, email: String, role: UserRole): Int =
        suspendTransaction(database) {
            Users.update({ (Users.id eq id) and (Users.markedAsDeleted eq false) }) {
                it[Users.name] = name
                it[Users.email] = email
                it[Users.role] = role.name
            }
        }

    /** Soft delete: blocks login, refresh rejects `user_gone`, the V1 partial index frees the email. */
    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        Users.update({ (Users.id eq id) and (Users.markedAsDeleted eq false) }) {
            it[markedAsDeleted] = true
        }
    }

    /** Backs the last-admin protections (delete/demote of the final active ADMIN → 409). */
    suspend fun countActiveAdmins(): Long = suspendTransaction(database) {
        Users.selectAll().where { (Users.role eq UserRole.ADMIN.name) and active() }.count()
    }

    private fun buildPredicate(filter: UserListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.name?.takeIf { it.isNotBlank() }?.let { op = op and (Users.name.containsNormalized(it)) }
        filter.email?.takeIf { it.isNotBlank() }?.let { op = op and (Users.email.containsNormalized(it)) }
        filter.role?.let { op = op and (Users.role eq it.name) }
        return op
    }

    /** Bootstrap: rotate a user's password only while they still carry [expectedHash]. */
    suspend fun rotatePasswordIfHashMatches(email: String, expectedHash: String, newHash: String): Int =
        suspendTransaction(database) {
            Users.update({ (Users.email eq email) and (Users.passwordHash eq expectedHash) and active() }) {
                it[passwordHash] = newHash
                it[passwordChangedAt] = System.currentTimeMillis()
            }
        }

    /** Bootstrap: how many active accounts still carry [hash] (the well-known seed password). */
    suspend fun countActiveWithPasswordHash(hash: String): Long = suspendTransaction(database) {
        Users.selectAll().where { (Users.passwordHash eq hash) and active() }.count()
    }

    private fun active(): Op<Boolean> = Users.markedAsDeleted eq false

    private fun ResultRow.toUser(): User = User(
        name = this[Users.name],
        email = this[Users.email],
        passwordHash = this[Users.passwordHash],
        role = UserRole.valueOf(this[Users.role]),
        passwordChangedAt = this[Users.passwordChangedAt],
    )
}
