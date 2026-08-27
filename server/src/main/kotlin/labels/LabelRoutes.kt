package ch.nokillswit.labels

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
@Resource("/api/v1/labels")
class LabelsRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: LabelsRoute = LabelsRoute(), val id: UInt)
}

fun Application.configureLabelRoutes() {
    val labelService = attributes[LabelServiceKey]

    routing {
        authenticate {
            // The read is any-authenticated: the registry is the editor's picker source and
            // the Labels page's read-only list. Every mutation is ADMIN-only, with the guard
            // BEFORE the id lookup (a non-admin probe gets a uniform 403 whether or not the
            // id exists — the guard-before-read idiom).
            get<LabelsRoute> {
                call.caller()
                call.respond(HttpStatusCode.OK, LabelList(items = labelService.list()))
            }
            post<LabelsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedLabelRequest(call.receive())
                validateLabelRequest(request)
                val id = labelService.create(request)
                audit(
                    "label.created",
                    "byUserId" to caller.userId.toLong(),
                    "labelId" to id.toLong(),
                    "key" to request.key,
                    "values" to request.values.size,
                    "kinds" to request.kinds.joinToString(","),
                )
                val created = LabelResponse(id = id, key = request.key, values = request.values, kinds = request.kinds)
                call.response.header(HttpHeaders.Location, call.application.href(LabelsRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, created)
            }
            put<LabelsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedLabelRequest(call.receive())
                validateLabelRequest(request)
                labelService.update(route.id, request).orNotFound("Label")
                audit(
                    "label.updated",
                    "byUserId" to caller.userId.toLong(),
                    "labelId" to route.id.toLong(),
                    "key" to request.key,
                    "values" to request.values.size,
                    "kinds" to request.kinds.joinToString(","),
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<LabelsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                labelService.delete(route.id).orNotFound("Label")
                audit(
                    "label.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "labelId" to route.id.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
