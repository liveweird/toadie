package ch.nokillswit.entityquery

import ch.nokillswit.entities.GraphBlueprint
import kotlinx.serialization.json.JsonPrimitive

/**
 * The entity query language's evaluator (`.claude/docs/entity-query-language.md`, "Evaluation
 * semantics"): a plain in-memory join/BFS engine over a [QueryGraph], behind the [QueryExecutor]
 * seam so a future implementation (a real graph store) can swap in without touching the parser/
 * validator. Pure, DB-free, suspend throughout so [QueryBudget.checkpoint] can cooperate with
 * caller cancellation — no worker pool, unlike the jq evaluator (`entities/JqCalculation.kt`):
 * this loop never blocks on I/O.
 */

/** The RETURNed rows — a set (RETURN's own dedupe), in first-binding order. */
data class QueryResult(val rows: List<QueryRow>)

// VariableSlots is internal (QueryValidator.kt) — every member that would otherwise expose it
// (the executor's own `execute` and this file's `runEntityQuery`) is `internal` too; a caller
// outside the module (there is none yet — PR2's service lands in this same module) would go
// through a future public wrapper that parses/validates/executes in one call instead.

/** The evaluation seam: one query, already validated and slot-bound, executed over one [graph] under one [budget]. */
internal interface QueryExecutor {
    suspend fun execute(query: Query, slots: VariableSlots, graph: QueryGraph, budget: QueryBudget): QueryResult
}

/**
 * One join/binding: a slot-indexed [nodes] array, `null` for a slot not yet (or never) matched,
 * plus the [edges] slots of the single-hop edge variables a WHERE reads as `r.$tier` (2.18.0 —
 * empty for every other query, so those keep today's binding shape and dedupe).
 */
private class Binding(val nodes: Array<QueryRow?>, val edges: Array<EdgeRef?>) {
    fun copy(): Binding = Binding(nodes.copyOf(), if (edges.isEmpty()) edges else edges.copyOf())
}

/** One neighbour reached by an edge; [edge] is set only when the edge variable is tier-referenced. */
private data class Hop(val row: QueryRow, val edge: EdgeRef?)

/** A pattern-in-progress: the accumulating outer [Binding] plus a scratch array keyed by NODE POSITION (not slot) for anonymous nodes. */
private typealias PatternState = Pair<Binding, Array<QueryRow?>>

/**
 * Parses [text], validates it against [graph]'s own schema, and executes it — the one seam a
 * caller (a test, or the PR2 service) needs. Throws [QueryException] on any parse/validation
 * failure; [QueryBudgetExceeded] and [kotlinx.coroutines.CancellationException] propagate
 * unchanged from [executor].
 */
internal suspend fun runEntityQuery(
    text: String,
    graph: QueryGraph,
    budget: QueryBudget,
    executor: QueryExecutor = InMemoryQueryExecutor(),
): QueryResult {
    val query = parseEntityQuery(text)
    val (diagnostics, slots) = validateAndBind(query, QuerySchema(graph.blueprints, graph.hierarchies))
    if (diagnostics.isNotEmpty()) throw QueryException(diagnostics)
    return executor.execute(query, slots, graph, budget)
}

internal class InMemoryQueryExecutor : QueryExecutor {
    override suspend fun execute(query: Query, slots: VariableSlots, graph: QueryGraph, budget: QueryBudget): QueryResult {
        var bindings: List<Binding> = listOf(Binding(arrayOfNulls(slots.nodes.size), arrayOfNulls(slots.edges.size)))
        query.matches.forEach { clause -> bindings = matchClauseBindings(clause, bindings, slots, graph, budget) }
        // Variables introduced by the clauses processed so far: an OPTIONAL MATCH that names one
        // of them must anchor on THAT binding — a null slot (an earlier optional found nothing)
        // means "cannot match", never "scan the workspace for a fresh row".
        val introduced = query.matches.flatMap { it.patterns }.flatMap { it.nodes }.mapNotNull { it.variable }.toMutableSet()
        query.optionals.forEach { clause ->
            bindings = applyOptional(clause, bindings, slots, graph, budget, introduced)
            introduced += clause.patterns.flatMap { it.nodes }.mapNotNull { it.variable }
        }

        val slotIndices = returnedSlotIndices(query.returns, slots)
        val rows = LinkedHashSet<QueryRow>()
        bindings.forEach { binding -> slotIndices.forEach { idx -> binding.nodes[idx]?.let { rows += it } } }
        val limited = query.limit?.let { rows.take(it) } ?: rows.toList()
        return QueryResult(limited)
    }
}

private fun returnedSlotIndices(returns: Returns, slots: VariableSlots): List<Int> = when (returns) {
    is Returns.All -> slots.nodes.values.toList()
    is Returns.Variables -> returns.names.mapNotNull { slots.nodes[it] }
}

// ------------------------------------------------------------------------------------------
// MATCH / OPTIONAL MATCH clause processing
// ------------------------------------------------------------------------------------------

private suspend fun matchClauseBindings(
    clause: MatchClause,
    bindingsIn: List<Binding>,
    slots: VariableSlots,
    graph: QueryGraph,
    budget: QueryBudget,
): List<Binding> {
    var bindings = bindingsIn
    for (pattern in clause.patterns) {
        val next = mutableListOf<Binding>()
        for (b in bindings) {
            next += matchPattern(pattern, b, slots, graph, budget)
            budget.countBindings(next.size)
        }
        bindings = next
    }
    val where = clause.where ?: return bindings
    return bindings.filter { b ->
        budget.checkpoint()
        evaluate(where, lookupOf(b, slots)) == Truth.TRUE
    }
}

private suspend fun applyOptional(
    clause: MatchClause,
    bindingsIn: List<Binding>,
    slots: VariableSlots,
    graph: QueryGraph,
    budget: QueryBudget,
    introducedBefore: Set<String>,
): List<Binding> {
    val anchoredSlots = clause.patterns.flatMap { it.nodes }.mapNotNull { it.variable }
        .filter { it in introducedBefore }.mapNotNull { slots.nodes[it] }.toSet()
    val result = mutableListOf<Binding>()
    for (b in bindingsIn) {
        val nullAnchor = anchoredSlots.any { b.nodes[it] == null }
        val extensions = if (nullAnchor) emptyList() else optionalExtensions(clause, b, slots, graph, budget)
        if (extensions.isEmpty()) result += b else result += extensions
        budget.countBindings(result.size)
    }
    return result
}

private suspend fun optionalExtensions(
    clause: MatchClause,
    binding: Binding,
    slots: VariableSlots,
    graph: QueryGraph,
    budget: QueryBudget,
): List<Binding> {
    var extended = listOf(binding)
    for (pattern in clause.patterns) {
        val next = mutableListOf<Binding>()
        for (e in extended) next += matchPattern(pattern, e, slots, graph, budget)
        extended = next
    }
    val where = clause.where ?: return extended
    return extended.filter { e ->
        budget.checkpoint()
        evaluate(where, lookupOf(e, slots)) == Truth.TRUE
    }
}

/**
 * WHERE's view of one [binding]: a node variable resolves through [nodeValue] (the seven shared metas,
 * the two tier metas, stored properties); a tier-referenced edge variable resolves `$tier` off
 * its bound [EdgeRef] (the validator admits no other key on an edge variable). An unbound slot
 * resolves to `null`, same as a missing property.
 */
private fun lookupOf(binding: Binding, slots: VariableSlots): PropertyLookup = { variable, key ->
    val nodeSlot = slots.nodes[variable]
    if (nodeSlot != null) {
        binding.nodes[nodeSlot]?.let { nodeValue(key, it) }
    } else {
        slots.edges[variable]?.let { binding.edges[it] }?.tier?.let { JsonPrimitive(it) }
    }
}

// ------------------------------------------------------------------------------------------
// One pattern (a node-edge-node chain): anchor + expand left/right
// ------------------------------------------------------------------------------------------

private suspend fun matchPattern(
    pattern: Pattern,
    binding: Binding,
    slots: VariableSlots,
    graph: QueryGraph,
    budget: QueryBudget,
): List<Binding> {
    val anchorIndex = chooseAnchorIndex(pattern, binding, slots)
    val anchorNode = pattern.nodes[anchorIndex]
    val anchorExisting = anchorNode.variable?.let { v -> slots.nodes[v]?.let { binding.nodes[it] } }
    val anchorRows = candidatesForNode(anchorNode, anchorExisting, graph)

    var current = mutableListOf<PatternState>()
    for (row in anchorRows) {
        budget.checkpoint()
        val newBinding = binding.copy()
        if (!tryAssign(newBinding, anchorNode.variable, row, slots)) continue
        val positions = arrayOfNulls<QueryRow?>(pattern.nodes.size)
        positions[anchorIndex] = row
        current += newBinding to positions
    }
    budget.countBindings(current.size)

    for (i in anchorIndex until pattern.edges.size) {
        current = expandOneHop(current, pattern, i, i + 1, slots, graph, budget).toMutableList()
        budget.countBindings(current.size)
    }
    for (i in anchorIndex - 1 downTo 0) {
        current = expandOneHop(current, pattern, i + 1, i, slots, graph, budget).toMutableList()
        budget.countBindings(current.size)
    }
    return current.map { it.first }
}

private fun chooseAnchorIndex(pattern: Pattern, binding: Binding, slots: VariableSlots): Int {
    pattern.nodes.forEachIndexed { i, node ->
        val slot = node.variable?.let { slots.nodes[it] }
        if (slot != null && binding.nodes[slot] != null) return i
    }
    var bestIndex = 0
    var bestScore = -1
    pattern.nodes.forEachIndexed { i, node ->
        val score = constraintScore(node)
        if (score > bestScore) {
            bestScore = score
            bestIndex = i
        }
    }
    return bestIndex
}

private fun constraintScore(node: NodePattern): Int = when {
    node.labels.isEmpty() -> 0
    identifierLiteral(node) != null -> 3
    node.properties.isNotEmpty() -> 2
    else -> 1
}

private fun tryAssign(binding: Binding, variable: String?, row: QueryRow, slots: VariableSlots): Boolean {
    if (variable == null) return true
    val slot = slots.nodes[variable] ?: return true
    val existing = binding.nodes[slot]
    if (existing != null) return existing.key == row.key
    binding.nodes[slot] = row
    return true
}

private fun candidatesForNode(node: NodePattern, existingRow: QueryRow?, graph: QueryGraph): List<QueryRow> {
    if (existingRow != null) {
        return if (matchesLabels(node, existingRow) && matchesNodeProperties(node, existingRow)) listOf(existingRow) else emptyList()
    }
    return nodeCandidates(node, graph)
}

private fun nodeCandidates(node: NodePattern, graph: QueryGraph): List<QueryRow> {
    val idLiteral = identifierLiteral(node)
    val base: Collection<QueryRow> = when {
        node.labels.isNotEmpty() && idLiteral != null -> node.labels.mapNotNull { graph.row(it.lowercase(), idLiteral) }
        node.labels.isNotEmpty() -> node.labels.flatMapTo(LinkedHashSet()) { graph.rows(it.lowercase()) }
        else -> graph.allRows()
    }
    return base.filter { matchesNodeProperties(node, it) }
}

private fun identifierLiteral(node: NodePattern): String? =
    (node.properties["\$identifier"]?.value as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun matchesLabels(node: NodePattern, row: QueryRow): Boolean =
    node.labels.isEmpty() || node.labels.any { it.equals(row.blueprint.identifier, ignoreCase = true) }

private fun matchesNodeProperties(node: NodePattern, row: QueryRow): Boolean =
    node.properties.all { (key, literal) -> compare(ComparisonOp.EQUAL, nodeValue(key, row), literal.value) == Truth.TRUE }

// ------------------------------------------------------------------------------------------
// One edge hop (single or variable-length), expanding a list of partial matches by one node
// ------------------------------------------------------------------------------------------

private suspend fun expandOneHop(
    current: List<PatternState>,
    pattern: Pattern,
    knownPos: Int,
    unknownPos: Int,
    slots: VariableSlots,
    graph: QueryGraph,
    budget: QueryBudget,
): List<PatternState> {
    val edgeIndex = minOf(knownPos, unknownPos)
    val edge = pattern.edges[edgeIndex]
    val edgeSlot = edge.variable?.let { slots.edges[it] }
    val unknownNode = pattern.nodes[unknownPos]
    val knownIsLeft = knownPos < unknownPos
    val results = mutableListOf<PatternState>()
    for ((bnd, pos) in current) {
        val knownRow = pos[knownPos] ?: continue
        for ((candidate, edgeRef) in neighborsFor(knownRow, edge, knownIsLeft, edgeSlot != null, graph, budget)) {
            budget.checkpoint()
            if (!matchesLabels(unknownNode, candidate) || !matchesNodeProperties(unknownNode, candidate)) continue
            val newBinding = bnd.copy()
            if (!tryAssign(newBinding, unknownNode.variable, candidate, slots)) continue
            if (edgeSlot != null) newBinding.edges[edgeSlot] = edgeRef
            val newPos = pos.copyOf()
            newPos[unknownPos] = candidate
            results += newBinding to newPos
            // Checked per produced row, not per finished hop: one dense hop (1000 rows × a
            // 1000-wide `many` relation) would otherwise materialize far past the cap first.
            budget.countBindings(results.size)
        }
    }
    return results
}

/**
 * Single hop (no range) or level-set BFS (a range), always oriented by [knownIsLeft] against
 * [edge]'s own declared direction. With [trackEdge] (a single-hop edge variable read as
 * `r.$tier`) every hop carries its [EdgeRef] and two relation keys from one source to the same
 * target stay two hops; otherwise neighbours are deduplicated by row, exactly as before 2.18.0.
 */
private suspend fun neighborsFor(
    knownRow: QueryRow,
    edge: EdgePattern,
    knownIsLeft: Boolean,
    trackEdge: Boolean,
    graph: QueryGraph,
    budget: QueryBudget,
): List<Hop> {
    val range = edge.range
    if (range == null) {
        val direct = if (trackEdge) {
            expandHops(knownRow, edge, knownIsLeft, graph)
        } else {
            expandFrom(knownRow, edge, knownIsLeft, graph).map { Hop(it, null) }
        }
        direct.forEach { budget.checkpoint() }
        return direct
    }
    return variableLengthNeighbors(knownRow, edge, knownIsLeft, range, graph, budget).map { Hop(it, null) }
}

private suspend fun variableLengthNeighbors(
    start: QueryRow,
    edge: EdgePattern,
    knownIsLeft: Boolean,
    range: IntRange,
    graph: QueryGraph,
    budget: QueryBudget,
): List<QueryRow> {
    val levels = mutableListOf<Set<QueryRow>>()
    var frontier: Set<QueryRow> = setOf(start)
    for (depth in 1..range.last) {
        val next = LinkedHashSet<QueryRow>()
        for (row in frontier) {
            for (candidate in expandFrom(row, edge, knownIsLeft, graph)) {
                budget.checkpoint()
                next += candidate
            }
        }
        levels += next
        if (next.isEmpty()) break
        frontier = next
    }
    val matched = LinkedHashSet<QueryRow>()
    if (range.first == 0) matched += start
    for (depth in maxOf(range.first, 1)..range.last) {
        levels.getOrNull(depth - 1)?.let { matched += it }
    }
    return matched.toList()
}

/**
 * One hop from [knownRow] toward the OTHER end of [edge], resolved per [edge]'s OWN declared
 * direction (never per traversal order): OUT = left is the relation source; IN = right is; an
 * UNDIRECTED edge is the union of both readings.
 */
private fun expandFrom(knownRow: QueryRow, edge: EdgePattern, knownIsLeft: Boolean, graph: QueryGraph): List<QueryRow> =
    expandDirected(edge, knownIsLeft, { outNeighbors(knownRow, edge.types, graph) }, { inNeighbors(knownRow, edge.types, graph) })

/** [expandFrom] keeping the traversed relationship: one [Hop] per (neighbour, source, relation key), for `r.$tier`. */
private fun expandHops(knownRow: QueryRow, edge: EdgePattern, knownIsLeft: Boolean, graph: QueryGraph): List<Hop> =
    expandDirected(edge, knownIsLeft, { outHops(knownRow, edge.types, graph) }, { inHops(knownRow, edge.types, graph) })

private fun <T> expandDirected(edge: EdgePattern, knownIsLeft: Boolean, out: () -> List<T>, inn: () -> List<T>): List<T> =
    when (edge.direction) {
        EdgeDirection.OUT -> if (knownIsLeft) out() else inn()
        EdgeDirection.IN -> if (knownIsLeft) inn() else out()
        EdgeDirection.UNDIRECTED -> (out() + inn()).distinct()
    }

/**
 * Every relation KEY [type] resolves to on [sourceBlueprint]: [type] itself when it names one
 * of the blueprint's OWN relations, UNION the relation [type] (case-insensitively) maps to
 * when it names an active hierarchy id — so a relation literally sharing a hierarchy's name is
 * never shadowed by the alias (`.claude/docs/entity-query-language.md`).
 */
private fun relationKeysFor(sourceBlueprint: GraphBlueprint, type: String, graph: QueryGraph): Set<String> {
    val keys = mutableSetOf<String>()
    if (type in sourceBlueprint.definition.relations) keys += type
    if (graph.isHierarchyType(type)) {
        graph.hierarchyRelation(sourceBlueprint, type.lowercase())?.let { keys += it }
    }
    return keys
}

/**
 * [sourceRow] treated as the relation SOURCE: its declared relations, plus `$team`; empty
 * [types] = every one of them. [emit] receives (neighbour, relation key — null for `$team`).
 */
private inline fun forEachOut(sourceRow: QueryRow, types: List<String>, graph: QueryGraph, emit: (QueryRow, String?) -> Unit) {
    val resolvedTypes = types.ifEmpty { sourceRow.blueprint.definition.relations.keys.toList() + OWNERSHIP_EDGE_TYPE }
    for (type in resolvedTypes) {
        if (type == OWNERSHIP_EDGE_TYPE) {
            for (owner in graph.owners(sourceRow)) emit(owner, null)
            continue
        }
        for (key in relationKeysFor(sourceRow.blueprint, type, graph)) {
            for (target in graph.outgoing(sourceRow, key)) emit(target, key)
        }
    }
}

/**
 * [targetRow] treated as the relation TARGET: every row naming it, plus its owned-by set for
 * `$team`; empty [types] = all. [emit] receives (source row, relation key — null for `$team`);
 * the source IS the neighbour.
 */
private inline fun forEachIn(targetRow: QueryRow, types: List<String>, graph: QueryGraph, emit: (QueryRow, String?) -> Unit) {
    if (types.isEmpty() || types.any { it == OWNERSHIP_EDGE_TYPE }) graph.ownedBy(targetRow).forEach { emit(it, null) }
    val incoming = graph.incoming(targetRow)
    if (types.isEmpty()) {
        incoming.forEach { (source, relKey) -> emit(source, relKey) }
    } else {
        val relationTypes = types.filterNot { it == OWNERSHIP_EDGE_TYPE }
        incoming.forEach { (source, relKey) ->
            val resolved = relationTypes.flatMapTo(mutableSetOf()) { t -> relationKeysFor(source.blueprint, t, graph) }
            if (relKey in resolved) emit(source, relKey)
        }
    }
}

private fun outNeighbors(sourceRow: QueryRow, types: List<String>, graph: QueryGraph): List<QueryRow> {
    val result = LinkedHashSet<QueryRow>()
    forEachOut(sourceRow, types, graph) { neighbour, _ -> result += neighbour }
    return result.toList()
}

private fun inNeighbors(targetRow: QueryRow, types: List<String>, graph: QueryGraph): List<QueryRow> {
    val result = LinkedHashSet<QueryRow>()
    forEachIn(targetRow, types, graph) { neighbour, _ -> result += neighbour }
    return result.toList()
}

private fun outHops(sourceRow: QueryRow, types: List<String>, graph: QueryGraph): List<Hop> {
    val result = LinkedHashSet<Hop>()
    forEachOut(sourceRow, types, graph) { neighbour, key -> result += Hop(neighbour, EdgeRef(sourceRow, key)) }
    return result.toList()
}

private fun inHops(targetRow: QueryRow, types: List<String>, graph: QueryGraph): List<Hop> {
    val result = LinkedHashSet<Hop>()
    forEachIn(targetRow, types, graph) { source, key -> result += Hop(source, EdgeRef(source, key)) }
    return result.toList()
}

private fun QueryGraph.isHierarchyType(type: String): Boolean = hierarchies.any { it.equals(type, ignoreCase = true) }
