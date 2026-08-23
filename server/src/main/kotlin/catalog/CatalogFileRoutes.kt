package ch.nokillswit.catalog

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
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
@Resource("/api/v1/catalog-files")
class CatalogFiles {
    @Serializable
    @Resource("{id}")
    class Id(val parent: CatalogFiles = CatalogFiles(), val id: UInt)
}

fun Application.configureCatalogFileRoutes() {
    val catalogFileService = attributes[CatalogFileServiceKey]

    routing {
        authenticate {
            // Shared workspace: every authenticated user has full CRUD on every file (ADMIN
            // gets no special content access — the standing rule), so the only "guard" is the
            // bare caller() authentication check.
            get<CatalogFiles> {
                call.caller()
                val paging = call.parsePaging(sortable = setOf("id", "name", "namespace", "updatedAt"))
                val filter = CatalogFileListFilter(
                    name = call.request.queryParameters.optionalString("name"),
                    namespace = call.request.queryParameters.optionalString("namespace"),
                )
                val result = catalogFileService.list(filter, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            post<CatalogFiles> {
                val caller = call.caller()
                val file = sanitizedCatalogFile(call.receive())
                validateCatalogFile(file)
                val id = catalogFileService.create(file, caller.userId)
                audit("catalog_file.created", "byUserId" to caller.userId.toLong(), "catalogFileId" to id.toLong())
                // Read back for the creator/timestamp envelope — post-commit, so a miss is a 500.
                val created = catalogFileService.read(id).orVanished("CatalogFile", id)
                call.response.header(HttpHeaders.Location, call.application.href(CatalogFiles.Id(id = id)))
                call.respond(HttpStatusCode.Created, created.toResponse())
            }
            get<CatalogFiles.Id> { route ->
                call.caller()
                val detail = catalogFileService.read(route.id)
                    ?: throw NotFoundException("Catalog file not found")
                call.respond(HttpStatusCode.OK, detail.toResponse())
            }
            put<CatalogFiles.Id> { route ->
                val caller = call.caller()
                val file = sanitizedCatalogFile(call.receive())
                validateCatalogFile(file)
                if (catalogFileService.update(route.id, file) == 0) {
                    throw NotFoundException("Catalog file not found")
                }
                audit(
                    "catalog_file.updated",
                    "byUserId" to caller.userId.toLong(),
                    "catalogFileId" to route.id.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<CatalogFiles.Id> { route ->
                val caller = call.caller()
                if (catalogFileService.delete(route.id) == 0) {
                    throw NotFoundException("Catalog file not found")
                }
                audit(
                    "catalog_file.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "catalogFileId" to route.id.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
