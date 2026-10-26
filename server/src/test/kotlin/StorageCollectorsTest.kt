package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.telemetry.COUNTED_TABLES
import ch.nokillswit.telemetry.FLYWAY_TABLE
import ch.nokillswit.telemetry.HeavyReader
import ch.nokillswit.telemetry.Reading
import ch.nokillswit.telemetry.STORAGE_GROUPS
import ch.nokillswit.telemetry.TelemetryMetrics
import ch.nokillswit.telemetry.query
import ch.nokillswit.telemetry.storageReadings
import ch.nokillswit.users.UserRole
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `telemetry/StorageCollectors.kt` over the shared container: the hand-listed groups cover every `public` table exactly once (a
 * migration that adds a table fails here until it is assigned), the sizes are the catalog's, and `ws.rows` equals an independent
 * `count(*)` with soft-deleted rows excluded - read by SQL of the test's own, not through the collector.
 */
class StorageCollectorsTest {
    private val reader = HeavyReader(telemetryStore())

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private suspend fun publicTables(): List<String> = suspendTransaction(sharedDatabaseForTests()) {
        query("SELECT tablename FROM pg_tables WHERE schemaname = 'public'") { it.get(0, String::class.java).orEmpty() }
    }.filterNot { it == FLYWAY_TABLE }

    private suspend fun independentCount(table: String, activeOnly: Boolean): Long = suspendTransaction(sharedDatabaseForTests()) {
        val where = if (activeOnly) " WHERE marked_as_deleted = FALSE" else ""
        query("SELECT count(*)::bigint FROM $table$where") { it.get(0, Long::class.javaObjectType) ?: 0L }.single()
    }

    private suspend fun rows(): Map<String, Long> =
        reader.storageReadings().filter { it.metric == TelemetryMetrics.WS_ROWS }.associate { it.label to it.value.toLong() }

    private fun List<Reading>.of(metric: ch.nokillswit.telemetry.MetricDef) = filter { it.metric == metric }

    @Test
    fun `every public table is in exactly one storage group`() = runBlocking {
        val tables = publicTables()
        val grouped = STORAGE_GROUPS.values.flatten()
        assertEquals(emptyList(), tables - grouped.toSet(), "a table is in no group: assign it in StorageCollectors.kt")
        assertEquals(emptyList(), grouped.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList(), "a table is in two groups")
        assertEquals(emptyList(), grouped - tables.toSet(), "a group names a table that does not exist")
        assertTrue(FLYWAY_TABLE !in grouped)
    }

    @Test
    fun `every counted table is a grouped table, and only plain names are ever interpolated`() {
        val grouped = STORAGE_GROUPS.values.flatten().toSet()
        assertEquals(emptyList(), COUNTED_TABLES.map { it.name }.filterNot { it in grouped }, "a counted table is in no storage group")
        assertEquals(COUNTED_TABLES.size, COUNTED_TABLES.map { it.name }.toSet().size, "a table is counted twice")
        assertTrue(COUNTED_TABLES.all { Regex("[a-z][a-z0-9_]*").matches(it.name) })
    }

    @Test
    fun `the readings carry the database size and one size per group`() = runBlocking {
        val readings = reader.storageReadings()
        val database = readings.of(TelemetryMetrics.DB_SIZE).single()
        assertEquals("", database.label)
        val groups = readings.of(TelemetryMetrics.DB_GROUP_SIZE)
        assertEquals(STORAGE_GROUPS.keys.toList(), groups.map { it.label })
        assertTrue(groups.all { it.value > 0.0 }, "every group holds relations with at least an index page: $groups")
        assertTrue(groups.sumOf { it.value } <= database.value, "the groups are part of the database")
    }

    @Test
    fun `row counts equal an independent count of the active rows`() = runBlocking {
        val counted = rows()
        assertEquals(COUNTED_TABLES.map { it.name }.toSet(), counted.keys)
        for (table in COUNTED_TABLES) {
            assertEquals(independentCount(table.name, table.activeOnly), counted.getValue(table.name), table.name)
        }
    }

    @Test
    fun `soft-deleted rows are not counted and the append-only history counts every event`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("storage-rows", UserRole.ADMIN)
        val before = rows()
        val bp = unique("bp-storage")
        val ent = unique("ent-storage")
        val file = unique("storage-file")
        try {
            client.postJson("/api/v1/blueprints", BlueprintRequest(identifier = bp, title = "T", schema = BlueprintSchema()))
            client.postJson("/api/v1/entities", EntityRequest(blueprint = bp, identifier = ent, title = "T"))
            val created = client.createCatalogFile(groupFile(file))
            val during = rows()
            assertEquals(before.getValue("blueprints") + 1, during.getValue("blueprints"))
            assertEquals(before.getValue("entities") + 1, during.getValue("entities"))
            assertEquals(before.getValue("catalog_files") + 1, during.getValue("catalog_files"))
            assertEquals(before.getValue("catalog_file_events") + 1, during.getValue("catalog_file_events"), "the created event")

            assertEquals(HttpStatusCode.NoContent, client.delete("$CATALOG_FILES_PATH/${created.id}").status)
            TestEntities.remove(ent)
            TestBlueprints.remove(bp)
            val after = rows()
            assertEquals(before.getValue("blueprints"), after.getValue("blueprints"))
            assertEquals(before.getValue("entities"), after.getValue("entities"))
            assertEquals(before.getValue("catalog_files"), after.getValue("catalog_files"))
            val events = after.getValue("catalog_file_events")
            assertEquals(before.getValue("catalog_file_events") + 2, events, "created + deleted: history outlives the file")
            val stored = independentCount("catalog_files", activeOnly = false)
            assertTrue(stored > after.getValue("catalog_files"), "the soft-deleted row is still stored")
            assertTrue(independentCount("entities", activeOnly = false) > after.getValue("entities"))
        } finally {
            TestEntities.remove(ent)
            TestBlueprints.remove(bp)
        }
    }
}
