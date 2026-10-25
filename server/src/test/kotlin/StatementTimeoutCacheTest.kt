package ch.nokillswit

import ch.nokillswit.infra.db.StatementTimeoutCachingConnectionFactory
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.ValidationDepth
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.sql.DriverManager
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Correctness pins for `StatementTimeoutCachingConnection` (`infra/db/StatementTimeoutCache.kt`, ported from Flow;
 * `.claude/docs/persistence.md` "Statement timeouts: the per-connection cache"), each on its OWN ONE-connection pool so
 * every borrower is the same backend and no test depends on the shared pool's state or order. The pool is built like
 * `Database.kt`'s (the caching factory BELOW it, REMOTE validation); the application-wired end of the same behaviour is
 * `ConnectionPoolTest`'s statement-timeout cases.
 */
class StatementTimeoutCacheTest {
    private fun withSingleConnectionDb(block: suspend (R2dbcDatabase) -> Unit): Unit = runBlocking {
        val options = ConnectionFactoryOptions.parse(PostgresTestSupport.r2dbcUrl)
            .mutate()
            .option(ConnectionFactoryOptions.USER, PostgresTestSupport.user)
            .option(ConnectionFactoryOptions.PASSWORD, PostgresTestSupport.password)
            .build()
        val pool = ConnectionPool(
            ConnectionPoolConfiguration.builder(StatementTimeoutCachingConnectionFactory(ConnectionFactories.get(options)))
                .maxSize(1)
                .initialSize(1)
                .maxAcquireTime(Duration.ofSeconds(10))
                .validationDepth(ValidationDepth.REMOTE)
                .build(),
        )
        try {
            block(
                R2dbcDatabase.connect(
                    connectionFactory = pool,
                    databaseConfig = R2dbcDatabaseConfig.Builder().apply {
                        connectionFactoryOptions = options
                        defaultMaxAttempts = 1
                    },
                ),
            )
        } finally {
            pool.dispose()
        }
    }

    private suspend fun R2dbcTransaction.showTimeout(): String? =
        exec("SHOW statement_timeout") { row -> row.get(0, String::class.java) }?.toList()?.singleOrNull()

    private suspend fun R2dbcDatabase.show(seconds: Int): String? = suspendTransaction(this) {
        queryTimeout = seconds
        showTimeout()
    }

    private suspend fun R2dbcDatabase.backendPid(): Int = suspendTransaction(this) {
        exec("SELECT pg_backend_pid()", explicitStatementType = StatementType.SELECT) { row ->
            row.get(0, java.lang.Integer::class.java)!!.toInt()
        }?.toList()?.single()
    } ?: error("pg_backend_pid() returned no row")

    @Test
    fun `every borrower converges to its own timeout, including 0 after a budgeted one`() = withSingleConnectionDb { db ->
        assertEquals("0", db.show(0))
        assertEquals("2s", db.show(2))
        assertEquals("2s", db.show(2))
        assertEquals("0", db.show(0))
        assertEquals("3s", db.show(3))
        assertEquals("0", db.show(0))
    }

    @Test
    fun `a rolled-back transaction does not leave a stale remembered value`() = withSingleConnectionDb { db ->
        assertEquals("0", db.show(0))
        val failure = runCatching {
            suspendTransaction(db) {
                queryTimeout = 2
                assertEquals("2s", showTimeout())
                error("boom")
            }
        }.exceptionOrNull()
        assertNotNull(failure)
        // PostgreSQL reverted the rolled-back SET to 0; the next borrower asking for 2s must get it re-applied.
        assertEquals("2s", db.show(2))
    }

    @Test
    fun `a transaction aborted by an error does not leave the budget remembered (library-upgrade canary)`() = withSingleConnectionDb { db ->
        // Pins two behaviours outside this repo: r2dbc-postgresql turns the ROLLBACK command tag of a COMMIT on an aborted
        // transaction into an exception, and Exposed rolls back after a failed commit. Together they revert the transaction's
        // session-level SET on the server, and the cache must have forgotten it: the next borrower asking for 2s sees 2s.
        assertEquals("0", db.show(0))
        runCatching {
            suspendTransaction(db) {
                queryTimeout = 2
                assertEquals("2s", showTimeout())
                runCatching { exec("SELECT 1/0") }
            }
        }
        assertEquals("2s", db.show(2))
    }

    @Test
    fun `a budget is enforced again after a statement was cancelled by it`() = withSingleConnectionDb { db ->
        repeat(2) { round ->
            val failure = runCatching {
                suspendTransaction(db) {
                    queryTimeout = 1
                    exec("SELECT pg_sleep(4)")
                }
            }.exceptionOrNull()
            assertNotNull(failure, "round $round: pg_sleep(4) must not outlive a 1 s budget")
            assertTrue(failure.isQueryCanceled(), "round $round: expected query_canceled (57014), got $failure")
        }
        assertEquals("0", db.show(0))
    }

    @Test
    fun `a connection the server killed is replaced and the new backend gets the timeout sent`() = withSingleConnectionDb { db ->
        assertEquals("2s", db.show(2))
        val first = db.backendPid()
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { conn ->
            conn.prepareStatement("SELECT pg_terminate_backend(?)").use { stmt ->
                stmt.setInt(1, first)
                stmt.executeQuery().use { it.next() }
            }
        }
        // REMOTE validation discards the dead connection and the pool creates a new one: a fresh decorator, nothing remembered.
        assertEquals("2s", db.show(2))
        assertNotEquals(first, db.backendPid(), "the killed backend must have been replaced")
    }

    @Test
    fun `an unchanged timeout is not re-sent, which is why only the queryTimeout may ever set it`() = withSingleConnectionDb { db ->
        // Pins the skip itself and its documented limit (`persistence.md`): a hand-rolled SET behind the cache is NOT
        // undone by the next statement, because the connection already holds the requested 0. The pool is this test's
        // own and is disposed afterwards, so the desynchronised connection never reaches another test.
        suspendTransaction(db) {
            exec("SELECT 1")
            exec("SET statement_timeout = 7000")
            assertEquals("7s", showTimeout(), "the cache skipped the unchanged 0, so the hand-rolled value survived")
        }
        assertEquals("2s", db.show(2), "a changed value is always sent")
    }
}
