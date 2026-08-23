package ch.nokillswit.catalog

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.isUniqueViolation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
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

    // Literal segments win over {id} in Ktor's route resolution (pinned by CrossCheckTest).
    @Serializable
    @Resource("cross-check")
    class CrossCheck(val parent: CatalogFiles = CatalogFiles())

    @Serializable
    @Resource("check")
    class Check(val parent: CatalogFiles = CatalogFiles())

    @Serializable
    @Resource("graph")
    class Graph(val parent: CatalogFiles = CatalogFiles())

    @Serializable
    @Resource("export")
    class Export(val parent: CatalogFiles = CatalogFiles())

    @Serializable
    @Resource("import")
    class Import(val parent: CatalogFiles = CatalogFiles())

    @Serializable
    @Resource("fetch")
    class Fetch(val parent: CatalogFiles = CatalogFiles())
}

fun Application.configureCatalogFileRoutes() {
    val catalogFileService = attributes[CatalogFileServiceKey]
    // Stateless, no DB — constructed here rather than in the composition root.
    val urlFetcher = CatalogUrlFetcher()

    routing {
        authenticate {
            // Shared workspace: every authenticated user has full CRUD on every file (ADMIN
            // gets no special content access — the standing rule), so the only "guard" is the
            // bare caller() authentication check.
            get<CatalogFiles> {
                call.caller()
                val paging = call.parsePaging(sortable = setOf("id", "kind", "name", "namespace", "updatedAt"))
                val filter = CatalogFileListFilter(
                    name = call.request.queryParameters.optionalString("name"),
                    namespace = call.request.queryParameters.optionalString("namespace"),
                    kind = call.request.queryParameters.optionalString("kind")?.let { raw ->
                        SUPPORTED_KINDS.firstOrNull { it.equals(raw, ignoreCase = true) }
                            ?: throw BadRequestException(
                                "Unknown kind: $raw (allowed: ${SUPPORTED_KINDS.joinToString()})",
                            )
                    },
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
            get<CatalogFiles.CrossCheck> {
                call.caller()
                call.respond(HttpStatusCode.OK, catalogFileService.crossCheck())
            }
            post<CatalogFiles.Check> {
                call.caller()
                // Sanitized but deliberately NOT validated: the editor checks in-progress
                // documents as the user types — findings-so-far, never a 400. A pure
                // computation (POST only because the document travels in the body); no audit.
                val file = sanitizedCatalogFile(call.receive())
                call.respond(HttpStatusCode.OK, catalogFileService.check(file))
            }
            get<CatalogFiles.Graph> {
                call.caller()
                val namespace = call.request.queryParameters.optionalString("namespace")
                call.respond(HttpStatusCode.OK, catalogFileService.graph(namespace))
            }
            get<CatalogFiles.Export> {
                call.caller()
                val namespace = call.request.queryParameters.optionalString("namespace")
                call.respond(HttpStatusCode.OK, catalogFileService.export(namespace))
            }
            post<CatalogFiles.Import> {
                val caller = call.caller()
                val request = call.receive<ImportRequest>()
                if (request.files.size > MAX_IMPORT_FILES) {
                    throw BadRequestException("files must have at most $MAX_IMPORT_FILES entries")
                }
                // Report & skip: each document imports independently — a bad or clashing
                // document becomes its own result row and never aborts its neighbors.
                val results = request.files.mapIndexed { index, raw ->
                    importOne(catalogFileService, caller.userId, index, raw)
                }
                call.respond(HttpStatusCode.OK, ImportResponse(results = results))
            }
            post<CatalogFiles.Fetch> {
                val caller = call.caller()
                val request = call.receive<FetchUrlRequest>()
                val content = try {
                    urlFetcher.fetch(request.url)
                } catch (blocked: BlockedUrlException) {
                    // A blocked fetch attempt is a probe signal worth keeping; the response
                    // itself stays uniform so nothing about the internal network leaks.
                    audit(
                        "catalog_file.fetch_blocked",
                        "byUserId" to caller.userId.toLong(),
                        "scheme" to blocked.scheme,
                        "host" to blocked.host,
                    )
                    throw BadRequestException(FETCH_URL_INVALID_DETAIL)
                }
                call.respond(HttpStatusCode.OK, FetchUrlResponse(content = content))
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

/**
 * One import document, in isolation: sanitize → validate (a validator 400 becomes INVALID with
 * its message) → create (an identity clash — the partial unique index's 23505 — becomes
 * CONFLICT, report & skip; any other storage failure becomes ERROR). Nothing here rethrows —
 * the batch always runs to completion and the result rows ARE the outcome.
 */
private suspend fun importOne(
    service: CatalogFileService,
    callerUserId: UInt,
    index: Int,
    raw: CatalogFile,
): ImportFileResult {
    val file = sanitizedCatalogFile(raw)
    val base = ImportFileResult(
        index = index,
        kind = file.kind,
        namespace = file.metadata.namespace,
        name = file.metadata.name,
        status = ImportResultStatus.ERROR,
    )
    try {
        validateCatalogFile(file)
    } catch (e: BadRequestException) {
        return base.copy(status = ImportResultStatus.INVALID, message = e.message)
    }
    return try {
        val id = service.create(file, callerUserId)
        audit(
            "catalog_file.created",
            "byUserId" to callerUserId.toLong(),
            "catalogFileId" to id.toLong(),
            "import" to true,
        )
        base.copy(status = ImportResultStatus.CREATED, fileId = id)
    } catch (e: Exception) {
        if (e.isUniqueViolation()) {
            base.copy(
                status = ImportResultStatus.CONFLICT,
                message = "An active catalog file with this kind, namespace, and name already exists",
            )
        } else {
            base.copy(status = ImportResultStatus.ERROR, message = e.message ?: "Storage failed")
        }
    }
}
