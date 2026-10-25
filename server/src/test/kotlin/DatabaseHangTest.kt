package ch.nokillswit

import ch.nokillswit.infra.db.DatabaseKey
import ch.nokillswit.infra.db.SEED_ADMIN_EMAIL
import ch.nokillswit.infra.db.lockingTransaction
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Replays the incident the hung-database bounds exist for (`.claude/docs/persistence.md` "A hung
 * database is bounded"; `.claude/docs/testing.md` "Hung-database regressions") against a REALLY
 * frozen PostgreSQL: `docker pause` on a DEDICATED container. The kernel keeps ACKing the paused
 * process's sockets, exactly like the original outage, so only the pool's client-side deadlines can
 * fire. `ConnectionPoolTest` covers the same bounds with [FreezableRelay] against the shared
 * container; this single test is the end-to-end check that a pause (not a relay) is bounded and that
 * the pool, the connection slot and a locking transaction all recover after the thaw.
 *
 * NEVER pause the shared [PostgresTestSupport] container: the whole suite runs against it.
 */
class DatabaseHangTest {

    private fun jdbcConnection(container: PostgreSQLContainer) =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    /** Whether backend [pid] still exists — a plain JDBC read against the dedicated container. */
    private fun backendExists(container: PostgreSQLContainer, pid: Int): Boolean =
        jdbcConnection(container).use { conn ->
            conn.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE pid = ?").use { stmt ->
                stmt.setInt(1, pid)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    /** Backends of [applicationName] — a plain JDBC read against the dedicated container. */
    private fun appBackends(container: PostgreSQLContainer, applicationName: String): Int =
        jdbcConnection(container).use { conn ->
            conn.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name = ?").use { stmt ->
                stmt.setString(1, applicationName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private suspend fun backendPid(db: R2dbcDatabase): Int = suspendTransaction(db) {
        exec("SELECT pg_backend_pid()", explicitStatementType = StatementType.SELECT) { row ->
            row.get(0, java.lang.Integer::class.java)!!.toInt()
        }?.toList()?.single()
    } ?: error("pg_backend_pid() returned no row")

    @Test
    fun `a paused database is bounded and the pool, its slot and the table locks recover after the unpause`() {
        val container = PostgreSQLContainer(PostgresTestSupport.IMAGE).apply {
            withDatabaseName("toadie_hang")
            withUsername("toadie")
            withPassword("toadie")
        }
        val appName = "pool-paused-${UUID.randomUUID()}"
        container.start()
        try {
            testApplication {
                configureApp(
                    "postgres.jdbcUrl" to container.jdbcUrl,
                    "postgres.r2dbcUrl" to "r2dbc:postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}",
                    "postgres.user" to container.username,
                    "postgres.password" to container.password,
                    "postgres.pool.applicationName" to appName,
                    "postgres.pool.maxSize" to "1",
                    "postgres.pool.initialSize" to "1",
                    "postgres.pool.maxAcquireTimeSeconds" to "3",
                    "postgres.pool.maxValidationTimeSeconds" to "1",
                    "postgres.pool.maxCreateConnectionTimeSeconds" to "2",
                )
                startApplication()
                val client = authedClient(SEED_ADMIN_EMAIL, "changeme")
                assertEquals(HttpStatusCode.OK, client.get("/readyz").status, "the database must answer before the pause")
                val db = application.attributes[DatabaseKey]
                val pid0 = backendPid(db)

                val dockerClient = container.dockerClient
                dockerClient.pauseContainerCmd(container.containerId).exec()
                try {
                    val startedAt = System.nanoTime()
                    val response = withTimeout(20_000) { client.get(CATALOG_FILES_PATH) }
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                    assertEquals(HttpStatusCode.InternalServerError, response.status)
                    assertEquals("An unexpected error occurred", response.body<ProblemDetail>().detail)
                    assertTrue(
                        elapsedMillis < 10_000,
                        "a paused database must fail the request within the pool bounds (took $elapsedMillis ms)",
                    )

                    val probeStartedAt = System.nanoTime()
                    val probe = withTimeout(20_000) { client.get("/readyz").status }
                    val probeMillis = (System.nanoTime() - probeStartedAt) / 1_000_000
                    assertEquals(HttpStatusCode.ServiceUnavailable, probe)
                    assertTrue(probeMillis < 5_000, "the readiness probe must answer 503 within its own deadline (took $probeMillis ms)")
                } finally {
                    runCatching { dockerClient.unpauseContainerCmd(container.containerId).exec() }
                }

                // Recovery, every wait a bounded poll (never a sleep).
                val ready = withTimeoutOrNull(15_000) {
                    while (client.get("/readyz").status != HttpStatusCode.OK) delay(100)
                    true
                }
                assertNotNull(ready, "readiness must recover within 15 s of the unpause")
                assertEquals(HttpStatusCode.OK, client.get(CATALOG_FILES_PATH).status)

                val pid1 = backendPid(db)
                assertNotEquals(pid0, pid1, "the frozen connection must have been replaced, not reused")
                val gone = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(10_000) {
                        while (backendExists(container, pid0)) delay(100)
                        true
                    }
                }
                assertNotNull(gone, "the invalidated connection's backend $pid0 must be closed within 10 s of the unpause")

                // No handshake socket leaked mid-create: this app's backends settle to at most maxSize + 1.
                val settled = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(10_000) {
                        while (appBackends(container, appName) > 2) delay(100)
                        true
                    }
                }
                assertNotNull(settled, "this app's backends must settle to at most maxSize + 1 (2) within 10 s of the unpause")

                // The single pool slot is free again and a table-locking transaction completes.
                withTimeout(5_000) {
                    lockingTransaction(db, "LOCK TABLE blueprints IN SHARE ROW EXCLUSIVE MODE") {}
                }
            }
        } finally {
            // Idempotent: the inner finally already unpaused unless the test died before the pause.
            runCatching { container.dockerClient.unpauseContainerCmd(container.containerId).exec() }
            container.stop()
        }
    }
}
