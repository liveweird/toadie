package ch.nokillswit

import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A granted SHARE lock on `entities` lets ordinary reads pass but holds a writer's SHARE
 * ROW EXCLUSIVE — observable without timing assertions (the `RegistryWriteBarrier` idiom
 * from `BlueprintConcurrencyTest`/`TagCategoryConcurrencyTest`, retargeted at `entities`).
 */
internal class EntityWriteBarrier private constructor(
    private val holder: Connection,
    private val observer: Connection,
) {
    private var released = false

    suspend fun awaitWriters(count: Int) {
        withTimeout(15_000) { while (waitingWriters() != count) delay(25) }
    }

    private suspend fun waitingWriters(): Int = withContext(Dispatchers.IO) {
        observer.prepareStatement(
            """
            SELECT COUNT(*)
            FROM pg_locks
            WHERE relation = 'entities'::regclass
              AND mode = 'ShareRowExclusiveLock'
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
        suspend fun acquire(): EntityWriteBarrier = withContext(NonCancellable + Dispatchers.IO) {
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
                holder.createStatement().use { it.execute("LOCK TABLE entities IN SHARE MODE") }
                EntityWriteBarrier(holder, observer)
            } catch (failure: Exception) {
                runCatching { holder?.close() }
                runCatching { observer?.close() }
                throw failure
            }
        }
    }
}
