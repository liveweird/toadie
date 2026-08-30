package ch.nokillswit.lenses

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
@Resource("/api/v1/lenses")
class LensesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: LensesRoute = LensesRoute(), val id: UInt)
}

/**
 * Maps the service's mutation verdict to the deliberate hybrid disclosure policy (see
 * `.claude/docs/authorization.md`): foreign-PRIVATE and unknown ids are uniformly 404,
 * foreign-PUBLIC is the honest 403 (audited `authz.denied` centrally by throwing).
 */
private fun LensMutationResult.orThrow() {
    when (this) {
        LensMutationResult.OK -> Unit
        LensMutationResult.NOT_FOUND -> throw NotFoundException("Lens not found")
        LensMutationResult.FORBIDDEN_PUBLIC ->
            throw ForbiddenException("Only the creator may modify a public lens")
    }
}

fun Application.configureLensRoutes() {
    val lensService = attributes[LensServiceKey]

    routing {
        authenticate {
            // Every route is any-authenticated: lenses are per-user content, and the
            // ownership/visibility rules live in the service verdict — there is no admin
            // gate anywhere (ADMIN gets no special content access, the standing rule).
            get<LensesRoute> {
                val caller = call.caller()
                call.respond(HttpStatusCode.OK, LensList(items = lensService.list(caller.userId)))
            }
            post<LensesRoute> {
                val caller = call.caller()
                val request = sanitizedLensRequest(call.receive())
                validateLensRequest(request)
                val created = lensService.create(request, caller.userId)
                audit(
                    "lens.created",
                    "byUserId" to caller.userId.toLong(),
                    "lensId" to created.id.toLong(),
                    "name" to created.name,
                    "visibility" to created.visibility.name,
                )
                call.response.header(HttpHeaders.Location, call.application.href(LensesRoute.Id(id = created.id)))
                call.respond(HttpStatusCode.Created, created)
            }
            put<LensesRoute.Id> { route ->
                val caller = call.caller()
                // No route-side validateLensRequest here: the service checks the ownership
                // verdict FIRST so 403/404 wins over 400 (the password-PUT precedent), then
                // validates inside the same transaction.
                val request = sanitizedLensRequest(call.receive())
                lensService.update(route.id, request, caller.userId).orThrow()
                audit(
                    "lens.updated",
                    "byUserId" to caller.userId.toLong(),
                    "lensId" to route.id.toLong(),
                    "name" to request.name,
                    "visibility" to request.visibility.name,
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<LensesRoute.Id> { route ->
                val caller = call.caller()
                lensService.delete(route.id, caller.userId).orThrow()
                audit(
                    "lens.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "lensId" to route.id.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
