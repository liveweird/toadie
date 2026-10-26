package ch.nokillswit.telemetry

/**
 * The storage groups the workspace section reports, HAND-LISTED: every table of the `public` schema except Flyway's own history
 * belongs to exactly one group (`StorageCollectorsTest` fails when a migration adds a table that is in none, or lists one twice),
 * so a group's size is the sum of its tables' `pg_total_relation_size` (heap, TOAST and indexes). The group names are only map
 * lookups and labels; no SQL text is built from them.
 */
internal val STORAGE_GROUPS: Map<String, List<String>> = linkedMapOf(
    "catalog" to listOf("catalog_files", "catalog_file_events"),
    "ontology" to listOf("blueprints", "entities", "conversion_mappings", "ontology_revision"),
    "registries" to listOf("dictionary_entries", "labels", "annotation_keys", "tag_categories", "entity_types"),
    "views" to listOf("lenses", "entity_queries", "graph_layouts", "entity_graph_layouts"),
    "accounts" to listOf(
        "users", "user_disabled_features", "auth_sessions", "revoked_tokens", "password_reset_tokens", "integration_clients",
    ),
    "telemetry" to listOf("telemetry_processes", "telemetry_samples"),
)

/** The table Flyway keeps its own history in: in no storage group (it is not project data and has no business entity). */
internal const val FLYWAY_TABLE = "flyway_schema_history"

/**
 * One counted workspace table: its `ws.rows` is the ACTIVE rows (`NOT marked_as_deleted`) - or, for an append-only table with no
 * soft delete ([activeOnly] false), all of them. The counted tables are the ones a user can see growing.
 */
internal class CountedTable(val name: String, val activeOnly: Boolean)

internal val COUNTED_TABLES: List<CountedTable> = listOf(
    CountedTable("catalog_files", activeOnly = true),
    CountedTable("catalog_file_events", activeOnly = false),
    CountedTable("blueprints", activeOnly = true),
    CountedTable("entities", activeOnly = true),
    CountedTable("conversion_mappings", activeOnly = true),
    CountedTable("lenses", activeOnly = true),
    CountedTable("entity_queries", activeOnly = true),
)

/** A belt-and-braces check on the one thing interpolated into SQL: a [COUNTED_TABLES] name, never input. */
private val IDENTIFIER = Regex("[a-z][a-z0-9_]*")

private fun sqlIdentifier(name: String): String {
    require(IDENTIFIER.matches(name)) { "not a plain table name: $name" }
    return name
}

/**
 * The workspace section's readings: `db.size` (`pg_database_size`), `db.group.size` per [STORAGE_GROUPS] entry and `ws.rows` per
 * [COUNTED_TABLES] entry. The only identifiers interpolated into SQL are the fixed [COUNTED_TABLES] names, each guarded by
 * [sqlIdentifier]'s plain-name check - never input. Catalog sizes plus `count(*)` only - no table's content
 * column is ever selected (so no `ReadLedger`
 * reservation is needed, `.claude/docs/security.md` read-ledger rule: identity-only reads), and it needs no other package's
 * service. Every statement runs in a transaction of its own (see [HeavyReader]).
 */
internal suspend fun HeavyReader.storageReadings(): List<Reading> {
    val total = read { query("SELECT pg_database_size(current_database())") { it.get(0, Long::class.javaObjectType) ?: 0L }.single() }
    val sizes = read {
        query(
            """
            SELECT c.relname, pg_total_relation_size(c.oid)::bigint
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'm')
            """.trimIndent(),
        ) { it.get(0, String::class.java).orEmpty() to (it.get(1, Long::class.javaObjectType) ?: 0L) }.toMap()
    }
    val groups = STORAGE_GROUPS.map { (group, tables) ->
        Reading(TelemetryMetrics.DB_GROUP_SIZE, tables.sumOf { sizes[it] ?: 0L }.toDouble(), group)
    }
    val rows = COUNTED_TABLES.map { table ->
        val where = if (table.activeOnly) " WHERE NOT marked_as_deleted" else ""
        val count = read {
            val sql = "SELECT count(*)::bigint FROM ${sqlIdentifier(table.name)}$where"
            query(sql) { it.get(0, Long::class.javaObjectType) ?: 0L }.single()
        }
        Reading(TelemetryMetrics.WS_ROWS, count.toDouble(), table.name)
    }
    return listOf(Reading(TelemetryMetrics.DB_SIZE, total.toDouble())) + groups + rows
}
