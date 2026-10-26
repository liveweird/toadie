package ch.nokillswit.conversion

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
import kotlinx.serialization.json.JsonArray

@Serializable
@Resource("/api/v1/conversion-mappings")
class ConversionMappingsRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: ConversionMappingsRoute = ConversionMappingsRoute(), val id: UInt)
}

private fun ConversionMapping.pairCount(): Int = (document["pairs"] as? JsonArray)?.size ?: 0

fun Application.configureConversionMappingRoutes() {
    val mappingService = attributes[ConversionMappingServiceKey]

    routing {
        authenticate {
            // Reads are any-authenticated (the registries' posture: the mapping is documentation
            // of the model as much as configuration); every mutation is ADMIN-only, with the
            // guard BEFORE receive/lookup (a non-admin probe gets a uniform 403 whether or not
            // the id exists — the guard-before-read idiom).
            get<ConversionMappingsRoute> {
                call.caller()
                call.respond(HttpStatusCode.OK, ConversionMappingList(items = mappingService.list()))
            }
            get<ConversionMappingsRoute.Id> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, mappingService.read(route.id).orNotFound("Conversion mapping"))
            }
            post<ConversionMappingsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedConversionMappingRequest(call.receive())
                val created = mappingService.create(request, caller.userId)
                audit(
                    "conversion_mapping.created",
                    "byUserId" to caller.userId.toLong(),
                    "mappingId" to created.id.toLong(),
                    "name" to created.name,
                    "pairs" to created.pairCount(),
                )
                call.response.header(HttpHeaders.Location, call.application.href(ConversionMappingsRoute.Id(id = created.id)))
                call.respond(HttpStatusCode.Created, created)
            }
            put<ConversionMappingsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                // No route-side sanitize or validate here: the service checks the row's existence
                // FIRST so a missing id 404s before an invalid name or document would 400 (the
                // blueprint PUT rule; sanitizing the name could itself 400), then sanitizes and
                // validates inside the same transaction.
                val request = call.receive<ConversionMappingRequest>()
                val updated = mappingService.update(route.id, request).orNotFound("Conversion mapping")
                audit(
                    "conversion_mapping.updated",
                    "byUserId" to caller.userId.toLong(),
                    "mappingId" to route.id.toLong(),
                    "name" to updated.name,
                    "pairs" to updated.pairCount(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<ConversionMappingsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                mappingService.delete(route.id).orNotFound("Conversion mapping")
                audit(
                    "conversion_mapping.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "mappingId" to route.id.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
