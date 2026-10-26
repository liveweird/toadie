package ch.nokillswit.infra.db

import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/**
 * The largest number of bind parameters one [insertRowsIgnoringConflicts] statement carries. The PostgreSQL wire protocol counts a
 * Bind message's parameters in an Int16 (65,535); half of that leaves headroom, and the row chunk is derived from it, so a wide
 * table simply gets smaller chunks.
 */
private const val MAX_BIND_PARAMETERS = 32_000

/** The row an [insertRowsIgnoringConflicts] body fills: `this[Column] = value`, the same DSL as `batchInsert`'s lambda. */
class InsertRow {
    internal val values = LinkedHashMap<Column<*>, Any?>()

    operator fun <S> set(column: Column<S>, value: S) {
        values[column] = value
    }

    // A `reference(...)` column is a `Column<EntityID<ID>>` that callers fill with the plain id, exactly as `batchInsert`'s own
    // setter takes it; it is bound through the referenced id column's own type.
    @JvmName("setEntityId")
    operator fun <ID : Comparable<ID>> set(column: Column<EntityID<ID>>, value: ID) {
        values[column] = value
    }

    @JvmName("setNullableEntityId")
    operator fun <ID : Comparable<ID>> set(column: Column<EntityID<ID>?>, value: ID?) {
        values[column] = value
    }
}

/**
 * `batchInsert`'s call shape - `table.insertRowsIgnoringConflicts(rows) { this[Col] = it.value }` - executed as multi-row
 * `INSERT ... VALUES (...), (...) ON CONFLICT DO NOTHING` statements (one per power-of-two chunk, all in the caller's transaction)
 * instead of one statement execution per row. Ported from Flow's `infra/db/MultiRowInsert.kt` (its non-ignoring `insertRows` and
 * `upsertRows` left behind: nothing here writes in bulk but the telemetry sampler, and that write is append-only).
 *
 * Why it exists: `exposed-r2dbc`'s `batchInsert` runs every row as its own bound execution of the prepared statement, ~0.15 ms
 * per row however small the row. The telemetry sampler writes ~100 rows every tick (`TelemetryStore.kt`), where the per-row
 * executions were 60 % of the tick (`.claude/docs/observability.md` "Telemetry", Measured cost). The same rows as multi-row
 * statements cost about half (8.2 vs 17.4 ms for 98 rows). Nothing else differs: the rows, their order and their column values are
 * the ones `batchInsert` writes (every value still goes through its column's own type, every value is still a BOUND parameter,
 * never string-built - the only text in the SQL is the table and column identifiers from the Exposed table object) and it runs
 * inside the caller's current transaction.
 *
 * A row that violates any unique constraint is silently dropped, the first of two rows sharing a key winning (PostgreSQL skips a
 * duplicate inside one `DO NOTHING` statement). Nothing is returned, so the number of rows actually written is unknown.
 *
 * Contract (checked, not assumed): every row sets the SAME columns. The statement lists the table's columns in declaration order:
 * each column a row sets, plus each column carrying an Exposed client-side `default(...)` that no row sets (filled with that
 * default, as `batchInsert` does). Any other unset column is omitted, so its database default applies (a `SERIAL` id, a NULL).
 * Generated values are never returned. An empty [rows] is a no-op. Every set column must belong to this table, and the table must
 * have no sequence-backed `autoIncrement("seq")` column (plain `SERIAL` ids, which the database fills, are fine). Exposed's
 * client-side value validation (a `varchar` length check) is NOT run: an overflow fails server-side (SQLSTATE 22001) instead.
 *
 * **Statement texts are quantized** ([chunkSizes]): the SQL text depends on the row count, and every distinct text becomes one named
 * server-side prepared statement that the driver caches per connection, so a chunk is either the table's maximum rows per statement
 * or a power of two - a handful of texts per table, however many rows a batch has (98 rows go out as 64 + 32 + 2).
 *
 * **Memory:** every chunk's executed statement is released as soon as it ran (`clearExecutedStatements`), so a transaction's heap
 * does not grow with the rows it writes. That empties the transaction's WHOLE list - the caller's earlier statements too.
 */
suspend fun <T : Table, E> T.insertRowsIgnoringConflicts(rows: Iterable<E>, body: InsertRow.(E) -> Unit) {
    writeRows("insertRowsIgnoringConflicts", rows, body)
}

private suspend fun <T : Table, E> T.writeRows(
    caller: String,
    rows: Iterable<E>,
    body: InsertRow.(E) -> Unit,
) {
    val allRows = rows.map { row -> InsertRow().also { it.body(row) }.values }
    if (allRows.isEmpty()) return
    val setColumns = allRows.first().keys
    require(setColumns.isNotEmpty()) { "$caller: a row that sets no column cannot be inserted" }
    require(allRows.all { it.keys == setColumns }) { "$caller: every row must set the same columns" }
    setColumns.firstOrNull { it.table != this }?.let { foreign ->
        throw IllegalArgumentException("$caller: column ${foreign.name} belongs to ${foreign.table.tableName}, not ${this.tableName}")
    }
    require(this.columns.none { (it.columnType as? AutoIncColumnType<*>)?.sequence != null }) {
        "$caller: ${this.tableName} has a sequence-backed autoIncrement column; use batchInsert"
    }
    val columns = this.columns.filter { it in setColumns || it.defaultValueFun != null }
    val tx = TransactionManager.current()
    val header = "INSERT INTO ${tx.identity(this)} (${columns.joinToString(", ") { tx.identity(it) }}) VALUES "
    val suffix = " ON CONFLICT DO NOTHING"
    val maxRows = (MAX_BIND_PARAMETERS / columns.size).coerceAtLeast(1)
    var offset = 0
    for (size in chunkSizes(allRows.size, maxRows)) {
        val chunk = allRows.subList(offset, offset + size)
        offset += size
        val arguments = ArrayList<Pair<IColumnType<*>, Any?>>(chunk.size * columns.size)
        val sql = StringBuilder(header)
        chunk.forEachIndexed { index, row ->
            if (index > 0) sql.append(", ")
            sql.append('(')
            columns.forEachIndexed { columnIndex, column ->
                val value = if (column in row) row[column] else column.defaultValueFun?.invoke()
                if (columnIndex > 0) sql.append(", ")
                val bound = bindable(column, value)
                sql.append(marker(bound.first, bound.second))
                arguments += bound
            }
            sql.append(')')
        }
        sql.append(suffix)
        tx.exec(sql.toString(), arguments)
        // Exposed keeps every executed statement until the transaction ends: a 32,000-parameter statement is ~8 MB of parsed tokens
        // and encoded parameters. Clearing the list after each chunk is a sanctioned operation (`executeIn` itself does it when
        // `!supportsMultipleResultSets`).
        tx.clearExecutedStatements()
    }
}

/**
 * The row counts of the statements that write [total] rows: as many full chunks of [maxRows] as fit, then the remainder as its
 * binary decomposition (13 -> 8, 4, 1) - at most `log2(maxRows) + 1` distinct sizes, so a table ever produces only a handful of
 * SQL texts (see [insertRowsIgnoringConflicts]).
 */
internal fun chunkSizes(total: Int, maxRows: Int): List<Int> {
    val sizes = ArrayList<Int>()
    var left = total
    while (left >= maxRows) {
        sizes += maxRows
        left -= maxRows
    }
    var bit = Integer.highestOneBit(left)
    while (left > 0) {
        if (left >= bit) {
            sizes += bit
            left -= bit
        }
        bit = bit shr 1
    }
    return sizes
}

/**
 * The type and value to bind for [column]. An `EntityID` (reference / id) column is bound as its underlying id column
 * (`EntityIDColumnType.notNullValueToDB` insists on an `EntityID` wrapper, which a plain-id caller does not have and the wire never
 * needs).
 */
private fun bindable(column: Column<*>, value: Any?): Pair<IColumnType<*>, Any?> {
    val type = column.columnType
    if (type !is EntityIDColumnType<*>) return type to value
    return type.idColumn.columnType to (if (value is EntityID<*>) value.value else value)
}

// The column type's own placeholder (`?` for every type the callers use; a type that needs a cast says so here, exactly as
// Exposed's single-row insert asks it).
@Suppress("UNCHECKED_CAST") // the value comes from the same column's own `set`, so its type always matches
private fun marker(type: IColumnType<*>, value: Any?): String = (type as IColumnType<Any?>).parameterMarker(value)
