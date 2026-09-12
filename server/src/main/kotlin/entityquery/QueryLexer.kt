package ch.nokillswit.entityquery

/**
 * Lexical tokens of the entity query language (`.claude/docs/entity-query-language.md`).
 * Keywords are NOT separate kinds — the parser matches [IDENT] token text case-insensitively
 * (`TokenCursor.isKeyword`), so `MATCH`, `match`, and `Match` all lex identically.
 */
internal enum class TokenKind {
    IDENT,
    BACKTICK_IDENT,
    META,
    STRING,
    NUMBER,
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    LBRACE,
    RBRACE,
    COLON,
    COMMA,
    DOT,
    DOTDOT,
    PIPE,
    STAR,
    MINUS,
    LT,
    GT,
    EQ,
    NEQ,
    LTE,
    GTE,
    EOF,
}

/**
 * One lexical token. [span] is the 1-based, end-exclusive source position; [offset] is the
 * 0-based character index into the source text. The pattern parser assembles arrows from
 * OFFSET-adjacent `-`/`<`/`>` tokens (`a.offset + a.text.length == b.offset`) — whitespace or a
 * comment between them breaks adjacency, so `-->` is one arrow while `- ->` is not.
 */
internal data class Token(val kind: TokenKind, val text: String, val span: Span, val offset: Int)

/**
 * Tokenizes [text] into a flat list always ending with one EOF token. Pure — no database, no
 * Ktor. Throws [QueryException] with a single `SYNTAX` diagnostic on the first lexical error:
 * an unterminated string, backtick identifier or block comment, or an illegal character.
 */
internal fun lex(text: String): List<Token> = Lexer(text).tokenize()

private const val UNICODE_ESCAPE_DIGITS = 4
private const val UNICODE_RADIX = 16

private fun isIdentStart(c: Char) = c == '_' || c in 'A'..'Z' || c in 'a'..'z'

private fun isIdentPart(c: Char) = isIdentStart(c) || c in '0'..'9'

private fun isAsciiDigit(c: Char) = c in '0'..'9'

private class Lexer(private val text: String) {
    private var pos = 0
    private var line = 1
    private var column = 1

    private fun atEnd() = pos >= text.length

    private fun peek(offset: Int = 0): Char? {
        val i = pos + offset
        return if (i < text.length) text[i] else null
    }

    private fun advance(): Char {
        val c = text[pos]
        pos++
        if (c == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        return c
    }

    private fun here() = Span(line, column, line, column)

    private fun syntaxError(message: String, start: Span): Nothing = throw QueryException(
        QueryDiagnostic(QueryDiagnosticCodes.SYNTAX, message, start.line, start.column, line, column),
    )

    private fun unsupportedError(message: String, start: Span): Nothing = throw QueryException(
        QueryDiagnostic(QueryDiagnosticCodes.UNSUPPORTED, message, start.line, start.column, line, column),
    )

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            skipTrivia()
            if (atEnd()) {
                tokens += Token(TokenKind.EOF, "", here(), pos)
                return tokens
            }
            tokens += nextToken()
        }
    }

    private fun skipTrivia() {
        while (!atEnd()) {
            val c = peek()
            when {
                c == ' ' || c == '\t' || c == '\r' || c == '\n' -> advance()
                c == '/' && peek(1) == '/' -> skipLineComment()
                c == '/' && peek(1) == '*' -> skipBlockComment()
                else -> return
            }
        }
    }

    private fun skipLineComment() {
        while (!atEnd() && peek() != '\n') advance()
    }

    private fun skipBlockComment() {
        val start = here()
        advance() // '/'
        advance() // '*'
        while (true) {
            if (atEnd()) syntaxError("Unterminated block comment", start)
            if (peek() == '*' && peek(1) == '/') {
                advance()
                advance()
                return
            }
            advance()
        }
    }

    private fun nextToken(): Token {
        val start = here()
        val startOffset = pos
        return when (val c = peek()!!) {
            in 'A'..'Z', in 'a'..'z', '_' -> identifierToken(start, startOffset)
            '`' -> backtickToken(start, startOffset)
            '$' -> metaToken(start, startOffset)
            '\'', '"' -> stringToken(start, startOffset, c)
            in '0'..'9' -> numberToken(start, startOffset)
            else -> punctuationToken(start, startOffset, c)
        }
    }

    private fun identifierToken(start: Span, startOffset: Int): Token {
        val sb = StringBuilder()
        while (!atEnd() && isIdentPart(peek()!!)) sb.append(advance())
        return Token(TokenKind.IDENT, sb.toString(), start.until(here()), startOffset)
    }

    private fun metaToken(start: Span, startOffset: Int): Token {
        advance() // '$'
        if (atEnd() || !isIdentStart(peek()!!)) syntaxError("Illegal character '$'", start)
        val sb = StringBuilder("$")
        while (!atEnd() && isIdentPart(peek()!!)) sb.append(advance())
        return Token(TokenKind.META, sb.toString(), start.until(here()), startOffset)
    }

    private fun backtickToken(start: Span, startOffset: Int): Token {
        advance() // opening backtick
        val sb = StringBuilder()
        while (true) {
            if (atEnd()) syntaxError("Unterminated backtick identifier", start)
            val c = advance()
            if (c == '`') break
            sb.append(c)
        }
        return Token(TokenKind.BACKTICK_IDENT, sb.toString(), start.until(here()), startOffset)
    }

    private fun stringToken(start: Span, startOffset: Int, quote: Char): Token {
        advance() // opening quote
        val sb = StringBuilder()
        while (true) {
            if (atEnd()) syntaxError("Unterminated string literal", start)
            val c = advance()
            when {
                c == quote -> return Token(TokenKind.STRING, sb.toString(), start.until(here()), startOffset)
                c == '\\' -> {
                    val escapeStart = Span(line, column - 1, line, column - 1)
                    sb.append(readEscape(start, escapeStart))
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readEscape(stringStart: Span, escapeStart: Span): Char {
        if (atEnd()) syntaxError("Unterminated string literal", stringStart)
        return when (val e = advance()) {
            '\\' -> '\\'
            '\'' -> '\''
            '"' -> '"'
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            'u' -> readUnicodeEscape(escapeStart)
            else -> syntaxError("Invalid escape sequence '\\$e'", stringStart)
        }
    }

    private fun readUnicodeEscape(escapeStart: Span): Char {
        val sb = StringBuilder()
        var count = 0
        while (count < UNICODE_ESCAPE_DIGITS && !atEnd()) {
            val c = peek()!!
            if (Character.digit(c, UNICODE_RADIX) < 0) break
            sb.append(advance())
            count++
        }
        if (count < UNICODE_ESCAPE_DIGITS) {
            syntaxError("Invalid unicode escape `\\u$sb`", escapeStart.copy(endLine = line, endColumn = column))
        }
        val code = sb.toString().toIntOrNull(UNICODE_RADIX)
            ?: syntaxError("Invalid unicode escape `\\u$sb`", escapeStart.copy(endLine = line, endColumn = column))
        return code.toChar()
    }

    private fun numberToken(start: Span, startOffset: Int): Token {
        val sb = StringBuilder()
        while (!atEnd() && isAsciiDigit(peek()!!)) sb.append(advance())
        if (peek() == '.' && peek(1)?.let(::isAsciiDigit) == true) {
            sb.append(advance()) // '.'
            while (!atEnd() && isAsciiDigit(peek()!!)) sb.append(advance())
        }
        return Token(TokenKind.NUMBER, sb.toString(), start.until(here()), startOffset)
    }

    private fun punctuationToken(start: Span, startOffset: Int, c: Char): Token = when (c) {
        '(' -> simple(TokenKind.LPAREN, start, startOffset)
        ')' -> simple(TokenKind.RPAREN, start, startOffset)
        '[' -> simple(TokenKind.LBRACKET, start, startOffset)
        ']' -> simple(TokenKind.RBRACKET, start, startOffset)
        '{' -> simple(TokenKind.LBRACE, start, startOffset)
        '}' -> simple(TokenKind.RBRACE, start, startOffset)
        ':' -> simple(TokenKind.COLON, start, startOffset)
        ',' -> simple(TokenKind.COMMA, start, startOffset)
        '|' -> simple(TokenKind.PIPE, start, startOffset)
        '*' -> simple(TokenKind.STAR, start, startOffset)
        '-' -> simple(TokenKind.MINUS, start, startOffset)
        '.' -> dotToken(start, startOffset)
        '<' -> ltToken(start, startOffset)
        '>' -> gtToken(start, startOffset)
        '!' -> bangToken(start, startOffset)
        '=' -> eqToken(start, startOffset)
        else -> syntaxError("Illegal character '$c'", start)
    }

    private fun simple(kind: TokenKind, start: Span, startOffset: Int): Token {
        val c = advance()
        return Token(kind, c.toString(), start.until(here()), startOffset)
    }

    private fun dotToken(start: Span, startOffset: Int): Token {
        advance()
        return if (peek() == '.') {
            advance()
            Token(TokenKind.DOTDOT, "..", start.until(here()), startOffset)
        } else {
            Token(TokenKind.DOT, ".", start.until(here()), startOffset)
        }
    }

    private fun ltToken(start: Span, startOffset: Int): Token {
        advance()
        return when (peek()) {
            '=' -> {
                advance()
                Token(TokenKind.LTE, "<=", start.until(here()), startOffset)
            }
            '>' -> {
                advance()
                Token(TokenKind.NEQ, "<>", start.until(here()), startOffset)
            }
            else -> Token(TokenKind.LT, "<", start.until(here()), startOffset)
        }
    }

    private fun gtToken(start: Span, startOffset: Int): Token {
        advance()
        return if (peek() == '=') {
            advance()
            Token(TokenKind.GTE, ">=", start.until(here()), startOffset)
        } else {
            Token(TokenKind.GT, ">", start.until(here()), startOffset)
        }
    }

    private fun bangToken(start: Span, startOffset: Int): Token {
        advance()
        return if (peek() == '=') {
            advance()
            Token(TokenKind.NEQ, "!=", start.until(here()), startOffset)
        } else {
            syntaxError("Illegal character '!'", start)
        }
    }

    /** `=~` is the Cypher regex-match operator — rejected with a friendlier message than a bare illegal character. */
    private fun eqToken(start: Span, startOffset: Int): Token {
        advance() // '='
        if (peek() == '~') {
            advance()
            unsupportedError("regular expressions are not supported — use CONTAINS / STARTS WITH / ENDS WITH", start)
        }
        return Token(TokenKind.EQ, "=", start.until(here()), startOffset)
    }
}
