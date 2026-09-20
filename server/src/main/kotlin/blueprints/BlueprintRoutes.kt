package ch.nokillswit.blueprints

import ch.nokillswit.audit.AuditEvent
import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.fetch.fetchForCaller
import ch.nokillswit.infra.fetch.UrlFetcher
import ch.nokillswit.infra.fetch.UrlFetcherKey
import ch.nokillswit.infra.fetch.sanitizedSourceUrl
import ch.nokillswit.infra.importing.ImportMutation
import ch.nokillswit.infra.importing.ImportMutationKind
import ch.nokillswit.infra.importing.requireBatchSize
import ch.nokillswit.infra.validation.requireNoDocumentSourceUrl
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
@Resource("/api/v1/blueprints")
class BlueprintsRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: BlueprintsRoute = BlueprintsRoute(), val id: UInt) {
        @Serializable
        @Resource("sync")
        class Sync(val parent: Id)
    }

    // A literal segment beats {id} in Ktor's route resolution (the CatalogFiles.Import idiom).
    @Serializable
    @Resource("import")
    class Import(val parent: BlueprintsRoute = BlueprintsRoute()) {
        @Serializable
        @Resource("check")
        class Check(val parent: Import = Import())
    }

    // 2.10.0: source references & HTTP re-sync — the `entities/EntitiesRoute.Fetch` twin, one
    // level up; a literal segment beats {id}, the same idiom.
    @Serializable
    @Resource("fetch")
    class Fetch(val parent: BlueprintsRoute = BlueprintsRoute())
}

/**
 * Encodes with [blueprintJson] via [TextContent] (the `respondProblem` precedent,
 * `plugins/ErrorHandling.kt:48`) so unset optional fields are ABSENT — the ordinary
 * `call.respond` path goes through `DefaultJson` (`explicitNulls = true`), which would
 * serialize them as explicit `null` and break the Port-shape round trip a strict consumer
 * relies on.
 */
private suspend inline fun <reified T> ApplicationCall.respondBlueprint(status: HttpStatusCode, body: T) {
    respond(TextContent(blueprintJson.encodeToString(body), ContentType.Application.Json.withCharset(Charsets.UTF_8), status))
}

fun Application.configureBlueprintRoutes() {
    val blueprintService = attributes[BlueprintServiceKey]
    // Stateless, no DB — constructed here rather than in the composition root, the
    // `entities/EntityRoutes.kt` idiom (same shared `UrlFetcherKey` test seam).
    val urlFetcher by lazy { attributes.getOrNull(UrlFetcherKey) ?: UrlFetcher() }

    routing {
        authenticate {
            // Reads are any-authenticated (the registries' posture); every mutation is
            // ADMIN-only, guarded BEFORE receive/lookup (a non-admin probe gets a uniform 403
            // whether or not the id exists — the guard-before-read idiom).
            get<BlueprintsRoute> {
                call.caller()
                call.respondBlueprint(HttpStatusCode.OK, BlueprintList(items = blueprintService.list()))
            }
            get<BlueprintsRoute.Id> { route ->
                call.caller()
                val blueprint = blueprintService.read(route.id).orNotFound("Blueprint")
                call.respondBlueprint(HttpStatusCode.OK, blueprint)
            }
            // 2.10.0: source references & HTTP re-sync — the SSRF-guarded fetch, verbatim from
            // `entities/EntityRoutes.kt` (`.claude/docs/security.md` "Outbound URL fetch"), but
            // ADMIN-only (writes to this registry already are) and guarded BEFORE receive.
            post<BlueprintsRoute.Fetch> {
                val caller = call.caller()
                requireAdmin(caller)
                call.fetchForCaller(
                    urlFetcher,
                    blocked = AuditEvent("blueprint.fetch_blocked"),
                    fetched = AuditEvent("blueprint.fetched"),
                    byUserId = caller.userId,
                )
            }
            // GET: any authenticated user (whoever reads a blueprint reads its sync state).
            get<BlueprintsRoute.Id.Sync> { route ->
                call.caller()
                val state = blueprintService.syncState(route.parent.id).orNotFound("Blueprint")
                call.respondBlueprint(HttpStatusCode.OK, state)
            }
            // POST: ADMIN (writes to this registry already are), guarded BEFORE receive.
            post<BlueprintsRoute.Id.Sync> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                // The remote->DB sync: the client fetched the source URL (POST …/blueprints/fetch)
                // and parsed/decoded it (a client concern); strict, no waiver exists for
                // blueprints (unlike the catalog's repo sync) — a fetched copy failing
                // validation is refused outright.
                // The service checks the row's existence FIRST, so a missing id 404s before the
                // service's OWN validation would 400 (the PUT precedent above); the two
                // route-side guards below — `requireNoDocumentSourceUrl` and the sanitizer —
                // still run before the service and can answer 400 for an unknown id, the same
                // partial ordering as the PUT.
                val request = call.receive<SyncBlueprintRequest>()
                requireNoDocumentSourceUrl(request.document.sourceUrl)
                val document = sanitizedBlueprintRequest(request.document)
                val result = blueprintService.syncFromSource(route.parent.id, document)
                result.affected.orNotFound("Blueprint")
                val fields = buildList<Pair<String, Any?>> {
                    add("byUserId" to caller.userId.toLong())
                    add("blueprintId" to route.parent.id.toLong())
                    add("identifier" to document.identifier)
                    add("properties" to document.schema.properties.size)
                    add("relations" to document.relations.size)
                    add("cascaded" to result.cascaded.size)
                    add("system" to result.system)
                    result.renamedFrom?.let { add("renamedFrom" to it) }
                }
                audit("blueprint.synced", *fields.toTypedArray())
                call.respond(HttpStatusCode.NoContent)
            }
            post<BlueprintsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedBlueprintRequest(call.receive())
                validateBlueprintRequest(request)
                val created = blueprintService.create(request, caller.userId)
                audit(
                    "blueprint.created",
                    "byUserId" to caller.userId.toLong(),
                    "blueprintId" to created.id.toLong(),
                    "identifier" to created.identifier,
                    "properties" to created.schema.properties.size,
                    "relations" to created.relations.size,
                )
                call.response.header(HttpHeaders.Location, call.application.href(BlueprintsRoute.Id(id = created.id)))
                call.respondBlueprint(HttpStatusCode.Created, created)
            }
            put<BlueprintsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                // No route-side validateBlueprintRequest here: the service checks the row's
                // existence FIRST so a missing id 404s before an invalid body would 400 (the
                // LensRoutes PUT precedent), then validates inside the same transaction.
                val request = sanitizedBlueprintRequest(call.receive())
                val result = blueprintService.update(route.id, request)
                result.affected.orNotFound("Blueprint")
                val fields = buildList<Pair<String, Any?>> {
                    add("byUserId" to caller.userId.toLong())
                    add("blueprintId" to route.id.toLong())
                    add("identifier" to request.identifier)
                    add("properties" to request.schema.properties.size)
                    add("relations" to request.relations.size)
                    add("cascaded" to result.cascaded.size)
                    add("system" to result.system)
                    result.renamedFrom?.let { add("renamedFrom" to it) }
                }
                audit("blueprint.updated", *fields.toTypedArray())
                call.respond(HttpStatusCode.NoContent)
            }
            delete<BlueprintsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val result = blueprintService.delete(route.id)
                result.affected.orNotFound("Blueprint")
                audit(
                    "blueprint.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "blueprintId" to route.id.toLong(),
                    "identifier" to result.identifier,
                )
                call.respond(HttpStatusCode.NoContent)
            }
            // Bulk import (phase 6, v1.28.0): ADMIN only, guarded BEFORE receive (a non-admin
            // probe never even decodes the body). The dry-run and the real run share ONE
            // classification (BlueprintImport.kt); only the real run writes and audits.
            post<BlueprintsRoute.Import.Check> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = call.receive<BlueprintImportRequest>()
                requireBatchSize(request.documents.size)
                val rows = blueprintService.importCheck(request.documents, request.replaceExisting)
                call.respondBlueprint(HttpStatusCode.OK, BlueprintImportResponse(rows))
            }
            post<BlueprintsRoute.Import> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = call.receive<BlueprintImportRequest>()
                requireBatchSize(request.documents.size)
                val rows = blueprintService.import(
                    request.documents,
                    caller.userId,
                    request.replaceExisting,
                    sourceUrl = sanitizedSourceUrl(request.sourceUrl),
                ) { mutation ->
                    auditImportedBlueprintMutation(caller.userId, mutation)
                }
                call.respondBlueprint(HttpStatusCode.OK, BlueprintImportResponse(rows))
            }
        }
    }
}

/**
 * `blueprint.created`/`blueprint.updated`, `import: true`, `system: true` on an updated system
 * row — the plain create/update audit shape, emitted from the committed pass-1 mutation.
 */
private fun auditImportedBlueprintMutation(callerId: UInt, mutation: ImportMutation) {
    when (mutation.kind) {
        ImportMutationKind.CREATED -> audit(
            "blueprint.created",
            "byUserId" to callerId.toLong(),
            "blueprintId" to mutation.id.toLong(),
            "identifier" to mutation.identifier,
            "import" to true,
        )
        ImportMutationKind.UPDATED -> audit(
            "blueprint.updated",
            "byUserId" to callerId.toLong(),
            "blueprintId" to mutation.id.toLong(),
            "identifier" to mutation.identifier,
            "import" to true,
            "system" to mutation.system,
        )
    }
}
