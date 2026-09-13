package ch.nokillswit.entityquery

import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.entities.GraphBlueprint
import ch.nokillswit.entities.MAX_ENTITIES_TOTAL

/**
 * The entity query validator (`.claude/docs/entity-query-language.md`, PR1 P1.4): pure,
 * schema-aware checks over `QueryParser.kt`'s AST — no database, no evaluation. Every rule is
 * an ERROR (the strict posture every other registry check in this codebase takes): a query is
 * either accepted whole or refused, never partially. [validateEntityQuery] reports EVERY
 * finding it can (unlike the parser, which stops at its first syntax error).
 */

/**
 * The schema snapshot a query is checked against: the active blueprints (identifier -> in the
 * `entities/EntityGraph.kt` shape the graph builder already uses) and the active `HIERARCHY`
 * dictionary values a `[:hierarchyId]` edge type may name. [foldedBlueprints]/[foldedHierarchies]
 * are the case-folded lookup indexes every check below uses (blueprint identifiers and hierarchy
 * ids fold case; relation keys and property ids stay byte-exact).
 */
data class QuerySchema(val blueprints: Map<String, GraphBlueprint>, val hierarchies: Set<String>) {
    val foldedBlueprints: Map<String, GraphBlueprint> by lazy { blueprints.values.associateBy { it.identifier.lowercase() } }
    val foldedHierarchies: Set<String> by lazy { hierarchies.map { it.lowercase() }.toSet() }
}

/** Node-variable name -> slot index, assigned in first-appearance order — what the evaluator binds against. */
internal data class VariableSlots(val nodes: Map<String, Int>)

private enum class VariableKind { NODE, EDGE }

private data class VariableBinding(val kind: VariableKind, val labels: List<String>)

/** Every finding [validateEntityQuery] can report, discarding the [VariableSlots] a caller doesn't need. */
fun validateEntityQuery(query: Query, schema: QuerySchema): List<QueryDiagnostic> = validateAndBind(query, schema).first

/** [validateEntityQuery] plus the variable-slot assignment the evaluator needs — one pass produces both. */
internal fun validateAndBind(query: Query, schema: QuerySchema): Pair<List<QueryDiagnostic>, VariableSlots> =
    QueryValidation(schema).run(query)

/**
 * One validation run's mutable state. A fresh instance per call — nothing here survives past
 * [run], so the class carries no thread-safety concerns despite the mutable fields.
 */
private class QueryValidation(private val schema: QuerySchema) {
    private val diagnostics = mutableListOf<QueryDiagnostic>()
    private val nodeSlots = LinkedHashMap<String, Int>()
    private val bindings = mutableMapOf<String, VariableBinding>()
    private val boundNodeVars = mutableSetOf<String>()

    // Union-find over PLAIN (non-optional) pattern indices — every node inside one pattern is
    // already connected by construction (a linear node-edge-node chain), so disconnection is a
    // question of whether two DIFFERENT patterns share a variable, not a per-node graph.
    private lateinit var patternParent: IntArray
    private lateinit var patternVarNames: MutableList<MutableSet<String>>
    private lateinit var patternSpans: MutableList<Span>

    fun run(query: Query): Pair<List<QueryDiagnostic>, VariableSlots> {
        checkCaps(query)
        val totalPlainPatterns = query.matches.sumOf { it.patterns.size }
        patternParent = IntArray(totalPlainPatterns) { it }
        patternVarNames = MutableList(totalPlainPatterns) { mutableSetOf() }
        patternSpans = MutableList(totalPlainPatterns) { query.span }

        var plainIndex = 0
        query.matches.forEach { clause ->
            clause.patterns.forEach { pattern ->
                patternSpans[plainIndex] = pattern.span
                processPattern(pattern, plainIndex)
                boundNodeVars += patternVarNames[plainIndex]
                plainIndex++
            }
            clause.where?.let { validateExpr(it) }
        }
        checkDisconnected(totalPlainPatterns, query.span)
        query.optionals.forEach { processOptionalClause(it) }
        validateReturns(query.returns)
        validateLimit(query.limit, query.span)
        // Source order regardless of which pass found what (the disconnection check runs after
        // every plain clause, so its span can precede a later clause's WHERE finding).
        val ordered = diagnostics.sortedWith(compareBy({ it.line ?: Int.MAX_VALUE }, { it.column ?: Int.MAX_VALUE }))
        return ordered to VariableSlots(nodeSlots.toMap())
    }

    private fun checkCaps(query: Query) {
        val allClauses = query.matches + query.optionals
        val totalNodes = allClauses.sumOf { clause -> clause.patterns.sumOf { it.nodes.size } }
        if (totalNodes > MAX_QUERY_NODE_PATTERNS) {
            diagnostics += diag(QueryDiagnosticCodes.TOO_MANY_PATTERNS, "Too many node patterns (max $MAX_QUERY_NODE_PATTERNS)", query.span)
        }
        if (allVariableNames(allClauses).size > MAX_QUERY_VARIABLES) {
            diagnostics += diag(QueryDiagnosticCodes.TOO_MANY_VARIABLES, "Too many variables (max $MAX_QUERY_VARIABLES)", query.span)
        }
    }

    private fun allVariableNames(clauses: List<MatchClause>): Set<String> {
        val patterns = clauses.flatMap { it.patterns }
        val nodeVars = patterns.flatMap { it.nodes }.mapNotNull { it.variable }
        val edgeVars = patterns.flatMap { it.edges }.mapNotNull { it.variable }
        return (nodeVars + edgeVars).toSet()
    }

    private fun processPattern(pattern: Pattern, plainPatternIndex: Int?) {
        pattern.nodes.forEach { node -> processNode(node, plainPatternIndex) }
        pattern.edges.forEachIndexed { i, edge -> processEdge(edge, pattern.nodes[i], pattern.nodes[i + 1]) }
    }

    private fun processNode(node: NodePattern, plainPatternIndex: Int?) {
        validateLabels(node.labels, node.span)
        node.properties.keys.forEach { key -> validatePropertyKey(key, node.labels, node.span) }
        val variable = node.variable ?: return
        trackVariable(variable, VariableKind.NODE, node.labels, node.span)
        nodeSlots.putIfAbsent(variable, nodeSlots.size)
        if (plainPatternIndex != null) unionOnVariable(variable, plainPatternIndex)
    }

    private fun unionOnVariable(variable: String, plainPatternIndex: Int) {
        patternVarNames[plainPatternIndex] += variable
        val firstSeenAt = patternVarNames.withIndex().firstOrNull { (i, vars) -> i != plainPatternIndex && variable in vars }?.index
        if (firstSeenAt != null) union(firstSeenAt, plainPatternIndex)
    }

    private fun processEdge(edge: EdgePattern, left: NodePattern, right: NodePattern) {
        validateEdge(edge, left, right)
        val variable = edge.variable ?: return
        trackVariable(variable, VariableKind.EDGE, emptyList(), edge.span)
    }

    private fun trackVariable(name: String, kind: VariableKind, labels: List<String>, span: Span) {
        val existing = bindings[name]
        if (existing == null) {
            bindings[name] = VariableBinding(kind, labels)
            return
        }
        if (existing.kind != kind) {
            diagnostics += diag(QueryDiagnosticCodes.DUPLICATE_VARIABLE, "Variable `$name` is already bound as a different kind", span)
            return
        }
        if (kind == VariableKind.EDGE) {
            diagnostics += diag(QueryDiagnosticCodes.DUPLICATE_VARIABLE, "Relationship variable `$name` is already used", span)
            return
        }
        // An unlabelled mention is a plain re-reference (Cypher's rule) whichever side it is on;
        // the FIRST labelled mention fixes the binding's labels so later property checks use them.
        if (labels.isEmpty()) return
        if (existing.labels.isEmpty()) {
            bindings[name] = existing.copy(labels = labels)
            return
        }
        if (foldedLabels(labels) != foldedLabels(existing.labels)) {
            diagnostics += diag(QueryDiagnosticCodes.DUPLICATE_VARIABLE, "Variable `$name` is re-bound with different labels", span)
        }
    }

    private fun foldedLabels(labels: List<String>): Set<String> = labels.map { it.lowercase() }.toSet()

    // --- labels / properties -------------------------------------------------------------

    private fun validateLabels(labels: List<String>, span: Span) {
        labels.forEach { label ->
            if (label.lowercase() !in schema.foldedBlueprints) {
                diagnostics += unknownLabelDiagnostic(label, span)
            }
        }
    }

    // The message never embeds the suggestion — like every other UNKNOWN_* code it travels in
    // the separate `suggestion` field, which the SPA renders once ("Did you mean `x`?").
    private fun unknownLabelDiagnostic(label: String, span: Span): QueryDiagnostic =
        diag(QueryDiagnosticCodes.UNKNOWN_LABEL, "Unknown blueprint `$label`", span, suggestion = labelSuggestion(label))

    /** Matched against identifiers AND titles (`(a:Service)` → `service`), but always suggests the IDENTIFIER. */
    private fun labelSuggestion(label: String): String? {
        val byIdentifier = schema.blueprints.values.map { it.identifier to it.identifier }
        val byTitle = schema.blueprints.values.map { it.title to it.identifier }
        return bestMatch(label, byIdentifier + byTitle)?.second
    }

    private fun resolvedBlueprintsFor(labels: List<String>): List<GraphBlueprint>? {
        if (labels.isEmpty()) return schema.blueprints.values.toList()
        val resolved = labels.mapNotNull { schema.foldedBlueprints[it.lowercase()] }
        return resolved.ifEmpty { null }
    }

    private fun validatePropertyKey(key: String, labels: List<String>, span: Span) {
        if (key in QUERY_META_PROPERTIES) return
        // A label that failed to resolve already carries its own UNKNOWN_LABEL — skip the
        // property check rather than piling on a guaranteed-wrong "unknown property" too.
        val candidates = resolvedBlueprintsFor(labels)?.flatMap { it.definition.schema.properties.keys }?.toSet() ?: return
        if (key !in candidates) {
            diagnostics += diag(
                QueryDiagnosticCodes.UNKNOWN_PROPERTY,
                "Unknown property `$key`",
                span,
                suggestion = suggest(key, candidates + QUERY_META_PROPERTIES),
            )
        }
    }

    // --- relations -------------------------------------------------------------------------

    private fun validateEdge(edge: EdgePattern, left: NodePattern, right: NodePattern) {
        validateRange(edge.range, edge.span)
        val pairs = sourceFarPairs(edge.direction, left, right)
        edge.types.forEach { type -> validateEdgeType(type, pairs, edge.span) }
    }

    private fun sourceFarPairs(direction: EdgeDirection, left: NodePattern, right: NodePattern): List<Pair<NodePattern, NodePattern>> =
        when (direction) {
            EdgeDirection.OUT -> listOf(left to right)
            EdgeDirection.IN -> listOf(right to left)
            EdgeDirection.UNDIRECTED -> listOf(left to right, right to left)
        }

    private fun validateRange(range: IntRange?, span: Span) {
        range ?: return
        if (range.first > range.last || range.first < 0 || range.last > MAX_QUERY_HOPS) {
            diagnostics += diag(QueryDiagnosticCodes.RANGE_INVALID, "Invalid hop range ${range.first}..${range.last}", span)
        }
    }

    private fun validateEdgeType(type: String, pairs: List<Pair<NodePattern, NodePattern>>, span: Span) {
        if (type == OWNERSHIP_EDGE_TYPE) {
            validateOwnershipTargets(pairs, span)
            return
        }
        if (type.lowercase() in schema.foldedHierarchies) return
        // Every (source blueprint, relation) the type can mean across the candidate source
        // blueprints — the union, never a single arbitrary pick: an unlabelled source means
        // "any blueprint that has this relation key".
        val candidates = pairs.flatMap { (source, far) -> sourceRelations(source, type).map { Triple(it.first, it.second, far) } }
        if (candidates.isEmpty()) {
            diagnostics += diag(
                QueryDiagnosticCodes.UNKNOWN_RELATION,
                "Unknown relation `$type`",
                span,
                suggestion = relationSuggestion(type, pairs),
            )
            return
        }
        validateRelationTargets(type, candidates, span)
    }

    /**
     * The labels a node pattern EFFECTIVELY carries: its own, or — for an unlabelled re-reference
     * of a variable labelled earlier — the labels that earlier mention bound it to.
     */
    private fun effectiveLabels(node: NodePattern): List<String> =
        node.labels.ifEmpty { node.variable?.let { bindings[it]?.labels }.orEmpty() }

    private fun sourceRelations(node: NodePattern, type: String): List<Pair<GraphBlueprint, RelationDefinition>> =
        resolvedBlueprintsFor(effectiveLabels(node)).orEmpty().mapNotNull { bp -> bp.definition.relations[type]?.let { bp to it } }

    /**
     * The targets-`Y`-not-`Z` check runs only when the far end is (effectively) labelled, and
     * passes when ANY candidate relation targets one of those labels — a query that could match
     * under some source blueprint is never refused on the strength of another.
     */
    private fun validateRelationTargets(
        type: String,
        candidates: List<Triple<GraphBlueprint, RelationDefinition, NodePattern>>,
        span: Span,
    ) {
        val checkable = candidates.filter { (_, _, far) -> effectiveLabels(far).isNotEmpty() }
        if (checkable.isEmpty()) return
        val reachable = checkable.any { (_, relation, far) -> relation.target.lowercase() in foldedLabels(effectiveLabels(far)) }
        if (!reachable) {
            val (source, relation, far) = checkable.first()
            diagnostics += diag(
                QueryDiagnosticCodes.UNKNOWN_RELATION,
                "relation `$type` of `${source.identifier}` targets `${relation.target}`, not `${effectiveLabels(far).first()}`",
                span,
            )
        }
    }

    private fun validateOwnershipTargets(pairs: List<Pair<NodePattern, NodePattern>>, span: Span) {
        // One diagnostic per edge even for an undirected edge (two orientation pairs): refused
        // only when NO orientation can reach a `_team` end.
        val labelledFar = pairs.map { (_, far) -> effectiveLabels(far) }.filter { it.isNotEmpty() }
        if (labelledFar.isEmpty() || labelledFar.any { SYSTEM_TEAM_BLUEPRINT in foldedLabels(it) }) return
        diagnostics += diag(
            QueryDiagnosticCodes.UNKNOWN_RELATION,
            "relation `$OWNERSHIP_EDGE_TYPE` targets `$SYSTEM_TEAM_BLUEPRINT`, not `${labelledFar.first().first()}`",
            span,
        )
    }

    private fun relationSuggestion(type: String, pairs: List<Pair<NodePattern, NodePattern>>): String? {
        val relationKeys = pairs.flatMap { (source, _) -> resolvedBlueprintsFor(source.labels).orEmpty() }
            .flatMap { it.definition.relations.keys }
        val candidates = (relationKeys + schema.hierarchies + OWNERSHIP_EDGE_TYPE).toSet()
        return suggest(type, candidates)
    }

    // --- WHERE / RETURN ----------------------------------------------------------------------

    private fun validateExpr(expr: Expr) {
        when (expr) {
            is Expr.And -> { validateExpr(expr.left); validateExpr(expr.right) }
            is Expr.Or -> { validateExpr(expr.left); validateExpr(expr.right) }
            is Expr.Not -> validateExpr(expr.operand)
            is Expr.Compare -> { validateOperand(expr.left); validateOperand(expr.right) }
            is Expr.In -> { validateOperand(expr.left); validateOperand(expr.right) }
            is Expr.StringOp -> { validateOperand(expr.left); validateOperand(expr.right) }
            is Expr.IsNull -> validateOperand(expr.operand)
            is Expr.Truthy -> validateOperand(expr.operand)
        }
    }

    private fun validateOperand(operand: Operand) {
        if (operand !is Operand.Property) return
        val binding = bindings[operand.variable]
        when {
            binding == null -> diagnostics += diag(
                QueryDiagnosticCodes.UNKNOWN_VARIABLE,
                "Unknown variable `${operand.variable}`",
                operand.span,
                suggestion = suggest(operand.variable, bindings.keys),
            )
            binding.kind == VariableKind.EDGE -> diagnostics += diag(
                QueryDiagnosticCodes.RELATIONSHIP_VARIABLE_REFERENCE,
                "Relationship variable `${operand.variable}` cannot be used — relations carry no properties in Toadie",
                operand.span,
            )
            else -> validatePropertyKey(operand.key, binding.labels, operand.span)
        }
    }

    private fun validateReturns(returns: Returns) {
        if (returns !is Returns.Variables) return
        returns.names.forEach { name ->
            val binding = bindings[name]
            when {
                binding == null -> diagnostics += diag(
                    QueryDiagnosticCodes.UNKNOWN_VARIABLE,
                    "Unknown variable `$name`",
                    returns.span,
                    suggestion = suggest(name, bindings.keys),
                )
                binding.kind == VariableKind.EDGE -> diagnostics += diag(
                    QueryDiagnosticCodes.RELATIONSHIP_VARIABLE_REFERENCE,
                    "Relationship variable `$name` cannot be returned — relations carry no properties in Toadie",
                    returns.span,
                )
                else -> {}
            }
        }
    }

    private fun validateLimit(limit: Int?, span: Span) {
        limit ?: return
        if (limit < 1 || limit > MAX_ENTITIES_TOTAL) {
            diagnostics += diag(QueryDiagnosticCodes.LIMIT_INVALID, "LIMIT must be between 1 and $MAX_ENTITIES_TOTAL", span)
        }
    }

    // --- OPTIONAL MATCH / disconnection -------------------------------------------------------

    private fun processOptionalClause(clause: MatchClause) {
        val ownVars = mutableSetOf<String>()
        clause.patterns.forEach { pattern ->
            processPattern(pattern, plainPatternIndex = null)
            pattern.nodes.forEach { it.variable?.let { v -> ownVars += v } }
        }
        if (ownVars.none { it in boundNodeVars }) {
            diagnostics += diag(
                QueryDiagnosticCodes.DISCONNECTED_PATTERN,
                "OPTIONAL MATCH must reuse a variable from a preceding MATCH",
                clause.span,
            )
        }
        boundNodeVars += ownVars
        clause.where?.let { validateExpr(it) }
    }

    private fun checkDisconnected(totalPlainPatterns: Int, fallbackSpan: Span) {
        if (totalPlainPatterns <= 1) return
        val groups = (0 until totalPlainPatterns).groupBy { find(it) }
        if (groups.size <= 1) return
        val names = groups.values.map { indices ->
            indices.firstNotNullOfOrNull { patternVarNames[it].minOrNull() } ?: "an anonymous pattern"
        }
        val joined = names.dropLast(1).joinToString(", ") { "`$it`" } + " and `${names.last()}`"
        val span = patternSpans.getOrElse(groups.values.drop(1).first().first()) { fallbackSpan }
        val message = "Patterns $joined share no variable — a cartesian product is refused"
        diagnostics += diag(QueryDiagnosticCodes.DISCONNECTED_PATTERN, message, span)
    }

    private fun find(i: Int): Int {
        var x = i
        while (patternParent[x] != x) x = patternParent[x]
        patternParent[i] = x
        return x
    }

    private fun union(a: Int, b: Int) {
        val rootA = find(a)
        val rootB = find(b)
        if (rootA != rootB) patternParent[rootA] = rootB
    }

    private fun diag(code: String, message: String, span: Span, suggestion: String? = null): QueryDiagnostic =
        QueryDiagnostic(code, message, span.line, span.column, span.endLine, span.endColumn, suggestion)
}
