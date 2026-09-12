package ch.nokillswit.entityquery

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * WHERE-expression grammar: precedence climbing `OR < AND < NOT < predicate`, then operand and
 * literal parsing shared with `PatternParser.kt`'s property maps ([parseLiteral]).
 */

internal const val MAX_EXPRESSION_DEPTH = 64

internal fun parseExpression(cursor: TokenCursor): Expr = parseOr(cursor, depth = 0)

private fun parseOr(cursor: TokenCursor, depth: Int): Expr {
    var left = parseAnd(cursor, depth)
    while (cursor.checkKeyword("OR")) {
        cursor.advance()
        val right = parseAnd(cursor, depth)
        left = Expr.Or(left, right, left.span.until(right.span))
    }
    return left
}

private fun parseAnd(cursor: TokenCursor, depth: Int): Expr {
    var left = parseNot(cursor, depth)
    while (cursor.checkKeyword("AND")) {
        cursor.advance()
        val right = parseNot(cursor, depth)
        left = Expr.And(left, right, left.span.until(right.span))
    }
    return left
}

private fun parseNot(cursor: TokenCursor, depth: Int): Expr {
    if (cursor.checkKeyword("NOT")) {
        val notToken = cursor.peek()
        if (depth + 1 > MAX_EXPRESSION_DEPTH) throw QueryException(
            QueryDiagnostic(
                QueryDiagnosticCodes.SYNTAX,
                "Expression nesting deeper than $MAX_EXPRESSION_DEPTH levels",
                notToken.span.line,
                notToken.span.column,
                notToken.span.endLine,
                notToken.span.endColumn,
            )
        )
        cursor.advance()
        val operand = parseNot(cursor, depth + 1)
        return Expr.Not(operand, notToken.span.until(operand.span))
    }
    return parsePredicate(cursor, depth)
}

private fun parsePredicate(cursor: TokenCursor, depth: Int): Expr {
    rejectClauseKeyword(cursor)
    if (cursor.check(TokenKind.LPAREN)) {
        val lparen = cursor.peek()
        if (depth + 1 > MAX_EXPRESSION_DEPTH) throw QueryException(
            QueryDiagnostic(
                QueryDiagnosticCodes.SYNTAX,
                "Expression nesting deeper than $MAX_EXPRESSION_DEPTH levels",
                lparen.span.line,
                lparen.span.column,
                lparen.span.endLine,
                lparen.span.endColumn,
            )
        )
        cursor.advance()
        val expr = parseOr(cursor, depth + 1)
        cursor.expect(TokenKind.RPAREN)
        return expr
    }
    val operand = parseOperand(cursor)
    return parseComparisonOrTruthy(cursor, operand)
}

private fun comparisonOpFor(kind: TokenKind): ComparisonOp? = when (kind) {
    TokenKind.EQ -> ComparisonOp.EQUAL
    TokenKind.NEQ -> ComparisonOp.NOT_EQUAL
    TokenKind.LT -> ComparisonOp.LESS
    TokenKind.LTE -> ComparisonOp.LESS_OR_EQUAL
    TokenKind.GT -> ComparisonOp.GREATER
    TokenKind.GTE -> ComparisonOp.GREATER_OR_EQUAL
    else -> null
}

private fun parseComparisonOrTruthy(cursor: TokenCursor, left: Operand): Expr {
    val op = comparisonOpFor(cursor.peek().kind)
    if (op != null) {
        cursor.advance()
        val right = parseOperand(cursor)
        return Expr.Compare(op, left, right, left.span.until(right.span))
    }
    return parseStringOrMembershipOrNullCheck(cursor, left) ?: Expr.Truthy(left, left.span)
}

private fun parseStringOrMembershipOrNullCheck(cursor: TokenCursor, left: Operand): Expr? {
    val stringOp = stringOperatorFor(cursor)
    if (stringOp != null) {
        val right = parseOperand(cursor)
        return Expr.StringOp(stringOp, left, right, left.span.until(right.span))
    }
    if (cursor.checkKeyword("IN")) {
        cursor.advance()
        val right = parseOperand(cursor)
        return Expr.In(left, right, left.span.until(right.span))
    }
    if (cursor.checkKeyword("IS")) {
        val isToken = cursor.advance()
        val negated = cursor.matchKeyword("NOT")
        val nullToken = cursor.expectKeyword("NULL")
        return Expr.IsNull(left, negated, isToken.span.until(nullToken.span))
    }
    return null
}

/** Consumes `CONTAINS`, `STARTS WITH`, or `ENDS WITH` (the operator keywords only) and returns the matching operator. */
private fun stringOperatorFor(cursor: TokenCursor): StringOperator? {
    if (cursor.checkKeyword("CONTAINS")) {
        cursor.advance()
        return StringOperator.CONTAINS
    }
    if (cursor.checkKeyword("STARTS")) {
        cursor.advance()
        cursor.expectKeyword("WITH")
        return StringOperator.STARTS_WITH
    }
    if (cursor.checkKeyword("ENDS")) {
        cursor.advance()
        cursor.expectKeyword("WITH")
        return StringOperator.ENDS_WITH
    }
    return null
}

private fun parseOperand(cursor: TokenCursor): Operand {
    rejectClauseKeyword(cursor)
    val token = cursor.peek()
    if (token.kind == TokenKind.META) {
        throw unsupported(token.span, "query parameters are not supported")
    }
    if (token.kind == TokenKind.IDENT && !isReservedWord(token.text)) {
        if (cursor.peek(1).kind == TokenKind.LPAREN) {
            throw unsupported(token.span, "functions are not supported")
        }
        val variable = cursor.advance().text
        cursor.expect(TokenKind.DOT)
        val key = parseOperandPropertyKey(cursor)
        return Operand.Property(variable, key, token.span.until(cursor.previous().span))
    }
    val literal = parseLiteral(cursor)
    return Operand.Value(literal, literal.span)
}

private fun parseOperandPropertyKey(cursor: TokenCursor): String {
    val token = cursor.peek()
    return when (token.kind) {
        TokenKind.IDENT, TokenKind.BACKTICK_IDENT, TokenKind.META -> cursor.advance().text
        else -> cursor.unexpected("a property key")
    }
}

/** `STRING | '-'? NUMBER | TRUE | FALSE | NULL | '[' (literal (',' literal)*)? ']'` — shared with property maps. */
internal fun parseLiteral(cursor: TokenCursor, depth: Int = 0): Literal {
    val token = cursor.peek()
    return when {
        token.kind == TokenKind.STRING -> {
            cursor.advance()
            Literal(JsonPrimitive(token.text), token.span)
        }
        token.kind == TokenKind.MINUS -> parseNegativeNumberLiteral(cursor)
        token.kind == TokenKind.NUMBER -> parseNumberLiteral(cursor)
        cursor.isKeyword(token, "TRUE") -> {
            cursor.advance()
            Literal(JsonPrimitive(true), token.span)
        }
        cursor.isKeyword(token, "FALSE") -> {
            cursor.advance()
            Literal(JsonPrimitive(false), token.span)
        }
        cursor.isKeyword(token, "NULL") -> {
            cursor.advance()
            Literal(JsonNull, token.span)
        }
        token.kind == TokenKind.LBRACKET -> parseListLiteral(cursor, depth)
        else -> cursor.unexpected("a literal")
    }
}

private fun parseNegativeNumberLiteral(cursor: TokenCursor): Literal {
    val minus = cursor.advance()
    val numberToken = cursor.expect(TokenKind.NUMBER)
    return Literal(parseJsonNumber(numberToken.text, negative = true, numberToken.span), minus.span.until(numberToken.span))
}

private fun parseNumberLiteral(cursor: TokenCursor): Literal {
    val token = cursor.advance()
    return Literal(parseJsonNumber(token.text, negative = false, token.span), token.span)
}

private fun parseJsonNumber(text: String, negative: Boolean, span: Span): JsonPrimitive = if (text.contains('.')) {
    val d = text.toDoubleOrNull()
    if (d == null || !d.isFinite()) throw QueryException(
        QueryDiagnostic(
            QueryDiagnosticCodes.SYNTAX,
            "Number literal out of range",
            span.line,
            span.column,
            span.endLine,
            span.endColumn,
        )
    )
    JsonPrimitive(if (negative) -d else d)
} else {
    val l = text.toLongOrNull()
    if (l == null) throw QueryException(
        QueryDiagnostic(
            QueryDiagnosticCodes.SYNTAX,
            "Number literal out of range",
            span.line,
            span.column,
            span.endLine,
            span.endColumn,
        )
    )
    JsonPrimitive(if (negative) -l else l)
}

private fun parseListLiteral(cursor: TokenCursor, depth: Int): Literal {
    val open = cursor.peek()
    if (depth + 1 > MAX_EXPRESSION_DEPTH) throw QueryException(
        QueryDiagnostic(
            QueryDiagnosticCodes.SYNTAX,
            "Expression nesting deeper than $MAX_EXPRESSION_DEPTH levels",
            open.span.line,
            open.span.column,
            open.span.endLine,
            open.span.endColumn,
        )
    )
    cursor.advance()
    val items = mutableListOf<JsonElement>()
    if (!cursor.check(TokenKind.RBRACKET)) {
        items += parseLiteral(cursor, depth + 1).value
        while (cursor.match(TokenKind.COMMA) != null) {
            items += parseLiteral(cursor, depth + 1).value
        }
    }
    val close = cursor.expect(TokenKind.RBRACKET)
    return Literal(JsonArray(items), open.span.until(close.span))
}
