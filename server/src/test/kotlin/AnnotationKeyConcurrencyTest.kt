package ch.nokillswit

import ch.nokillswit.annotations.AnnotationKeyList
import ch.nokillswit.annotations.AnnotationKeyRequest
import ch.nokillswit.annotations.MAX_ANNOTATION_KEYS
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
 * Concurrency coverage for `AnnotationKeyService.create`'s `MAX_ANNOTATION_KEYS`
 * cap-enforcement race — the `TagCategoryConcurrencyTest` capacity-boundary case, retargeted
 * at the `annotation_keys` table now that `create` runs its count-then-insert check under the
 * same `lockingTransaction` `SHARE ROW EXCLUSIVE` table lock (`.claude/docs/persistence.md`).
 */
class AnnotationKeyConcurrencyTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun request(key: String, kinds: List<String> = listOf("Component")) =
        AnnotationKeyRequest(key = key, kinds = kinds)

    private suspend fun HttpClient.readAnnotationKeys(): List<ch.nokillswit.annotations.AnnotationKeyResponse> =
        get("/api/v1/annotation-keys").body<AnnotationKeyList>().items

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
                WHERE relation = 'annotation_keys'::regclass
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
                    holder.createStatement().use { it.execute("LOCK TABLE annotation_keys IN SHARE MODE") }
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
                        "INSERT INTO annotation_keys (key, allowed_kinds) VALUES (?, ?)",
                    ).use { statement ->
                        repeat(count) { index ->
                            statement.setString(1, "$prefix-filler-$index")
                            statement.setString(2, "[\"Component\"]")
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

    private suspend fun removeOwnedAnnotationKeys(prefix: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl,
                PostgresTestSupport.user,
                PostgresTestSupport.password,
            ).use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        "UPDATE annotation_keys SET marked_as_deleted = true WHERE key LIKE ?",
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
    fun `concurrent creates at the capacity boundary store only the two-hundredth annotation key`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("annotation-key-concurrent-capacity", UserRole.ADMIN)
        val prefix = unique("annotation-key-capacity")
        val initialCount = admin.readAnnotationKeys().size
        check(initialCount < MAX_ANNOTATION_KEYS - 1) {
            "annotation-key fixtures leaked into the shared registry: $initialCount active rows"
        }
        try {
            insertCapacityFillers(prefix, MAX_ANNOTATION_KEYS - 1 - initialCount)
            val responses = raceBehindBarrier(
                { admin.postJson("/api/v1/annotation-keys", request("$prefix-a")) },
                { admin.postJson("/api/v1/annotation-keys", request("$prefix-b")) },
            )

            assertEquals(
                listOf(HttpStatusCode.Created, HttpStatusCode.BadRequest),
                responses.map { it.status }.sortedBy { it.value },
            )
            val rejected = responses.single { it.status == HttpStatusCode.BadRequest }
            assertTrue(rejected.body<ProblemDetail>().detail!!.contains("registry is full"))
            assertEquals(MAX_ANNOTATION_KEYS, admin.readAnnotationKeys().size)
        } finally {
            removeOwnedAnnotationKeys(prefix)
        }
    }
}
