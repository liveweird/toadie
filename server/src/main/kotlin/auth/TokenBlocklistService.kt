package ch.nokillswit.auth

import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val TokenBlocklistServiceKey = AttributeKey<TokenBlocklistService>("TokenBlocklistService")

class TokenBlocklistService(private val database: R2dbcDatabase) {
    object RevokedTokens : Table("revoked_tokens") {
        val jti = varchar("jti", 36)
        val expiresAt = long("expires_at")
        override val primaryKey = PrimaryKey(jti)
    }

    suspend fun revoke(jti: String, expiresAtEpochMillis: Long) {
        suspendTransaction(database) {
            RevokedTokens.deleteWhere { expiresAt less System.currentTimeMillis() }
            RevokedTokens.insertIgnore {
                it[RevokedTokens.jti] = jti
                it[RevokedTokens.expiresAt] = expiresAtEpochMillis
            }
        }
    }

    suspend fun isRevoked(jti: String): Boolean = suspendTransaction(database) {
        RevokedTokens.selectAll()
            .where { RevokedTokens.jti eq jti }
            .count() > 0
    }
}
