package ch.nokillswit

import ch.nokillswit.entityquery.QueryDiagnosticCodes
import ch.nokillswit.entityquery.QueryException
import ch.nokillswit.entityquery.Token
import ch.nokillswit.entityquery.TokenKind
import ch.nokillswit.entityquery.lex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure coverage of `entityquery/QueryLexer.kt` — one case per token kind, escape handling, both
 * comment forms, multi-line spans, arrow-adjacency offsets, and every lexical error. No
 * database — `lex` is a pure function over the source text.
 */
class QueryLexerTest {

    private fun tokenKinds(text: String): List<TokenKind> = lex(text).map { it.kind }

    private fun single(text: String): Token {
        val tokens = lex(text)
        assertEquals(2, tokens.size, "expected exactly one token plus EOF, got $tokens")
        return tokens[0]
    }

    @Test
    fun `identifiers lex as IDENT`() {
        val token = single("match_1")
        assertEquals(TokenKind.IDENT, token.kind)
        assertEquals("match_1", token.text)
    }

    @Test
    fun `backtick identifiers unquote and keep arbitrary characters`() {
        val token = single("`web-service.v2`")
        assertEquals(TokenKind.BACKTICK_IDENT, token.kind)
        assertEquals("web-service.v2", token.text)
    }

    @Test
    fun `meta properties keep the leading dollar`() {
        val token = single("\$identifier")
        assertEquals(TokenKind.META, token.kind)
        assertEquals("\$identifier", token.text)
    }

    @Test
    fun `strings unescape backslash quotes and whitespace escapes`() {
        val token = single("""'a\\b\'c\"d\ne\tf\rg'""")
        assertEquals(TokenKind.STRING, token.kind)
        assertEquals("a\\b'c\"d\ne\tf\rg", token.text)
    }

    @Test
    fun `strings support double quotes and unicode escapes`() {
        val token = single(""""café"""")
        assertEquals(TokenKind.STRING, token.kind)
        assertEquals("café", token.text)
    }

    @Test
    fun `integers and decimals lex as NUMBER`() {
        assertEquals("12", single("12").text)
        assertEquals("3.5", single("3.5").text)
    }

    @Test
    fun `a dot not followed by a digit does not join a leading integer`() {
        val tokens = tokenKinds("1..3")
        assertEquals(listOf(TokenKind.NUMBER, TokenKind.DOTDOT, TokenKind.NUMBER, TokenKind.EOF), tokens)
    }

    @Test
    fun `single character punctuation tokens`() {
        val text = "()[]{}:,.|*-"
        val expected = listOf(
            TokenKind.LPAREN, TokenKind.RPAREN, TokenKind.LBRACKET, TokenKind.RBRACKET,
            TokenKind.LBRACE, TokenKind.RBRACE, TokenKind.COLON, TokenKind.COMMA,
            TokenKind.DOT, TokenKind.PIPE, TokenKind.STAR, TokenKind.MINUS, TokenKind.EOF,
        )
        assertEquals(expected, tokenKinds(text))
    }

    @Test
    fun `two character operators`() {
        assertEquals(TokenKind.LTE, single("<=").kind)
        assertEquals(TokenKind.GTE, single(">=").kind)
        assertEquals(TokenKind.NEQ, single("<>").kind)
        assertEquals(TokenKind.NEQ, single("!=").kind)
        assertEquals(TokenKind.DOTDOT, single("..").kind)
        assertEquals(TokenKind.LT, single("<").kind)
        assertEquals(TokenKind.GT, single(">").kind)
        assertEquals(TokenKind.EQ, single("=").kind)
    }

    @Test
    fun `line comments are skipped to end of line`() {
        val tokens = lex("a // comment\nb")
        assertEquals(listOf(TokenKind.IDENT, TokenKind.IDENT, TokenKind.EOF), tokens.map { it.kind })
        assertEquals("a", tokens[0].text)
        assertEquals("b", tokens[1].text)
        assertEquals(2, tokens[1].span.line)
    }

    @Test
    fun `block comments span multiple lines and are skipped`() {
        val tokens = lex("a /* multi\nline\ncomment */ b")
        assertEquals(listOf(TokenKind.IDENT, TokenKind.IDENT, TokenKind.EOF), tokens.map { it.kind })
        assertEquals(3, tokens[1].span.line)
    }

    @Test
    fun `whitespace and newlines advance line and column tracking`() {
        val tokens = lex("a\n  b")
        assertEquals(1, tokens[0].span.line)
        assertEquals(1, tokens[0].span.column)
        assertEquals(2, tokens[1].span.line)
        assertEquals(3, tokens[1].span.column)
    }

    @Test
    fun `bare arrow tokens are offset adjacent`() {
        val tokens = lex("-->")
        assertEquals(listOf(TokenKind.MINUS, TokenKind.MINUS, TokenKind.GT, TokenKind.EOF), tokens.map { it.kind })
        assertEquals(0, tokens[0].offset)
        assertEquals(1, tokens[1].offset)
        assertEquals(2, tokens[2].offset)
        assertTrue(tokens[0].offset + tokens[0].text.length == tokens[1].offset)
        assertTrue(tokens[1].offset + tokens[1].text.length == tokens[2].offset)
    }

    @Test
    fun `a space inside an arrow breaks offset adjacency`() {
        val tokens = lex("- ->")
        assertEquals(listOf(TokenKind.MINUS, TokenKind.MINUS, TokenKind.GT, TokenKind.EOF), tokens.map { it.kind })
        // The first MINUS (offset 0) is NOT adjacent to the second MINUS (offset 2) — a gap for the space.
        assertTrue(tokens[0].offset + tokens[0].text.length != tokens[1].offset)
        // The second MINUS and the GT that follow it directly stay adjacent.
        assertTrue(tokens[1].offset + tokens[1].text.length == tokens[2].offset)
    }

    @Test
    fun `a comparison minus before a number in WHERE lexes as separate tokens`() {
        val tokens = tokenKinds("a < -1")
        assertEquals(listOf(TokenKind.IDENT, TokenKind.LT, TokenKind.MINUS, TokenKind.NUMBER, TokenKind.EOF), tokens)
    }

    @Test
    fun `regex operator is rejected as unsupported, not an illegal character`() {
        val ex = assertFailsWith<QueryException> { lex("a =~ 'x'") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.UNSUPPORTED, diagnostic.code)
        assertTrue(diagnostic.message.contains("regular expressions"))
    }

    @Test
    fun `an unterminated string is a positioned syntax error`() {
        val ex = assertFailsWith<QueryException> { lex("'abc") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertEquals(1, diagnostic.line)
        assertEquals(1, diagnostic.column)
        assertEquals(5, diagnostic.endColumn)
    }

    @Test
    fun `an unterminated backtick identifier is a positioned syntax error`() {
        val ex = assertFailsWith<QueryException> { lex("`abc") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertEquals(1, diagnostic.column)
    }

    @Test
    fun `an unterminated block comment is a positioned syntax error`() {
        val ex = assertFailsWith<QueryException> { lex("a /* never closed") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertEquals(3, diagnostic.column)
    }

    @Test
    fun `an illegal character is a positioned syntax error`() {
        val ex = assertFailsWith<QueryException> { lex("a # b") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertEquals(3, diagnostic.column)
        assertTrue(diagnostic.message.contains("#"))
    }

    @Test
    fun `an empty query lexes to just EOF`() {
        assertEquals(listOf(TokenKind.EOF), tokenKinds(""))
    }

    @Test
    fun `a valid unicode escape with exactly 4 hex digits decodes correctly`() {
        val token = single("'é'")
        assertEquals(TokenKind.STRING, token.kind)
        assertEquals("é", token.text)
    }

    @Test
    fun `fewer than 4 hex digits in unicode escape is a positioned syntax error`() {
        val ex = assertFailsWith<QueryException> { lex("'\\u12'") }
        val diagnostic = ex.diagnostics.single()
        assertEquals(QueryDiagnosticCodes.SYNTAX, diagnostic.code)
        assertTrue(diagnostic.message.contains("Invalid unicode escape"))
        // Error should be positioned at the \u start, not including the closing quote
        assertFalse(diagnostic.message.contains("'"))
        // End column should be after \u12
        assertTrue((diagnostic.endColumn ?: 0) > (diagnostic.column ?: 0))
    }
}
