package ch.nokillswit

import ch.nokillswit.infra.db.DEFAULT_IDLE_IN_TRANSACTION_TIMEOUT
import ch.nokillswit.infra.db.DatabaseKey
import ch.nokillswit.infra.db.withIdleInTransactionTimeout
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserService
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Option
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bounded R2DBC connection pool (`infra/db/Database.kt`, `.claude/docs/persistence.md`
 * "Connection pool"): before it existed, a plain `r2dbc:postgresql://` connect opened one
 * PostgreSQL backend per `suspendTransaction` with nothing capping how many ran at once —
 * measured against the compose stack, 120 parallel `GET /api/v1/entities/graph` requests
 * produced 81 concurrent backends against PostgreSQL's default `max_connections = 100`.
 *
 * Each test mints a unique `postgres.pool.applicationName` so overlapping test applications
 * sharing the Testcontainer never share one `pg_stat_activity` count.
 */
class ConnectionPoolTest {

    /** A plain JDBC round trip against the shared Testcontainer — never through the pool under test. */
    private fun activeConnections(applicationName: String): Int =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { conn ->
            conn.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name = ?").use { stmt ->
                stmt.setString(1, applicationName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Whether backend [pid] still exists — a plain JDBC read, never through the pool under test. */
    private fun backendExists(pid: Int): Boolean =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { conn ->
            conn.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE pid = ?").use { stmt ->
                stmt.setInt(1, pid)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    /** A trivial query — just enough to make the transaction actually acquire a pooled connection. */
    private suspend fun org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction.touchDatabase() {
        UserService.Users.selectAll().limit(1).toList()
    }

    @Test
    fun `concurrent transactions are bounded by postgres pool maxSize`() = testApplication {
        val appName = "toadie-test-${UUID.randomUUID()}"
        configureApp("postgres.pool.maxSize" to "4", "postgres.pool.applicationName" to appName)
        startApplication()
        val db = application.attributes[DatabaseKey]

        val gate = CompletableDeferred<Unit>()
        coroutineScope {
            repeat(12) {
                launch(Dispatchers.IO) {
                    suspendTransaction(db) {
                        touchDatabase()
                        gate.await()
                    }
                }
            }
            // Poll while (up to) four of the twelve coroutines are holding a pooled connection
            // and the rest are queued waiting to acquire one.
            var maxObserved = 0
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                maxObserved = maxOf(maxObserved, activeConnections(appName))
                delay(50)
            }
            assertTrue(
                maxObserved in 1..4,
                "expected at most 4 concurrent pooled connections (postgres.pool.maxSize), observed $maxObserved",
            )
            gate.complete(Unit)
            // Exiting this coroutineScope suspends until all 12 launched transactions complete —
            // a hang here means a released connection was never handed to a queued waiter.
        }
    }

    @Test
    fun `a saturated pool times out an acquire instead of hanging`() = testApplication {
        val appName = "toadie-test-${UUID.randomUUID()}"
        configureApp(
            "postgres.pool.maxSize" to "1",
            "postgres.pool.initialSize" to "0",
            "postgres.pool.maxAcquireTimeSeconds" to "1",
            "postgres.pool.applicationName" to appName,
        )
        startApplication()
        val db = application.attributes[DatabaseKey]

        val gate = CompletableDeferred<Unit>()
        val holderStarted = CompletableDeferred<Unit>()
        coroutineScope {
            val holder = launch(Dispatchers.IO) {
                suspendTransaction(db) {
                    touchDatabase()
                    holderStarted.complete(Unit)
                    gate.await()
                }
            }
            holderStarted.await()

            // Database.kt pins defaultMaxAttempts = 1, so the 1-second acquire deadline surfaces after
            // ONE attempt (Exposed's default of three would triple it) — a generous outer bound around
            // that observed failure, never a sleep (.claude/docs/testing.md).
            val failure = withTimeoutOrNull(20_000) {
                runCatching { suspendTransaction(db) { touchDatabase() } }.exceptionOrNull()
            }
            assertNotNull(failure, "the second transaction must fail rather than hang past maxAcquireTimeSeconds")
            val chain = generateSequence(failure) { it.cause }.toList()
            assertTrue(
                chain.any { it is TimeoutException },
                "expected the pool's acquire-timeout exception in the cause chain, got: ${chain.map { it::class.qualifiedName }}",
            )

            gate.complete(Unit)
            holder.join()
        }
    }

    @Test
    fun `the pool releases every connection when the application stops`() {
        val appName = "toadie-test-${UUID.randomUUID()}"
        testApplication {
            configureApp("postgres.pool.applicationName" to appName)
            startApplication()
            suspendTransaction(application.attributes[DatabaseKey]) { touchDatabase() }
            assertTrue(activeConnections(appName) >= 1, "expected at least one pooled backend while the app is running")
        }
        // The testApplication{} block above has returned, which stops the embedded application
        // (and, via our ApplicationStopped hook, disposes the pool) before control reaches here.
        runBlocking {
            val cleared = withTimeoutOrNull(5_000) {
                while (isActive && activeConnections(appName) > 0) delay(100)
                true
            }
            assertNotNull(cleared, "expected pooled connections to be released once the application stopped")
        }
    }

    @Test
    fun `the pool is released even when a later module refuses startup`() {
        // Bootstrap runs AFTER Database (application.yaml module order) and fails closed on a
        // burned ADMIN_INITIAL_PASSWORD while the seed admin still carries the seed hash — so the
        // pool has already been built when startup aborts. Stopping the half-started application
        // must still dispose it.
        val appName = "toadie-test-${UUID.randomUUID()}"
        testApplication {
            configureApp(
                "postgres.pool.applicationName" to appName,
                "bootstrap.adminInitialPassword" to "changeme",
            )
            assertStartupFails("ADMIN_INITIAL_PASSWORD must not use a well-known seed") { startApplication() }
        }
        runBlocking {
            val cleared = withTimeoutOrNull(5_000) {
                while (isActive && activeConnections(appName) > 0) delay(100)
                true
            }
            assertNotNull(cleared, "expected the pool to be disposed after a failed startup")
        }
    }

    @Test
    fun `postgres pool maxSize out of range fails startup`() = testApplication {
        configureApp("postgres.pool.maxSize" to "0")
        assertStartupFails("postgres.pool.maxSize") { startApplication() }
    }

    @Test
    fun `postgres pool initialSize above maxSize fails startup`() = testApplication {
        configureApp("postgres.pool.initialSize" to "5", "postgres.pool.maxSize" to "2")
        assertStartupFails("postgres.pool.initialSize") { startApplication() }
    }

    @Test
    fun `postgres pool maxLifeTimeSeconds out of range fails startup`() {
        for (bad in listOf("0", "86401")) {
            testApplication {
                configureApp("postgres.pool.maxLifeTimeSeconds" to bad)
                assertStartupFails("postgres.pool.maxLifeTimeSeconds") { startApplication() }
            }
        }
    }

    @Test
    fun `a connection older than maxLifeTimeSeconds is replaced rather than reused`() = testApplication {
        // maxSize 1 forces both reads onto the SAME pool slot; the second acquire can only get a
        // different backend if the pool evicted the expired connection (r2dbc-pool checks the
        // eviction predicate on acquire as well as in its background sweep, so this holds whichever
        // fires first). The wait only has to exceed the 1-second lifetime — a lower bound, not a race.
        configureApp(
            "postgres.pool.maxSize" to "1",
            "postgres.pool.initialSize" to "0",
            "postgres.pool.maxLifeTimeSeconds" to "1",
            "postgres.pool.applicationName" to "toadie-test-${UUID.randomUUID()}",
        )
        startApplication()
        val db = application.attributes[DatabaseKey]

        suspend fun backendPid(): Int = suspendTransaction(db) {
            exec("SELECT pg_backend_pid()", explicitStatementType = StatementType.SELECT) { row ->
                row.get(0, java.lang.Integer::class.java)!!.toInt()
            }?.toList()?.single()
        } ?: error("pg_backend_pid() returned no row")

        val first = backendPid()
        // Real time is required here: the pool's lifetime clock is the system clock, and runTest-style
        // virtual time would not advance it (hence Dispatchers.IO, not the test scheduler).
        withContext(Dispatchers.IO) { delay(2_500) }
        assertNotEquals(first, backendPid(), "a connection past maxLifeTimeSeconds must be replaced by a fresh backend")
    }

    @Test
    fun `an idle connection past maxLifeTimeSeconds is closed by the background sweep with no further acquire`() = testApplication {
        // The sweep interval is min(60 s, maxIdle, maxLife) = 1 s here. Without the explicit
        // backgroundEvictionInterval it would default to maxIdle (an hour), so the idle backend would
        // stay open for the whole poll window. No acquire happens after the first transaction, so
        // acquire-time eviction cannot be what closes it.
        configureApp(
            "postgres.pool.maxSize" to "1",
            "postgres.pool.initialSize" to "0",
            "postgres.pool.maxIdleTimeSeconds" to "3600",
            "postgres.pool.maxLifeTimeSeconds" to "1",
            "postgres.pool.applicationName" to "toadie-test-${UUID.randomUUID()}",
        )
        startApplication()
        val db = application.attributes[DatabaseKey]

        val pid = suspendTransaction(db) {
            exec("SELECT pg_backend_pid()", explicitStatementType = StatementType.SELECT) { row ->
                row.get(0, java.lang.Integer::class.java)!!.toInt()
            }?.toList()?.single()
        } ?: error("pg_backend_pid() returned no row")
        assertTrue(backendExists(pid), "the pooled backend must exist right after the transaction")

        val gone = withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + 10_000_000_000L
            var closed = false
            while (!closed && System.nanoTime() < deadline) {
                closed = !backendExists(pid)
                if (!closed) delay(250)
            }
            closed
        }
        assertTrue(gone, "the idle backend $pid must be closed by the background sweep within 10 s of exceeding maxLifeTimeSeconds")
    }

    // ---- A hung database is bounded (.claude/docs/persistence.md) -------------------------

    /** Terminates every backend of [applicationName] from outside the pool; returns how many. */
    private fun terminateBackends(applicationName: String): Int =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM (SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = ?) t",
            ).use { stmt ->
                stmt.setString(1, applicationName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun `a hung database fails an authenticated request within the pool bounds and heals when it answers again`() {
        // The relay goes silent mid-life: established pooled connections stop answering and a new
        // connection's handshake never completes. The acquire-time validation fails after
        // maxValidationTime and a new connection after maxCreateConnectionTime, so the acquire fails
        // within its budget instead of hanging forever (the driver has no read timeout).
        FreezableRelay.forTestDatabase().use { relay ->
            testApplication {
                configureApp(
                    "postgres.r2dbcUrl" to relay.relayed(PostgresTestSupport.r2dbcUrl),
                    "postgres.pool.applicationName" to "pool-hung-${UUID.randomUUID()}",
                    "postgres.pool.maxValidationTimeSeconds" to "1",
                    "postgres.pool.maxCreateConnectionTimeSeconds" to "2",
                    "postgres.pool.maxAcquireTimeSeconds" to "3",
                )
                startApplication()
                val client = seededClient("pool-hung")
                assertEquals(HttpStatusCode.OK, client.get(CATALOG_FILES_PATH).status, "the relay must work while thawed")

                relay.frozen = true
                try {
                    val startedAt = System.nanoTime()
                    val response = withTimeout(20_000) { client.get(CATALOG_FILES_PATH) }
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                    assertEquals(HttpStatusCode.InternalServerError, response.status)
                    assertEquals("An unexpected error occurred", response.body<ProblemDetail>().detail)
                    // ~2 x maxAcquireTime worst case (r2dbc-pool retries a timed-out acquire once).
                    assertTrue(
                        elapsedMillis < 10_000,
                        "a hung database must fail the request within the pool bounds (took $elapsedMillis ms)",
                    )

                    val probeStartedAt = System.nanoTime()
                    val probe = withTimeout(20_000) { client.get("/readyz").status }
                    val probeMillis = (System.nanoTime() - probeStartedAt) / 1_000_000
                    assertEquals(HttpStatusCode.ServiceUnavailable, probe)
                    assertTrue(probeMillis < 5_000, "the readiness probe must answer 503 within its own deadline (took $probeMillis ms)")
                } finally {
                    // Thaw before the app stops, so disposing the pool does not wait on silent sockets.
                    relay.frozen = false
                }

                // Healing: a bounded poll, never a sleep.
                val healed = withTimeoutOrNull(15_000) {
                    while (client.get("/readyz").status != HttpStatusCode.OK) delay(100)
                    true
                }
                assertNotNull(healed, "readiness must recover within 15 s of the database answering again")
                assertEquals(HttpStatusCode.OK, client.get(CATALOG_FILES_PATH).status)
            }
        }
    }

    @Test
    fun `a connection the server killed is replaced at acquire, not handed to a request`() = testApplication {
        // A "killed connection costs no request" regression: the backend is terminated server-side and the
        // next transaction must still succeed. It does NOT pin ValidationDepth.REMOTE (pg_terminate_backend
        // closes the socket, which a local check would catch too) — the relay test above is the one that does.
        val appName = "pool-killed-${UUID.randomUUID()}"
        configureApp("postgres.pool.applicationName" to appName, "postgres.pool.initialSize" to "1", "postgres.pool.maxSize" to "1")
        startApplication()
        val db = application.attributes[DatabaseKey]
        suspendTransaction(db) { touchDatabase() }
        assertEquals(1, terminateBackends(appName), "exactly the one pooled backend is killed")

        // The next transaction acquires the dead connection's slot: validation replaces it.
        suspendTransaction(db) { touchDatabase() }
    }

    @Test
    fun `statementTimeoutSeconds cancels a statement the server runs too long`() = testApplication {
        configureApp(
            "postgres.pool.applicationName" to "pool-stmt-${UUID.randomUUID()}",
            "postgres.statementTimeoutSeconds" to "1",
        )
        startApplication()
        val db = application.attributes[DatabaseKey]
        // The value the server actually sees — Exposed SETs statement_timeout per statement from
        // its defaultQueryTimeout, which silently overwrote any other way of setting it.
        val setting = suspendTransaction(db) { exec("SHOW statement_timeout") { row -> row.get(0, String::class.java) }?.toList() }
        assertEquals(listOf("1s"), setting)
        val failure = runCatching {
            suspendTransaction(db) { exec("SELECT pg_sleep(3)") { row -> row.get(0) }?.toList() }
        }.exceptionOrNull()
        assertNotNull(failure, "a 3 s statement must not survive a 1 s statement_timeout")
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue("statement timeout" in messages, "unexpected failure: $messages")
    }

    @Test
    fun `the hung-database bounds are range-checked at boot`() {
        for ((key, value) in listOf(
            "postgres.pool.maxValidationTimeSeconds" to "0",
            "postgres.pool.maxValidationTimeSeconds" to "61",
            "postgres.pool.maxCreateConnectionTimeSeconds" to "0",
            "postgres.pool.maxCreateConnectionTimeSeconds" to "601",
            "postgres.connectTimeoutSeconds" to "0",
            "postgres.connectTimeoutSeconds" to "601",
            "postgres.statementTimeoutSeconds" to "-1",
            "postgres.statementTimeoutSeconds" to "3601",
        )) {
            testApplication {
                configureApp(key to value)
                assertStartupFails(key) { startApplication() }
            }
        }
    }

    // ---- idle_in_transaction_session_timeout (the server-side backstop) -------------------

    /** `SHOW idle_in_transaction_session_timeout` as the server reports it on a pooled connection. */
    private suspend fun showIdleInTransactionTimeout(db: org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase): String? =
        suspendTransaction(db) {
            exec("SHOW idle_in_transaction_session_timeout") { it.get(0, String::class.java) }?.toList()?.single()
        }

    @Test
    fun `postgres pool idleInTransactionTimeoutSeconds out of range fails startup`() {
        for (bad in listOf("-1", "86401")) {
            testApplication {
                configureApp("postgres.pool.idleInTransactionTimeoutSeconds" to bad)
                assertStartupFails("postgres.pool.idleInTransactionTimeoutSeconds") { startApplication() }
            }
        }
    }

    @Test
    fun `postgres pool idleInTransactionTimeoutSeconds 0 turns the timeout off and boots`() = testApplication {
        configureApp(
            "postgres.pool.idleInTransactionTimeoutSeconds" to "0",
            "postgres.pool.applicationName" to "pool-iitt-${UUID.randomUUID()}",
        )
        startApplication()
        assertEquals("0", showIdleInTransactionTimeout(application.attributes[DatabaseKey]))
    }

    @Test
    fun `every pooled connection carries the idle-in-transaction timeout, five minutes by default`() = testApplication {
        configureApp("postgres.pool.applicationName" to "pool-iitt-${UUID.randomUUID()}")
        startApplication()
        assertEquals("5min", showIdleInTransactionTimeout(application.attributes[DatabaseKey]))
        // The shipped default is the documented constant (a rewritten yaml default would otherwise drift silently).
        val shipped = ApplicationConfig("application.yaml").property("postgres.pool.idleInTransactionTimeoutSeconds").getString().toLong()
        assertEquals(DEFAULT_IDLE_IN_TRANSACTION_TIMEOUT.seconds, shipped)
    }

    @Test
    fun `the idle-in-transaction startup option merges with an operator's options and never overrides theirs`() {
        val opt = PostgresqlConnectionFactoryProvider.OPTIONS
        val key = "idle_in_transaction_session_timeout"
        val five = Duration.ofMinutes(5)
        val base = ConnectionFactoryOptions.parse("r2dbc:postgresql://localhost:5432/toadie")
        assertEquals(mapOf(key to "300000"), withIdleInTransactionTimeout(base, five).getValue(opt))
        val withMap = base.mutate().option(opt, mapOf("search_path" to "public")).build()
        assertEquals(mapOf("search_path" to "public", key to "300000"), withIdleInTransactionTimeout(withMap, five).getValue(opt))
        val operatorSet = base.mutate().option(opt, mapOf(key to "1000")).build()
        assertEquals(mapOf(key to "1000"), withIdleInTransactionTimeout(operatorSet, five).getValue(opt))
        // A URL's `?options=` arrives as its string form; Option.valueOf interns by name, so this IS the driver's option.
        val stringForm = Option.valueOf<Any>("options")
        val asString = base.mutate().option(stringForm, "search_path=public").build()
        assertEquals("search_path=public;$key=300000", withIdleInTransactionTimeout(asString, five).getValue(stringForm).toString())
        val stringOperatorSet = base.mutate().option(stringForm, "$key=1000").build()
        assertEquals("$key=1000", withIdleInTransactionTimeout(stringOperatorSet, five).getValue(stringForm).toString())
    }
}
