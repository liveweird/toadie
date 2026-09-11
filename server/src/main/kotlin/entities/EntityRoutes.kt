package ch.nokillswit.entities

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.repeatedValues
import ch.nokillswit.infra.paging.toPage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
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
@Resource("/api/v1/entities")
class EntitiesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: EntitiesRoute = EntitiesRoute(), val id: UInt)

    // A literal segment beats {id} in Ktor's route resolution (the CatalogFiles.Graph idiom).
    @Serializable
    @Resource("graph")
    class Graph(val parent: EntitiesRoute = EntitiesRoute())
}

/**
 * Encodes with [blueprintJson] via [TextContent] — the `blueprints/BlueprintRoutes.kt`
 * `respondBlueprint` precedent — so unset optional fields (`icon`, `team`) are ABSENT rather
 * than explicit `null`.
 */
private suspend inline fun <reified T> ApplicationCall.respondEntity(status: HttpStatusCode, body: T) {
    respond(TextContent(blueprintJson.encodeToString(body), ContentType.Application.Json.withCharset(Charsets.UTF_8), status))
}

fun Application.configureEntityRoutes() {
    val entityService = attributes[EntityServiceKey]

    routing {
        authenticate {
            // Shared workspace, the catalog-files/blueprints-read posture: every authenticated
            // user has full CRUD (no isAdmin gate anywhere in this feature — creator recorded
            // for display/audit only).
            get<EntitiesRoute> {
                call.caller()
                val paging = call.parsePaging(
                    sortable = ENTITY_SORT_FIELDS,
                    defaultSort = listOf(SortField("identifier", descending = false)),
                )
                val filter = EntityFilter(
                    blueprint = call.request.queryParameters.optionalString("blueprint"),
                    q = call.request.queryParameters.optionalString("q"),
                    team = call.request.queryParameters.optionalString("team"),
                )
                val result = entityService.list(filter, paging)
                call.respondEntity(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            get<EntitiesRoute.Id> { route ->
                call.caller()
                val entity = entityService.read(route.id).orNotFound("Entity")
                call.respondEntity(HttpStatusCode.OK, entity)
            }
            get<EntitiesRoute.Graph> {
                call.caller()
                val filter = EntityGraphFilter(
                    blueprints = call.request.queryParameters.repeatedValues("blueprint"),
                    q = call.request.queryParameters.optionalString("q"),
                    team = call.request.queryParameters.optionalString("team"),
                )
                call.respondEntity(HttpStatusCode.OK, entityService.graph(filter))
            }
            post<EntitiesRoute> {
                val caller = call.caller()
                val request = sanitizedEntityRequest(call.receive())
                validateEntityRequest(request)
                val created = entityService.create(request, caller.userId)
                audit(
                    "entity.created",
                    "byUserId" to caller.userId.toLong(),
                    "entityId" to created.id.toLong(),
                    "blueprint" to created.blueprint,
                    "identifier" to created.identifier,
                    // The STORED count (created.properties also carries computed values since
                    // v1.27.0 — mirror/calculation/aggregation ids the request could never have
                    // sent), matching entity.updated's own audit field.
                    "properties" to request.properties.size,
                    "relations" to created.relations.size,
                )
                call.response.header(HttpHeaders.Location, call.application.href(EntitiesRoute.Id(id = created.id)))
                call.respondEntity(HttpStatusCode.Created, created)
            }
            put<EntitiesRoute.Id> { route ->
                val caller = call.caller()
                // No route-side validateEntityRequest: the service checks the row's existence
                // FIRST so a missing id 404s before an invalid body would 400 (the
                // BlueprintRoutes/LensRoutes PUT precedent), then validates inside the same
                // locked transaction.
                val request = sanitizedEntityRequest(call.receive())
                val result = entityService.update(route.id, request)
                result.affected.orNotFound("Entity")
                val fields = buildList<Pair<String, Any?>> {
                    add("byUserId" to caller.userId.toLong())
                    add("entityId" to route.id.toLong())
                    add("blueprint" to request.blueprint)
                    add("identifier" to request.identifier)
                    add("properties" to request.properties.size)
                    add("relations" to request.relations.size)
                    add("cascaded" to result.cascaded.size)
                    result.renamedFrom?.let { add("renamedFrom" to it) }
                }
                audit("entity.updated", *fields.toTypedArray())
                call.respond(HttpStatusCode.NoContent)
            }
            delete<EntitiesRoute.Id> { route ->
                val caller = call.caller()
                val result = entityService.delete(route.id)
                result.affected.orNotFound("Entity")
                audit(
                    "entity.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "entityId" to route.id.toLong(),
                    "blueprint" to result.blueprint,
                    "identifier" to result.identifier,
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
