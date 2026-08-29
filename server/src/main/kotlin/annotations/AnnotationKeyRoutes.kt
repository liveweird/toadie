package ch.nokillswit.annotations

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
@Resource("/api/v1/annotation-keys")
class AnnotationKeysRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: AnnotationKeysRoute = AnnotationKeysRoute(), val id: UInt)
}

fun Application.configureAnnotationKeyRoutes() {
    val annotationKeyService = attributes[AnnotationKeyServiceKey]

    routing {
        authenticate {
            // The read is any-authenticated: the registry is the editor's key-picker source
            // and the Annotations page's read-only list. Every mutation is ADMIN-only, with
            // the guard BEFORE the id lookup (a non-admin probe gets a uniform 403 whether
            // or not the id exists — the guard-before-read idiom).
            get<AnnotationKeysRoute> {
                call.caller()
                call.respond(HttpStatusCode.OK, AnnotationKeyList(items = annotationKeyService.list()))
            }
            post<AnnotationKeysRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedAnnotationKeyRequest(call.receive())
                validateAnnotationKeyRequest(request)
                val id = annotationKeyService.create(request)
                audit(
                    "annotation_key.created",
                    "byUserId" to caller.userId.toLong(),
                    "annotationKeyId" to id.toLong(),
                    "key" to request.key,
                    "kinds" to request.kinds.joinToString(","),
                )
                val created = AnnotationKeyResponse(id = id, key = request.key, kinds = request.kinds)
                call.response.header(HttpHeaders.Location, call.application.href(AnnotationKeysRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, created)
            }
            put<AnnotationKeysRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedAnnotationKeyRequest(call.receive())
                validateAnnotationKeyRequest(request)
                annotationKeyService.update(route.id, request).orNotFound("Annotation key")
                audit(
                    "annotation_key.updated",
                    "byUserId" to caller.userId.toLong(),
                    "annotationKeyId" to route.id.toLong(),
                    "key" to request.key,
                    "kinds" to request.kinds.joinToString(","),
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<AnnotationKeysRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                annotationKeyService.delete(route.id).orNotFound("Annotation key")
                audit(
                    "annotation_key.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "annotationKeyId" to route.id.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
