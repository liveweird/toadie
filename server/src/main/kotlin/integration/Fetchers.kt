package ch.nokillswit.integration

import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintService
import ch.nokillswit.entities.BlueprintErrorRow
import ch.nokillswit.entities.EntityErrorRow
import ch.nokillswit.entities.EntityFilter
import ch.nokillswit.entities.EntityGraphFilter
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.entities.EntityService
import ch.nokillswit.entities.OntologyErrorsReport
import ch.nokillswit.entities.OntologyReadBudget
import ch.nokillswit.entities.ontologyErrors
import ch.nokillswit.infra.paging.DEFAULT_PAGE_SIZE
import ch.nokillswit.infra.paging.MAX_PAGE_SIZE
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.TypeRuntimeWiring
import io.ktor.server.plugins.BadRequestException
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.future.future
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

internal const val SCOPE_CONTEXT_KEY = "toadie.integration.scope"
internal const val MEMO_CONTEXT_KEY = "toadie.integration.memo"

internal class IntegrationServices(
    val blueprints: BlueprintService,
    val entities: EntityService,
)

internal class RequestMemo(
    private val scope: CoroutineScope,
    private val retainedReservation: IntegrationRetainedLedger.Reservation,
) {
    private val values = mutableMapOf<RootRequestKey, Deferred<Any?>>()
    private val loadMutex = Mutex()
    private var retainedBytes = 0

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: RootRequestKey, load: suspend () -> T): T {
        val deferred = synchronized(values) {
            values[key] ?: run {
                if (values.size >= MAX_DISTINCT_EXPENSIVE_ROOTS) {
                    throw BadRequestException("Too many distinct root reads")
                }
                scope.async {
                    loadMutex.withLock {
                        val loaded = load()
                        reserveRetained(loaded)
                        loaded
                    }
                }.also { values[key] = it }
            }
        }
        return deferred.await() as T
    }

    private fun reserveRetained(value: Any?) {
        val tree = (value as? RetainedGraphQLValue)?.retentionTree ?: value
        retainedReservation.charge(tree)
        val remaining = MAX_RESPONSE_BYTES - synchronized(values) { retainedBytes }
        val encoded = boundedGraphQLJson(tree, remaining)
            ?: throw BadRequestException("Root result exceeds the response memory limit")
        synchronized(values) { retainedBytes += encoded.utf8Size() }
    }
}

internal sealed interface RootRequestKey {
    data class Blueprints(val page: Int, val pageSize: Int) : RootRequestKey
    data class Blueprint(val id: UInt) : RootRequestKey
    data class Entities(val page: Int, val pageSize: Int, val blueprint: String?, val q: String?, val team: String?) : RootRequestKey
    data class Entity(val id: UInt) : RootRequestKey
    data class Errors(
        val blueprints: List<String>,
        val q: String?,
        val team: String?,
        val entityPages: Set<PageKey>,
        val blueprintPages: Set<PageKey>,
    ) : RootRequestKey
}

internal data class PageKey(val page: Int, val pageSize: Int)

private interface RetainedGraphQLValue {
    val retentionTree: Any?
}

private data class ErrorReportView(
    val entityPages: Map<PageKey, Map<String, Any?>>,
    val blueprintPages: Map<PageKey, Map<String, Any?>>,
    val checkedEntities: Int,
    val checkedBlueprints: Int,
    /** The V39 ontology counter, as a decimal string — the `Blueprint`/`Entity` `id` idiom. */
    val revision: String,
) : RetainedGraphQLValue {
    override val retentionTree: Any? = mapOf(
        "entityPages" to entityPages.values,
        "blueprintPages" to blueprintPages.values,
        "checkedEntities" to checkedEntities,
        "checkedBlueprints" to checkedBlueprints,
        "revision" to revision,
    )
}

internal fun <T> suspendFetcher(block: suspend (DataFetchingEnvironment) -> T): DataFetcher<CompletableFuture<T>> =
    DataFetcher { environment ->
        val scope = checkNotNull(environment.graphQlContext.get<CoroutineScope>(SCOPE_CONTEXT_KEY))
        scope.future { block(environment) }
    }

internal fun TypeRuntimeWiring.Builder.queryFetchers(services: IntegrationServices): TypeRuntimeWiring.Builder = this
    .dataFetcher("blueprints", suspendFetcher { environment ->
        val paging = environment.pageRequest()
        environment.memo().get(RootRequestKey.Blueprints(paging.page, paging.pageSize)) {
            val result = services.blueprints.listPage(paging)
            pageEnvelope(result.items.map(::blueprintMap), paging, result.total) + ("revision" to result.revision.toString())
        }
    })
    .dataFetcher("blueprint", suspendFetcher { environment ->
        val id = environment.uintId("id")
        environment.memo().get(RootRequestKey.Blueprint(id)) {
            services.blueprints.read(id, OntologyReadBudget())?.let(::blueprintMap)
        }
    })
    .dataFetcher("entities", suspendFetcher { environment ->
        val paging = environment.pageRequest()
        val filter = EntityFilter(
            blueprint = environment.optionalString("blueprint"),
            q = environment.optionalString("q"),
            team = environment.optionalString("team"),
        )
        val key = RootRequestKey.Entities(paging.page, paging.pageSize, filter.blueprint, filter.q, filter.team)
        environment.memo().get(key) {
            val result = services.entities.list(filter, paging, OntologyReadBudget())
            pageEnvelope(result.items.map(::entityMap), paging, result.total) + ("revision" to result.revision.toString())
        }
    })
    .dataFetcher("entity", suspendFetcher { environment ->
        val id = environment.uintId("id")
        environment.memo().get(RootRequestKey.Entity(id)) {
            services.entities.read(id, OntologyReadBudget())?.let(::entityMap)
        }
    })
    .dataFetcher("errors", suspendFetcher { environment ->
        val filter = EntityGraphFilter(
            blueprints = environment.getArgument<List<String>>("blueprints").orEmpty().filter { it.isNotBlank() },
            q = environment.optionalString("q"),
            team = environment.optionalString("team"),
        )
        val entityPages = environment.selectedPages("entities")
        val blueprintPages = environment.selectedPages("blueprints")
        val key = RootRequestKey.Errors(filter.blueprints, filter.q, filter.team, entityPages, blueprintPages)
        environment.memo().get(key) {
            services.entities.ontologyErrors(filter, OntologyReadBudget()).toView(entityPages, blueprintPages)
        }
    })

internal fun TypeRuntimeWiring.Builder.errorReportFetchers(): TypeRuntimeWiring.Builder = this
    .dataFetcher("entities") { environment ->
        val report = checkNotNull(environment.getSource<ErrorReportView>())
        checkNotNull(report.entityPages[environment.pageRequest().toPageKey()])
    }
    .dataFetcher("blueprints") { environment ->
        val report = checkNotNull(environment.getSource<ErrorReportView>())
        checkNotNull(report.blueprintPages[environment.pageRequest().toPageKey()])
    }
    .dataFetcher("checkedEntities") { environment -> checkNotNull(environment.getSource<ErrorReportView>()).checkedEntities }
    .dataFetcher("checkedBlueprints") { environment -> checkNotNull(environment.getSource<ErrorReportView>()).checkedBlueprints }
    .dataFetcher("revision") { environment -> checkNotNull(environment.getSource<ErrorReportView>()).revision }

internal fun DataFetchingEnvironment.pageRequest(): PageRequest {
    val page = getArgument<Int>("page") ?: 1
    val pageSize = getArgument<Int>("pageSize") ?: DEFAULT_PAGE_SIZE
    if (page < 1) throw BadRequestException("page must be >= 1")
    if (pageSize !in 1..MAX_PAGE_SIZE) throw BadRequestException("pageSize must be between 1 and $MAX_PAGE_SIZE")
    return PageRequest(page, pageSize, listOf(SortField("id", descending = false)))
}

private fun DataFetchingEnvironment.uintId(name: String): UInt {
    val raw = getArgument<String>(name)
    return raw?.toUIntOrNull() ?: throw BadRequestException("$name must be an unsigned decimal identifier")
}

private fun DataFetchingEnvironment.optionalString(name: String): String? =
    getArgument<String>(name)?.takeIf { it.isNotBlank() }

private fun DataFetchingEnvironment.memo(): RequestMemo =
    checkNotNull(graphQlContext.get<RequestMemo>(MEMO_CONTEXT_KEY))

private fun DataFetchingEnvironment.selectedPages(fieldName: String): Set<PageKey> =
    selectionSet.getImmediateFields().filter { it.name == fieldName }.map { field ->
        PageKey(
            page = (field.arguments["page"] as? Int) ?: 1,
            pageSize = (field.arguments["pageSize"] as? Int) ?: DEFAULT_PAGE_SIZE,
        ).also {
            if (it.page < 1) throw BadRequestException("page must be >= 1")
            if (it.pageSize !in 1..MAX_PAGE_SIZE) {
                throw BadRequestException("pageSize must be between 1 and $MAX_PAGE_SIZE")
            }
        }
    }.toSet()

private fun pageEnvelope(
    items: List<Map<String, Any?>>,
    paging: PageRequest,
    total: Long,
): Map<String, Any?> = mapOf("items" to items, "page" to paging.page, "pageSize" to paging.pageSize, "total" to total)

private fun <T> inMemoryPage(items: List<T>, paging: PageRequest, mapper: (T) -> Map<String, Any?>): Map<String, Any?> {
    val offset = ((paging.page - 1).toLong() * paging.pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val end = (offset.toLong() + paging.pageSize).coerceAtMost(items.size.toLong()).toInt()
    val pageItems = if (offset >= items.size) emptyList() else items.subList(offset, end).map(mapper)
    return pageEnvelope(pageItems, paging, items.size.toLong())
}

private fun PageRequest.toPageKey() = PageKey(page, pageSize)

private fun OntologyErrorsReport.toView(entityPages: Set<PageKey>, blueprintPages: Set<PageKey>) = ErrorReportView(
    entityPages = entityPages.associateWith { key ->
        val paging = PageRequest(key.page, key.pageSize, listOf(SortField("id", false)))
        inMemoryPage(entities, paging, ::entityErrorMap)
    },
    blueprintPages = blueprintPages.associateWith { key ->
        val paging = PageRequest(key.page, key.pageSize, listOf(SortField("id", false)))
        inMemoryPage(blueprints, paging, ::blueprintErrorMap)
    },
    checkedEntities = checkedEntities,
    checkedBlueprints = checkedBlueprints,
    revision = revision.toString(),
)

private val mappingJson = Json { encodeDefaults = true; explicitNulls = false }

private fun BlueprintResponse.jsonFields(): Map<String, Any?> = mapOf(
    "schema" to mappingJson.encodeToJsonElement(schema),
    "relations" to mappingJson.encodeToJsonElement(relations),
    "mirrorProperties" to mappingJson.encodeToJsonElement(mirrorProperties),
    "calculationProperties" to mappingJson.encodeToJsonElement(calculationProperties),
    "aggregationProperties" to mappingJson.encodeToJsonElement(aggregationProperties),
    "ownership" to ownership?.let { mappingJson.encodeToJsonElement(it) },
    "hierarchyRelations" to hierarchyRelations?.let { mappingJson.encodeToJsonElement(it) },
)

private fun blueprintMap(value: BlueprintResponse): Map<String, Any?> = mapOf(
    "id" to value.id.toString(), "identifier" to value.identifier, "title" to value.title,
    "description" to value.description, "icon" to value.icon,
    "createdBy" to value.createdBy.toString(), "creatorName" to value.creatorName,
    "creatorDeleted" to value.creatorDeleted, "createdAt" to value.createdAt,
    "updatedAt" to value.updatedAt, "system" to value.system,
) + value.jsonFields()

private fun entityMap(value: EntityResponse): Map<String, Any?> = mapOf(
    "id" to value.id.toString(), "blueprint" to value.blueprint, "blueprintId" to value.blueprintId.toString(),
    "identifier" to value.identifier, "title" to value.title, "icon" to value.icon,
    "team" to value.team, "properties" to value.properties, "relations" to value.relations,
    "findings" to value.findings.map { findingMap(it.code, it.field, it.message) },
    "createdBy" to value.createdBy.toString(), "creatorName" to value.creatorName,
    "creatorDeleted" to value.creatorDeleted, "createdAt" to value.createdAt, "updatedAt" to value.updatedAt,
)

private fun entityErrorMap(value: EntityErrorRow): Map<String, Any?> = mapOf(
    "id" to value.id.toString(), "blueprintId" to value.blueprintId.toString(),
    "blueprint" to value.blueprint, "blueprintTitle" to value.blueprintTitle,
    "identifier" to value.identifier, "title" to value.title, "team" to value.team,
    "findings" to value.findings.map { findingMap(it.code, it.field, it.message) },
)

private fun blueprintErrorMap(value: BlueprintErrorRow): Map<String, Any?> = mapOf(
    "id" to value.id.toString(), "identifier" to value.identifier, "title" to value.title,
    "findings" to value.findings.map { findingMap(it.code, it.field, it.message) },
)

private fun findingMap(code: String, field: String, message: String): Map<String, String> =
    mapOf("code" to code, "field" to field, "message" to message)
