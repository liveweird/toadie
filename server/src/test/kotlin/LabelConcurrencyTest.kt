package ch.nokillswit

import ch.nokillswit.labels.LabelList
import ch.nokillswit.labels.LabelRequest
import ch.nokillswit.labels.MAX_LABELS
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
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
import kotlin.test.assertTrue

/**
 * Concurrency coverage for `LabelService.create`'s `MAX_LABELS` cap-enforcement race — the
 * `TagCategoryConcurrencyTest` capacity-boundary case, retargeted at the `labels` table now
 * that `create` runs its count-then-insert check under the same `lockingTransaction`
 * `SHARE ROW EXCLUSIVE` table lock (`.claude/docs/persistence.md`).
 */
class LabelConcurrencyTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun request(key: String, values: List<String>, kinds: List<String> = listOf("Component")) =
        LabelRequest(key = key, values = values, kinds = kinds)

    private suspend fun HttpClient.readLabels(): List<ch.nokillswit.labels.LabelResponse> =
        get("/api/v1/labels").body<LabelList>().items

    /**
     * A granted SHARE lock lets the old ownership SELECTs pass but holds their later DML.
     * The fixed service instead waits immediately on SHARE ROW EXCLUSIVE, which is observable
     * without timing assertions and keeps both contenders behind the same starting barrier.
     */
    private class RegistryWriteBarrier private constructor(
        private val holder: Connection,
        private val observer: Connection,
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
                SELECT COUNT(*)
                FROM pg_locks
                WHERE relation = 'labels'::regclass
                  AND mode IN ('ShareRowExclusiveLock', 'RowExclusiveLock')
                  AND NOT granted
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
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
            suspend fun acquire(): RegistryWriteBarrier = withContext(NonCancellable + Dispatchers.IO) {
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
                    holder.createStatement().use { it.execute("LOCK TABLE labels IN SHARE MODE") }
                    RegistryWriteBarrier(holder, observer)
                } catch (failure: Exception) {
                    runCatching { holder?.close() }
                    runCatching { observer?.close() }
                    throw failure
                }
            }
        }
    }

    private suspend fun raceBehindBarrier(
        first: suspend () -> HttpResponse,
        second: suspend () -> HttpResponse,
    ): List<HttpResponse> = coroutineScope {
        val barrier = RegistryWriteBarrier.acquire()
        val firstResponse = async { first() }
        val secondResponse = async { second() }
        try {
            barrier.awaitWriters(2)
        } finally {
            barrier.release()
        }
        withTimeout(15_000) { listOf(firstResponse.await(), secondResponse.await()) }
    }

    private suspend fun insertCapacityFillers(prefix: String, count: Int) =
        withContext(NonCancellable + Dispatchers.IO) {
            DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl,
                PostgresTestSupport.user,
                PostgresTestSupport.password,
            ).use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        "INSERT INTO labels (key, allowed_kinds, allowed_values) VALUES (?, ?, ?)",
                    ).use { statement ->
                        repeat(count) { index ->
                            statement.setString(1, "$prefix-filler-$index")
                            statement.setString(2, "[\"Component\"]")
                            statement.setString(3, "[\"value\"]")
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    connection.commit()
                } catch (failure: Exception) {
                    runCatching { connection.rollback() }
                    throw failure
                }
            }
        }

    private suspend fun removeOwnedLabels(prefix: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl,
                PostgresTestSupport.user,
                PostgresTestSupport.password,
            ).use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        "UPDATE labels SET marked_as_deleted = true WHERE key LIKE ?",
                    ).use {
                        it.setString(1, "$prefix%")
                        it.executeUpdate()
                    }
                    connection.commit()
                } catch (failure: Exception) {
                    runCatching { connection.rollback() }
                    throw failure
                }
            }
        }

    @Test
    fun `concurrent creates at the capacity boundary store only the two-hundredth label`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("label-concurrent-capacity", UserRole.ADMIN)
        val prefix = unique("label-capacity")
        val initialCount = admin.readLabels().size
        check(initialCount < MAX_LABELS - 1) {
            "label fixtures leaked into the shared registry: $initialCount active rows"
        }
        try {
            insertCapacityFillers(prefix, MAX_LABELS - 1 - initialCount)
            val responses = raceBehindBarrier(
                { admin.postJson("/api/v1/labels", request("$prefix-a", listOf("value-a"))) },
                { admin.postJson("/api/v1/labels", request("$prefix-b", listOf("value-b"))) },
            )

            assertEquals(
                listOf(HttpStatusCode.Created, HttpStatusCode.BadRequest),
                responses.map { it.status }.sortedBy { it.value },
            )
            val rejected = responses.single { it.status == HttpStatusCode.BadRequest }
            assertTrue(rejected.body<ProblemDetail>().detail!!.contains("registry is full"))
            assertEquals(MAX_LABELS, admin.readLabels().size)
        } finally {
            removeOwnedLabels(prefix)
        }
    }
}
