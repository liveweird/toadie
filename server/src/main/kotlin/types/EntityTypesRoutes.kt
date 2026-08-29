package ch.nokillswit.types

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/entity-types")
class EntityTypesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: EntityTypesRoute = EntityTypesRoute(), val id: UInt)
}

fun Application.configureEntityTypesRoutes() {
    val entityTypesService = attributes[EntityTypesServiceKey]

    routing {
        authenticate {
            // The read is any-authenticated: the registry is the editor's type-picker source
            // and the Types page's read-only list. Every mutation is ADMIN-only, with the
            // guard BEFORE the id lookup (a non-admin probe gets a uniform 403 whether or
            // not the id exists — the guard-before-read idiom).
            get<EntityTypesRoute> {
                call.caller()
                call.respond(HttpStatusCode.OK, EntityTypesList(items = entityTypesService.list()))
            }
            post<EntityTypesRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedEntityTypesRequest(call.receive())
                validateEntityTypesRequest(request)
                val id = entityTypesService.create(request)
                audit(
                    "entity_types.created",
                    "byUserId" to caller.userId.toLong(),
                    "entityTypesId" to id.toLong(),
                    "kind" to request.kind,
                    "types" to request.types.size,
                )
                val created = EntityTypesResponse(id = id, kind = request.kind, types = request.types)
                call.response.header(HttpHeaders.Location, call.application.href(EntityTypesRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, created)
            }
            put<EntityTypesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedEntityTypesRequest(call.receive())
                validateEntityTypesRequest(request)
                entityTypesService.update(route.id, request).orNotFound("Entity-type dictionary")
                audit(
                    "entity_types.updated",
                    "byUserId" to caller.userId.toLong(),
                    "entityTypesId" to route.id.toLong(),
                    "kind" to request.kind,
                    "types" to request.types.size,
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<EntityTypesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                entityTypesService.delete(route.id).orNotFound("Entity-type dictionary")
                audit(
                    "entity_types.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "entityTypesId" to route.id.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
