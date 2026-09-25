package ch.nokillswit.integration

import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.users.MAX_NAME_LENGTH
import ch.nokillswit.users.SERVICE_ACCOUNT_EMAIL_DOMAIN
import ch.nokillswit.users.UserService
import ch.nokillswit.users.discardedServiceAccountPasswordHash
import ch.nokillswit.users.insertServiceAccountInTransaction
import io.ktor.util.AttributeKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

const val INTEGRATION_API_KEY_PREFIX = "toadie_int_"
private val keyRandom = SecureRandom()

/** 256 bits of random entropy, URL-safe and returned once. */
internal fun generateApiKey(): String = INTEGRATION_API_KEY_PREFIX +
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(keyRandom::nextBytes))

val IntegrationClientServiceKey = AttributeKey<IntegrationClientService>("IntegrationClientService")

/** The authenticated machine identity behind an integration API key (see integration/Integration.kt). */
data class IntegrationClientPrincipal(
    val clientId: UInt,
    val name: String,
    val scope: IntegrationScope,
    val serviceUserId: UInt?,
)

/**
 * The `created_by` a `write`-scope client's entity writes are attributed to (the MCP write
 * tools, a later change). Every client carries one regardless of scope (see [IntegrationClientService.create]);
 * only `write` scope is expected to ever use it.
 */
fun IntegrationClientPrincipal.writerId(): UInt =
    checkNotNull(serviceUserId) { "integration client $clientId has no paired service account" }

enum class RevokeOutcome { REVOKED, NOT_FOUND, ALREADY_REVOKED }

/** [IntegrationClientService.revoke]'s result: the outcome plus the client's paired service
 *  account id (null for a pre-V42 row that never got one), so the route can audit it without a
 *  second read. */
data class RevokeResult(val outcome: RevokeOutcome, val serviceUserId: UInt?)

/** SHA-256 hex digest — the at-rest form of every API key. No bcrypt work factor needed:
 *  keys contain 256 bits of server-generated randomness, not human-chosen secrets. */
internal fun apiKeyHash(rawKey: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(rawKey.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Admin-managed technical identities for the read-only integration API (v2.7.0).
 * A client holds exactly one immutable key: create → shown once, revoke → terminal
 * (`revoked_at` is the removal — no update/delete; see migration V36). The paginated
 * registry retains revoked rows for administrators to inspect.
 */
class IntegrationClientService(val database: R2dbcDatabase) {
    object IntegrationClients : UIntIdTable("integration_clients") {
        val name = varchar("name", length = 100)
        val keyHash = varchar("key_hash", length = 64)
        val createdBy = reference("created_by", UserService.Users)
        val createdAt = long("created_at")
        val lastUsedAt = long("last_used_at").nullable()
        val revokedAt = long("revoked_at").nullable()
        val scope = varchar("scope", length = 10)
        val serviceUserId = reference("service_user_id", UserService.Users).nullable()
    }

    /** The result of [create]: the new client's id, its one-time plaintext key, and the
     *  paired service account (V41) every client owns regardless of scope. */
    data class IntegrationClientCreated(val id: UInt, val apiKey: String, val serviceUserId: UInt)

    /**
     * Inserts the client AND its paired service account in one transaction — three statements,
     * since the service account's email embeds the client's own id: the client row (inserted
     * as `read` with `service_user_id` NULL, the one state the V42 CHECK permits without a
     * service user), the service account (name = the client's own, email
     * `integration-client-<clientId>@toadie.invalid`), then ONE update setting the requested
     * scope and `service_user_id` together, so `write` never exists without its service user.
     * The service account's discarded password is hashed with bcrypt BEFORE the transaction
     * opens ([discardedServiceAccountPasswordHash]'s own contract) so the CPU-bound hash never
     * runs while this create holds a pooled connection.
     */
    suspend fun create(
        name: String,
        createdBy: UInt,
        scope: IntegrationScope = IntegrationScope.READ,
    ): IntegrationClientCreated {
        validateIntegrationClientName(name)
        val serviceAccountPasswordHash = discardedServiceAccountPasswordHash()
        return suspendTransaction(database) {
            val rawKey = generateApiKey()
            val record = IntegrationClients.insert {
                it[IntegrationClients.name] = name
                it[keyHash] = apiKeyHash(rawKey)
                it[IntegrationClients.createdBy] = createdBy
                it[createdAt] = System.currentTimeMillis()
                it[IntegrationClients.scope] = IntegrationScope.READ.name.lowercase()
            }
            val clientId = record[IntegrationClients.id].value
            val serviceUserId = insertServiceAccountInTransaction(
                name = name.take(MAX_NAME_LENGTH),
                email = "integration-client-$clientId@$SERVICE_ACCOUNT_EMAIL_DOMAIN",
                passwordHash = serviceAccountPasswordHash,
            )
            IntegrationClients.update({ IntegrationClients.id eq clientId }) {
                it[IntegrationClients.scope] = scope.name.lowercase()
                it[IntegrationClients.serviceUserId] = serviceUserId
            }
            IntegrationClientCreated(id = clientId, apiKey = rawKey, serviceUserId = serviceUserId)
        }
    }

    suspend fun read(id: UInt): IntegrationClientResponse? = suspendTransaction(database) {
        joined().where { IntegrationClients.id eq id }.map { it.toResponse() }.singleOrNull()
    }

    suspend fun list(paging: PageRequest): IntegrationClientListResponse = suspendTransaction(database) {
        val total = IntegrationClients.selectAll().count()
        val items = joined().applyPaging(paging, mapOf("id" to IntegrationClients.id))
            .map { it.toResponse() }.toList()
        paging.toPage(items, total)
    }

    suspend fun revoke(id: UInt): RevokeResult = suspendTransaction(database) {
        // Keep the whole row: a `map { it[revokedAt] }.singleOrNull()` would fold the
        // legitimate "exists with NULL revoked_at" case into "missing".
        val row = IntegrationClients.selectAll()
            .where { IntegrationClients.id eq id }
            .singleOrNull()
            ?: return@suspendTransaction RevokeResult(RevokeOutcome.NOT_FOUND, serviceUserId = null)
        val serviceUserId = row[IntegrationClients.serviceUserId]?.value
        if (row[IntegrationClients.revokedAt] != null) {
            return@suspendTransaction RevokeResult(RevokeOutcome.ALREADY_REVOKED, serviceUserId)
        }
        // Conditional on the flag so a concurrent revoke loses cleanly: the second caller's
        // update matches zero rows and answers 409 instead of double-stamping (checkup #30, A-L1).
        val updated = IntegrationClients.update({
            (IntegrationClients.id eq id) and IntegrationClients.revokedAt.isNull()
        }) {
            it[IntegrationClients.revokedAt] = System.currentTimeMillis()
        }
        if (updated == 0) return@suspendTransaction RevokeResult(RevokeOutcome.ALREADY_REVOKED, serviceUserId)
        // Soft-delete the paired service account (V41) in the SAME transaction. Pre-V42 rows
        // (migrated with no service_user_id) skip this — nothing to delete.
        serviceUserId?.let { accountId ->
            UserService.Users.update({ UserService.Users.id eq accountId }) {
                it[UserService.Users.markedAsDeleted] = true
            }
        }
        RevokeResult(RevokeOutcome.REVOKED, serviceUserId)
    }

    /** The bearer-provider lookup: non-revoked hash match → principal (stamping `last_used_at`), else null. */
    suspend fun authenticate(rawKey: String): IntegrationClientPrincipal? = suspendTransaction(database) {
        val row = IntegrationClients.selectAll()
            .where { (IntegrationClients.keyHash eq apiKeyHash(rawKey)) and IntegrationClients.revokedAt.isNull() }
            .singleOrNull()
            ?: return@suspendTransaction null
        val clientId = row[IntegrationClients.id].value
        // Conditional on non-revoked so a revoke committing between the select and this update
        // is never recorded as a post-revocation "use" (checkup #30, A-L2).
        val updated = IntegrationClients.update({
            (IntegrationClients.id eq clientId) and IntegrationClients.revokedAt.isNull()
        }) {
            it[lastUsedAt] = System.currentTimeMillis()
        }
        if (updated == 0) null else IntegrationClientPrincipal(
            clientId = clientId,
            name = row[IntegrationClients.name],
            scope = IntegrationScope.valueOf(row[IntegrationClients.scope].uppercase()),
            serviceUserId = row[IntegrationClients.serviceUserId]?.value,
        )
    }

    // Two FKs into Users (createdBy, serviceUserId) since V42 — the join must name the
    // creator's column explicitly, or Exposed's FK auto-detection is ambiguous.
    private fun joined() = IntegrationClients.innerJoin(
        UserService.Users,
        onColumn = { IntegrationClients.createdBy },
        otherColumn = { UserService.Users.id },
    ).select(
        IntegrationClients.id,
        IntegrationClients.name,
        IntegrationClients.createdAt,
        IntegrationClients.lastUsedAt,
        IntegrationClients.revokedAt,
        IntegrationClients.scope,
        UserService.Users.name,
    )

    private fun ResultRow.toResponse() = IntegrationClientResponse(
        id = this[IntegrationClients.id].value,
        name = this[IntegrationClients.name],
        createdAt = this[IntegrationClients.createdAt],
        createdByName = this[UserService.Users.name],
        lastUsedAt = this[IntegrationClients.lastUsedAt],
        revoked = this[IntegrationClients.revokedAt] != null,
        revokedAt = this[IntegrationClients.revokedAt],
        scope = IntegrationScope.valueOf(this[IntegrationClients.scope].uppercase()),
    )
}
