package ch.nokillswit.blueprints

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.infra.importing.requireBatchSize
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
    class Id(val parent: BlueprintsRoute = BlueprintsRoute(), val id: UInt)

    // A literal segment beats {id} in Ktor's route resolution (the CatalogFiles.Import idiom).
    @Serializable
    @Resource("import")
    class Import(val parent: BlueprintsRoute = BlueprintsRoute()) {
        @Serializable
        @Resource("check")
        class Check(val parent: Import = Import())
    }
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
                val rows = blueprintService.import(request.documents, caller.userId, request.replaceExisting)
                rows.forEach { row -> auditImportedBlueprintRow(caller.userId, row) }
                call.respondBlueprint(HttpStatusCode.OK, BlueprintImportResponse(rows))
            }
        }
    }
}

/**
 * `blueprint.created`/`blueprint.updated`, `import: true`, `system: true` on an updated system
 * row — the plain create/update audit shape, reduced.
 */
private fun auditImportedBlueprintRow(callerId: UInt, row: BlueprintImportRow) {
    val id = row.id ?: return
    val identifier = row.identifier
    when (row.status) {
        OntologyImportStatus.CREATED -> audit(
            "blueprint.created",
            "byUserId" to callerId.toLong(),
            "blueprintId" to id.toLong(),
            "identifier" to identifier,
            "import" to true,
        )
        OntologyImportStatus.UPDATED -> audit(
            "blueprint.updated",
            "byUserId" to callerId.toLong(),
            "blueprintId" to id.toLong(),
            "identifier" to identifier,
            "import" to true,
            "system" to (identifier?.let { isSystemIdentifier(it) } == true),
        )
        else -> Unit
    }
}
