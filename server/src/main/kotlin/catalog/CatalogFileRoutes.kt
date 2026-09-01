package ch.nokillswit.catalog

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.EVENT_LOG_DEFAULT_SORT
import ch.nokillswit.infra.db.EVENT_LOG_SORT_FIELDS
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
    class Id(val parent: CatalogFiles = CatalogFiles(), val id: UInt) {
        @Serializable
        @Resource("sync")
        class Sync(val parent: Id)

        @Serializable
        @Resource("events")
        class Events(val parent: Id)
    }

    // Literal segments win over {id} in Ktor's route resolution (pinned by ErrorsTest).
    @Serializable
    @Resource("errors")
    class Errors(val parent: CatalogFiles = CatalogFiles())

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
    class Import(val parent: CatalogFiles = CatalogFiles()) {
        @Serializable
        @Resource("check")
        class Check(val parent: Import = Import())
    }

    @Serializable
    @Resource("fetch")
    class Fetch(val parent: CatalogFiles = CatalogFiles())
}

/**
 * The catalog-file mutation audit fields, shared by created/updated/synced and the import
 * loop: `waivedFindings` (always the COUNT — one type per field name, SIEM-friendly) rides
 * along ONLY when the save actually waived something; `import`/`withFindings` are the import
 * loop's boolean markers. The strict happy path keeps its two-field shape.
 */
private fun catalogAuditFields(
    byUserId: UInt,
    catalogFileId: UInt,
    waived: Int = 0,
    import: Boolean = false,
    withFindings: Boolean = false,
): Array<Pair<String, Any?>> =
    buildList<Pair<String, Any?>> {
        add("byUserId" to byUserId.toLong())
        add("catalogFileId" to catalogFileId.toLong())
        if (waived > 0) add("waivedFindings" to waived)
        if (import) add("import" to true)
        if (withFindings) add("withFindings" to true)
    }.toTypedArray()

/**
 * The ONE filter parser shared by the list, graph, and errors GETs (the three endpoints
 * declare the same filter set): kind canonicalized against the whitelist, owner parsed to the
 * entity identity it targets, labelValue the repeated any-of param that requires label.
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
    val eventService = attributes[CatalogFileEventServiceKey]
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
                val body = call.receive<CatalogFileWriteRequest>()
                val file = sanitizedCatalogFile(body.document())
                validateCatalogFile(file)
                val sourceUrl = sanitizedSourceUrl(body.sourceUrl)
                // The explicit soft-check waiver: `?allowInvalid=true` stores despite
                // unresolved references / registry findings (structural rules stay hard).
                val allowInvalid = call.request.queryParameters.optionalBoolean("allowInvalid") ?: false
                val saved = catalogFileService.create(
                    file,
                    caller.userId,
                    allowInvalid = allowInvalid,
                    sourceUrl = sourceUrl,
                )
                audit("catalog_file.created", *catalogAuditFields(caller.userId, saved.id, waived = saved.waived.size))
                // The user-facing history, beside the SIEM-facing audit line above (the two
                // trails are documented in observability.md). Appended AFTER the mutation
                // commits, in its own transaction — the ported consistency model.
                eventService.record(saved.id, caller.userId, catalogFileCreationEvent(file.kind))
                // Read back for the creator/timestamp envelope — post-commit, so a miss is a 500.
                val created = catalogFileService.read(saved.id).orVanished("CatalogFile", saved.id)
                call.response.header(HttpHeaders.Location, call.application.href(CatalogFiles.Id(id = saved.id)))
                call.respond(HttpStatusCode.Created, created.toResponse())
            }
            get<CatalogFiles.Errors> {
                call.caller()
                // The shared filter set narrows which files' errors are reported; references
                // still resolve against the whole workspace, so narrowing the report never
                // manufactures a MISSING finding (the graph, where the filter decides what is
                // SHOWN, deliberately reads the same params differently).
                call.respond(HttpStatusCode.OK, catalogFileService.errors(call.catalogFileFilter()))
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
            post<CatalogFiles.Import.Check> {
                call.caller()
                val request = call.receive<ImportRequest>()
                if (request.files.size > MAX_IMPORT_FILES) {
                    throw BadRequestException("files must have at most $MAX_IMPORT_FILES entries")
                }
                // The import's dry-run: the same per-row report, nothing stored. A pure
                // computation like the other check endpoints — no audit events (and no
                // created-audit loop: predicted rows have no fileId).
                call.respond(HttpStatusCode.OK, ImportResponse(results = catalogFileService.importCheck(request.files)))
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
                val results = catalogFileService.import(
                    request.files,
                    caller.userId,
                    sourceUrl = sanitizedSourceUrl(request.sourceUrl),
                )
                for (result in results.filter { it.status in storedStatuses }) {
                    val fileId = checkNotNull(result.fileId) { "stored import row without a fileId" }
                    audit(
                        "catalog_file.created",
                        *catalogAuditFields(
                            caller.userId,
                            fileId,
                            import = true,
                            withFindings = result.status == ImportResultStatus.CREATED_WITH_FINDINGS,
                        ),
                    )
                    eventService.record(fileId, caller.userId, catalogFileCreationEvent(result.kind, viaImport = true))
                }
                call.respond(HttpStatusCode.OK, ImportResponse(results = results))
            }
            post<CatalogFiles.Fetch> {
                val caller = call.caller()
                val request = call.receive<FetchUrlRequest>()
                val fetched = try {
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
                // The success trail: the server pulled a body from a public host on user
                // command — record who and from where (scheme/host ONLY, never the full URL).
                audit(
                    "catalog_file.fetched",
                    "byUserId" to caller.userId.toLong(),
                    "scheme" to fetched.uri.scheme,
                    "host" to fetched.uri.host,
                )
                call.respond(HttpStatusCode.OK, FetchUrlResponse(content = fetched.content))
            }
            get<CatalogFiles.Id> { route ->
                call.caller()
                val detail = catalogFileService.read(route.id).orNotFound("Catalog file")
                call.respond(HttpStatusCode.OK, detail.toResponse())
            }
            put<CatalogFiles.Id> { route ->
                val caller = call.caller()
                val body = call.receive<CatalogFileWriteRequest>()
                val file = sanitizedCatalogFile(body.document())
                validateCatalogFile(file)
                val sourceUrl = sanitizedSourceUrl(body.sourceUrl)
                val allowInvalid = call.request.queryParameters.optionalBoolean("allowInvalid") ?: false
                val result = catalogFileService.update(
                    route.id,
                    file,
                    allowInvalid = allowInvalid,
                    sourceUrl = sourceUrl,
                )
                result.rows.orNotFound("Catalog file")
                audit("catalog_file.updated", *catalogAuditFields(caller.userId, route.id, waived = result.waived.size))
                // A save that changed nothing records nothing (no empty history entries).
                catalogFileUpdateEvent(result.changes)?.let { eventService.record(route.id, caller.userId, it) }
                call.respond(HttpStatusCode.NoContent)
            }
            get<CatalogFiles.Id.Sync> { route ->
                call.caller()
                val state = catalogFileService.syncState(route.parent.id).orNotFound("Catalog file")
                call.respond(HttpStatusCode.OK, state.toResponse())
            }
            post<CatalogFiles.Id.Sync> { route ->
                val caller = call.caller()
                // The repo→DB overwrite: the client fetched the source URL (POST /files/fetch)
                // and parsed the YAML (a client concern); the service always waives soft
                // findings (the import posture) and stamps the sync state. Confirmation is a
                // client concern (the sync modal); rejecting a source-less row is not.
                val request = call.receive<SyncCatalogFileRequest>()
                val file = sanitizedCatalogFile(request.document)
                validateCatalogFile(file)
                val result = catalogFileService.syncFromRepo(route.parent.id, file)
                result.rows.orNotFound("Catalog file")
                audit(
                    "catalog_file.synced",
                    *catalogAuditFields(caller.userId, route.parent.id, waived = result.waived.size),
                )
                // Recorded even when the repo copy matched: pulling it IS the act (it stamps
                // the sync state), unlike a no-op PUT.
                eventService.record(route.parent.id, caller.userId, catalogFileSyncEvent(result.changes))
                call.respond(HttpStatusCode.NoContent)
            }
            get<CatalogFiles.Id.Events> { route ->
                call.caller()
                // Whoever may read the file may read its history — in this shared workspace,
                // every authenticated user. A missing or deleted file is the plain 404.
                catalogFileService.read(route.parent.id).orNotFound("Catalog file")
                val paging = call.parsePaging(
                    sortable = EVENT_LOG_SORT_FIELDS,
                    defaultSort = EVENT_LOG_DEFAULT_SORT,
                )
                val result = eventService.listForFile(route.parent.id, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            delete<CatalogFiles.Id> { route ->
                val caller = call.caller()
                catalogFileService.delete(route.id).orNotFound("Catalog file")
                audit(
                    "catalog_file.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "catalogFileId" to route.id.toLong(),
                )
                // The file soft-deletes, so its events outlive it — this one lands in a history
                // the UI can no longer reach, kept deliberately for the record.
                eventService.record(route.id, caller.userId, catalogFileDeletionEvent())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
