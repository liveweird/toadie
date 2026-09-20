package ch.nokillswit.entities

import ch.nokillswit.audit.AuditEvent
import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entityquery.EntityQueryCheckRequest
import ch.nokillswit.entityquery.EntityQueryCheckResponse
import ch.nokillswit.entityquery.MAX_QUERY_LENGTH
import ch.nokillswit.infra.fetch.fetchForCaller
import ch.nokillswit.infra.fetch.UrlFetcher
import ch.nokillswit.infra.fetch.UrlFetcherKey
import ch.nokillswit.infra.fetch.sanitizedSourceUrl
import ch.nokillswit.infra.importing.ImportMutation
import ch.nokillswit.infra.importing.ImportMutationKind
import ch.nokillswit.infra.importing.requireBatchSize
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.repeatedValues
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.infra.validation.requireNoDocumentSourceUrl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
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
@Resource("/api/v1/entities")
class EntitiesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: EntitiesRoute = EntitiesRoute(), val id: UInt) {
        @Serializable
        @Resource("sync")
        class Sync(val parent: Id)
    }

    // A literal segment beats {id} in Ktor's route resolution (the CatalogFiles.Graph idiom).
    @Serializable
    @Resource("graph")
    class Graph(val parent: EntitiesRoute = EntitiesRoute())

    // 2.9.0: source references & HTTP re-sync — the `catalog/CatalogFiles.Fetch` twin, one
    // level down; a literal segment beats {id}, the same idiom.
    @Serializable
    @Resource("fetch")
    class Fetch(val parent: EntitiesRoute = EntitiesRoute())

    // 2.5.0: the Port-world Errors report — a second literal segment beating {id}, same idiom.
    @Serializable
    @Resource("errors")
    class Errors(val parent: EntitiesRoute = EntitiesRoute())

    @Serializable
    @Resource("import")
    class Import(val parent: EntitiesRoute = EntitiesRoute()) {
        @Serializable
        @Resource("check")
        class Check(val parent: Import = Import())
    }

    // Phase 7 (2.0.0, entity query bar): a second literal segment, the same Import.Check shape.
    @Serializable
    @Resource("query")
    class Query(val parent: EntitiesRoute = EntitiesRoute()) {
        @Serializable
        @Resource("check")
        class Check(val parent: Query = Query())
    }
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
    // Stateless, no DB — constructed here rather than in the composition root, the
    // `catalog/CatalogFileRoutes.kt` idiom (same shared `UrlFetcherKey` test seam).
    val urlFetcher by lazy { attributes.getOrNull(UrlFetcherKey) ?: UrlFetcher() }

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
                val query = call.request.queryParameters.optionalString("query")
                requireQueryLength(query)
                call.respondEntity(HttpStatusCode.OK, entityService.graph(call.entityGraphFilter(query)))
            }
            // 2.5.0: the Port-world Errors report — a pure read, never audited (the
            // `/check`/`/errors`/export rule, `.claude/docs/authorization.md`). The `query`
            // filter is deliberately absent here (the report is a workspace sweep, not a
            // traversal); [entityGraphFilter] simply reads `null` for it.
            get<EntitiesRoute.Errors> {
                val caller = call.caller()
                call.respondEntity(HttpStatusCode.OK, entityService.errors(call.entityGraphFilter(), caller.userId))
            }
            // 2.9.0: source references & HTTP re-sync — the SSRF-guarded fetch, verbatim from
            // `catalog/CatalogFileRoutes.kt` (`.claude/docs/security.md` "Outbound URL fetch").
            post<EntitiesRoute.Fetch> {
                val caller = call.caller()
                call.fetchForCaller(
                    urlFetcher,
                    blocked = AuditEvent("entity.fetch_blocked"),
                    fetched = AuditEvent("entity.fetched"),
                    byUserId = caller.userId,
                )
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
            get<EntitiesRoute.Id.Sync> { route ->
                call.caller()
                val state = entityService.syncState(route.parent.id).orNotFound("Entity")
                call.respondEntity(HttpStatusCode.OK, state)
            }
            post<EntitiesRoute.Id.Sync> { route ->
                val caller = call.caller()
                // The remote->DB sync: the client fetched the source URL (POST …/entities/fetch)
                // and parsed/decoded it (a client concern); strict, no waiver exists for
                // entities (unlike the catalog's repo sync) — a fetched copy failing
                // `entityFindings` is refused outright.
                // The service checks the row's existence FIRST, so a missing id 404s before the
                // service's OWN `entityFindings` validation would 400 (the PUT precedent above);
                // the two route-side guards below — `requireNoDocumentSourceUrl` and the
                // sanitizer — still run before the service and can answer 400 for an unknown id,
                // the same partial ordering as the PUT.
                val request = call.receive<SyncEntityRequest>()
                requireNoDocumentSourceUrl(request.document.sourceUrl)
                val document = sanitizedEntityRequest(request.document)
                val result = entityService.syncFromSource(route.parent.id, document)
                result.affected.orNotFound("Entity")
                val fields = buildList<Pair<String, Any?>> {
                    add("byUserId" to caller.userId.toLong())
                    add("entityId" to route.parent.id.toLong())
                    add("blueprint" to document.blueprint)
                    add("identifier" to document.identifier)
                    add("properties" to document.properties.size)
                    add("relations" to document.relations.size)
                    add("cascaded" to result.cascaded.size)
                    result.renamedFrom?.let { add("renamedFrom" to it) }
                }
                audit("entity.synced", *fields.toTypedArray())
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
            // Bulk import (phase 6, v1.28.0): the same shared-workspace posture as the rest of
            // this feature — no admin gate. The dry-run and the real run share ONE
            // classification (EntityImport.kt); only the real run writes and audits.
            post<EntitiesRoute.Import.Check> {
                call.caller()
                val request = call.receive<EntityImportRequest>()
                requireBatchSize(request.documents.size)
                val rows = entityService.importCheck(request.documents, request.replaceExisting)
                call.respondEntity(HttpStatusCode.OK, EntityImportResponse(rows))
            }
            post<EntitiesRoute.Import> {
                val caller = call.caller()
                val request = call.receive<EntityImportRequest>()
                requireBatchSize(request.documents.size)
                val rows = entityService.import(
                    request.documents,
                    caller.userId,
                    request.replaceExisting,
                    sourceUrl = sanitizedSourceUrl(request.sourceUrl),
                ) { mutation ->
                    auditImportedEntityMutation(caller.userId, mutation)
                }
                call.respondEntity(HttpStatusCode.OK, EntityImportResponse(rows))
            }
            // Phase 7 (2.0.0, entity query bar): the editor's live-diagnostics call — a pure
            // read (never evaluates the query), so no audit event (the `/errors`/`/check`
            // posture, `.claude/docs/authorization.md`).
            post<EntitiesRoute.Query.Check> {
                call.caller()
                val request = call.receive<EntityQueryCheckRequest>()
                requireQueryLength(request.query)
                call.respondEntity(HttpStatusCode.OK, EntityQueryCheckResponse(entityService.checkQuery(request.query)))
            }
        }
    }
}

/**
 * `blueprint`/`q`/`team` (the [EntityGraphFilter] shared filter set) shared by
 * `GET …/entities/graph` and 2.5.0's `GET …/entities/errors` (the `catalog/CatalogFileFilter.kt`
 * `catalogFileFilter()` precedent). [query] is the graph route's already-length-checked `query`
 * param — the Errors report has no such param and always passes the default `null`.
 */
private fun ApplicationCall.entityGraphFilter(query: String? = null): EntityGraphFilter = EntityGraphFilter(
    blueprints = request.queryParameters.repeatedValues("blueprint"),
    q = request.queryParameters.optionalString("q"),
    team = request.queryParameters.optionalString("team"),
    query = query,
)

/** The route-level `query` length gate, shared by the graph filter and the check body — a plain 400 before the service ever sees it. */
private fun requireQueryLength(query: String?) {
    if (query != null && query.length > MAX_QUERY_LENGTH) {
        throw BadRequestException("query must be at most $MAX_QUERY_LENGTH characters")
    }
}

/** `entity.created`/`entity.updated`, `import: true` — emitted from the committed pass-1 mutation. */
private fun auditImportedEntityMutation(callerId: UInt, mutation: ImportMutation) {
    when (mutation.kind) {
        ImportMutationKind.CREATED -> audit(
            "entity.created",
            "byUserId" to callerId.toLong(),
            "entityId" to mutation.id.toLong(),
            "blueprint" to mutation.blueprint,
            "identifier" to mutation.identifier,
            "import" to true,
        )
        ImportMutationKind.UPDATED -> audit(
            "entity.updated",
            "byUserId" to callerId.toLong(),
            "entityId" to mutation.id.toLong(),
            "blueprint" to mutation.blueprint,
            "identifier" to mutation.identifier,
            "import" to true,
        )
    }
}
