package ch.nokillswit

import ch.nokillswit.catalog.CatalogFileService
import ch.nokillswit.infra.db.jsonObjectValueIn
import ch.nokillswit.infra.db.jsonTextEqualsFolded
import ch.nokillswit.infra.db.orVanished
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pure unit tests of the small SQL-adjacent helpers in infra/db/Sql.kt; the SQL renderings
 * (containsNormalized, the JSON-path helpers) are covered by the list-endpoint filter tests
 * against the real database.
 */
class SqlHelpersTest {

    @Test
    fun `orVanished returns the value when present and errors when it is gone`() {
        assertEquals("here", ("here" as String?).orVanished("CatalogFile", 1u))
        val failure = assertFailsWith<IllegalStateException> {
            (null as String?).orVanished("CatalogFile", 7u, "after opening")
        }
        assertEquals("CatalogFile 7 vanished after opening", failure.message)
    }

    @Test
    fun `the JSON-path helpers refuse non-identifier path segments and empty value lists`() {
        val content = CatalogFileService.CatalogFiles.content
        // Path segments land inside a SQL literal — anything but simple identifiers is a bug.
        assertFailsWith<IllegalArgumentException> { content.jsonTextEqualsFolded(emptyList(), "x") }
        assertFailsWith<IllegalArgumentException> { content.jsonTextEqualsFolded(listOf("spec,owner"), "x") }
        assertFailsWith<IllegalArgumentException> { content.jsonObjectValueIn(listOf("a'b"), "k", listOf("v")) }
        assertFailsWith<IllegalArgumentException> { content.jsonObjectValueIn(listOf("metadata"), "k", emptyList()) }
    }
}
