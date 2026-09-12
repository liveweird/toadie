package ch.nokillswit.entityquery

/**
 * Node/edge/arrow/range/property-map grammar for the entity query language's parser
 * (`QueryParser.kt` owns clause structure and the rejected-keyword table; `ExpressionParser.kt`
 * owns WHERE precedence climbing and [parseLiteral], reused here for property-map values).
 */

private fun isEdgeStart(cursor: TokenCursor): Boolean = cursor.check(TokenKind.MINUS) || cursor.check(TokenKind.LT)

internal fun parsePattern(cursor: TokenCursor): Pattern {
    rejectPathVariable(cursor)
    val start = cursor.peek().span
    val nodes = mutableListOf(parseNode(cursor))
    val edges = mutableListOf<EdgePattern>()
    while (isEdgeStart(cursor)) {
        edges += parseEdge(cursor)
        nodes += parseNode(cursor)
    }
    return Pattern(nodes, edges, start.until(cursor.previous().span))
}

/** `p = (…)` — path variables are outside the accepted subset. */
private fun rejectPathVariable(cursor: TokenCursor) {
    val head = cursor.peek()
    if (head.kind != TokenKind.IDENT || isReservedWord(head.text)) return
    if (cursor.peek(1).kind == TokenKind.EQ && cursor.peek(2).kind == TokenKind.LPAREN) {
        throw unsupported(head.span, "path variables are not supported")
    }
}

internal fun parseNode(cursor: TokenCursor): NodePattern {
    val open = cursor.expect(TokenKind.LPAREN)
    var variable: String? = null
    if (cursor.check(TokenKind.IDENT) && !isReservedWord(cursor.peek().text)) {
        variable = parseVariableName(cursor)
    }
    val labels = parseLabels(cursor)
    val properties = if (cursor.check(TokenKind.LBRACE)) parsePropertyMap(cursor) else emptyMap()
    if (cursor.checkKeyword("WHERE")) {
        throw unsupported(cursor.peek().span, "WHERE is not supported inside a node pattern")
    }
    val close = cursor.expect(TokenKind.RPAREN)
    return NodePattern(variable, labels, properties, open.span.until(close.span))
}

/** `:label ('|' label)*` — a second leading `:` after the alternatives means multiple labels, which is unsupported. */
private fun parseLabels(cursor: TokenCursor): List<String> {
    if (cursor.match(TokenKind.COLON) == null) return emptyList()
    val labels = mutableListOf(parseLabelName(cursor))
    while (cursor.match(TokenKind.PIPE) != null) {
        labels += parseLabelName(cursor)
    }
    if (cursor.check(TokenKind.COLON)) {
        throw unsupported(cursor.peek().span, "an entity has exactly one blueprint — use `:A|B` for alternatives")
    }
    return labels
}

private fun parseLabelName(cursor: TokenCursor): String {
    val token = cursor.peek()
    return when (token.kind) {
        TokenKind.IDENT, TokenKind.BACKTICK_IDENT -> cursor.advance().text
        else -> cursor.unexpected("a blueprint identifier")
    }
}

/**
 * Assembles one edge from `-`/`<`/`>` tokens plus an optional bracketed body: `-->`, `<--`,
 * `--`, `-[body]->`, `<-[body]-`, `-[body]-`. Every dash/arrow segment must be OFFSET-adjacent
 * to its neighbour (`TokenCursor.adjacent`) — a space anywhere in the arrow is a syntax error,
 * which also keeps `a < -1` in WHERE from ever reaching this parser (arrows are only assembled
 * where a pattern is expected).
 */
internal fun parseEdge(cursor: TokenCursor): EdgePattern {
    val edgeStart = cursor.peek().span
    var incoming = false
    var lastToken: Token
    if (cursor.check(TokenKind.LT)) {
        val lt = cursor.advance()
        lastToken = cursor.expect(TokenKind.MINUS)
        requireAdjacent(cursor, lt, lastToken)
        incoming = true
    } else {
        lastToken = cursor.expect(TokenKind.MINUS)
    }

    var variable: String? = null
    var types: List<String> = emptyList()
    var range: IntRange? = null

    if (cursor.check(TokenKind.LBRACKET) && cursor.adjacent(lastToken, cursor.peek())) {
        cursor.advance()
        if (cursor.check(TokenKind.IDENT) && !isReservedWord(cursor.peek().text)) {
            variable = parseVariableName(cursor)
        }
        if (cursor.check(TokenKind.COLON)) {
            types = parseEdgeTypes(cursor)
        }
        if (cursor.check(TokenKind.STAR)) {
            range = parseRange(cursor)
        }
        lastToken = cursor.expect(TokenKind.RBRACKET)
    }

    val closeDash = cursor.expect(TokenKind.MINUS)
    requireAdjacent(cursor, lastToken, closeDash)
    lastToken = closeDash

    var outgoing = false
    if (!incoming && cursor.check(TokenKind.GT) && cursor.adjacent(lastToken, cursor.peek())) {
        lastToken = cursor.advance()
        outgoing = true
    }

    val direction = when {
        incoming -> EdgeDirection.IN
        outgoing -> EdgeDirection.OUT
        else -> EdgeDirection.UNDIRECTED
    }
    return EdgePattern(variable, types, direction, range, edgeStart.until(lastToken.span))
}

private fun requireAdjacent(cursor: TokenCursor, a: Token, b: Token) {
    if (!cursor.adjacent(a, b)) {
        throw QueryException(
            QueryDiagnostic(
                QueryDiagnosticCodes.SYNTAX,
                "Expected an unbroken edge arrow but found a gap before '${b.text}'",
                b.span.line,
                b.span.column,
                b.span.endLine,
                b.span.endColumn,
            ),
        )
    }
}

private fun parseEdgeTypes(cursor: TokenCursor): List<String> {
    cursor.expect(TokenKind.COLON)
    val types = mutableListOf(parseEdgeTypeName(cursor))
    while (cursor.match(TokenKind.PIPE) != null) {
        types += parseEdgeTypeName(cursor)
    }
    return types
}

private fun parseEdgeTypeName(cursor: TokenCursor): String {
    val token = cursor.peek()
    return when (token.kind) {
        TokenKind.IDENT, TokenKind.BACKTICK_IDENT, TokenKind.META -> cursor.advance().text
        else -> cursor.unexpected("a relation type")
    }
}

/** `* (INTEGER? ('..' INTEGER?)?)?` — `*` = `1..MAX_QUERY_HOPS`, `*n` = `n..n`, `*n..`/`*..m` fill the other bound. */
internal fun parseRange(cursor: TokenCursor): IntRange {
    val star = cursor.expect(TokenKind.STAR)
    val minToken = if (cursor.check(TokenKind.NUMBER)) cursor.advance() else null
    if (cursor.match(TokenKind.DOTDOT) == null) {
        val n = minToken?.let { parseRangeInteger(it) } ?: return 1..MAX_QUERY_HOPS
        validateRange(star.span, n, n)
        return n..n
    }
    val maxToken = if (cursor.check(TokenKind.NUMBER)) cursor.advance() else null
    val min = minToken?.let { parseRangeInteger(it) } ?: 1
    val max = maxToken?.let { parseRangeInteger(it) } ?: MAX_QUERY_HOPS
    validateRange(star.span, min, max)
    return min..max
}

private fun parseRangeInteger(token: Token): Int {
    if (token.text.contains('.')) throw rangeInvalid(token.span, "Range bounds must be integers")
    return token.text.toIntOrNull() ?: throw rangeInvalid(token.span, "Range bounds must be integers")
}

private fun validateRange(span: Span, min: Int, max: Int) {
    // min < 0 is unreachable — the lexer never produces a signed NUMBER token.
    if (max < min || max > MAX_QUERY_HOPS) {
        throw rangeInvalid(span, "Range must satisfy 0 <= min <= max <= $MAX_QUERY_HOPS")
    }
}

private fun rangeInvalid(span: Span, message: String): QueryException = QueryException(
    QueryDiagnostic(QueryDiagnosticCodes.RANGE_INVALID, message, span.line, span.column, span.endLine, span.endColumn),
)

internal fun parsePropertyMap(cursor: TokenCursor): Map<String, Literal> {
    cursor.expect(TokenKind.LBRACE)
    val map = linkedMapOf<String, Literal>()
    if (!cursor.check(TokenKind.RBRACE)) {
        parsePropertyEntry(cursor, map)
        while (cursor.match(TokenKind.COMMA) != null) {
            parsePropertyEntry(cursor, map)
        }
    }
    cursor.expect(TokenKind.RBRACE)
    return map
}

private fun parsePropertyEntry(cursor: TokenCursor, map: MutableMap<String, Literal>) {
    val key = parsePropertyKey(cursor)
    cursor.expect(TokenKind.COLON)
    map[key] = parseLiteral(cursor)
}

private fun parsePropertyKey(cursor: TokenCursor): String {
    val token = cursor.peek()
    return when (token.kind) {
        TokenKind.IDENT, TokenKind.BACKTICK_IDENT, TokenKind.META -> cursor.advance().text
        else -> cursor.unexpected("a property key")
    }
}
