package ch.nokillswit.integration

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.entities.EntityImportRow
import ch.nokillswit.entities.auditImportedEntityMutation
import ch.nokillswit.entities.findByIdentity
import ch.nokillswit.entities.import
import ch.nokillswit.infra.importing.MAX_IMPORT_DOCUMENTS
import ch.nokillswit.infra.importing.OntologyImportStatus
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The three WRITE tools — registered for EVERY integration key (2.15.0+) so `tools/list` always
 * shows the full ten-tool catalogue, but refused with `FORBIDDEN` inside `guarded`'s `write = true`
 * gate unless the caller's key carries `write` scope (`.claude/docs/authorization.md` "Machine
 * integration clients"). Every write is attributed to the client's paired service account
 * ([ch.nokillswit.integration.writerId], V41), never to a human `users` row, and reuses the
 * ordinary [ch.nokillswit.entities.EntityService] entry points (`import`/`delete`) so it runs
 * under the SAME V28 lock protocol, findings rules, and ontology-revision bump as the REST
 * surface — there is no separate write path for MCP.
 */
internal fun Server.registerWriteTools(context: McpToolContext) {
    addUpsertEntity(context)
    addImportEntities(context)
    addDeleteEntity(context)
}

private fun Server.addUpsertEntity(context: McpToolContext) {
    addTool(
        name = "upsert_entity",
        description = "Create or replace one Port entity document — create if new, replace in place if its " +
            "(blueprint, identifier) already exists.",
        inputSchema = objectSchema(
            mapOf(
                "document" to objectProp("The Port entity document, including its blueprint identifier"),
                "sourceUrl" to stringProp("An optional https source reference to stamp on the stored row"),
            ),
            required = listOf("document"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    ) { request ->
        context.guarded("upsert_entity", write = true) {
            val document = request.requireObject("document")
            val sourceUrl = request.argString("sourceUrl")
            val callerId = context.principal.writerId()
            val clientId = context.principal.clientId.toLong()
            val rows = context.services.entities.import(
                listOf(document), callerId, replaceExisting = true, sourceUrl = sourceUrl,
            ) { mutation -> auditImportedEntityMutation(callerId, mutation, "clientId" to clientId) }
            entityUpsertResult(rows.single())
        }
    }
}

private fun entityUpsertResult(row: EntityImportRow): CallToolResult = when (row.status) {
    OntologyImportStatus.CREATED, OntologyImportStatus.UPDATED -> toolResult(
        buildJsonObject {
            row.id?.let { put("id", it.toString()) }
            row.blueprint?.let { put("blueprint", it) }
            row.identifier?.let { put("identifier", it) }
            put("status", row.status.name)
            row.findings?.let { put("findings", findingsJson(it)) }
        },
    )
    else -> toolError(
        row.status.name,
        row.message ?: "The entity was not stored",
        buildJsonObject { row.findings?.let { put("findings", findingsJson(it)) } },
    )
}

private fun Server.addImportEntities(context: McpToolContext) {
    addTool(
        name = "import_entities",
        description = "Bulk create/replace up to 200 Port entity documents in one call — report-and-skip, never all-or-nothing.",
        inputSchema = objectSchema(
            mapOf(
                "documents" to arrayOfObjectsProp(
                    "1-200 Port entity documents, each including its blueprint identifier",
                    1,
                    MAX_IMPORT_DOCUMENTS,
                ),
                "replaceExisting" to boolProp(
                    "Treat an existing (blueprint, identifier) document as an update rather than EXISTS (default false)",
                ),
                "sourceUrl" to stringProp("An optional https source reference stamped on every CREATED/UPDATED row"),
            ),
            required = listOf("documents"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    ) { request ->
        context.guarded("import_entities", write = true) {
            val documents = request.argObjects("documents", 1, MAX_IMPORT_DOCUMENTS)
            val replaceExisting = request.argBool("replaceExisting")
            val sourceUrl = request.argString("sourceUrl")
            val callerId = context.principal.writerId()
            val rows = context.services.entities.import(documents, callerId, replaceExisting, sourceUrl) { mutation ->
                auditImportedEntityMutation(callerId, mutation, "clientId" to context.principal.clientId.toLong())
            }
            toolResult(importResultJson(rows))
        }
    }
}

private fun Server.addDeleteEntity(context: McpToolContext) {
    addTool(
        name = "delete_entity",
        description = "Delete one Port entity by blueprint + identifier — refused (CONFLICT) while another entity still references it.",
        inputSchema = objectSchema(
            mapOf(
                "blueprint" to stringProp("The owning blueprint's identifier"),
                "identifier" to stringProp("The entity's identifier"),
            ),
            required = listOf("blueprint", "identifier"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false),
    ) { request ->
        context.guarded("delete_entity", write = true) {
            val blueprint = request.requireString("blueprint")
            val identifier = request.requireString("identifier")
            val callerId = context.principal.writerId()
            val id = context.services.entities.findByIdentity(blueprint, identifier).orNotFound("Entity")
            val result = context.services.entities.delete(id)
            result.affected.orNotFound("Entity")
            audit(
                "entity.deleted",
                "byUserId" to callerId.toLong(),
                "entityId" to id.toLong(),
                "blueprint" to result.blueprint,
                "identifier" to result.identifier,
                "clientId" to context.principal.clientId.toLong(),
            )
            toolResult(
                buildJsonObject {
                    put("id", id.toString())
                    put("blueprint", result.blueprint)
                    put("identifier", result.identifier)
                    put("deleted", true)
                },
            )
        }
    }
}
