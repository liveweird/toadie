package ch.nokillswit.entityquery

import ch.nokillswit.entities.MAX_ENTITIES_TOTAL

/**
 * The entity query language's parser (`.claude/docs/entity-query-language.md`): a hand-written
 * recursive-descent parser over [lex]'s token stream, producing the [Query] AST
 * (`QueryAst.kt`). Pure — no database, no Ktor. Clause structure and the rejected-keyword
 * table live here; node/edge/property-map grammar is in `PatternParser.kt`, WHERE-expression
 * precedence climbing in `ExpressionParser.kt`; all three share [TokenCursor] and the helpers
 * below ([rejectClauseKeyword], [unsupported], [isReservedWord], [parseVariableName]).
 *
 * Two deliberate simplifications, both documented in the language spec: keywords are NOT
 * reserved in the lexer — they are plain [TokenKind.IDENT] tokens the parser recognizes by
 * text — but a recognized keyword MAY NOT be used as a variable name (`match`, `where`, … as a
 * variable is a `SYNTAX` error, not a grammar ambiguity to resolve); and the parser stops at
 * its FIRST error — a recursive-descent parser cannot usefully resynchronize after a syntax
 * error, unlike the validator which reports every finding it can.
 */

/** Parses [text] into a [Query]. Throws [QueryException] on any lexical, grammatical, or
 * rejected-construct failure, or when a query/pattern/variable cap is exceeded. */
fun parseEntityQuery(text: String): Query {
    if (text.length > MAX_QUERY_LENGTH) {
        throw QueryException(
            QueryDiagnostic(QueryDiagnosticCodes.QUERY_TOO_LONG, "Query exceeds $MAX_QUERY_LENGTH characters"),
        )
    }
    val cursor = TokenCursor(lex(text))
    return parseQuery(cursor)
}

/** A fixed cursor over the lexed [tokens] — no backtracking, since the grammar never needs it. */
internal class TokenCursor(private val tokens: List<Token>) {
    private var index = 0

    fun peek(offset: Int = 0): Token {
        val i = index + offset
        return if (i < tokens.size) tokens[i] else tokens.last()
    }

    fun previous(): Token = tokens[if (index > 0) index - 1 else 0]

    fun advance(): Token {
        val t = peek()
        if (index < tokens.size - 1) index++
        return t
    }

    fun check(kind: TokenKind): Boolean = peek().kind == kind

    fun match(kind: TokenKind): Token? = if (check(kind)) advance() else null

    fun expect(kind: TokenKind): Token {
        if (!check(kind)) unexpected(describeKind(kind))
        return advance()
    }

    fun isKeyword(token: Token, keyword: String): Boolean =
        token.kind == TokenKind.IDENT && token.text.equals(keyword, ignoreCase = true)

    fun checkKeyword(keyword: String): Boolean = isKeyword(peek(), keyword)

    fun matchKeyword(keyword: String): Boolean {
        if (!checkKeyword(keyword)) return false
        advance()
        return true
    }

    fun expectKeyword(keyword: String): Token {
        if (!checkKeyword(keyword)) unexpected("'$keyword'")
        return advance()
    }

    /** True when [a] is immediately followed by [b] in the source text — no whitespace/comment between them. */
    fun adjacent(a: Token, b: Token): Boolean = a.offset + a.text.length == b.offset

    fun unexpected(expectedDescription: String): Nothing {
        val t = peek()
        val found = if (t.kind == TokenKind.EOF) "end of query" else "'${t.text}'"
        throw QueryException(
            QueryDiagnostic(
                QueryDiagnosticCodes.SYNTAX,
                "Expected $expectedDescription but found $found",
                t.span.line,
                t.span.column,
                t.span.endLine,
                t.span.endColumn,
            ),
        )
    }
}

private fun describeKind(kind: TokenKind): String = when (kind) {
    TokenKind.LPAREN -> "'('"
    TokenKind.RPAREN -> "')'"
    TokenKind.LBRACKET -> "'['"
    TokenKind.RBRACKET -> "']'"
    TokenKind.LBRACE -> "'{'"
    TokenKind.RBRACE -> "'}'"
    TokenKind.COLON -> "':'"
    TokenKind.COMMA -> "','"
    TokenKind.DOT -> "'.'"
    TokenKind.EOF -> "end of query"
    TokenKind.IDENT -> "an identifier"
    TokenKind.STRING -> "a string"
    TokenKind.NUMBER -> "a number"
    else -> kind.name
}

/** Cypher features Toadie 2.0.0 does not accept — checked at every clause boundary and wherever an operand is expected. */
internal val REJECTED_KEYWORDS: List<String> = listOf(
    "CREATE", "MERGE", "SET", "DELETE", "DETACH", "REMOVE", "CALL", "WITH",
    "UNWIND", "FOREACH", "LOAD", "UNION", "ORDER", "SKIP", "CASE", "XOR",
)

/** Keywords recognized inside the accepted grammar — reserved against use as a variable name alongside [REJECTED_KEYWORDS]. */
private val GRAMMAR_KEYWORDS: Set<String> = setOf(
    "MATCH", "OPTIONAL", "WHERE", "RETURN", "LIMIT", "DISTINCT",
    "AND", "OR", "NOT", "IN", "CONTAINS", "STARTS", "WITH", "ENDS",
    "IS", "NULL", "TRUE", "FALSE", "AS",
)

private val RESERVED_WORDS: Set<String> = (GRAMMAR_KEYWORDS + REJECTED_KEYWORDS).mapTo(mutableSetOf()) { it.uppercase() }

/** True for any keyword recognized anywhere in the grammar (accepted or rejected) — never a valid variable/name. */
internal fun isReservedWord(text: String): Boolean = text.uppercase() in RESERVED_WORDS

private fun rejectedKeywordMessage(keyword: String): String =
    "`$keyword` is not supported — Toadie 2.0.0 accepts MATCH, OPTIONAL MATCH, WHERE, RETURN and LIMIT only"

/** Builds an `UNSUPPORTED` [QueryException] positioned at [span]. */
internal fun unsupported(span: Span, message: String): QueryException = QueryException(
    QueryDiagnostic(QueryDiagnosticCodes.UNSUPPORTED, message, span.line, span.column, span.endLine, span.endColumn),
)

/** Throws when the current token is one of [REJECTED_KEYWORDS], with the fixed message shape. */
internal fun rejectClauseKeyword(cursor: TokenCursor) {
    val token = cursor.peek()
    if (token.kind != TokenKind.IDENT) return
    val keyword = REJECTED_KEYWORDS.firstOrNull { it.equals(token.text, ignoreCase = true) } ?: return
    throw unsupported(token.span, rejectedKeywordMessage(keyword))
}

/** Parses a bound name (node/edge variable) — a plain, non-reserved identifier. */
internal fun parseVariableName(cursor: TokenCursor): String {
    rejectClauseKeyword(cursor)
    val token = cursor.peek()
    if (token.kind != TokenKind.IDENT || isReservedWord(token.text)) {
        cursor.unexpected("a variable name")
    }
    return cursor.advance().text
}

private fun parseQuery(cursor: TokenCursor): Query {
    val start = cursor.peek().span
    val matches = mutableListOf<MatchClause>()
    val optionals = mutableListOf<MatchClause>()
    parseMatchClauses(cursor, matches, optionals)
    if (matches.isEmpty()) cursor.unexpected("'MATCH'")
    val returns = parseReturnClause(cursor)
    val limit = parseOptionalLimitClause(cursor)
    val end = cursor.expect(TokenKind.EOF)
    validateCaps(matches, optionals)
    return Query(matches, optionals, returns, limit, start.until(end.span))
}

private fun parseMatchClauses(cursor: TokenCursor, matches: MutableList<MatchClause>, optionals: MutableList<MatchClause>) {
    var seenOptional = false
    while (true) {
        rejectClauseKeyword(cursor)
        when {
            cursor.checkKeyword("OPTIONAL") -> {
                optionals += parseOptionalMatchClause(cursor)
                seenOptional = true
            }
            cursor.checkKeyword("MATCH") -> {
                if (seenOptional) {
                    throw unsupported(
                        cursor.peek().span,
                        "a plain MATCH after OPTIONAL MATCH is not supported — put every plain MATCH first",
                    )
                }
                matches += parseMatchClause(cursor)
            }
            else -> return
        }
    }
}

private fun parseMatchClause(cursor: TokenCursor): MatchClause {
    val start = cursor.expectKeyword("MATCH")
    val patterns = parsePatternList(cursor)
    val where = parseOptionalWhere(cursor)
    return MatchClause(patterns, where, optional = false, span = start.span.until(cursor.previous().span))
}

private fun parseOptionalMatchClause(cursor: TokenCursor): MatchClause {
    val start = cursor.expectKeyword("OPTIONAL")
    cursor.expectKeyword("MATCH")
    val patterns = parsePatternList(cursor)
    val where = parseOptionalWhere(cursor)
    return MatchClause(patterns, where, optional = true, span = start.span.until(cursor.previous().span))
}

private fun parsePatternList(cursor: TokenCursor): List<Pattern> {
    val patterns = mutableListOf(parsePattern(cursor))
    while (cursor.match(TokenKind.COMMA) != null) {
        patterns += parsePattern(cursor)
    }
    return patterns
}

private fun parseOptionalWhere(cursor: TokenCursor): Expr? {
    if (!cursor.checkKeyword("WHERE")) return null
    cursor.advance()
    return parseExpression(cursor)
}

private fun parseReturnClause(cursor: TokenCursor): Returns {
    rejectClauseKeyword(cursor)
    val start = cursor.expectKeyword("RETURN")
    cursor.matchKeyword("DISTINCT")
    if (cursor.check(TokenKind.STAR)) {
        val star = cursor.advance()
        return Returns.All(start.span.until(star.span))
    }
    val names = mutableListOf(parseReturnItem(cursor))
    while (cursor.match(TokenKind.COMMA) != null) {
        names += parseReturnItem(cursor)
    }
    return Returns.Variables(names, start.span.until(cursor.previous().span))
}

private fun parseReturnItem(cursor: TokenCursor): String {
    rejectClauseKeyword(cursor)
    val token = cursor.peek()
    if (token.kind != TokenKind.IDENT || isReservedWord(token.text)) {
        throw unsupported(token.span, "RETURN yields entities only — list variables or use *")
    }
    val name = cursor.advance().text
    if (cursor.check(TokenKind.LPAREN)) {
        throw unsupported(cursor.peek().span, "functions are not supported")
    }
    if (cursor.check(TokenKind.DOT) || cursor.checkKeyword("AS")) {
        throw unsupported(cursor.peek().span, "RETURN yields entities only — list variables or use *")
    }
    return name
}

private fun parseOptionalLimitClause(cursor: TokenCursor): Int? {
    rejectClauseKeyword(cursor)
    if (!cursor.checkKeyword("LIMIT")) return null
    cursor.advance()
    val numberToken = cursor.peek()
    if (numberToken.kind != TokenKind.NUMBER || numberToken.text.contains('.')) {
        throw limitInvalid(numberToken.span, "LIMIT must be a positive integer")
    }
    cursor.advance()
    val value = numberToken.text.toLongOrNull() ?: throw limitInvalid(numberToken.span, "LIMIT must be a positive integer")
    if (value < 1 || value > MAX_ENTITIES_TOTAL) {
        throw limitInvalid(numberToken.span, "LIMIT must be between 1 and $MAX_ENTITIES_TOTAL")
    }
    return value.toInt()
}

private fun limitInvalid(span: Span, message: String): QueryException = QueryException(
    QueryDiagnostic(QueryDiagnosticCodes.LIMIT_INVALID, message, span.line, span.column, span.endLine, span.endColumn),
)

private fun validateCaps(matches: List<MatchClause>, optionals: List<MatchClause>) {
    val all = matches + optionals
    val nodeCount = all.sumOf { clause -> clause.patterns.sumOf { it.nodes.size } }
    if (nodeCount > MAX_QUERY_NODE_PATTERNS) {
        throw QueryException(
            QueryDiagnostic(QueryDiagnosticCodes.TOO_MANY_PATTERNS, "Query has more than $MAX_QUERY_NODE_PATTERNS node patterns"),
        )
    }
    val variableCount = countDistinctVariables(all)
    if (variableCount > MAX_QUERY_VARIABLES) {
        throw QueryException(
            QueryDiagnostic(QueryDiagnosticCodes.TOO_MANY_VARIABLES, "Query has more than $MAX_QUERY_VARIABLES distinct variables"),
        )
    }
}

private fun countDistinctVariables(clauses: List<MatchClause>): Int {
    val variables = mutableSetOf<String>()
    for (clause in clauses) {
        for (pattern in clause.patterns) {
            pattern.nodes.forEach { it.variable?.let(variables::add) }
            pattern.edges.forEach { it.variable?.let(variables::add) }
        }
    }
    return variables.size
}
