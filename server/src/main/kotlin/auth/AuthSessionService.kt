package ch.nokillswit.auth

import ch.nokillswit.users.UserService.Users
import com.auth0.jwt.interfaces.Payload
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val AuthSessionServiceKey = AttributeKey<AuthSessionService>("AuthSessionService")

/** Reject missing/out-of-range identity instead of wrapping a signed BIGINT into UInt. */
internal fun Payload.sessionUserId(): UInt? = getClaim("userId").asLong()
    ?.takeIf { it in 0..UInt.MAX_VALUE.toLong() }?.toUInt()

/**
 * One database row per login, shared by every access/refresh pair in its sliding session.
 * No cached acceptance: deletion, logout, and an account's auth-version bump are visible
 * on the next authentication check, even on another instance. Already-running requests
 * may finish; revocation is not cancellation of a previously authorized transaction.
 */
class AuthSessionService(
    private val database: R2dbcDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    object Sessions : Table("auth_sessions") {
        val id = varchar("id", 36)
        val userId = reference("user_id", Users)
        val authVersion = long("auth_version")
        val expiresAt = long("expires_at")
        override val primaryKey = PrimaryKey(id)
    }

    suspend fun create(id: String, userId: UInt, authVersion: Long, expiresAt: Long): Boolean {
        // Separate transaction: cleanup never holds another session's lock while waiting
        // for this user's lock (issuance and renewal always lock user before session).
        suspendTransaction(database) { Sessions.deleteWhere { Sessions.expiresAt lessEq clock() } }
        return suspendTransaction(database) {
            if (!lockCurrentUser(userId, authVersion)) return@suspendTransaction false
            Sessions.insert {
                it[Sessions.id] = id
                it[Sessions.userId] = userId
                it[Sessions.authVersion] = authVersion
                it[Sessions.expiresAt] = expiresAt
            }
            true
        }
    }

    suspend fun renew(id: String, userId: UInt, authVersion: Long, expiresAt: Long): Boolean =
        suspendTransaction(database) {
            if (!lockCurrentUser(userId, authVersion)) return@suspendTransaction false
            val currentExpiry = Sessions.select(Sessions.expiresAt).where {
                (Sessions.id eq id) and (Sessions.userId eq userId)
            }.toList().singleOrNull()?.get(Sessions.expiresAt) ?: return@suspendTransaction false
            // UPDATE, never upsert: a concurrent logout cannot resurrect a deleted family.
            Sessions.update({
                (Sessions.id eq id) and (Sessions.userId eq userId) and
                    (Sessions.authVersion eq authVersion) and (Sessions.expiresAt greater clock())
            }) { it[Sessions.expiresAt] = maxOf(currentExpiry, expiresAt) } == 1
        }

    suspend fun isActive(id: String, userId: UInt): Boolean = suspendTransaction(database) {
        (Sessions innerJoin Users).select(Sessions.id).where {
            (Sessions.id eq id) and (Sessions.userId eq userId) and
                (Sessions.authVersion eq Users.authVersion) and
                (Users.markedAsDeleted eq false) and (Sessions.expiresAt greater clock())
        }.count() == 1L
    }

    suspend fun revoke(id: String, userId: UInt) {
        suspendTransaction(database) {
            Sessions.deleteWhere { (Sessions.id eq id) and (Sessions.userId eq userId) }
        }
    }

    private suspend fun lockCurrentUser(userId: UInt, authVersion: Long): Boolean =
        Users.select(Users.id).where {
            (Users.id eq userId) and (Users.markedAsDeleted eq false) and (Users.authVersion eq authVersion)
        }.forUpdate().toList().isNotEmpty()
}
