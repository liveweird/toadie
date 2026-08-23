package ch.nokillswit

import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalLong
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.singleValue
import io.ktor.http.parametersOf
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests of the query-param parsing helpers (infra/paging/QueryParams.kt). The
 * paging pipeline itself (parsePaging + applyPaging) is covered by the list-endpoint route
 * tests against the real database.
 */
class QueryParamsTest {

    private enum class Fruit { APPLE, PEAR }

    @Test
    fun `singleValue returns the value, null when absent, and 400s a repeated key`() {
        val params = parametersOf("a" to listOf("x"), "b" to listOf("1", "2"))
        assertEquals("x", params.singleValue("a"))
        assertNull(params.singleValue("missing"))
        val failure = assertFailsWith<BadRequestException> { params.singleValue("b") }
        assertEquals("Parameter 'b' must not be repeated", failure.message)
    }

    @Test
    fun `optionalString treats blank as absent`() {
        val params = parametersOf("blank" to listOf("   "), "real" to listOf(" x "))
        assertNull(params.optionalString("blank"))
        assertEquals(" x ", params.optionalString("real"))
    }

    @Test
    fun `optionalUInt parses and rejects non-UInt values`() {
        val params = parametersOf("ok" to listOf("5"), "neg" to listOf("-1"), "junk" to listOf("abc"))
        assertEquals(5u, params.optionalUInt("ok"))
        assertNull(params.optionalUInt("missing"))
        assertFailsWith<BadRequestException> { params.optionalUInt("neg") }
        assertFailsWith<BadRequestException> { params.optionalUInt("junk") }
    }

    @Test
    fun `optionalLong parses and rejects non-Long values`() {
        val params = parametersOf("ok" to listOf("-7"), "junk" to listOf("seven"))
        assertEquals(-7L, params.optionalLong("ok"))
        assertNull(params.optionalLong("missing"))
        assertFailsWith<BadRequestException> { params.optionalLong("junk") }
    }

    @Test
    fun `optionalBoolean is strict`() {
        val params = parametersOf("t" to listOf("true"), "f" to listOf("false"), "junk" to listOf("maybe"))
        assertEquals(true, params.optionalBoolean("t"))
        assertEquals(false, params.optionalBoolean("f"))
        assertNull(params.optionalBoolean("missing"))
        assertFailsWith<BadRequestException> { params.optionalBoolean("junk") }
    }

    @Test
    fun `optionalEnum matches exact constant names and lists the allowed values on failure`() {
        val params = parametersOf("ok" to listOf("PEAR"), "junk" to listOf("pear"))
        assertEquals(Fruit.PEAR, params.optionalEnum<Fruit>("ok"))
        assertNull(params.optionalEnum<Fruit>("missing"))
        val failure = assertFailsWith<BadRequestException> { params.optionalEnum<Fruit>("junk") }
        assertTrue(failure.message!!.contains("APPLE, PEAR"))
    }
}
