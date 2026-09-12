package ch.nokillswit

import ch.nokillswit.entities.MAX_ENTITIES_TOTAL
import ch.nokillswit.entityquery.ComparisonOp
import ch.nokillswit.entityquery.EdgeDirection
import ch.nokillswit.entityquery.Expr
import ch.nokillswit.entityquery.MAX_QUERY_HOPS
import ch.nokillswit.entityquery.MAX_QUERY_LENGTH
import ch.nokillswit.entityquery.MAX_QUERY_NODE_PATTERNS
import ch.nokillswit.entityquery.Operand
import ch.nokillswit.entityquery.QueryDiagnosticCodes
import ch.nokillswit.entityquery.QueryException
import ch.nokillswit.entityquery.Returns
import ch.nokillswit.entityquery.StringOperator
import ch.nokillswit.entityquery.parseEntityQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure coverage of `entityquery/QueryParser.kt` + `PatternParser.kt` + `ExpressionParser.kt` —
 * one case per grammar production, plus every rejection message and cap. No database —
 * `parseEntityQuery` is a pure function over the source text.
 */
class QueryParserTest {

    // ---- Grammar productions -------------------------------------------------------------

    @Test
    fun `a bare OUT arrow parses two node patterns and one edge`() {
        val query = parseEntityQuery("MATCH (a)-->(b) RETURN a, b")
        val pattern = query.matches.single().patterns.single()
        assertEquals(listOf("a", "b"), pattern.nodes.map { it.variable })
        val edge = pattern.edges.single()
        assertEquals(EdgeDirection.OUT, edge.direction)
        assertNull(edge.variable)
        assertTrue(edge.types.isEmpty())
        assertNull(edge.range)
    }

    @Test
    fun `a bare IN arrow parses`() {
        val edge = parseEntityQuery("MATCH (a)<--(b) RETURN a").matches.single().patterns.single().edges.single()
        assertEquals(EdgeDirection.IN, edge.direction)
    }

    @Test
    fun `a bare undirected arrow parses`() {
        val edge = parseEntityQuery("MATCH (a)--(b) RETURN a").matches.single().patterns.single().edges.single()
        assertEquals(EdgeDirection.UNDIRECTED, edge.direction)
    }

    @Test
    fun `an edge body carries variable, types and direction`() {
        val edge = parseEntityQuery("MATCH (a)-[r:owned_by|\$team]->(b) RETURN a").matches.single().patterns.single().edges.single()
        assertEquals("r", edge.variable)
        assertEquals(listOf("owned_by", "\$team"), edge.types)
        assertEquals(EdgeDirection.OUT, edge.direction)
    }

    @Test
    fun `an incoming edge with a body parses`() {
        val edge = parseEntityQuery("MATCH (a)<-[:parent]-(b) RETURN a").matches.single().patterns.single().edges.single()
        assertEquals(EdgeDirection.IN, edge.direction)
        assertEquals(listOf("parent"), edge.types)
    }

    @Test
    fun `an undirected edge with a body parses`() {
        val edge = parseEntityQuery("MATCH (a)-[:related]-(b) RETURN a").matches.single().patterns.single().edges.single()
        assertEquals(EdgeDirection.UNDIRECTED, edge.direction)
    }

    @Test
    fun `range bounds - bare star defaults to 1 through MAX_QUERY_HOPS`() {
        val edge = parseEntityQuery("MATCH (a)-[:r*]->(b) RETURN a").matches.single().patterns.single().edges.single()
        assertEquals(1..MAX_QUERY_HOPS, edge.range)
    }

    @Test
    fun `range bounds - star n means exactly n hops`() {
        val edge = parseEntityQuery("MATCH (a)-[:r*3]->(b) RETURN a").matches.single().patterns.single().edges.single()
        assertEquals(3..3, edge.range)
    }

    @Test
    fun `range bounds - star n dotdot m is an explicit range`() {
        val edge = parseEntityQuery("MATCH (a)-[:r*1..3]->(b) RETURN a").matches.single().patterns.single().edges.single()
        assertEquals(1..3, edge.range)
    }

    @Test
    fun `range bounds - star n dotdot defaults the upper bound to MAX_QUERY_HOPS`() {
        val edge = parseEntityQuery("MATCH (a)-[:r*2..]->(b) RETURN a").matches.single().patterns.single().edges.single()
        assertEquals(2..MAX_QUERY_HOPS, edge.range)
    }

    @Test
    fun `range bounds - star dotdot m defaults the lower bound to 1`() {
        val edge = parseEntityQuery("MATCH (a)-[:r*..4]->(b) RETURN a").matches.single().patterns.single().edges.single()
        assertEquals(1..4, edge.range)
    }

    @Test
    fun `range bounds - star 0 dotdot 2 allows a zero lower bound`() {
        val edge = parseEntityQuery("MATCH (a)-[:r*0..2]->(b) RETURN a").matches.single().patterns.single().edges.single()
        assertEquals(0..2, edge.range)
    }

    @Test
    fun `node labels support alternatives with the pipe`() {
        val node = parseEntityQuery("MATCH (a:Foo|Bar) RETURN a").matches.single().patterns.single().nodes.single()
        assertEquals(listOf("Foo", "Bar"), node.labels)
    }

    @Test
    fun `backticked labels and property keys unquote hyphenated names`() {
        val node = parseEntityQuery(
            "MATCH (a:`web-service` {`display-name`: 'X', \$identifier: 'svc-1'}) RETURN a",
        ).matches.single().patterns.single().nodes.single()
        assertEquals(listOf("web-service"), node.labels)
        assertEquals("X", (node.properties.getValue("display-name").value as JsonPrimitive).content)
        assertEquals("svc-1", (node.properties.getValue("\$identifier").value as JsonPrimitive).content)
    }

    @Test
    fun `property maps accept list and negative number literals`() {
        val node = parseEntityQuery("MATCH (a {count: -3, tags: ['x', 'y']}) RETURN a")
            .matches.single().patterns.single().nodes.single()
        assertEquals("-3", node.properties.getValue("count").value.jsonPrimitive.content)
        val tags = node.properties.getValue("tags").value as JsonArray
        assertEquals(listOf("x", "y"), tags.map { it.jsonPrimitive.content })
    }

    @Test
    fun `where precedence is OR below AND below NOT`() {
        val where = parseEntityQuery("MATCH (a) WHERE a.x OR a.y AND NOT a.z RETURN a").matches.single().where
        val or = assertIs<Expr.Or>(where)
        assertIs<Expr.Truthy>(or.left)
        val and = assertIs<Expr.And>(or.right)
        assertIs<Expr.Truthy>(and.left)
        val not = assertIs<Expr.Not>(and.right)
        assertIs<Expr.Truthy>(not.operand)
    }

    @Test
    fun `parentheses override default precedence`() {
        val where = parseEntityQuery("MATCH (a) WHERE (a.x OR a.y) AND a.z RETURN a").matches.single().where
        val and = assertIs<Expr.And>(where)
        assertIs<Expr.Or>(and.left)
        assertIs<Expr.Truthy>(and.right)
    }

    @Test
    fun `every comparison operator parses to its ComparisonOp`() {
        val cases = listOf(
            "=" to ComparisonOp.EQUAL,
            "<>" to ComparisonOp.NOT_EQUAL,
            "!=" to ComparisonOp.NOT_EQUAL,
            "<" to ComparisonOp.LESS,
            "<=" to ComparisonOp.LESS_OR_EQUAL,
            ">" to ComparisonOp.GREATER,
            ">=" to ComparisonOp.GREATER_OR_EQUAL,
        )
        for ((symbol, expected) in cases) {
            val where = parseEntityQuery("MATCH (a) WHERE a.x $symbol 1 RETURN a").matches.single().where
            val compare = assertIs<Expr.Compare>(where, "operator $symbol")
            assertEquals(expected, compare.op, "operator $symbol")
        }
    }

    @Test
    fun `IN accepts a list literal on the right`() {
        val where = parseEntityQuery("MATCH (a) WHERE a.tags IN ['x', 'y'] RETURN a").matches.single().where
        val inExpr = assertIs<Expr.In>(where)
        assertIs<Operand.Property>(inExpr.left)
        val right = assertIs<Operand.Value>(inExpr.right)
        assertIs<JsonArray>(right.literal.value)
    }

    @Test
    fun `CONTAINS STARTS WITH and ENDS WITH parse to their StringOperator`() {
        val cases = listOf(
            "CONTAINS 'x'" to StringOperator.CONTAINS,
            "STARTS WITH 'x'" to StringOperator.STARTS_WITH,
            "ENDS WITH 'x'" to StringOperator.ENDS_WITH,
        )
        for ((clause, expected) in cases) {
            val where = parseEntityQuery("MATCH (a) WHERE a.name $clause RETURN a").matches.single().where
            val stringOp = assertIs<Expr.StringOp>(where, clause)
            assertEquals(expected, stringOp.op, clause)
        }
    }

    @Test
    fun `IS NULL and IS NOT NULL carry the negated flag`() {
        val isNull = assertIs<Expr.IsNull>(parseEntityQuery("MATCH (a) WHERE a.x IS NULL RETURN a").matches.single().where)
        assertEquals(false, isNull.negated)
        val isNotNull =
            assertIs<Expr.IsNull>(parseEntityQuery("MATCH (a) WHERE a.x IS NOT NULL RETURN a").matches.single().where)
        assertEquals(true, isNotNull.negated)
    }

    @Test
    fun `a bare operand is a truthy predicate`() {
        val truthy = assertIs<Expr.Truthy>(parseEntityQuery("MATCH (a) WHERE a.active RETURN a").matches.single().where)
        val property = assertIs<Operand.Property>(truthy.operand)
        assertEquals("a", property.variable)
        assertEquals("active", property.key)
    }

    @Test
    fun `multiple MATCH clauses and comma-separated patterns`() {
        val query = parseEntityQuery("MATCH (a), (b) MATCH (c) RETURN a, b, c")
        assertEquals(2, query.matches.size)
        assertEquals(2, query.matches[0].patterns.size)
        assertEquals(1, query.matches[1].patterns.size)
    }

    @Test
    fun `OPTIONAL MATCH with WHERE is kept separate from plain matches`() {
        val query = parseEntityQuery("MATCH (a) OPTIONAL MATCH (a)-->(b) WHERE b.active RETURN a, b")
        assertEquals(1, query.matches.size)
        assertEquals(1, query.optionals.size)
        assertTrue(query.optionals.single().optional)
        assertIs<Expr.Truthy>(query.optionals.single().where)
    }

    @Test
    fun `RETURN star yields Returns All`() {
        assertIs<Returns.All>(parseEntityQuery("MATCH (a) RETURN *").returns)
    }

    @Test
    fun `RETURN DISTINCT is accepted and dropped`() {
        val returns = assertIs<Returns.Variables>(parseEntityQuery("MATCH (a) RETURN DISTINCT a, b").returns)
        assertEquals(listOf("a", "b"), returns.names)
    }

    @Test
    fun `LIMIT is parsed as a plain integer`() {
        assertEquals(5, parseEntityQuery("MATCH (a) RETURN a LIMIT 5").limit)
    }

    @Test
    fun `comments are skipped and keywords are case-insensitive`() {
        val query = parseEntityQuery("match (a) // find a\nReTuRn a /* trailing */")
        assertEquals(1, query.matches.size)
        assertIs<Returns.Variables>(query.returns)
    }

    // ---- Rejections -----------------------------------------------------------------------

    @Test
    fun `every rejected keyword produces the fixed UNSUPPORTED message`() {
        val rejected = listOf(
            "CREATE", "MERGE", "SET", "DELETE", "DETACH", "REMOVE", "CALL", "WITH",
            "UNWIND", "FOREACH", "LOAD", "UNION", "ORDER", "SKIP", "CASE", "XOR",
        )
        for (keyword in rejected) {
            val ex = assertFailsWith<QueryException>("keyword $keyword") { parseEntityQuery("$keyword x") }
            val diagnostic = ex.diagnostics.single()
            assertEquals(QueryDiagnosticCodes.UNSUPPORTED, diagnostic.code, "keyword $keyword")
            assertEquals(
                "`$keyword` is not supported — Toadie 2.0.0 accepts MATCH, OPTIONAL MATCH, WHERE, RETURN and LIMIT only",
                diagnostic.message,
                "keyword $keyword",
            )
        }
    }

    @Test
    fun `a query parameter outside an allowed position is unsupported`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) WHERE \$x = 1 RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.UNSUPPORTED, diagnostic.code)
        assertEquals("query parameters are not supported", diagnostic.message)
    }

    @Test
    fun `the regex operator is unsupported`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) WHERE a.x =~ 'foo' RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.UNSUPPORTED, diagnostic.code)
        assertTrue(diagnostic.message.contains("regular expressions"))
    }

    @Test
    fun `a function call in operand position is unsupported`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) WHERE toLower(a.x) = 'y' RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.UNSUPPORTED, diagnostic.code)
        assertEquals("functions are not supported", diagnostic.message)
    }

    @Test
    fun `path variables are unsupported`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH p = (a)-->(b) RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.UNSUPPORTED, diagnostic.code)
        assertEquals("path variables are not supported", diagnostic.message)
    }

    @Test
    fun `multiple node labels are unsupported`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a:Foo:Bar) RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.UNSUPPORTED, diagnostic.code)
        assertEquals("an entity has exactly one blueprint — use `:A|B` for alternatives", diagnostic.message)
    }

    @Test
    fun `WHERE inside a node pattern is unsupported`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a WHERE a.x = 1) RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.UNSUPPORTED, diagnostic.code)
        assertEquals("WHERE is not supported inside a node pattern", diagnostic.message)
    }

    @Test
    fun `RETURN of a property, AS, or a non-variable is unsupported`() {
        val cases = listOf("MATCH (a) RETURN a.x", "MATCH (a) RETURN a AS b", "MATCH (a) RETURN 1")
        for (text in cases) {
            val ex = assertFailsWith<QueryException>(text) { parseEntityQuery(text) }
            val diagnostic = ex.diagnostics.single()
            assertEquals(QueryDiagnosticCodes.UNSUPPORTED, diagnostic.code, text)
            assertEquals("RETURN yields entities only — list variables or use *", diagnostic.message, text)
        }
    }

    @Test
    fun `a plain MATCH after OPTIONAL MATCH is unsupported`() {
        val ex = assertFailsWith<QueryException> {
            parseEntityQuery("MATCH (a) OPTIONAL MATCH (b) MATCH (c) RETURN a")
        }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.UNSUPPORTED, diagnostic.code)
        assertEquals("a plain MATCH after OPTIONAL MATCH is not supported — put every plain MATCH first", diagnostic.message)
    }

    @Test
    fun `a query longer than MAX_QUERY_LENGTH is rejected before lexing`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("a".repeat(MAX_QUERY_LENGTH + 1)) }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.QUERY_TOO_LONG, diagnostic.code)
        assertNull(diagnostic.line)
    }

    @Test
    fun `too many node patterns is rejected`() {
        val text = "MATCH " + (1..33).joinToString(", ") { "(n$it)" } + " RETURN n1"
        val ex = assertFailsWith<QueryException> { parseEntityQuery(text) }
        assertEquals(QueryDiagnosticCodes.TOO_MANY_PATTERNS, ex.diagnostics.single().code)
    }

    @Test
    fun `too many distinct variables is rejected without tripping the pattern cap`() {
        val nodeCount = 20
        val chain = buildString {
            append("(n1)")
            for (i in 2..nodeCount) append("-[r${i - 1}]->(n$i)")
        }
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH $chain RETURN n1") }
        assertEquals(QueryDiagnosticCodes.TOO_MANY_VARIABLES, ex.diagnostics.single().code)
    }

    @Test
    fun `a range whose minimum exceeds its maximum is invalid`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a)-[:r*5..2]->(b) RETURN a") }
        assertEquals(QueryDiagnosticCodes.RANGE_INVALID, ex.diagnostics.single().code)
    }

    @Test
    fun `a range exceeding MAX_QUERY_HOPS is invalid`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a)-[:r*${MAX_QUERY_HOPS + 1}]->(b) RETURN a") }
        assertEquals(QueryDiagnosticCodes.RANGE_INVALID, ex.diagnostics.single().code)
    }

    @Test
    fun `a non-integer range bound is invalid`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a)-[:r*1.5..3]->(b) RETURN a") }
        assertEquals(QueryDiagnosticCodes.RANGE_INVALID, ex.diagnostics.single().code)
    }

    @Test
    fun `a LIMIT of zero is invalid`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) RETURN a LIMIT 0") }
        assertEquals(QueryDiagnosticCodes.LIMIT_INVALID, ex.diagnostics.single().code)
    }

    @Test
    fun `a LIMIT above MAX_ENTITIES_TOTAL is invalid`() {
        val ex = assertFailsWith<QueryException> {
            parseEntityQuery("MATCH (a) RETURN a LIMIT ${MAX_ENTITIES_TOTAL + 1}")
        }
        assertEquals(QueryDiagnosticCodes.LIMIT_INVALID, ex.diagnostics.single().code)
    }

    @Test
    fun `a decimal LIMIT is invalid`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) RETURN a LIMIT 1.5") }
        assertEquals(QueryDiagnosticCodes.LIMIT_INVALID, ex.diagnostics.single().code)
    }

    @Test
    fun `an unterminated string surfaces through parseEntityQuery with a position`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) WHERE a.name = 'x RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertEquals(1, diagnostic.line)
        assertTrue(diagnostic.column != null)
    }

    @Test
    fun `an unexpected token names what was expected and found`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertTrue(diagnostic.message.contains("Expected"))
    }

    @Test
    fun `a keyword used as a variable name is a syntax error`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (match) RETURN match") }
        assertEquals(QueryDiagnosticCodes.SYNTAX, ex.diagnostics.single().code)
    }

    @Test
    fun `a variable in a property map value is a syntax error`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a {x: b}) RETURN a") }
        assertEquals(QueryDiagnosticCodes.SYNTAX, ex.diagnostics.single().code)
    }

    @Test
    fun `the NULL literal parses to JsonNull`() {
        val node = parseEntityQuery("MATCH (a {x: null}) RETURN a").matches.single().patterns.single().nodes.single()
        assertEquals(JsonNull, node.properties.getValue("x").value)
    }

    // ---- Additional tests for new fixes -----------------------------------------------

    @Test
    fun `a number literal that overflows throws SYNTAX error`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) WHERE a.x = 99999999999999999999 RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertTrue(diagnostic.message.contains("out of range"))
        assertEquals(1, diagnostic.line)
        assertEquals(23, diagnostic.column)
    }

    @Test
    fun `a negative number literal that overflows throws SYNTAX error`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) WHERE a.x = -99999999999999999999 RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertTrue(diagnostic.message.contains("out of range"))
    }

    @Test
    fun `a property map value with an overflowing number throws SYNTAX error`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a {x: 99999999999999999999}) RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertTrue(diagnostic.message.contains("out of range"))
    }

    @Test
    fun `a non-finite decimal number throws SYNTAX error`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) WHERE a.x = ${Double.POSITIVE_INFINITY} RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
    }

    @Test
    fun `a function call in RETURN is unsupported`() {
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) RETURN count(a)") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.UNSUPPORTED, diagnostic.code)
        assertTrue(diagnostic.message.contains("functions are not supported"))
        assertEquals(1, diagnostic.line)
        assertTrue((diagnostic.column ?: 0) > 0)
    }

    @Test
    fun `NOT a x = 1 parses as Not wrapping Compare`() {
        val query = parseEntityQuery("MATCH (a) WHERE NOT a.x = 1 RETURN a")
        val expr = query.matches.single().where!!
        assertIs<Expr.Not>(expr)
        val inner = (expr as Expr.Not).operand
        assertIs<Expr.Compare>(inner)
    }

    @Test
    fun `64 nested parentheses parse successfully`() {
        val inner = "a.x"
        val nested = (1..64).fold(inner) { acc, _ -> "($acc)" }
        val query = parseEntityQuery("MATCH (a) WHERE $nested RETURN a")
        assertNotNull(query.matches.single().where)
    }

    @Test
    fun `65 nested parentheses throw a SYNTAX depth error`() {
        val inner = "a.x"
        val nested = (1..65).fold(inner) { acc, _ -> "($acc)" }
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) WHERE $nested RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertTrue(diagnostic.message.contains("deeper than 64 levels"))
    }

    @Test
    fun `65 chained NOT operators throw SYNTAX error for depth exceeded`() {
        val inner = "a.x"
        val chained = (1..65).fold(inner) { acc, _ -> "NOT $acc" }
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a) WHERE $chained RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertTrue(diagnostic.message.contains("deeper than 64 levels"))
    }

    @Test
    fun `64 chained NOT operators parse successfully`() {
        val inner = "a.x"
        val chained = (1..64).fold(inner) { acc, _ -> "NOT $acc" }
        val query = parseEntityQuery("MATCH (a) WHERE $chained RETURN a")
        assertNotNull(query.matches.single().where)
    }

    @Test
    fun `nested list literals up to depth 64 parse successfully`() {
        val nested = (1..64).fold("1" as Any) { acc, _ -> "[1]" }
        val query = parseEntityQuery("MATCH (a {x: $nested}) RETURN a")
        assertNotNull(query)
    }

    @Test
    fun `nested list literals at depth 65 throw SYNTAX error`() {
        val nested = (1..65).fold("1") { acc, _ -> "[$acc]" }
        val ex = assertFailsWith<QueryException> { parseEntityQuery("MATCH (a {x: $nested}) RETURN a") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertTrue(diagnostic.message.contains("deeper than 64 levels"))
    }

    @Test
    fun `TOO_MANY_PATTERNS cap is counted across OPTIONAL MATCH clauses`() {
        val patterns = (1..MAX_QUERY_NODE_PATTERNS).joinToString(", ") { "(n$it)" }
        val optPatterns = (1..3).joinToString(", ") { "(o$it)" }
        val text = "MATCH $patterns OPTIONAL MATCH $optPatterns RETURN n1"
        val ex = assertFailsWith<QueryException> { parseEntityQuery(text) }
        assertEquals(QueryDiagnosticCodes.TOO_MANY_PATTERNS, ex.diagnostics.single().code)
    }

    @Test
    fun `TOO_MANY_VARIABLES cap is counted across OPTIONAL MATCH clauses`() {
        // 16 node + 15 edge variables (31) in the plain MATCH — under the variable cap and well under the
        // pattern cap — then the OPTIONAL MATCH adds two more variables, tipping only the VARIABLE count.
        val chain = (1..16).joinToString("") { i -> if (i == 1) "(v1)" else "-[e${i - 1}]-(v$i)" }
        val text = "MATCH $chain OPTIONAL MATCH (v1)-[extraEdge]-(extraNode) RETURN v1"
        val ex = assertFailsWith<QueryException> { parseEntityQuery(text) }
        assertEquals(QueryDiagnosticCodes.TOO_MANY_VARIABLES, ex.diagnostics.single().code)
    }

    @Test
    fun `a line comment between MATCH clauses is allowed`() {
        val query = parseEntityQuery("""MATCH (a) // comment
            | RETURN a""".trimMargin())
        assertNotNull(query)
    }

    @Test
    fun `a block comment between MATCH clauses is allowed`() {
        val query = parseEntityQuery("MATCH (a) /* comment */ RETURN a")
        assertNotNull(query)
    }
}
