package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.entities.GraphBlueprint
import ch.nokillswit.entities.MAX_ENTITIES_TOTAL
import ch.nokillswit.entityquery.ComparisonOp
import ch.nokillswit.entityquery.EdgeDirection
import ch.nokillswit.entityquery.EdgePattern
import ch.nokillswit.entityquery.Expr
import ch.nokillswit.entityquery.Literal
import ch.nokillswit.entityquery.MatchClause
import ch.nokillswit.entityquery.NodePattern
import ch.nokillswit.entityquery.Operand
import ch.nokillswit.entityquery.Pattern
import ch.nokillswit.entityquery.Query
import ch.nokillswit.entityquery.QueryDiagnosticCodes
import ch.nokillswit.entityquery.QuerySchema
import ch.nokillswit.entityquery.Returns
import ch.nokillswit.entityquery.Span
import ch.nokillswit.entityquery.validateAndBind
import ch.nokillswit.entityquery.validateEntityQuery
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `entityquery/QueryValidator.kt` (PR1 P1.4): one case per diagnostic code, hand-built ASTs (no
 * parser in this tree yet — `QueryLexer.kt`/`QueryParser.kt` land in a parallel PR1 slice), pure
 * — no database. Position assertions use a distinct line per node/edge so a mismatch is obvious.
 */
class QueryValidatorTest {

    // --- AST builders --------------------------------------------------------------------------

    private fun span(line: Int) = Span(line, 1, line, 10)

    private fun node(
        variable: String? = null,
        labels: List<String> = emptyList(),
        properties: Map<String, Literal> = emptyMap(),
        line: Int = 1,
    ) = NodePattern(variable, labels, properties, span(line))

    private fun edge(
        variable: String? = null,
        types: List<String> = emptyList(),
        direction: EdgeDirection = EdgeDirection.OUT,
        range: IntRange? = null,
        line: Int = 1,
    ) = EdgePattern(variable, types, direction, range, span(line))

    private fun singlePattern(vararg nodes: NodePattern, edges: List<EdgePattern> = emptyList()) =
        Pattern(nodes.toList(), edges, span(1))

    private fun chain(left: NodePattern, edge: EdgePattern, right: NodePattern) =
        Pattern(listOf(left, right), listOf(edge), span(1))

    private fun match(vararg patterns: Pattern, where: Expr? = null, optional: Boolean = false) =
        MatchClause(patterns.toList(), where, optional, span(1))

    private fun query(
        matches: List<MatchClause>,
        optionals: List<MatchClause> = emptyList(),
        returns: Returns = Returns.All(span(1)),
        limit: Int? = null,
    ) = Query(matches, optionals, returns, limit, span(1))

    private fun returnAll() = Returns.All(span(1))

    private fun returnVars(vararg names: String, line: Int = 1) = Returns.Variables(names.toList(), span(line))

    private fun prop(variable: String, key: String, line: Int = 1) = Operand.Property(variable, key, span(line))

    private fun lit(value: String) = Operand.Value(Literal(JsonPrimitive(value), span(1)), span(1))

    // --- Schema builders -------------------------------------------------------------------------

    private fun relation(target: String, many: Boolean = false, required: Boolean = false) =
        RelationDefinition(title = "Rel", target = target, required = required, many = many)

    private fun blueprint(
        identifier: String,
        title: String = identifier.replaceFirstChar { it.uppercase() },
        relations: Map<String, RelationDefinition> = emptyMap(),
        properties: Map<String, PropertyDefinition> = emptyMap(),
    ) = identifier to GraphBlueprint(
        identifier = identifier,
        title = title,
        definition = BlueprintDefinition(relations = relations, schema = BlueprintSchema(properties = properties)),
        hierarchyRelations = emptyMap(),
    )

    private fun schema(vararg entries: Pair<String, GraphBlueprint>, hierarchies: Set<String> = emptySet()) =
        QuerySchema(entries.toMap(), hierarchies)

    private fun baseSchema() = schema(
        blueprint(
            "service",
            title = "Service",
            relations = mapOf("owner" to relation("group")),
            properties = mapOf("name" to PropertyDefinition(type = "string")),
        ),
        blueprint("group", title = "Group"),
        blueprint("resource", title = "Resource"),
        blueprint(SYSTEM_TEAM_BLUEPRINT, title = "Team"),
        hierarchies = setOf("composition"),
    )

    private fun codes(query: Query, schema: QuerySchema = baseSchema()) = validateEntityQuery(query, schema).map { it.code }

    // --- Relation resolution through earlier bindings -------------------------------------------

    private fun chainSchema() = schema(
        blueprint("b0", relations = mapOf("next" to relation("b1"))),
        blueprint("b1", relations = mapOf("next" to relation("b2"))),
        blueprint("b2"),
    )

    @Test
    fun `an unlabelled re-reference resolves its relations through the labels it was bound with`() {
        // (a:b0)-[:next]->(b:b1), (b)-[:next]->(c:b2): `b` is a b1, whose `next` targets b2 — valid.
        val first = chain(node("a", listOf("b0")), edge(types = listOf("next")), node("b", listOf("b1")))
        val second = chain(node("b"), edge(types = listOf("next")), node("c", listOf("b2")))
        val q = query(listOf(match(first, second)), returns = returnAll())
        assertTrue(codes(q, chainSchema()).none { it == QueryDiagnosticCodes.UNKNOWN_RELATION })

        // The same shape pointing `b`'s next at b0 IS wrong: b1's `next` targets b2, not b0.
        val wrong = chain(node("b"), edge(types = listOf("next")), node("c", listOf("b0")))
        val q2 = query(listOf(match(first, wrong)), returns = returnAll())
        assertTrue(QueryDiagnosticCodes.UNKNOWN_RELATION in codes(q2, chainSchema()))
    }

    @Test
    fun `a truly unlabelled source accepts a relation when ANY blueprint with that key reaches the far label`() {
        // (x)-[:next]->(c:b2): b1's `next` reaches b2, so b0's `next` (→ b1) must not veto it.
        val q = query(listOf(match(chain(node("x"), edge(types = listOf("next")), node("c", listOf("b2"))))), returns = returnAll())
        assertTrue(codes(q, chainSchema()).none { it == QueryDiagnosticCodes.UNKNOWN_RELATION })
    }

    // --- UNKNOWN_LABEL ---------------------------------------------------------------------------

    @Test
    fun `unknown label reports position and a suggestion by identifier`() {
        val a = node("a", listOf("servics"), line = 5)
        val q = query(listOf(match(singlePattern(a), where = null)), returns = returnVars("a"))
        val diagnostics = validateEntityQuery(q, baseSchema())
        val finding = diagnostics.single { it.code == QueryDiagnosticCodes.UNKNOWN_LABEL }
        assertEquals(5, finding.line)
        assertEquals("service", finding.suggestion)
    }

    @Test
    fun `unknown label matched by title suggests the identifier, never embedded in the message`() {
        val s = schema(blueprint("svc", title = "Service"))
        val a = node("a", listOf("servicex"), line = 3)
        val q = query(listOf(match(singlePattern(a))), returns = returnVars("a"))
        val finding = validateEntityQuery(q, s).single { it.code == QueryDiagnosticCodes.UNKNOWN_LABEL }
        assertEquals("svc", finding.suggestion)
        // The suggestion travels in its own field only — the SPA renders it once.
        assertEquals("Unknown blueprint `servicex`", finding.message)
    }

    // --- UNKNOWN_RELATION ------------------------------------------------------------------------

    @Test
    fun `unknown relation type reports a suggestion over relation keys, hierarchy ids and team`() {
        val a = node("a", listOf("service"), line = 1)
        val b = node("b", listOf("group"), line = 1)
        val e = edge(types = listOf("onwer"), line = 7)
        val q = query(listOf(match(chain(a, e, b))), returns = returnVars("a", "b"))
        val finding = validateEntityQuery(q, baseSchema()).single { it.code == QueryDiagnosticCodes.UNKNOWN_RELATION }
        assertEquals(7, finding.line)
        assertEquals("owner", finding.suggestion)
    }

    @Test
    fun `a relation key resolving to the wrong far label is reported with both blueprint identifiers`() {
        val a = node("a", listOf("service"))
        val b = node("b", listOf("resource"))
        val e = edge(types = listOf("owner"), line = 9)
        val q = query(listOf(match(chain(a, e, b))), returns = returnVars("a", "b"))
        val finding = validateEntityQuery(q, baseSchema()).single { it.code == QueryDiagnosticCodes.UNKNOWN_RELATION }
        assertEquals(9, finding.line)
        assertTrue(finding.message.contains("targets `group`, not `resource`"), finding.message)
    }

    @Test
    fun `an unlabelled far end skips the target check entirely`() {
        val a = node("a", listOf("service"))
        val b = node("b")
        val e = edge(types = listOf("owner"))
        val q = query(listOf(match(chain(a, e, b))), returns = returnVars("a", "b"))
        assertTrue(codes(q).none { it == QueryDiagnosticCodes.UNKNOWN_RELATION })
    }

    @Test
    fun `a hierarchy id is accepted as an edge type without a target check`() {
        val a = node("a", listOf("service"))
        val b = node("b", listOf("resource"))
        val e = edge(types = listOf("composition"))
        val q = query(listOf(match(chain(a, e, b))), returns = returnVars("a", "b"))
        assertTrue(codes(q).none { it == QueryDiagnosticCodes.UNKNOWN_RELATION })
    }

    @Test
    fun `the team edge to a non-_team label is reported`() {
        val a = node("a", listOf("service"))
        val b = node("b", listOf("group"), line = 4)
        val e = edge(types = listOf("\$team"), line = 4)
        val q = query(listOf(match(chain(a, e, b))), returns = returnVars("a", "b"))
        val finding = validateEntityQuery(q, baseSchema()).single { it.code == QueryDiagnosticCodes.UNKNOWN_RELATION }
        assertTrue(finding.message.contains("targets `$SYSTEM_TEAM_BLUEPRINT`, not `group`"), finding.message)
    }

    @Test
    fun `the team edge to a _team label is accepted`() {
        val a = node("a", listOf("service"))
        val b = node("b", listOf(SYSTEM_TEAM_BLUEPRINT))
        val e = edge(types = listOf("\$team"))
        val q = query(listOf(match(chain(a, e, b))), returns = returnVars("a", "b"))
        assertTrue(codes(q).none { it == QueryDiagnosticCodes.UNKNOWN_RELATION })
    }

    // --- UNKNOWN_PROPERTY ------------------------------------------------------------------------

    @Test
    fun `unknown property in WHERE suggests the nearest known property`() {
        val a = node("a", listOf("service"))
        val where = Expr.Compare(ComparisonOp.EQUAL, prop("a", "nam", line = 11), lit("x"), span(11))
        val q = query(listOf(match(singlePattern(a), where = where)), returns = returnVars("a"))
        val finding = validateEntityQuery(q, baseSchema()).single { it.code == QueryDiagnosticCodes.UNKNOWN_PROPERTY }
        assertEquals(11, finding.line)
        assertEquals("name", finding.suggestion)
    }

    @Test
    fun `unknown property on a node's own property map is checked against that node's labels`() {
        val a = node("a", listOf("service"), properties = mapOf("nam" to Literal(JsonPrimitive("x"), span(2))), line = 2)
        val q = query(listOf(match(singlePattern(a))), returns = returnVars("a"))
        val finding = validateEntityQuery(q, baseSchema()).single { it.code == QueryDiagnosticCodes.UNKNOWN_PROPERTY }
        assertEquals(2, finding.line)
    }

    @Test
    fun `an unlabelled variable's property is checked against the union of every blueprint`() {
        val a = node("a")
        val where = Expr.Compare(ComparisonOp.EQUAL, prop("a", "name"), lit("x"), span(1))
        val q = query(listOf(match(singlePattern(a), where = where)), returns = returnVars("a"))
        // "name" exists on "service" only, but the union over an UNLABELLED variable still allows it.
        assertTrue(codes(q).none { it == QueryDiagnosticCodes.UNKNOWN_PROPERTY })
    }

    @Test
    fun `a meta property never trips UNKNOWN_PROPERTY`() {
        val a = node("a", listOf("service"))
        val where = Expr.Compare(ComparisonOp.EQUAL, prop("a", "\$identifier"), lit("x"), span(1))
        val q = query(listOf(match(singlePattern(a), where = where)), returns = returnVars("a"))
        assertTrue(codes(q).isEmpty())
    }

    // --- UNKNOWN_VARIABLE / RELATIONSHIP_VARIABLE_REFERENCE ---------------------------------------

    @Test
    fun `a never-bound RETURN variable is UNKNOWN_VARIABLE`() {
        val a = node("a", listOf("service"))
        val q = query(listOf(match(singlePattern(a))), returns = returnVars("a", "b", line = 6))
        val finding = validateEntityQuery(q, baseSchema()).single { it.code == QueryDiagnosticCodes.UNKNOWN_VARIABLE }
        assertEquals(6, finding.line)
    }

    @Test
    fun `a never-bound WHERE variable is UNKNOWN_VARIABLE with a suggestion`() {
        val a = node("a", listOf("service"))
        val where = Expr.Compare(ComparisonOp.EQUAL, prop("ab", "name", line = 8), lit("x"), span(8))
        val q = query(listOf(match(singlePattern(a), where = where)), returns = returnVars("a"))
        val finding = validateEntityQuery(q, baseSchema()).single { it.code == QueryDiagnosticCodes.UNKNOWN_VARIABLE }
        assertEquals("a", finding.suggestion)
    }

    @Test
    fun `an edge variable referenced in WHERE is RELATIONSHIP_VARIABLE_REFERENCE, not UNKNOWN_VARIABLE`() {
        val a = node("a")
        val b = node("b")
        val e = edge(variable = "r")
        val where = Expr.Truthy(prop("r", "\$identifier", line = 4), span(4))
        val q = query(listOf(match(chain(a, e, b), where = where)), returns = returnVars("a", "b"))
        val finding = validateEntityQuery(q, baseSchema()).single()
        assertEquals(QueryDiagnosticCodes.RELATIONSHIP_VARIABLE_REFERENCE, finding.code)
        assertEquals(4, finding.line)
    }

    @Test
    fun `an edge variable returned is RELATIONSHIP_VARIABLE_REFERENCE`() {
        val a = node("a")
        val b = node("b")
        val e = edge(variable = "r")
        val q = query(listOf(match(chain(a, e, b))), returns = returnVars("r", line = 9))
        val finding = validateEntityQuery(q, baseSchema()).single()
        assertEquals(QueryDiagnosticCodes.RELATIONSHIP_VARIABLE_REFERENCE, finding.code)
        assertEquals(9, finding.line)
    }

    // --- DUPLICATE_VARIABLE ------------------------------------------------------------------------

    @Test
    fun `the same name bound as node and edge is DUPLICATE_VARIABLE`() {
        val a = node("a")
        val b = node("b")
        val e = edge(variable = "a", line = 3)
        val q = query(listOf(match(chain(a, e, b))), returns = returnAll())
        val finding = validateEntityQuery(q, baseSchema()).single { it.code == QueryDiagnosticCodes.DUPLICATE_VARIABLE }
        assertEquals(3, finding.line)
    }

    @Test
    fun `a node variable re-bound with different labels is DUPLICATE_VARIABLE`() {
        val a1 = node("a", listOf("service"))
        val a2 = node("a", listOf("group"), line = 5)
        val q = query(listOf(match(singlePattern(a1), singlePattern(a2))), returns = returnAll())
        val finding = validateEntityQuery(q, baseSchema()).single { it.code == QueryDiagnosticCodes.DUPLICATE_VARIABLE }
        assertEquals(5, finding.line)
    }

    @Test
    fun `a node variable re-referenced with no labels or identical labels is legitimate, not flagged`() {
        val a1 = node("a", listOf("service"))
        val a2 = node("a")
        val q1 = query(listOf(match(singlePattern(a1), singlePattern(a2))), returns = returnAll())
        assertTrue(codes(q1).none { it == QueryDiagnosticCodes.DUPLICATE_VARIABLE })

        val a3 = node("a", listOf("service"))
        val a4 = node("a", listOf("service"))
        val q2 = query(listOf(match(singlePattern(a3), singlePattern(a4))), returns = returnAll())
        assertTrue(codes(q2).none { it == QueryDiagnosticCodes.DUPLICATE_VARIABLE })

        // The mirror order — unlabelled first, labelled later — is the same legitimate re-reference.
        val a5 = node("a")
        val a6 = node("a", listOf("service"))
        val q3 = query(listOf(match(singlePattern(a5), singlePattern(a6))), returns = returnAll())
        assertTrue(codes(q3).none { it == QueryDiagnosticCodes.DUPLICATE_VARIABLE })
    }

    @Test
    fun `an edge variable used twice is DUPLICATE_VARIABLE`() {
        val p1 = chain(node("a"), edge(variable = "r"), node("b"))
        val p2 = chain(node("c"), edge(variable = "r", line = 6), node("d"))
        val q = query(listOf(match(p1, p2)), returns = returnAll())
        val finding = validateEntityQuery(q, baseSchema()).single { it.code == QueryDiagnosticCodes.DUPLICATE_VARIABLE }
        assertEquals(6, finding.line)
    }

    // --- RANGE_INVALID / LIMIT_INVALID ---------------------------------------------------------------

    @Test
    fun `an inverted or out-of-bounds hop range is RANGE_INVALID`() {
        val inverted = edge(range = IntRange(5, 2), line = 1)
        val tooWide = edge(range = 0..11, line = 2)
        val q1 = query(listOf(match(chain(node("a"), inverted, node("b")))), returns = returnAll())
        val q2 = query(listOf(match(chain(node("a"), tooWide, node("b")))), returns = returnAll())
        assertEquals(1, validateEntityQuery(q1, baseSchema()).single { it.code == QueryDiagnosticCodes.RANGE_INVALID }.line)
        assertEquals(2, validateEntityQuery(q2, baseSchema()).single { it.code == QueryDiagnosticCodes.RANGE_INVALID }.line)
    }

    @Test
    fun `a LIMIT outside 1 to MAX_ENTITIES_TOTAL is LIMIT_INVALID`() {
        val a = node("a", listOf("service"))
        val tooLow = query(listOf(match(singlePattern(a))), returns = returnVars("a"), limit = 0)
        val tooHigh = query(listOf(match(singlePattern(a))), returns = returnVars("a"), limit = MAX_ENTITIES_TOTAL + 1)
        assertTrue(codes(tooLow).contains(QueryDiagnosticCodes.LIMIT_INVALID))
        assertTrue(codes(tooHigh).contains(QueryDiagnosticCodes.LIMIT_INVALID))
        val ok = query(listOf(match(singlePattern(a))), returns = returnVars("a"), limit = MAX_ENTITIES_TOTAL)
        assertTrue(codes(ok).none { it == QueryDiagnosticCodes.LIMIT_INVALID })
    }

    // --- DISCONNECTED_PATTERN -----------------------------------------------------------------------

    @Test
    fun `two plain patterns sharing no variable are DISCONNECTED_PATTERN naming both`() {
        val a = node("a", listOf("service"))
        val x = node("x", listOf("group"))
        val q = query(listOf(match(singlePattern(a), singlePattern(x))), returns = returnVars("a", "x"))
        val finding = validateEntityQuery(q, baseSchema()).single { it.code == QueryDiagnosticCodes.DISCONNECTED_PATTERN }
        assertTrue(finding.message.contains("`a`"), finding.message)
        assertTrue(finding.message.contains("`x`"), finding.message)
    }

    @Test
    fun `plain patterns sharing a variable across two MATCH clauses are connected`() {
        val a1 = node("a", listOf("service"))
        val a2 = node("a")
        val b = node("b", listOf("group"))
        val q = query(listOf(match(singlePattern(a1)), match(chain(a2, edge(types = listOf("owner")), b))), returns = returnVars("a", "b"))
        assertTrue(codes(q).none { it == QueryDiagnosticCodes.DISCONNECTED_PATTERN })
    }

    @Test
    fun `OPTIONAL MATCH reusing a bound variable is fine`() {
        val a = node("a", listOf("service"))
        val a2 = node("a")
        val b = node("b", listOf("group"))
        val plain = match(singlePattern(a))
        val opt = match(chain(a2, edge(types = listOf("owner")), b), optional = true)
        val q = query(listOf(plain), listOf(opt), returns = returnVars("a", "b"))
        assertTrue(codes(q).none { it == QueryDiagnosticCodes.DISCONNECTED_PATTERN })
    }

    @Test
    fun `OPTIONAL MATCH reusing nothing from the plain part is DISCONNECTED_PATTERN`() {
        val a = node("a", listOf("service"))
        val x = node("x")
        val y = node("y")
        val plain = match(singlePattern(a))
        val opt = match(chain(x, edge(types = listOf("owner")), y), optional = true)
        val q = query(listOf(plain), listOf(opt), returns = returnVars("a"))
        assertTrue(codes(q).contains(QueryDiagnosticCodes.DISCONNECTED_PATTERN))
    }

    // --- TOO_MANY_PATTERNS / TOO_MANY_VARIABLES ---------------------------------------------------

    @Test
    fun `more node patterns than the cap is TOO_MANY_PATTERNS`() {
        val patterns = (1..40).map { singlePattern(node("v$it", listOf("service"))) }
        val q = query(listOf(match(*patterns.toTypedArray())), returns = returnAll())
        assertTrue(codes(q).contains(QueryDiagnosticCodes.TOO_MANY_PATTERNS))
    }

    @Test
    fun `more distinct variables than the cap is TOO_MANY_VARIABLES`() {
        val patterns = (1..40).map { singlePattern(node("v$it")) }
        val q = query(listOf(match(*patterns.toTypedArray())), returns = returnAll())
        assertTrue(codes(q).contains(QueryDiagnosticCodes.TOO_MANY_VARIABLES))
    }

    // --- VariableSlots ------------------------------------------------------------------------------

    @Test
    fun `variable slots are assigned in first-appearance order, edges excluded`() {
        val a = node("a", listOf("service"))
        val b = node("b", listOf("group"))
        val e = edge(variable = "r", types = listOf("owner"))
        val q = query(listOf(match(chain(a, e, b))), returns = returnVars("a", "b"))
        val (_, slots) = validateAndBind(q, baseSchema())
        assertEquals(mapOf("a" to 0, "b" to 1), slots.nodes)
        assertNull(slots.nodes["r"])
    }
}
