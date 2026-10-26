package ch.nokillswit

import ch.nokillswit.infra.db.chunkSizes
import ch.nokillswit.infra.db.insertRowsIgnoringConflicts
import ch.nokillswit.telemetry.TelemetryStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `infra/db/MultiRowInsert.kt` (ported from Flow) writes exactly what Exposed's `batchInsert` writes, only as multi-row `VALUES`
 * statements. Every case inserts the SAME rows through both into two private telemetry processes and compares what the database
 * holds, so a difference in value conversion, null handling, client-side defaults or chunking shows up as a row difference. The
 * telemetry tables are the telemetry tests' own (`wipeTelemetry`).
 */
class MultiRowInsertTest {
    private val store = telemetryStore()
    private val samples = TelemetryStore.Samples
    private val processes = TelemetryStore.Processes

    @BeforeTest
    fun emptyTelemetry() = runBlocking { wipeTelemetry(store) }

    private suspend fun process(): Int = store.registerProcess(testIdentity(), 1L, null)

    /** Every column but the process id (which differs between the two writers). */
    private data class Sample(
        val resolution: Int,
        val ts: Long,
        val metric: String,
        val label: String,
        val n: Int,
        val avg: Double,
        val max: Double,
    )

    private suspend fun readSamples(processId: Int): List<Sample> = store.transaction {
        samples.selectAll().where { samples.processId eq processId }.orderBy(samples.metric to SortOrder.ASC).toList()
            .map {
                Sample(
                    it[samples.resolution], it[samples.ts], it[samples.metric], it[samples.label], it[samples.n], it[samples.avg],
                    it[samples.max],
                )
            }
    }

    /** Characters that would break a value spliced into SQL text or a placeholder scan: a quote, a `?` and backslashes. */
    private val hostile = "it's a ? \\ \\' \"x\" \u00e9"

    @Test
    fun `sample rows - several chunks and a client-side default left unset - equal what batchInsert writes`() = runBlocking {
        val viaBatch = process()
        val viaRows = process()
        // 7 columns set + the defaulted `n` = 8 -> 4,000 rows per statement: 9,000 rows are two full chunks and a decomposed rest.
        val metrics = (1..9_000).map { "m.%05d".format(it) }
        store.transaction {
            metrics.chunked(500).forEach { chunk ->
                samples.batchInsert(chunk, shouldReturnGeneratedValues = false) {
                    this[samples.resolution] = 300
                    this[samples.ts] = 600_000L
                    this[samples.processId] = viaBatch
                    this[samples.metric] = it
                    this[samples.label] = "$hostile ${it.last()}"
                    this[samples.avg] = it.hashCode() / 7.0
                    this[samples.max] = it.hashCode() / 3.0
                }
            }
            samples.insertRowsIgnoringConflicts(metrics) {
                this[samples.resolution] = 300
                this[samples.ts] = 600_000L
                this[samples.processId] = viaRows
                this[samples.metric] = it
                this[samples.label] = "$hostile ${it.last()}"
                this[samples.avg] = it.hashCode() / 7.0
                this[samples.max] = it.hashCode() / 3.0
            }
        }
        val expected = readSamples(viaBatch)
        assertEquals(9_000, expected.size)
        assertTrue(expected.all { it.n == 1 }, "the reference applies the client-side default")
        assertTrue(expected.all { hostile in it.label }, "the hostile characters round-tripped through the reference too")
        assertEquals(expected, readSamples(viaRows), "insertRowsIgnoringConflicts must store the same rows (every column) as batchInsert")
    }

    @Test
    fun `process rows - NULL and non-NULL columns and a database-filled id - equal what batchInsert writes`() = runBlocking {
        data class Run(val version: String?, val limit: Long?)
        val runs = listOf(Run("1.0", 512L), Run(null, null), Run("2.0", null), Run(null, 7L))
        fun tag(prefix: String) = "mri-$prefix-${System.nanoTime()}"
        val batchTag = tag("batch")
        val rowsTag = tag("rows")
        store.transaction {
            processes.batchInsert(runs.withIndex().toList(), shouldReturnGeneratedValues = false) { (i, run) ->
                this[processes.instance] = "$batchTag-$i-$hostile"
                this[processes.startedAt] = 5L
                this[processes.lastSeen] = 6L
                this[processes.buildVersion] = run.version
                this[processes.processors] = 4
                this[processes.heapMaxBytes] = 256L
                this[processes.containerLimitBytes] = run.limit
            }
            processes.insertRowsIgnoringConflicts(runs.withIndex().toList()) { (i, run) ->
                this[processes.instance] = "$rowsTag-$i-$hostile"
                this[processes.startedAt] = 5L
                this[processes.lastSeen] = 6L
                this[processes.buildVersion] = run.version
                this[processes.processors] = 4
                this[processes.heapMaxBytes] = 256L
                this[processes.containerLimitBytes] = run.limit
            }
        }
        suspend fun read(prefix: String) = store.transaction {
            processes.selectAll().toList().filter { it[processes.instance].startsWith(prefix) }
                .sortedBy { it[processes.instance] }
                .map {
                    listOf(
                        it[processes.instance].removePrefix(prefix), it[processes.startedAt], it[processes.lastSeen],
                        it[processes.buildVersion], it[processes.buildCommit], it[processes.processors], it[processes.heapMaxBytes],
                        it[processes.containerLimitBytes],
                    )
                }
        }
        val expected = read(batchTag)
        assertEquals(4, expected.size)
        assertEquals(listOf("1.0", null, "2.0", null), expected.map { it[3] }, "NULL and non-NULL both round-trip")
        assertTrue(expected.all { (it[0] as String).endsWith(hostile) }, "the hostile characters round-tripped")
        assertEquals(expected, read(rowsTag))
    }

    @Test
    fun `insertRowsIgnoringConflicts drops a present row and a repeated key, first write wins, across chunks`() = runBlocking {
        val id = process()
        suspend fun write(rows: List<Pair<String, Double>>) = store.transaction {
            samples.insertRowsIgnoringConflicts(rows) {
                this[samples.resolution] = 300
                this[samples.ts] = 600_000L
                this[samples.processId] = id
                this[samples.metric] = it.first
                this[samples.label] = ""
                this[samples.avg] = it.second
                this[samples.max] = it.second
            }
        }
        write(listOf("m.00001" to 100.0, "m.$hostile" to 7.0)) // already stored
        // 8 columns -> 4,000 rows per statement: 4,003 rows are three statements (4,000 + 2 + 1);
        // m.00002 and m.04000 repeat (the second copy loses).
        val metrics = (1..4_000).map { "m.%05d".format(it) to 1.0 } + listOf("m.00002" to 2.0, "m.04000" to 2.0, "m.$hostile" to 8.0)
        write(metrics)
        write(emptyList())
        val stored = readSamples(id).associateBy { it.metric }
        assertEquals(4_001, stored.size)
        assertEquals(7.0, stored.getValue("m.$hostile").avg, "a hostile-named present row is left untouched too")
        assertEquals(100.0, stored.getValue("m.00001").avg, "an already-present row is left untouched")
        assertEquals(1.0, stored.getValue("m.00002").avg, "the first of a repeated key wins")
        assertEquals(1.0, stored.getValue("m.04000").avg, "also across the chunk boundary")
    }

    @Test
    fun `no rows is a no-op and rows that set different columns, or none, are refused`() = runBlocking {
        val id = process()
        store.transaction { samples.insertRowsIgnoringConflicts(emptyList<String>()) { this[samples.processId] = id } }
        assertEquals(0, readSamples(id).size)
        assertFailsWith<IllegalArgumentException> {
            store.transaction {
                samples.insertRowsIgnoringConflicts(listOf(1, 2)) {
                    this[samples.processId] = id
                    if (it == 1) this[samples.metric] = "x"
                }
            }
        }
        assertFailsWith<IllegalArgumentException> { store.transaction { samples.insertRowsIgnoringConflicts(listOf(1)) { } } }
        Unit
    }

    /** The shape of a table whose id comes from a named sequence (`autoIncrement("...")`) - never inserted into. */
    private object SequencedTable : Table("telemetry_processes") {
        val id = long("id").autoIncrement("telemetry_processes_id_seq")
        val instance = varchar("instance", 100)
    }

    @Test
    fun `a column of another table and a sequence-backed autoIncrement table are refused`() = runBlocking {
        val foreign = assertFailsWith<IllegalArgumentException> {
            store.transaction { samples.insertRowsIgnoringConflicts(listOf(1)) { this[processes.processors] = it } }
        }
        assertTrue(foreign.message!!.contains("processors") && foreign.message!!.contains("telemetry_processes"), foreign.message)
        val sequenced = assertFailsWith<IllegalArgumentException> {
            store.transaction { SequencedTable.insertRowsIgnoringConflicts(listOf("x")) { this[SequencedTable.instance] = it } }
        }
        assertTrue(sequenced.message!!.contains("sequence-backed"), sequenced.message)
    }

    @Test
    fun `chunk sizes are full chunks then a binary decomposition - a handful of distinct statement texts`() {
        assertEquals(emptyList(), chunkSizes(0, 100))
        assertEquals(listOf(8, 4, 1), chunkSizes(13, 100))
        assertEquals(listOf(100, 100, 32, 8, 2, 1), chunkSizes(243, 100))
        assertEquals(listOf(100), chunkSizes(100, 100))
        assertEquals(listOf(1), chunkSizes(1, 5_333))
        val max = 4_000
        val seen = mutableSetOf<Int>()
        for (total in 0..9_000) {
            val sizes = chunkSizes(total, max)
            assertEquals(total, sizes.sum(), "every row is written exactly once (total $total)")
            assertTrue(sizes.all { it == max || Integer.bitCount(it) == 1 }, "only full or power-of-two chunks (total $total)")
            seen += sizes
        }
        assertTrue(seen.size <= 13, "distinct chunk sizes: ${seen.size}")
    }

    // Exposed keeps its executed-statement list internal; a rename on an upgrade fails here, loudly.
    private fun executedStatements(tx: R2dbcTransaction): Int {
        val field = R2dbcTransaction::class.java.getDeclaredField("executedStatements").also { it.isAccessible = true }
        return (field.get(tx) as List<*>).size
    }

    @Test
    fun `a transaction does not keep the statements insertRowsIgnoringConflicts executed`() = runBlocking {
        val id = process()
        suspendTransaction(sharedDatabaseForTests()) {
            repeat(3) { round ->
                samples.insertRowsIgnoringConflicts((1..50).map { "m.$round.$it" }) {
                    this[samples.resolution] = 300
                    this[samples.ts] = 600_000L + round
                    this[samples.processId] = id
                    this[samples.metric] = it
                    this[samples.label] = ""
                    this[samples.avg] = 1.0
                    this[samples.max] = 1.0
                }
            }
            assertEquals(0, executedStatements(this), "insertRowsIgnoringConflicts left its executed statements reachable until commit")
        }
    }
}
