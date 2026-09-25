package ch.nokillswit.integration

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.BlueprintErrorRow
import ch.nokillswit.entities.EntityErrorRow
import ch.nokillswit.entities.EntityFilter
import ch.nokillswit.entities.EntityGraphFilter
import ch.nokillswit.entities.EntityImportRow
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.entities.OntologyReadBudget
import ch.nokillswit.entities.findByIdentity
import ch.nokillswit.entities.importCheck
import ch.nokillswit.entities.ontologyErrors
import ch.nokillswit.entities.ontologyRevision
import ch.nokillswit.infra.importing.MAX_IMPORT_DOCUMENTS
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.infra.paging.DEFAULT_PAGE_SIZE
import ch.nokillswit.infra.paging.MAX_PAGE_SIZE
import ch.nokillswit.infra.paging.SortField
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The seven READ tools every integration key (read OR write scope) sees, registered on the
 * per-request [Server] by `integration/Mcp.kt`. Every tool body runs under its own
 * [MCP_READ_TOOL_TIMEOUT_MILLIS] deadline (`ch.nokillswit.integration.guarded` maps a timed-out
 * body to the `TIMEOUT` tool error), and returns through [McpToolContext.guarded] — the ONE
 * exception→result mapper.
 */
internal fun Server.registerReadTools(context: McpToolContext) {
    addListBlueprints(context)
    addGetBlueprint(context)
    addListEntities(context)
    addGetEntity(context)
    addOntologyErrors(context)
    addCheckEntities(context)
    addGetOntologyRevision(context)
}

private fun Server.addListBlueprints(context: McpToolContext) {
    addTool(
        name = "list_blueprints",
        description = "List Port blueprints (paged).",
        inputSchema = objectSchema(
            mapOf(
                "page" to intProp("1-based page number (default 1)", 1, Int.MAX_VALUE),
                "pageSize" to intProp("Page size 1-100 (default 20)", 1, MAX_PAGE_SIZE),
                "full" to boolProp("Return each blueprint's full Port definition instead of the summary shape (default false)"),
            ),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true),
    ) { request ->
        context.guarded("list_blueprints") {
            val paging = request.mcpPageRequest(listOf(SortField("id", false)))
            val full = request.argBool("full")
            val result = withTimeout(MCP_READ_TOOL_TIMEOUT_MILLIS) { context.services.blueprints.listPage(paging) }
            val items = result.items.map { if (full) blueprintFullJson(it) else blueprintSummaryJson(it) }
            toolResult(
                buildJsonObject {
                    put("items", JsonArray(items))
                    put("page", paging.page)
                    put("pageSize", paging.pageSize)
                    put("total", result.total)
                    put("revision", result.revision.toString())
                },
            )
        }
    }
}

private fun Server.addGetBlueprint(context: McpToolContext) {
    addTool(
        name = "get_blueprint",
        description = "Get one Port blueprint's full definition by identifier.",
        inputSchema = objectSchema(
            mapOf("identifier" to stringProp("The blueprint's identifier")),
            required = listOf("identifier"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true),
    ) { request ->
        context.guarded("get_blueprint") {
            val identifier = request.requireString("identifier")
            val blueprint = withTimeout(MCP_READ_TOOL_TIMEOUT_MILLIS) {
                context.services.blueprints.findByIdentifier(identifier, OntologyReadBudget())
            } ?: throw NotFoundException("Blueprint not found")
            toolResult(blueprintFullJson(blueprint))
        }
    }
}

private fun Server.addListEntities(context: McpToolContext) {
    addTool(
        name = "list_entities",
        description = "List Port entities (paged), optionally filtered by blueprint/team/free text.",
        inputSchema = objectSchema(
            mapOf(
                "blueprint" to stringProp("Exact, case-insensitive blueprint identifier filter"),
                "q" to stringProp("Case- and accent-insensitive substring filter over identifier/title"),
                "team" to stringProp("Effective team filter (Direct, Inherited, or the _team entity's own identifier)"),
                "page" to intProp("1-based page number (default 1)", 1, Int.MAX_VALUE),
                "pageSize" to intProp("Page size 1-100 (default 20)", 1, MAX_PAGE_SIZE),
                "includeProperties" to boolProp("Include each entity's stored+computed properties (default false)"),
            ),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true),
    ) { request ->
        context.guarded("list_entities") {
            val filter = EntityFilter(
                blueprint = request.argString("blueprint"),
                q = request.argString("q"),
                team = request.argString("team"),
            )
            val paging = request.mcpPageRequest(listOf(SortField("identifier", false), SortField("id", false)))
            val includeProperties = request.argBool("includeProperties")
            val result = withTimeout(MCP_READ_TOOL_TIMEOUT_MILLIS) {
                context.services.entities.list(filter, paging, OntologyReadBudget())
            }
            toolResult(
                buildJsonObject {
                    put("items", JsonArray(result.items.map { entitySummaryJson(it, includeProperties) }))
                    put("page", paging.page)
                    put("pageSize", paging.pageSize)
                    put("total", result.total)
                    put("revision", result.revision.toString())
                },
            )
        }
    }
}

private fun Server.addGetEntity(context: McpToolContext) {
    addTool(
        name = "get_entity",
        description = "Get one Port entity's full document by blueprint + identifier.",
        inputSchema = objectSchema(
            mapOf(
                "blueprint" to stringProp("The owning blueprint's identifier"),
                "identifier" to stringProp("The entity's identifier"),
            ),
            required = listOf("blueprint", "identifier"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true),
    ) { request ->
        context.guarded("get_entity") {
            val blueprint = request.requireString("blueprint")
            val identifier = request.requireString("identifier")
            val entity = withTimeout(MCP_READ_TOOL_TIMEOUT_MILLIS) {
                val id = context.services.entities.findByIdentity(blueprint, identifier) ?: throw NotFoundException("Entity not found")
                context.services.entities.read(id, OntologyReadBudget())
            } ?: throw NotFoundException("Entity not found")
            toolResult(blueprintJson.encodeToJsonElement(EntityResponse.serializer(), entity) as JsonObject)
        }
    }
}

private fun Server.addOntologyErrors(context: McpToolContext) {
    addTool(
        name = "ontology_errors",
        description = "Report stale/broken entities and blueprints (paged in memory over the full report).",
        inputSchema = objectSchema(
            mapOf(
                "blueprint" to stringProp("Narrow both reported entity and blueprint rows to this blueprint identifier"),
                "page" to intProp("1-based page number (default 1)", 1, Int.MAX_VALUE),
                "pageSize" to intProp("Page size 1-100 (default 20)", 1, MAX_PAGE_SIZE),
            ),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true),
    ) { request ->
        context.guarded("ontology_errors") {
            val blueprint = request.argString("blueprint")
            val page = request.argInt("page", 1, 1, Int.MAX_VALUE)
            val pageSize = request.argInt("pageSize", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE)
            val filter = EntityGraphFilter(blueprints = listOfNotNull(blueprint), q = null)
            val report = withTimeout(MCP_READ_TOOL_TIMEOUT_MILLIS) {
                context.services.entities.ontologyErrors(filter, OntologyReadBudget())
            }
            val (entityItems, entityTotal) = mcpInMemoryPage(report.entities, page, pageSize)
            val (blueprintItems, blueprintTotal) = mcpInMemoryPage(report.blueprints, page, pageSize)
            toolResult(
                buildJsonObject {
                    put("revision", report.revision.toString())
                    put("checkedEntities", report.checkedEntities)
                    put("checkedBlueprints", report.checkedBlueprints)
                    put(
                        "entities",
                        buildJsonObject {
                            put("items", JsonArray(entityItems.map(::entityErrorJson)))
                            put("total", entityTotal)
                        },
                    )
                    put(
                        "blueprints",
                        buildJsonObject {
                            put("items", JsonArray(blueprintItems.map(::blueprintErrorJson)))
                            put("total", blueprintTotal)
                        },
                    )
                },
            )
        }
    }
}

private fun Server.addCheckEntities(context: McpToolContext) {
    addTool(
        name = "check_entities",
        description = "Dry-run validate a batch of Port entity documents without storing anything.",
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
            ),
            required = listOf("documents"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true),
    ) { request ->
        context.guarded("check_entities") {
            val documents = request.argObjects("documents", 1, MAX_IMPORT_DOCUMENTS)
            val replaceExisting = request.argBool("replaceExisting")
            val rows = withTimeout(MCP_READ_TOOL_TIMEOUT_MILLIS) { context.services.entities.importCheck(documents, replaceExisting) }
            toolResult(importResultJson(rows))
        }
    }
}

private fun Server.addGetOntologyRevision(context: McpToolContext) {
    addTool(
        name = "get_ontology_revision",
        description = "Get the monotonic ontology-revision counter (V39) — bumps on every committed blueprint/entity write.",
        inputSchema = objectSchema(emptyMap()),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true),
    ) { _ ->
        context.guarded("get_ontology_revision") {
            val revision = withTimeout(MCP_READ_TOOL_TIMEOUT_MILLIS) { context.services.entities.ontologyRevision() }
            toolResult(buildJsonObject { put("revision", revision.toString()) })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// JSON shaping — plain functions over the existing response DTOs, no database.
// ---------------------------------------------------------------------------------------------

private fun blueprintFullJson(value: BlueprintResponse): JsonObject =
    blueprintJson.encodeToJsonElement(BlueprintResponse.serializer(), value) as JsonObject

private fun blueprintSummaryJson(value: BlueprintResponse): JsonObject = buildJsonObject {
    put("id", value.id.toString())
    put("identifier", value.identifier)
    put("title", value.title)
    value.description?.let { put("description", it) }
    put("system", value.system)
    put(
        "properties",
        JsonArray(
            value.schema.properties.map { (id, property) ->
                buildJsonObject {
                    put("id", id)
                    put("type", property.type)
                    property.format?.let { put("format", it) }
                    property.enum?.let { put("enum", JsonArray(it)) }
                    put("required", id in value.schema.required)
                }
            },
        ),
    )
    put(
        "relations",
        JsonArray(
            value.relations.map { (id, relation) ->
                buildJsonObject {
                    put("id", id)
                    put("target", relation.target)
                    put("many", relation.many)
                    put("required", relation.required)
                }
            },
        ),
    )
    value.ownership?.let { ownership ->
        put(
            "ownership",
            buildJsonObject {
                put("type", ownership.type)
                ownership.title?.let { put("title", it) }
                ownership.path?.let { put("path", it) }
            },
        )
    }
    value.hierarchyRelations?.let { hierarchyRelations ->
        put("hierarchyRelations", JsonObject(hierarchyRelations.mapValues { JsonPrimitive(it.value) }))
    }
}

private fun entitySummaryJson(value: EntityResponse, includeProperties: Boolean): JsonObject = buildJsonObject {
    put("id", value.id.toString())
    put("blueprint", value.blueprint)
    put("identifier", value.identifier)
    put("title", value.title)
    value.team?.let { put("team", it) }
    put("relations", value.relations)
    put("updatedAt", value.updatedAt)
    value.sourceUrl?.let { put("sourceUrl", it) }
    put("findingsCount", value.findings.size)
    if (includeProperties) put("properties", value.properties)
}

private fun entityErrorJson(row: EntityErrorRow): JsonObject = buildJsonObject {
    put("id", row.id.toString())
    put("blueprintId", row.blueprintId.toString())
    put("blueprint", row.blueprint)
    put("blueprintTitle", row.blueprintTitle)
    put("identifier", row.identifier)
    put("title", row.title)
    put("team", JsonArray(row.team.map(::JsonPrimitive)))
    put("findings", findingsJson(row.findings))
}

private fun blueprintErrorJson(row: BlueprintErrorRow): JsonObject = buildJsonObject {
    put("id", row.id.toString())
    put("identifier", row.identifier)
    put("title", row.title)
    put("findings", findingsJson(row.findings))
}

internal fun importResultJson(rows: List<EntityImportRow>): JsonObject {
    val summary = rows.groupingBy { it.status }.eachCount()
    return buildJsonObject {
        put("results", JsonArray(rows.map(::entityImportRowJson)))
        put("summary", buildJsonObject { OntologyImportStatus.entries.forEach { put(it.name, summary[it] ?: 0) } })
    }
}

private fun entityImportRowJson(row: EntityImportRow): JsonObject = buildJsonObject {
    put("index", row.index)
    row.blueprint?.let { put("blueprint", it) }
    row.identifier?.let { put("identifier", it) }
    put("status", row.status.name)
    row.id?.let { put("id", it.toString()) }
    row.message?.let { put("message", it) }
    row.findings?.let { put("findings", findingsJson(it)) }
}
