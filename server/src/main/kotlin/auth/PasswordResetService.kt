package ch.nokillswit.auth

import ch.nokillswit.users.UserService.Users
import ch.nokillswit.users.updatePasswordInTransaction
import io.ktor.util.AttributeKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val PasswordResetServiceKey = AttributeKey<PasswordResetService>("PasswordResetService")

private const val RESET_TOKEN_BYTES = 32
private const val DEFAULT_RESET_TTL_MILLIS = 900_000L
private val resetRandom = SecureRandom()
private val RESET_TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{43}")

/** A reset grant cannot authorize anything except replacing one account's password. */
class PasswordResetService(
    private val database: R2dbcDatabase,
    private val ttlMillis: Long = DEFAULT_RESET_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { require(ttlMillis in 1..3_600_000L) { "Password reset TTL must be positive and at most one hour" } }

    object Tokens : Table("password_reset_tokens") {
        val tokenHash = varchar("token_hash", 64)
        val userId = reference("user_id", Users)
        val authVersion = long("auth_version")
        val expiresAt = long("expires_at")
        override val primaryKey = PrimaryKey(tokenHash)
    }

    suspend fun issue(userId: UInt, authVersion: Long): String? {
        // Separate cleanup transaction preserves user-before-token lock ordering.
        suspendTransaction(database) { Tokens.deleteWhere { expiresAt lessEq clock() } }
        val bytes = ByteArray(RESET_TOKEN_BYTES).also(resetRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return suspendTransaction(database) {
            if (!lockUser(userId, authVersion)) return@suspendTransaction null
            Tokens.insert {
                it[tokenHash] = digest(token)
                it[Tokens.userId] = userId
                it[Tokens.authVersion] = authVersion
                it[expiresAt] = clock() + ttlMillis
            }
            token
        }
    }

    /** Cheap precheck before bcrypt; complete rechecks everything inside its transaction. */
    suspend fun isUsable(token: String): Boolean {
        if (!RESET_TOKEN_PATTERN.matches(token)) return false
        return suspendTransaction(database) {
            (Tokens innerJoin Users).select(Tokens.tokenHash).where {
                (Tokens.tokenHash eq digest(token)) and (Tokens.expiresAt greater clock()) and
                    (Users.markedAsDeleted eq false) and (Tokens.authVersion eq Users.authVersion)
            }.count() == 1L
        }
    }

    /** Consumption, password replacement, and revocation are one transaction, across instances. */
    suspend fun complete(token: String, passwordHash: String): UInt? {
        if (!RESET_TOKEN_PATTERN.matches(token)) return null
        val hash = digest(token)
        return suspendTransaction(database) {
            val grant = Tokens.selectAll().where { Tokens.tokenHash eq hash }.toList().singleOrNull()
                ?: return@suspendTransaction null
            val userId = grant[Tokens.userId].value
            val version = grant[Tokens.authVersion]
            if (!lockUser(userId, version)) return@suspendTransaction null
            // A competing request or delivery-failure cleanup may have removed the row.
            if (Tokens.deleteWhere { (tokenHash eq hash) and (expiresAt greater clock()) } != 1) {
                return@suspendTransaction null
            }
            check(updatePasswordInTransaction(userId, passwordHash, version, clock()) == 1)
            Tokens.deleteWhere { Tokens.userId eq userId }
            userId
        }
    }

    suspend fun revoke(token: String) {
        suspendTransaction(database) { Tokens.deleteWhere { tokenHash eq digest(token) } }
    }

    private suspend fun lockUser(userId: UInt, version: Long): Boolean =
        Users.select(Users.id).where {
            (Users.id eq userId) and (Users.markedAsDeleted eq false) and (Users.authVersion eq version)
        }.forUpdate().toList().isNotEmpty()

    private fun digest(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
}
