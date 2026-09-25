package ch.nokillswit.integration

import ch.nokillswit.infra.paging.PageResponse
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A key's scope (2.15.0): `read` grants the GraphQL API and the MCP read tools; `write`
 * additionally grants the MCP entity write tools. Immutable once created — rotate by creating
 * a new client. Every client, regardless of scope, is paired with its own service account
 * (V41) so an entity write made through the MCP endpoint carries an ordinary `created_by`.
 */
@Serializable
enum class IntegrationScope {
    @SerialName("read") READ,
    @SerialName("write") WRITE,
}

@Serializable
data class IntegrationClientRequest(
    val name: String,
    val scope: IntegrationScope = IntegrationScope.READ,
)

@Serializable
data class IntegrationClientResponse(
    val id: UInt,
    val name: String,
    val createdAt: Long,
    val createdByName: String,
    val lastUsedAt: Long? = null,
    val revoked: Boolean,
    val revokedAt: Long? = null,
    val scope: IntegrationScope,
)

/** The one-time create response: only the key's SHA-256 digest is stored. */
@Serializable
data class IntegrationClientCreateResponse(
    val client: IntegrationClientResponse,
    val apiKey: String,
)

typealias IntegrationClientListResponse = PageResponse<IntegrationClientResponse>

// Column limit (integration_clients.name varchar(100)) enforced up-front: 400 instead of a
// DB-level 500. The route checks it for the
// 403-before-400 ordering, and the service re-checks so direct service callers stay guarded.
internal const val MAX_INTEGRATION_CLIENT_NAME_LENGTH = 100

internal fun validateIntegrationClientName(name: String) {
    if (name.isBlank()) throw BadRequestException("Client name must not be blank")
    if (name.length > MAX_INTEGRATION_CLIENT_NAME_LENGTH) {
        throw BadRequestException("Client name must be at most $MAX_INTEGRATION_CLIENT_NAME_LENGTH characters")
    }
}
