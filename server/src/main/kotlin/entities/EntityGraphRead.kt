package ch.nokillswit.entities

import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.entityquery.EntityQueryInvalidException
import ch.nokillswit.entityquery.InMemoryQueryExecutor
import ch.nokillswit.entityquery.InMemoryQueryGraph
import ch.nokillswit.entityquery.MAX_CONCURRENT_ENTITY_QUERIES
import ch.nokillswit.entityquery.MAX_QUERY_BINDINGS
import ch.nokillswit.entityquery.Query
import ch.nokillswit.entityquery.QueryBudget
import ch.nokillswit.entityquery.QueryBudgetExceeded
import ch.nokillswit.entityquery.QueryDiagnostic
import ch.nokillswit.entityquery.QueryDiagnosticCodes
import ch.nokillswit.entityquery.QueryException
import ch.nokillswit.entityquery.QueryGraph
import ch.nokillswit.entityquery.QueryResult
import ch.nokillswit.entityquery.QuerySchema
import ch.nokillswit.entityquery.VariableSlots
import ch.nokillswit.entityquery.parseEntityQuery
import ch.nokillswit.entityquery.validateAndBind
import ch.nokillswit.entityquery.validateEntityQuery
import ch.nokillswit.infra.concurrency.READ_BUDGET_BYTES
import ch.nokillswit.infra.concurrency.ReadBudgetExceeded
import ch.nokillswit.infra.concurrency.ReadLedger
import ch.nokillswit.infra.concurrency.throwReadBudgetHttp
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * The Entity graph read and the entity-query orchestration (phase 7, 2.0.0) — `graph` and
 * `checkQuery`, the query parse/validate/evaluate helpers, and the read-budget HTTP mapping.
 * Declared as `EntityService` EXTENSION functions (the `blueprints/BlueprintSync.kt` /
 * `entities/EntityWorkspaceRead.kt` idiom) purely to keep `EntityService.kt` under the repo's
 * `LargeClass` threshold (`config/detekt/detekt.yml`): a pure move, no behaviour change.
 * `queryDeadlineMillis`/`queryClock`/`querySlots`/`loadQuerySchema` widened to `internal` so this
 * file can reach them.
 */

/** Entity-query evaluation logs (budget misses only — never the query text) on this logger, the `entities/JqCalculation.kt` idiom. */
private val queryLog = LoggerFactory.getLogger("ch.nokillswit.entityquery")

/**
 * The dedicated `entity-query` pool every query evaluation runs on (phase 7, 2.0.0 —
 * `.claude/docs/security.md` "Entity query evaluation"): exactly [MAX_CONCURRENT_ENTITY_QUERIES]
 * daemon threads, the same count as the permits [EntityService] hands out, so an evaluation
 * never queues and — unlike `Dispatchers.Default` — a slow query can never starve bcrypt or the
 * request pipeline. Process-lifetime, like `JQ_EXECUTOR` in `JqCalculation.kt`.
 */
private val QUERY_DISPATCHER = Executors.newFixedThreadPool(
    MAX_CONCURRENT_ENTITY_QUERIES,
    ThreadFactory { runnable -> Thread(runnable, "entity-query").apply { isDaemon = true } },
).asCoroutineDispatcher()

/** [graph]'s parse/validate result — produced OUTSIDE any transaction, before the workspace rows are loaded. */
internal data class ParsedQuery(val query: Query, val slots: VariableSlots, val hierarchies: Set<String>, val textLength: Int)

/** [graph]'s query orchestration payload — non-null only when [EntityGraphFilter.query] is present. */
internal data class GraphQueryPlan(
    val query: Query,
    val slots: VariableSlots,
    /** Undecoded on purpose — [WorkspaceRow.skeleton] decodes/charges on the query pool by [evaluateEntityQuery]. */
    val workspaceRows: List<WorkspaceRow>,
    val blueprintsByIdentifier: Map<String, GraphBlueprint>,
    val hierarchies: Set<String>,
    val textLength: Int,
)

private data class GraphMaterialized(
    val sources: List<EntityGraphSource>,
    val graphBlueprintsById: Map<UInt, GraphBlueprint>,
    val findings: (EntityGraphSource) -> Int,
    val queryPlan: GraphQueryPlan?,
)

/**
 * `GET …/entities/graph`: a plain read (no write-lock — reads never wait behind the
 * blueprints/entities writer lock). `blueprints` folds case-insensitively against the
 * active blueprint identifiers (the list filter's own idiom); if the filter names at
 * least one blueprint and NONE resolve, the graph is empty rather than "no predicate"
 * (an empty resolved-id set must narrow to nothing, not widen to everything). Loads ONLY
 * the rows the filter shows — an edge needs both ends shown, so a hidden row can never
 * contribute a node or an edge (`catalog/Graph.kt`'s rule, one level down) — with no
 * users join (the graph never needs creator display fields). Ownership edges follow the
 * SAME both-ends rule via [EntityGraphSource.team], the EFFECTIVE team. `team` matches that
 * SAME effective value (v1.30.0) — [EntityService.inheritedTeamMatches] resolves the Inherited half over
 * the candidate blueprints the `blueprints` filter already narrowed to, folded into the ONE
 * SQL predicate the same way [EntityService.list] does.
 *
 * Phase 7 (2.0.0 — `.claude/docs/entity-query-language.md`): when [EntityGraphFilter.query]
 * is set, the query is parsed/validated OUTSIDE any transaction against one committed read
 * of the blueprints and hierarchies (a refused query never reads an entity row, and the
 * suggestion scan never holds a pooled connection), then one of
 * [MAX_CONCURRENT_ENTITY_QUERIES] permits is taken (none free → `429`, before the workspace
 * is loaded — each in-flight evaluation holds a decoded workspace; a refused query costs no
 * permit), and only then is the workspace loaded and the query EVALUATED on the dedicated
 * `entity-query` pool after that transaction closed (the phase-5
 * computed-property rule: a pathological query pins a pool thread, never a pooled connection
 * or the entity write lock). A traversal runs over the FULL active workspace (an entity the
 * other filters hide may still be a hop along the way), and only the `RETURN`ed entities
 * narrow the shown rows.
 */
suspend fun EntityService.graph(filter: EntityGraphFilter): EntityGraph =
    withParsedQuery(filter.query) { parsed -> graphWithPlan(filter, parsed) }

/**
 * The query prologue every `query`-bearing entity read shares ([graph], the conversion export): with no
 * [query] text, [block] runs with `null`; otherwise the text is parsed/validated OUTSIDE any transaction
 * (a refused query never reads an entity row), one `entity-query` permit is taken (none free -> `429`,
 * before any workspace is loaded) and released when [block] ends.
 */
internal suspend fun <T> EntityService.withParsedQuery(query: String?, block: suspend (ParsedQuery?) -> T): T {
    val text = query ?: return block(null)
    val schema = suspendTransaction(database) { loadQuerySchema() }
    val (parsed, slots) = requireValidEntityQuery(text, schema)
    if (!querySlots.tryAcquire()) {
        queryRefusals.incrementAndGet()
        throw TooManyRequestsException("Too many entity queries are being evaluated — retry shortly")
    }
    try {
        return block(ParsedQuery(parsed, slots, schema.hierarchies, text.length))
    } finally {
        querySlots.release()
    }
}

private suspend fun EntityService.graphWithPlan(filter: EntityGraphFilter, parsed: ParsedQuery?): EntityGraph {
    readLedger.open().use { reservation ->
        try {
            val materialized = suspendTransaction(database) { materializeGraph(filter, parsed, reservation) }
                ?: return EntityGraph(emptyList(), emptyList())
            val sources = materialized.queryPlan?.let { plan ->
                narrowByQuery(materialized.sources, materialized.graphBlueprintsById, plan)
            } ?: materialized.sources
            return buildEntityGraph(sources, materialized.graphBlueprintsById, materialized.findings)
        } catch (cause: ReadBudgetExceeded) {
            throwReadBudgetQuery(cause)
        }
    }
}

/**
 * Loads the node/edge SHOWN set (`.claude/docs/persistence.md` "Entity read memory budget"):
 * ONE [loadReadSet] call whose `shownPredicate` is the ordinary `blueprint`/`q`/`team` filter
 * — the graph's own SELECT, replacing the former separate raw-row read — and whose targets
 * are EITHER the whole active workspace (a `query` may traverse through a hidden entity) OR
 * the narrower relation/ownership lookup targets of the shown blueprints (no `query`, since a
 * plain graph never evaluates computed properties). Each SHOWN row is decoded ONCE via
 * [WorkspaceRow.full] — transiently, for `entityFindings`/`effectiveTeam` only; nothing else
 * in this file re-reads its `properties`.
 */
private suspend fun EntityService.materializeGraph(
    filter: EntityGraphFilter,
    parsedQuery: ParsedQuery?,
    reservation: ReadLedger.Reservation,
): GraphMaterialized? {
    val activeBlueprints = loadActiveBlueprints()
    val blueprintsById = activeBlueprints.associateBy { it.id }
    val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
    val definitionsByIdentifier = activeBlueprints.associate { it.identifier to it.definition }
    val graphBlueprintsByIdentifier = activeBlueprints.associate {
        it.identifier to GraphBlueprint(it.identifier, it.title, it.definition, it.hierarchyRelations, it.tiers)
    }

    val scope = shownScope(filter, activeBlueprints, reservation) ?: return null

    val targets = if (parsedQuery != null) {
        blueprintsByIdentifier.keys
    } else {
        narrowTargets(scope.candidates.map { it.definition }, definitionsByIdentifier, computed = false)
    }
    val readSet = loadReadSet(scope.predicate, targets, blueprintsByIdentifier, reservation)
    val shownRows = readSet.rows.filter { it.shown }

    val sources = shownRows.map { workspaceRow ->
        val definition = blueprintsById.getValue(workspaceRow.blueprintId).definition
        val decoded = workspaceRow.full()
        EntityGraphSource(
            id = workspaceRow.id,
            blueprintId = workspaceRow.blueprintId,
            identifier = workspaceRow.identifier,
            title = workspaceRow.title,
            icon = workspaceRow.icon,
            relations = decoded.document.relations,
            team = teamValues(
                effectiveTeam(decoded.team, decoded.document, definition, definitionsByIdentifier, readSet.snapshot.rowLookup),
            ),
        )
    }
    val findingsByRowId = shownRows.associate { workspaceRow ->
        val decoded = workspaceRow.full()
        val definition = blueprintsById.getValue(workspaceRow.blueprintId).definition
        workspaceRow.id to entityFindings(decoded.document, definition, readSet.snapshot.targetExists, decoded.team).size
    }

    val graphBlueprintsById = blueprintsById.mapValues {
        GraphBlueprint(it.value.identifier, it.value.title, it.value.definition, it.value.hierarchyRelations, it.value.tiers)
    }
    return GraphMaterialized(
        sources = sources,
        graphBlueprintsById = graphBlueprintsById,
        findings = { source -> findingsByRowId[source.id] ?: 0 },
        queryPlan = parsedQuery?.let {
            GraphQueryPlan(it.query, it.slots, readSet.rows, graphBlueprintsByIdentifier, it.hierarchies, it.textLength)
        },
    )
}

/**
 * Evaluates [plan] over the workspace ([InMemoryQueryGraph]) OUTSIDE the caller's
 * transaction, then narrows [sources] to the `RETURN`ed `(blueprint, identifier)` keys —
 * the both-ends rule stays with [buildEntityGraph], unaffected by this narrowing.
 */
private suspend fun EntityService.narrowByQuery(
    sources: List<EntityGraphSource>,
    graphBlueprintsById: Map<UInt, GraphBlueprint>,
    plan: GraphQueryPlan,
): List<EntityGraphSource> {
    val result = evaluateEntityQuery(plan)
    val matched = result.rows.map { it.key }.toSet()
    return sources.filter { source -> (graphBlueprintsById.getValue(source.blueprintId).identifier to source.identifier) in matched }
}

/**
 * Compiles/validates [text] against [schema] — a `QueryException` (the parser's first
 * syntax error) or non-empty validator diagnostics both become [EntityQueryInvalidException],
 * the ONE findings-bearing `400` this endpoint throws for a refused query.
 */
internal fun requireValidEntityQuery(text: String, schema: QuerySchema): Pair<Query, VariableSlots> {
    val query = try {
        parseEntityQuery(text)
    } catch (cause: QueryException) {
        throw EntityQueryInvalidException(cause.diagnostics).apply { initCause(cause) }
    }
    val (diagnostics, slots) = validateAndBind(query, schema)
    if (diagnostics.isNotEmpty()) throw EntityQueryInvalidException(diagnostics)
    return query to slots
}

/**
 * Decodes the workspace, builds [InMemoryQueryGraph] and runs [InMemoryQueryExecutor] on the
 * dedicated `entity-query` pool ([QUERY_DISPATCHER]) under the cooperative [QueryBudget]:
 * every checkpoint observes caller cancellation and [queryClock], and per-candidate work is
 * capped (`MAX_STRING_OPERAND_CHARS`), so a deadline is honoured within one candidate's work
 * — nothing preempts a running candidate, which is why there is no outer `withTimeout`
 * (it could only cancel at the same checkpoint). A miss is [EntityQueryInvalidException]
 * with ONE [QueryDiagnostic] naming the budget code; [kotlinx.coroutines.CancellationException]
 * propagates unchanged.
 */
internal suspend fun EntityService.evaluateEntityQuery(plan: GraphQueryPlan): QueryResult {
    val budget = QueryBudget(queryDeadlineMillis, nanoTime = queryClock)
    try {
        return withContext(QUERY_DISPATCHER) {
            // Decode/charge relations+team for EVERY workspace row UNDER the cooperative
            // budget before InMemoryQueryGraph's own (unbudgeted) index-building loops touch
            // them — `properties` stays untouched (and uncharged) unless a WHERE/RETURN
            // actually reads one (`QueryRow.candidate`'s `by lazy`, `.claude/docs/persistence.md`).
            for (row in plan.workspaceRows) {
                budget.checkpoint()
                row.skeleton()
            }
            val graph: QueryGraph =
                InMemoryQueryGraph(plan.workspaceRows.map { it.view }, plan.blueprintsByIdentifier, plan.hierarchies)
            InMemoryQueryExecutor().execute(plan.query, plan.slots, graph, budget)
        }
    } catch (cause: QueryBudgetExceeded) {
        logQueryBudgetMiss(cause.code, plan.textLength)
        throw EntityQueryInvalidException(listOf(budgetDiagnostic(cause.code))).apply { initCause(cause) }
    }
    // ReadBudgetExceeded from row.skeleton() propagates unhandled — mapped by graphWithPlan's
    // catch (`throwReadBudgetQuery`), never confused with a QueryBudgetExceeded miss.
}

private fun EntityService.budgetDiagnostic(code: String): QueryDiagnostic =
    if (code == QueryDiagnosticCodes.BINDING_LIMIT) {
        QueryDiagnostic(code, "The query produced more than $MAX_QUERY_BINDINGS intermediate bindings")
    } else {
        deadlineDiagnostic()
    }

private fun EntityService.deadlineDiagnostic(): QueryDiagnostic =
    QueryDiagnostic(QueryDiagnosticCodes.DEADLINE_EXCEEDED, "The query exceeded its $queryDeadlineMillis ms evaluation budget")

private fun logQueryBudgetMiss(code: String, textLength: Int) {
    queryLog.debug("entity query budget miss ({}): queryLength={}", code, textLength)
}

/** [graph]'s query-shaped twin of `throwReadBudgetHttp` — an own-request miss is a query-shaped `400` (with or without a `query`). */
private fun throwReadBudgetQuery(cause: ReadBudgetExceeded): Nothing {
    if (cause.ownRequest) {
        throw EntityQueryInvalidException(
            listOf(
                QueryDiagnostic(
                    code = QueryDiagnosticCodes.WORKSPACE_TOO_LARGE,
                    message = "The entity workspace exceeds the $READ_BUDGET_BYTES-byte read budget — " +
                        "narrow the blueprint filter or reduce stored documents",
                ),
            ),
        )
    }
    throwReadBudgetHttp(cause, "entity")
}

/**
 * `POST …/entities/query/check`: parses/validates [text] against the CURRENT active
 * blueprints/`hierarchies` — never evaluates it, never throws (a [QueryException] simply
 * RETURNS its diagnostics, the parser's own failure shape). Blank text has no diagnostics.
 */
suspend fun EntityService.checkQuery(text: String): List<QueryDiagnostic> {
    if (text.isBlank()) return emptyList()
    val schema = suspendTransaction(database) { loadQuerySchema() }
    // Outside the transaction: the suggestion scan is CPU work, never on a pooled connection.
    return try {
        validateEntityQuery(parseEntityQuery(text), schema)
    } catch (e: QueryException) {
        e.diagnostics
    }
}
