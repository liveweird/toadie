package ch.nokillswit.conversion

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.entities.entityGraphFilter
import ch.nokillswit.entities.requireQueryLength
import ch.nokillswit.infra.importing.requireBatchSize
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalString
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
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
    class Id(val parent: ConversionMappingsRoute = ConversionMappingsRoute(), val id: UInt) {
        /** Phase 2: Port -> Backstage, a pure read (`ConversionExportService`). */
        @Serializable
        @Resource("catalog-documents")
        class CatalogDocuments(val parent: Id)

        /** Phase 4: Backstage -> Port, a pure computation (`ConversionImportService`). */
        @Serializable
        @Resource("entity-documents")
        class EntityDocuments(val parent: Id)
    }
}

private fun ConversionMapping.pairCount(): Int = (document["pairs"] as? JsonArray)?.size ?: 0

fun Application.configureConversionMappingRoutes() {
    val mappingService = attributes[ConversionMappingServiceKey]
    val exportService = attributes[ConversionExportServiceKey]
    val importService = attributes[ConversionImportServiceKey]

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
            // Phase 2: the Port -> Backstage conversion. A pure read — no audit event (the
            // `/check`/`/errors`/export rule, `.claude/docs/authorization.md`), any authenticated
            // user (it reads what the Entities and Files pages already show). Malformed filter params are a
            // 400 before anything is read; then the mapping lookup (404) and the mapping's own health (409).
            get<ConversionMappingsRoute.Id.CatalogDocuments> { route ->
                call.caller()
                val params = call.request.queryParameters
                val query = params.optionalString("query")
                requireQueryLength(query)
                val filter = call.entityGraphFilter(query)
                val entityId = params.optionalString("entityId")?.let {
                    it.toUIntOrNull()?.takeIf { id -> id > 0u } ?: throw BadRequestException("entityId must be a positive integer")
                }
                val merge = params.optionalBoolean("merge") ?: true
                // Encoded with blueprintJson (explicitNulls = false, encodeDefaults = true) INSIDE the service, while its
                // ledger reservation is still held: an absent value is an absent member, never an explicit null.
                val body = exportService.catalogDocumentsJson(route.parent.id, filter, entityId, merge)
                    .orNotFound("Conversion mapping")
                call.respond(TextContent(body, ContentType.Application.Json.withCharset(Charsets.UTF_8), HttpStatusCode.OK))
            }
            // Phase 4: the Backstage -> Port conversion. A pure computation behind POST only because the
            // input is a body of up to 200 documents — it stores, locks and audits nothing (the
            // `/check`/export idiom), any authenticated user (it reads what the Files and Entities pages
            // already show). Order: the body (400 malformed), the batch cap (400), then the mapping
            // lookup (404) and the mapping's own health (409).
            post<ConversionMappingsRoute.Id.EntityDocuments> { route ->
                call.caller()
                val request = call.receive<EntityDocumentsRequest>()
                requireBatchSize(request.files.size)
                val body = importService.entityDocumentsBody(route.parent.id, request).orNotFound("Conversion mapping")
                call.respond(TextContent(body, ContentType.Application.Json.withCharset(Charsets.UTF_8), HttpStatusCode.OK))
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
