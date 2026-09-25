package ch.nokillswit.integration

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/integration-clients")
class IntegrationClients {
    @Serializable
    @Resource("{id}")
    class Id(val parent: IntegrationClients = IntegrationClients(), val id: UInt) {
        @Serializable
        @Resource("revoke")
        class Revoke(val parent: Id)
    }
}

/**
 * Admin management of the integration API's technical clients (v2.7.0) — ADMIN-only
 * INCLUDING reads. Keys are
 * immutable: no PUT/DELETE — POST {id}/revoke is the terminal disable. Deliberately NOT
 * gated by `integration.enabled`, so an admin can prepare keys before enabling the endpoint.
 */
fun Application.configureIntegrationClientRoutes() {
    val clientService = attributes[IntegrationClientServiceKey]

    routing {
        authenticate {
            get<IntegrationClients> {
                requireAdmin(call.caller())
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(HttpStatusCode.OK, clientService.list(call.parsePaging(setOf("id"))))
            }
            post<IntegrationClients> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = call.receive<IntegrationClientRequest>()
                    .let { it.copy(name = sanitizeSingleLine(it.name, "Client name")) }
                validateIntegrationClientName(request.name)
                val created = clientService.create(request.name, caller.userId, request.scope)
                val client = checkNotNull(clientService.read(created.id)) { "just-created client ${created.id} must exist" }
                audit(
                    "integration_client.created",
                    "byUserId" to caller.userId.toLong(),
                    "clientId" to created.id.toLong(),
                    "name" to request.name,
                    "scope" to request.scope.name.lowercase(),
                    "serviceUserId" to created.serviceUserId.toLong(),
                )
                call.response.header(HttpHeaders.Location, call.application.href(IntegrationClients.Id(id = created.id)))
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(
                    HttpStatusCode.Created,
                    IntegrationClientCreateResponse(client = client, apiKey = created.apiKey),
                )
            }
            get<IntegrationClients.Id> { route ->
                requireAdmin(call.caller())
                val client = clientService.read(route.id)
                    ?: throw NotFoundException("Integration client not found")
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(HttpStatusCode.OK, client)
            }
            post<IntegrationClients.Id.Revoke> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val result = clientService.revoke(route.parent.id)
                when (result.outcome) {
                    RevokeOutcome.NOT_FOUND -> throw NotFoundException("Integration client not found")
                    RevokeOutcome.ALREADY_REVOKED -> throw ConflictException("Client is already revoked")
                    RevokeOutcome.REVOKED -> {
                        audit(
                            "integration_client.revoked",
                            "byUserId" to caller.userId.toLong(),
                            "clientId" to route.parent.id.toLong(),
                            "serviceUserId" to result.serviceUserId?.toLong(),
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}
