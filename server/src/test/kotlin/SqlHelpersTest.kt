package ch.nokillswit

import ch.nokillswit.catalog.CatalogFileService
import ch.nokillswit.infra.db.jsonObjectValueIn
import ch.nokillswit.infra.db.jsonStringOrArrayContainsFolded
import ch.nokillswit.infra.db.jsonTextEqualsFolded
import ch.nokillswit.infra.db.orVanished
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    // A minimal Expression stand-in for a real Column: Column.toQueryBuilder needs a live
    // transaction (identifier quoting), which a pure rendering test has no reason to open.
    private fun fakeColumn(name: String): Expression<String?> = object : Expression<String?>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append(name)
        }
    }

    @Test
    fun `jsonStringOrArrayContainsFolded renders the typeof-branch shape with the value bound, not inlined`() {
        val queryBuilder = QueryBuilder(prepared = true)
        fakeColumn("team").jsonStringOrArrayContainsFolded("T1").toQueryBuilder(queryBuilder)
        val sql = queryBuilder.toString()

        assertTrue(sql.contains("jsonb_typeof(CAST(team AS jsonb))"), sql)
        assertTrue(sql.contains("jsonb_build_array(CAST(team AS jsonb))"), sql)
        assertTrue(sql.contains("jsonb_array_elements_text("), sql)
        assertTrue(sql.contains("LOWER("), sql)
        // Bound as a parameter marker, never interpolated as a literal into the SQL text.
        assertFalse(sql.contains("'T1'") || sql.contains("'t1'"), sql)
        assertEquals(listOf("t1"), queryBuilder.args.map { it.second })
    }

    @Test
    fun `jsonStringOrArrayContainsFolded folds the bound value's case regardless of the input casing`() {
        val mixed = QueryBuilder(prepared = true)
        fakeColumn("team").jsonStringOrArrayContainsFolded("MiXeD-Case").toQueryBuilder(mixed)
        assertEquals(listOf("mixed-case"), mixed.args.map { it.second })

        val alreadyLower = QueryBuilder(prepared = true)
        fakeColumn("team").jsonStringOrArrayContainsFolded("already-lower").toQueryBuilder(alreadyLower)
        assertEquals(listOf("already-lower"), alreadyLower.args.map { it.second })
    }
}
