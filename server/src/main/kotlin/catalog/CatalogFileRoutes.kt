package ch.nokillswit.catalog

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.repeatedValues
import ch.nokillswit.infra.paging.toPage
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

// The URL says /files (the SPA's route name); the domain keeps its CatalogFile naming —
// classes, DTOs, audit events, and the OpenAPI operationIds all stay catalog-file-shaped.
@Serializable
@Resource("/api/v1/files")
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

/**
 * The created/updated audit fields: `waivedFindings` rides along ONLY when an allowInvalid
 * save actually waived something — the strict happy path keeps its two-field shape.
 */
private fun createdAuditFields(byUserId: UInt, catalogFileId: UInt, waived: Int): Array<Pair<String, Any?>> =
    buildList<Pair<String, Any?>> {
        add("byUserId" to byUserId.toLong())
        add("catalogFileId" to catalogFileId.toLong())
        if (waived > 0) add("waivedFindings" to waived)
    }.toTypedArray()

/**
 * The ONE filter parser shared by the list GET and the graph GET (the two endpoints declare
 * the same filter set): kind canonicalized against the whitelist, owner parsed to the entity
 * identity it targets, labelValue the repeated any-of param that requires label.
 */
private fun ApplicationCall.catalogFileFilter(): CatalogFileListFilter {
    val params = request.queryParameters
    val labelKey = params.optionalString("label")
    val labelValues = params.repeatedValues("labelValue")
    if (labelValues.isNotEmpty() && labelKey == null) {
        throw BadRequestException("labelValue requires the label parameter")
    }
    return CatalogFileListFilter(
        name = params.optionalString("name"),
        namespace = params.optionalString("namespace"),
        // Repetition is the documented any-of/IN idiom on kind (like labelValue below).
        kinds = params.repeatedValues("kind").map { raw ->
            SUPPORTED_KINDS.firstOrNull { it.equals(raw, ignoreCase = true) }
                ?: throw BadRequestException(
                    "Unknown kind: $raw (allowed: ${SUPPORTED_KINDS.joinToString()})",
                )
        }.distinct(),
        tag = params.optionalString("tag"),
        type = params.optionalString("type"),
        lifecycle = params.optionalString("lifecycle"),
        owner = params.optionalString("owner")?.let { raw ->
            ownerFilterTarget(raw)
                ?: throw BadRequestException("owner must be an entity reference ([kind:][namespace/]name)")
        },
        label = labelKey,
        labelValues = labelValues,
    )
}

fun Application.configureCatalogFileRoutes() {
    val catalogFileService = attributes[CatalogFileServiceKey]
    // Stateless, no DB — constructed here rather than in the composition root. Lazy so the
    // test seam (CatalogUrlFetcherKey, set after module load) can supply a fixture fetcher.
    val urlFetcher by lazy { attributes.getOrNull(CatalogUrlFetcherKey) ?: CatalogUrlFetcher() }

    routing {
        authenticate {
            // Shared workspace: every authenticated user has full CRUD on every file (ADMIN
            // gets no special content access — the standing rule), so the only "guard" is the
            // bare caller() authentication check.
            get<CatalogFiles> {
                call.caller()
                val paging = call.parsePaging(sortable = CATALOG_FILE_SORT_FIELDS)
                val result = catalogFileService.list(call.catalogFileFilter(), paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            post<CatalogFiles> {
                val caller = call.caller()
                val file = sanitizedCatalogFile(call.receive())
                validateCatalogFile(file)
                // The explicit soft-check waiver: `?allowInvalid=true` stores despite
                // unresolved references / registry findings (structural rules stay hard).
                val allowInvalid = call.request.queryParameters.optionalBoolean("allowInvalid") ?: false
                val saved = catalogFileService.create(file, caller.userId, allowInvalid = allowInvalid)
                audit("catalog_file.created", *createdAuditFields(caller.userId, saved.id, saved.waived.size))
                // Read back for the creator/timestamp envelope — post-commit, so a miss is a 500.
                val created = catalogFileService.read(saved.id).orVanished("CatalogFile", saved.id)
                call.response.header(HttpHeaders.Location, call.application.href(CatalogFiles.Id(id = saved.id)))
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
                call.respond(HttpStatusCode.OK, catalogFileService.graph(call.catalogFileFilter()))
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
                // Report & skip: the per-document orchestration lives in the service; audits
                // stay route-side (the repo convention) — one created event per stored row
                // (waived documents store too, marked withFindings).
                val storedStatuses = setOf(ImportResultStatus.CREATED, ImportResultStatus.CREATED_WITH_FINDINGS)
                val results = catalogFileService.import(request.files, caller.userId)
                for (result in results.filter { it.status in storedStatuses }) {
                    audit(
                        "catalog_file.created",
                        *buildList<Pair<String, Any?>> {
                            add("byUserId" to caller.userId.toLong())
                            add("catalogFileId" to result.fileId!!.toLong())
                            add("import" to true)
                            if (result.status == ImportResultStatus.CREATED_WITH_FINDINGS) add("withFindings" to true)
                        }.toTypedArray(),
                    )
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
                val detail = catalogFileService.read(route.id).orNotFound("Catalog file")
                call.respond(HttpStatusCode.OK, detail.toResponse())
            }
            put<CatalogFiles.Id> { route ->
                val caller = call.caller()
                val file = sanitizedCatalogFile(call.receive())
                validateCatalogFile(file)
                val allowInvalid = call.request.queryParameters.optionalBoolean("allowInvalid") ?: false
                val result = catalogFileService.update(route.id, file, allowInvalid = allowInvalid)
                result.rows.orNotFound("Catalog file")
                audit("catalog_file.updated", *createdAuditFields(caller.userId, route.id, result.waived.size))
                call.respond(HttpStatusCode.NoContent)
            }
            delete<CatalogFiles.Id> { route ->
                val caller = call.caller()
                catalogFileService.delete(route.id).orNotFound("Catalog file")
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
