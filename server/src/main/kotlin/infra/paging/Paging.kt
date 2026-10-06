package ch.nokillswit.infra.paging

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.r2dbc.Query

const val DEFAULT_PAGE_SIZE = 20
const val MAX_PAGE_SIZE = 100

data class SortField(val name: String, val descending: Boolean)

data class PageRequest(
    val page: Int,
    val pageSize: Int,
    val sort: List<SortField>,
)

fun ApplicationCall.parsePaging(
    sortable: Set<String>,
    defaultSort: List<SortField> = listOf(SortField("id", descending = false)),
): PageRequest {
    val params = request.queryParameters

    val page = params.singleValue("page")?.let { raw ->
        val parsed = raw.toIntOrNull() ?: throw BadRequestException("page must be a positive integer")
        if (parsed < 1) throw BadRequestException("page must be >= 1")
        parsed
    } ?: 1

    val pageSize = params.singleValue("pageSize")?.let { raw ->
        val parsed = raw.toIntOrNull() ?: throw BadRequestException("pageSize must be an integer")
        if (parsed < 1 || parsed > MAX_PAGE_SIZE) {
            throw BadRequestException("pageSize must be between 1 and $MAX_PAGE_SIZE")
        }
        parsed
    } ?: DEFAULT_PAGE_SIZE

    val sortParam = params.singleValue("sort")
    val requested = if (sortParam.isNullOrBlank()) {
        defaultSort
    } else {
        sortParam.split(',').map { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw BadRequestException("sort contains an empty field")
            val descending = trimmed.startsWith('-')
            val field = if (descending) trimmed.drop(1) else trimmed
            if (field !in sortable) {
                throw BadRequestException("Unknown sort field: $field (allowed: ${sortable.sorted().joinToString()})")
            }
            SortField(field, descending)
        }
    }
    val withTiebreaker = if (requested.any { it.name == "id" }) requested else requested + SortField("id", false)
    return PageRequest(page = page, pageSize = pageSize, sort = withTiebreaker)
}

fun Query.applyPaging(req: PageRequest, columns: Map<String, Column<*>>): Query {
    val order = req.sort.map { sf ->
        val column = columns[sf.name]
            ?: error("Sort field '${sf.name}' has no mapped column (paging helper invariant violated)")
        column to if (sf.descending) SortOrder.DESC else SortOrder.ASC
    }
    return orderBy(*order.toTypedArray())
        .limit(req.pageSize)
        .offset(((req.page - 1).toLong()) * req.pageSize)
}

/**
 * The bound-checked page request for callers that already hold their `page`/`pageSize` as numbers
 * (the GraphQL fetchers' arguments) rather than raw query parameters [parsePaging] reads: `null`
 * means the default (`1` / [DEFAULT_PAGE_SIZE]); `page` must be at least 1 and `pageSize` within
 * `1..`[MAX_PAGE_SIZE], else a 400 with the same messages [parsePaging] speaks.
 */
fun validatedPage(
    page: Int?,
    pageSize: Int?,
    sort: List<SortField> = listOf(SortField("id", descending = false)),
): PageRequest {
    val resolvedPage = page ?: 1
    val resolvedPageSize = pageSize ?: DEFAULT_PAGE_SIZE
    if (resolvedPage < 1) throw BadRequestException("page must be >= 1")
    if (resolvedPageSize !in 1..MAX_PAGE_SIZE) throw BadRequestException("pageSize must be between 1 and $MAX_PAGE_SIZE")
    return PageRequest(resolvedPage, resolvedPageSize, sort)
}

/**
 * One one-based page of an already materialized list, with the full list's size as the total —
 * the shared slice for the integration adapters (GraphQL's `errors` report pages, MCP's list
 * tools), whose rows are computed in memory rather than paged by SQL. A page past the end is
 * empty, never an error; the offset arithmetic is `Long` so a huge `page` cannot overflow.
 */
fun <T> inMemoryPage(items: List<T>, page: Int, pageSize: Int): Pair<List<T>, Long> {
    val offset = ((page - 1).toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val end = (offset.toLong() + pageSize).coerceAtMost(items.size.toLong()).toInt()
    val pageItems = if (offset >= items.size) emptyList() else items.subList(offset, end)
    return pageItems to items.size.toLong()
}
