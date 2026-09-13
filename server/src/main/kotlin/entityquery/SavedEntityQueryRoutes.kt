package ch.nokillswit.entityquery

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
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
@Resource("/api/v1/entity-queries")
class EntityQueriesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: EntityQueriesRoute = EntityQueriesRoute(), val id: UInt)
}

/**
 * Maps the service's mutation verdict to the deliberate hybrid disclosure policy (see
 * `.claude/docs/authorization.md`): foreign-PRIVATE and unknown ids are uniformly 404,
 * foreign-PUBLIC is the honest 403 (audited `authz.denied` centrally by throwing).
 */
private fun SavedEntityQueryMutationResult.orThrow() {
    when (this) {
        SavedEntityQueryMutationResult.OK -> Unit
        SavedEntityQueryMutationResult.NOT_FOUND -> throw NotFoundException("Saved entity query not found")
        SavedEntityQueryMutationResult.FORBIDDEN_PUBLIC ->
            throw ForbiddenException("Only the creator may modify a public saved entity query")
    }
}

fun Application.configureSavedEntityQueryRoutes() {
    val entityQueryService = attributes[SavedEntityQueryServiceKey]

    routing {
        authenticate {
            // Every route is any-authenticated: saved queries are per-user content, and the
            // ownership/visibility rules live in the service verdict — there is no admin gate
            // anywhere (ADMIN gets no special content access, the standing rule).
            get<EntityQueriesRoute> {
                val caller = call.caller()
                call.respond(
                    HttpStatusCode.OK,
                    SavedEntityQueryList(items = entityQueryService.list(caller.userId)),
                )
            }
            post<EntityQueriesRoute> {
                val caller = call.caller()
                val request = sanitizedSavedEntityQueryRequest(call.receive())
                validateSavedEntityQueryRequest(request)
                val created = entityQueryService.create(request, caller.userId)
                audit(
                    "entity_query.created",
                    "byUserId" to caller.userId.toLong(),
                    "entityQueryId" to created.id.toLong(),
                    "name" to created.name,
                    "visibility" to created.visibility.name,
                )
                call.response.header(
                    HttpHeaders.Location,
                    call.application.href(EntityQueriesRoute.Id(id = created.id)),
                )
                call.respond(HttpStatusCode.Created, created)
            }
            put<EntityQueriesRoute.Id> { route ->
                val caller = call.caller()
                // No route-side validateSavedEntityQueryRequest here: the service checks the
                // ownership verdict FIRST so 403/404 wins over 400 (the password-PUT precedent,
                // the lens idiom), then validates inside the same transaction.
                val request = sanitizedSavedEntityQueryRequest(call.receive())
                entityQueryService.update(route.id, request, caller.userId).orThrow()
                audit(
                    "entity_query.updated",
                    "byUserId" to caller.userId.toLong(),
                    "entityQueryId" to route.id.toLong(),
                    "name" to request.name,
                    "visibility" to request.visibility.name,
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<EntityQueriesRoute.Id> { route ->
                val caller = call.caller()
                entityQueryService.delete(route.id, caller.userId).orThrow()
                audit(
                    "entity_query.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "entityQueryId" to route.id.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
