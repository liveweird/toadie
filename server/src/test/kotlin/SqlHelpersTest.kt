package ch.nokillswit

import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.db.requireValidReferences
import io.ktor.server.plugins.BadRequestException
import io.r2dbc.spi.R2dbcException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * Pure unit tests of the small SQL-adjacent helpers in infra/db/Sql.kt; containsNormalized's
 * SQL rendering is covered by the list-endpoint diacritics tests against the real database.
 */
class SqlHelpersTest {

    private class FakeR2dbcException : R2dbcException("fk violated")

    @Test
    fun `requireValidReferences passes the block result through on success`() = runBlocking {
        assertEquals(42, requireValidReferences("unused") { 42 })
    }

    @Test
    fun `requireValidReferences translates an R2DBC failure into a 400 with the given message`() {
        runBlocking {
            val failure = assertFailsWith<BadRequestException> {
                requireValidReferences("Referenced user does not exist") { throw FakeR2dbcException() }
            }
            assertEquals("Referenced user does not exist", failure.message)
        }
    }

    @Test
    fun `orVanished returns the value when present and errors when it is gone`() {
        assertEquals("here", ("here" as String?).orVanished("CatalogFile", 1u))
        val failure = assertFailsWith<IllegalStateException> {
            (null as String?).orVanished("CatalogFile", 7u, "after opening")
        }
        assertEquals("CatalogFile 7 vanished after opening", failure.message)
    }
}
