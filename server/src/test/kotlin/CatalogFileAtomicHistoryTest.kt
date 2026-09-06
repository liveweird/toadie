package ch.nokillswit

import ch.nokillswit.catalog.CatalogFileEventPageResponse
import ch.nokillswit.catalog.CatalogFileEventType
import ch.nokillswit.catalog.CatalogFileResponse
import ch.nokillswit.catalog.CatalogFileWriteRequest
import ch.nokillswit.catalog.ImportRequest
import ch.nokillswit.catalog.ImportResponse
import ch.nokillswit.catalog.ImportResultStatus
import ch.nokillswit.catalog.SyncCatalogFileRequest
import ch.nokillswit.catalog.SyncStateResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PostgreSQL-backed rollback regressions for catalog rows and their product history. */
class CatalogFileAtomicHistoryTest {

    private data class StoredState(
        val rowJson: String,
        val eventCount: Int,
    )

    private fun writeRequest(file: ch.nokillswit.catalog.CatalogFile, sourceUrl: String? = null) =
        CatalogFileWriteRequest(file.kind, file.metadata, file.spec, sourceUrl)

    private fun sqlLiteral(value: String): String = "'${value.replace("'", "''")}'"

    /** Holds one catalog row and observes the complete queue of writers blocked behind it. */
    private class CatalogRowBarrier private constructor(
        private val holder: Connection,
        private val observer: Connection,
        private val holderPid: Int,
    ) {
        private var released = false

        suspend fun awaitWriters(count: Int) {
            withTimeout(15_000) {
                while (waitingWriters() != count) delay(25)
            }
        }

        private suspend fun waitingWriters(): Int = withContext(Dispatchers.IO) {
            observer.prepareStatement(
                """
                WITH RECURSIVE blocked(pid) AS (
                    SELECT pid
                    FROM pg_stat_activity
                    WHERE ? = ANY(pg_blocking_pids(pid))
                    UNION
                    SELECT activity.pid
                    FROM pg_stat_activity activity
                    JOIN blocked blocker ON blocker.pid = ANY(pg_blocking_pids(activity.pid))
                )
                SELECT COUNT(*) FROM blocked
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, holderPid)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }

        suspend fun release() {
            if (released) return
            released = true
            withContext(NonCancellable + Dispatchers.IO) {
                var committed = false
                try {
                    holder.commit()
                    committed = true
                } finally {
                    if (!committed) runCatching { holder.rollback() }
                    try {
                        holder.close()
                    } finally {
                        observer.close()
                    }
                }
            }
        }

        companion object {
            suspend fun acquire(id: UInt): CatalogRowBarrier {
                var acquired: CatalogRowBarrier? = null
                return try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        var holder: Connection? = null
                        var observer: Connection? = null
                        try {
                            holder = DriverManager.getConnection(
                                PostgresTestSupport.jdbcUrl,
                                PostgresTestSupport.user,
                                PostgresTestSupport.password,
                            )
                            observer = DriverManager.getConnection(
                                PostgresTestSupport.jdbcUrl,
                                PostgresTestSupport.user,
                                PostgresTestSupport.password,
                            )
                            holder.autoCommit = false
                            val pid = holder.createStatement().use { statement ->
                                statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                                    rows.next()
                                    rows.getInt(1)
                                }
                            }
                            holder.prepareStatement("SELECT id FROM catalog_files WHERE id = ? FOR UPDATE")
                                .use { statement ->
                                    statement.setLong(1, id.toLong())
                                    statement.executeQuery().use { rows -> check(rows.next()) }
                                }
                            CatalogRowBarrier(holder, observer, pid).also { acquired = it }
                        } catch (failure: Exception) {
                            runCatching { holder?.close() }
                            runCatching { observer?.close() }
                            throw failure
                        }
                    }
                } catch (failure: Exception) {
                    try {
                        acquired?.release()
                    } catch (cleanupFailure: Exception) {
                        failure.addSuppressed(cleanupFailure)
                    }
                    throw failure
                }
            }
        }
    }

    /** Installs a uniquely named trigger whose predicate is limited to one actor and file. */
    private suspend fun <T> withFailingEventInsert(
        actorId: UInt,
        fileName: String,
        fileId: UInt? = null,
        block: suspend () -> T,
    ): T {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "fail_catalog_event_$suffix"
        val trigger = "fail_catalog_event_trigger_$suffix"
        val targetPredicate = fileId?.let { "NEW.catalog_file_id = ${it.toLong()}" }
            ?: "EXISTS (SELECT 1 FROM catalog_files WHERE id = NEW.catalog_file_id " +
                "AND name = ${sqlLiteral(fileName)})"
        var installed = false
        return try {
            withContext(NonCancellable + Dispatchers.IO) {
                DriverManager.getConnection(
                    PostgresTestSupport.jdbcUrl,
                    PostgresTestSupport.user,
                    PostgresTestSupport.password,
                ).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """
                                CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS ${'$'}body${'$'}
                                BEGIN
                                    IF NEW.user_id = ${actorId.toLong()} AND $targetPredicate THEN
                                        RAISE EXCEPTION 'forced catalog history insert failure';
                                    END IF;
                                    RETURN NEW;
                                END
                                ${'$'}body${'$'}
                            """.trimIndent(),
                        )
                        try {
                            statement.execute(
                                "CREATE TRIGGER $trigger BEFORE INSERT ON catalog_file_events " +
                                    "FOR EACH ROW EXECUTE FUNCTION $function()",
                            )
                            installed = true
                        } catch (failure: Exception) {
                            runCatching { statement.execute("DROP FUNCTION IF EXISTS $function()") }
                            throw failure
                        }
                    }
                }
            }
            block()
        } finally {
            if (installed) {
                withContext(NonCancellable + Dispatchers.IO) {
                    DriverManager.getConnection(
                        PostgresTestSupport.jdbcUrl,
                        PostgresTestSupport.user,
                        PostgresTestSupport.password,
                    ).use { connection ->
                        connection.createStatement().use { statement ->
                            try {
                                statement.execute("DROP TRIGGER IF EXISTS $trigger ON catalog_file_events")
                            } finally {
                                statement.execute("DROP FUNCTION IF EXISTS $function()")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fileCount(name: String): Int =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM catalog_files WHERE name = ?").use { statement ->
                    statement.setString(1, name)
                    statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                }
            }

    private fun eventCountForActor(actorId: UInt): Int =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM catalog_file_events WHERE user_id = ?").use {
                    it.setLong(1, actorId.toLong())
                    it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                }
            }

    private fun storedState(id: UInt): StoredState =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .use { connection ->
                connection.prepareStatement(
                    """
                    SELECT row_to_json(f)::text,
                           (SELECT COUNT(*) FROM catalog_file_events e WHERE e.catalog_file_id = f.id)
                    FROM catalog_files f
                    WHERE f.id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, id.toLong())
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "missing catalog fixture $id" }
                        StoredState(
                            rowJson = rows.getString(1),
                            eventCount = rows.getInt(2),
                        )
                    }
                }
            }

    @Test
    fun `a failed creation event rolls back the catalog row`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("atomic-create")
        val actorId = TestUsers.seed(email, "pw", role = UserRole.USER)
        val client = authedClient(email, "pw")
        val name = uniqueEntityName("atomic-create")

        val response = withFailingEventInsert(actorId, name) {
            client.postJson(CATALOG_FILES_PATH, componentFile(name))
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(0, fileCount(name))
        assertEquals(0, eventCountForActor(actorId))
    }

    @Test
    fun `failed update sync and delete events roll back their complete catalog mutations`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("atomic-write")
        val actorId = TestUsers.seed(email, "pw", role = UserRole.USER)
        val client = authedClient(email, "pw")
        val name = uniqueEntityName("atomic-write")
        val originalUrl = "https://example.com/$name/original.yaml"
        val created: CatalogFileResponse = client.postJson(
            CATALOG_FILES_PATH,
            writeRequest(componentFile(name, title = "Original"), originalUrl),
        ).body()
        client.postJson(
            "$CATALOG_FILES_PATH/${created.id}/sync",
            SyncCatalogFileRequest(componentFile(name, title = "Baseline")),
        )
        val baseline = storedState(created.id)

        val update = withFailingEventInsert(actorId, name, created.id) {
            client.putJson(
                "$CATALOG_FILES_PATH/${created.id}",
                writeRequest(
                    componentFile("$name-renamed", namespace = "external", title = "Local edit"),
                    "https://example.com/$name/replacement.yaml",
                ),
            )
        }
        assertEquals(HttpStatusCode.InternalServerError, update.status)
        assertEquals(baseline, storedState(created.id))

        val sync = withFailingEventInsert(actorId, name, created.id) {
            client.postJson(
                "$CATALOG_FILES_PATH/${created.id}/sync",
                SyncCatalogFileRequest(componentFile(name, title = "Repo edit", lifecycle = "deprecated")),
            )
        }
        assertEquals(HttpStatusCode.InternalServerError, sync.status)
        assertEquals(baseline, storedState(created.id))

        val deletion = withFailingEventInsert(actorId, name, created.id) {
            client.delete("$CATALOG_FILES_PATH/${created.id}")
        }
        assertEquals(HttpStatusCode.InternalServerError, deletion.status)
        assertEquals(baseline, storedState(created.id))

        assertEquals(HttpStatusCode.OK, client.get("$CATALOG_FILES_PATH/${created.id}").status)
        client.delete("$CATALOG_FILES_PATH/${created.id}")
    }

    @Test
    fun `an import event failure rolls back only its row and keeps adjacent URL imports`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("atomic-import")
        val actorId = TestUsers.seed(email, "pw", role = UserRole.USER)
        val client = authedClient(email, "pw")
        val failedName = uniqueEntityName("atomic-import-fail")
        val successfulBeforeName = uniqueEntityName("atomic-import-before")
        val successfulAfterName = uniqueEntityName("atomic-import-after")
        val sourceUrl = "https://example.com/${uniqueEntityName("atomic-import-source")}.yaml"

        val response: ImportResponse = withFailingEventInsert(actorId, failedName) {
            client.postJson(
                "$CATALOG_FILES_PATH/import",
                ImportRequest(
                    files = listOf(
                        componentFile(successfulBeforeName),
                        componentFile(failedName),
                        componentFile(successfulAfterName),
                    ),
                    sourceUrl,
                ),
            ).body()
        }

        val failed = response.results[1]
        assertEquals(ImportResultStatus.ERROR, failed.status)
        assertEquals("Storage failed", failed.message)
        assertNull(failed.fileId)
        assertEquals(0, fileCount(failedName))
        assertEquals(2, eventCountForActor(actorId))

        val successful = listOf(response.results[0], response.results[2])
        val successfulIds = successful.map { result ->
            assertTrue(result.status in setOf(ImportResultStatus.CREATED, ImportResultStatus.CREATED_WITH_FINDINGS))
            checkNotNull(result.fileId)
        }
        try {
            successfulIds.zip(listOf(successfulBeforeName, successfulAfterName)).forEach { (id, expectedName) ->
                val stored = storedState(id)
                assertTrue(sourceUrl in stored.rowJson)
                val syncState: SyncStateResponse = client.get("$CATALOG_FILES_PATH/$id/sync").body()
                assertTrue(syncState.lastSyncedAt > 0)
                assertEquals(expectedName, syncState.syncedDocument?.metadata?.name)
                val events: CatalogFileEventPageResponse = client.get("$CATALOG_FILES_PATH/$id/events").body()
                assertEquals(listOf(CatalogFileEventType.CREATED), events.items.map { it.type })
                assertEquals("import", events.items.single().params["origin"])
            }
        } finally {
            successfulIds.forEach { client.delete("$CATALOG_FILES_PATH/$it") }
        }
    }

    @Test
    fun `overlapping updates derive each history diff from the preceding committed row`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("atomic-overlap")
        val name = uniqueEntityName("atomic-overlap")
        val created = client.createCatalogFile(componentFile(name, title = "Original"))
        val barrier = CatalogRowBarrier.acquire(created.id)
        try {
            coroutineScope {
                val first = async {
                    client.putJson("$CATALOG_FILES_PATH/${created.id}", componentFile(name, title = "First"))
                }
                val second = async {
                    client.putJson("$CATALOG_FILES_PATH/${created.id}", componentFile(name, title = "Second"))
                }
                try {
                    barrier.awaitWriters(2)
                } finally {
                    barrier.release()
                }
                withTimeout(15_000) {
                    assertEquals(HttpStatusCode.NoContent, first.await().status)
                    assertEquals(HttpStatusCode.NoContent, second.await().status)
                }
            }
        } finally {
            barrier.release()
        }

        val events: CatalogFileEventPageResponse =
            client.get("$CATALOG_FILES_PATH/${created.id}/events?sort=timestamp,id").body()
        val updates = events.items.filter { it.type == CatalogFileEventType.UPDATED }
        assertEquals(2, updates.size)
        assertEquals("Original", updates[0].params["metadata.title.from"])
        assertEquals(updates[0].params["metadata.title.to"], updates[1].params["metadata.title.from"])
        val current: CatalogFileResponse = client.get("$CATALOG_FILES_PATH/${created.id}").body()
        assertEquals(current.metadata.title, updates[1].params["metadata.title.to"])

        client.delete("$CATALOG_FILES_PATH/${created.id}")
    }
}
