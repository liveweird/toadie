package ch.nokillswit

import ch.nokillswit.infra.paging.DEFAULT_PAGE_SIZE
import ch.nokillswit.infra.paging.MAX_PAGE_SIZE
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.inMemoryPage
import ch.nokillswit.infra.paging.validatedPage
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The pure paging helpers shared by the integration adapters (`infra/paging/Paging.kt`):
 * [validatedPage]'s bound check and [inMemoryPage]'s slice — the edges the GraphQL fetchers and
 * the MCP list tools both ride.
 */
class PagingHelpersTest {
    @Test
    fun `validatedPage defaults, accepts the bounds, and refuses everything outside them`() {
        val defaults = validatedPage(null, null)
        assertEquals(1, defaults.page)
        assertEquals(DEFAULT_PAGE_SIZE, defaults.pageSize)
        assertEquals(listOf(SortField("id", descending = false)), defaults.sort)

        assertEquals(MAX_PAGE_SIZE, validatedPage(1, MAX_PAGE_SIZE).pageSize)
        assertEquals(1, validatedPage(1, 1).pageSize)
        assertEquals(Int.MAX_VALUE, validatedPage(Int.MAX_VALUE, 1).page)
        val sort = listOf(SortField("identifier", false), SortField("id", false))
        assertEquals(sort, validatedPage(2, 5, sort).sort)

        assertEquals(
            "pageSize must be between 1 and $MAX_PAGE_SIZE",
            assertFailsWith<BadRequestException> { validatedPage(1, MAX_PAGE_SIZE + 1) }.message,
        )
        assertEquals("pageSize must be between 1 and $MAX_PAGE_SIZE", assertFailsWith<BadRequestException> { validatedPage(1, 0) }.message)
        assertEquals("pageSize must be between 1 and $MAX_PAGE_SIZE", assertFailsWith<BadRequestException> { validatedPage(1, -1) }.message)
        assertEquals("page must be >= 1", assertFailsWith<BadRequestException> { validatedPage(0, 10) }.message)
        assertEquals("page must be >= 1", assertFailsWith<BadRequestException> { validatedPage(-3, null) }.message)
    }

    @Test
    fun `inMemoryPage slices one-based pages and reports the full total`() {
        val items = (1..5).toList()
        assertEquals(listOf(1, 2) to 5L, inMemoryPage(items, page = 1, pageSize = 2))
        assertEquals(listOf(3, 4) to 5L, inMemoryPage(items, page = 2, pageSize = 2))
        assertEquals(listOf(5) to 5L, inMemoryPage(items, page = 3, pageSize = 2)) // a partial last page
        assertEquals(items to 5L, inMemoryPage(items, page = 1, pageSize = MAX_PAGE_SIZE)) // a size larger than the list
        assertEquals(emptyList<Int>() to 5L, inMemoryPage(items, page = 4, pageSize = 2)) // past the end: empty, not an error
        assertEquals(emptyList<Int>() to 5L, inMemoryPage(items, page = 3, pageSize = 5)) // exactly past the last full page
        assertEquals(emptyList<Int>() to 0L, inMemoryPage(emptyList<Int>(), page = 1, pageSize = 2))
        // The offset arithmetic is Long: a huge page neither overflows nor throws.
        assertEquals(emptyList<Int>() to 5L, inMemoryPage(items, page = Int.MAX_VALUE, pageSize = MAX_PAGE_SIZE))
    }
}
