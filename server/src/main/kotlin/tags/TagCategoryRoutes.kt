package ch.nokillswit.tags

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
@Resource("/api/v1/tag-categories")
class TagCategoriesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: TagCategoriesRoute = TagCategoriesRoute(), val id: UInt)
}

fun Application.configureTagCategoryRoutes() {
    val tagCategoryService = attributes[TagCategoryServiceKey]

    routing {
        authenticate {
            // The read is any-authenticated: the registry is the editor's tag-picker source
            // and the Tags page's read-only list. Every mutation is ADMIN-only, with the
            // guard BEFORE the id lookup (a non-admin probe gets a uniform 403 whether or
            // not the id exists — the guard-before-read idiom).
            get<TagCategoriesRoute> {
                call.caller()
                call.respond(HttpStatusCode.OK, TagCategoryList(items = tagCategoryService.list()))
            }
            post<TagCategoriesRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedTagCategoryRequest(call.receive())
                validateTagCategoryRequest(request)
                val id = tagCategoryService.create(request)
                audit(
                    "tag_category.created",
                    "byUserId" to caller.userId.toLong(),
                    "categoryId" to id.toLong(),
                    "name" to request.name,
                    "tags" to request.tags.size,
                    "kinds" to request.kinds.joinToString(","),
                )
                val created = TagCategoryResponse(id = id, name = request.name, tags = request.tags, kinds = request.kinds)
                call.response.header(HttpHeaders.Location, call.application.href(TagCategoriesRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, created)
            }
            put<TagCategoriesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedTagCategoryRequest(call.receive())
                validateTagCategoryRequest(request)
                tagCategoryService.update(route.id, request).orNotFound("Tag category")
                audit(
                    "tag_category.updated",
                    "byUserId" to caller.userId.toLong(),
                    "categoryId" to route.id.toLong(),
                    "name" to request.name,
                    "tags" to request.tags.size,
                    "kinds" to request.kinds.joinToString(","),
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<TagCategoriesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                tagCategoryService.delete(route.id).orNotFound("Tag category")
                audit(
                    "tag_category.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "categoryId" to route.id.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
