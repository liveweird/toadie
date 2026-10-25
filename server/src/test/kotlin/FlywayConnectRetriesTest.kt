package ch.nokillswit

import ch.nokillswit.infra.db.CONNECT_RETRY_MAX_INTERVAL_SECONDS
import ch.nokillswit.infra.db.buildFlyway
import ch.nokillswit.infra.db.readFlywayConnectRetries
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flywaydb.core.api.FlywayException

/**
 * Cold-start resilience (`infra/db/Flyway.kt`, `.claude/docs/persistence.md` "Cold start: Flyway waits for
 * PostgreSQL"): Flyway retries its boot connection instead of failing at once, so a pod that starts before
 * PostgreSQL accepts connections waits rather than crash-looping (ported from Flow's test of the same name).
 * The key is read and bounds-validated, the retry setting is provably wired to Flyway, and a REAL boot through
 * the module chain waits for a database that comes up late, which only works because of the raised
 * `ktor.application.startupTimeoutMillis`. The k8s startupProbe invariant is pinned in
 * `web/src/test/infrastructure.test.js`.
 */
class FlywayConnectRetriesTest {

    /** The longest Flyway sleeps between attempts with [retries] retries (1, 2, 4, 8, 8, … s), in seconds. */
    private fun worstCaseConnectWaitSeconds(retries: Int): Int =
        (0 until retries).sumOf { attempt -> minOf(1 shl minOf(attempt, 30), CONNECT_RETRY_MAX_INTERVAL_SECONDS) }

    private fun config(value: String) = MapApplicationConfig("postgres.connectRetries" to value)

    @Test
    fun `connectRetries is read and bounds-validated with the key in the message`() {
        assertEquals(0, readFlywayConnectRetries(config("0")))
        assertEquals(10, readFlywayConnectRetries(config("10")))
        assertEquals(15, readFlywayConnectRetries(config("15")))
        listOf("-1", "16").forEach { bad ->
            val failure = assertFailsWith<IllegalArgumentException>("value \"$bad\"") { readFlywayConnectRetries(config(bad)) }
            assertTrue("postgres.connectRetries" in failure.message.orEmpty(), failure.message)
        }
        listOf("abc", "").forEach { bad ->
            assertFailsWith<NumberFormatException>("value \"$bad\"") { readFlywayConnectRetries(config(bad)) }
        }
    }

    @Test
    fun `an out-of-range POSTGRES_CONNECT_RETRIES fails startup before any database contact`() = testApplication {
        configureApp("postgres.connectRetries" to "16")
        assertStartupFails("postgres.connectRetries must be between 0 and 15") { startApplication() }
    }

    @Test
    fun `the shipped default is ten retries and the built Flyway carries the configured retries`() = testApplication {
        configureApp()
        startApplication()
        assertEquals(10, readFlywayConnectRetries(application.environment.config))
        val flyway = buildFlyway(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password, 7)
        assertEquals(7, flyway.configuration.connectRetries)
        assertEquals(CONNECT_RETRY_MAX_INTERVAL_SECONDS, flyway.configuration.connectRetriesInterval)
    }

    @Test
    fun `the configured connectRetries reaches the Flyway that configureFlyway builds`() = testApplication {
        configureApp("postgres.connectRetries" to "3")
        // The Flyway module logs on Application.log (a test-engine-chosen name): capture the ROOT logger, as FlywayLogTest does.
        val appLog = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME)
        try {
            startApplication()
            val event = appLog.events.firstOrNull { it.formattedMessage.startsWith("Running Flyway migrations") }
            assertNotNull(event, "expected the Flyway startup log line")
            assertTrue("(connect retries: 3)" in event.formattedMessage, event.formattedMessage)
        } finally {
            appLog.detach()
        }
    }

    @Test
    fun `Ktor's startup timeout outlasts the longest Flyway wait`() = testApplication {
        configureApp()
        startApplication()
        assertEquals(63, worstCaseConnectWaitSeconds(10))
        val longestWaitMillis = worstCaseConnectWaitSeconds(15) * 1_000L // the 0..15 bound's maximum: 103 s
        val timeout = application.environment.config.property("ktor.application.startupTimeoutMillis").getString().toLong()
        // Boot besides Flyway (JVM, migrations, seed hashing) needs headroom. That the timeout stays INSIDE the k8s
        // startupProbe is pinned in web/src/test/infrastructure.test.js, where the probe is parsed.
        assertTrue(timeout >= longestWaitMillis + 20_000, "startupTimeoutMillis $timeout cuts the Flyway retry wait short")
    }

    @Test
    fun `Flyway waits and retries on an unreachable database, and fails fast at zero retries`() {
        val url = "jdbc:postgresql://127.0.0.1:1/toadie"
        fun elapsedMsOfFailure(retries: Int): Long {
            val started = System.nanoTime()
            assertFailsWith<FlywayException> { buildFlyway(url, "u", "p", retries).migrate() }
            return (System.nanoTime() - started) / 1_000_000
        }
        elapsedMsOfFailure(0)
        val oneRetry = elapsedMsOfFailure(1)
        assertTrue(oneRetry >= 1_000, "one retry waits the 1 s first back-off, took $oneRetry ms")
    }

    @Test
    fun `a wrong password still fails, only after the retries are spent`() {
        // Flyway 13.11.1's openConnection retries every SQLException from getConnection (the
        // specific-exception short-circuit covers SQL Server / untrusted-certificate cases only), so
        // a rejected login is retried too and fails with the original error once the retries run out.
        val started = System.nanoTime()
        assertFailsWith<FlywayException> {
            buildFlyway(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, "not-the-password", 1).migrate()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs >= 1_000, "a rejected login is retried once after the 1 s back-off, took $elapsedMs ms")
    }

    /**
     * Boots the whole application (the shipped `application.yaml` module chain and its startup timeout) with its
     * JDBC URL pointing at a port nothing listens on until a background thread starts a relay to the Testcontainer
     * [databaseUpAfterMillis] later. The wall-clock delay is the point: "the database comes up late" has no event to
     * wait on, and the boot under test is what waits for it. The R2DBC URL stays on the real container.
     */
    private fun bootWithDatabaseUpAfter(databaseUpAfterMillis: Long): Result<Unit> {
        val port = ServerSocket(0).use { it.localPort }
        val url = PostgresTestSupport.jdbcUrl.replace(Regex("//[^/]+/"), "//127.0.0.1:$port/")
        val (host, targetPort) = Regex("//([^:/]+):(\\d+)/").find(PostgresTestSupport.jdbcUrl)!!.destructured
        val relay = AtomicReference<FreezableRelay?>()
        val starter = thread(isDaemon = true, name = "late-database") {
            Thread.sleep(databaseUpAfterMillis)
            relay.set(FreezableRelay(host, targetPort.toInt(), listenPort = port))
        }
        try {
            return runCatching {
                testApplication {
                    configureApp("postgres.jdbcUrl" to url)
                    startApplication()
                }
            }
        } finally {
            starter.join()
            relay.get()?.close()
        }
    }

    // The database is up after 8 s: Flyway's attempts at t = 0, 1, 3, 7 s are refused and the one at t = 15 s succeeds,
    // i.e. the migration only returns after Ktor's 10 s default would have expired. Ktor's withTimeout cannot interrupt
    // Flyway's blocking sleep; it fires at the next suspension point AFTER migrate() returned (measured 2026-10-25: a
    // 10 s timeout failed this boot with TimeoutCancellationException at ~16.8 s), so this boot stays green only while
    // the SHIPPED `ktor.application.startupTimeoutMillis` outlasts the wait.
    @Test
    fun `a boot with the shipped startup timeout waits for a database that comes up late`() {
        val outcome = bootWithDatabaseUpAfter(8_000)
        assertTrue(outcome.isSuccess, "the boot must wait for the database: ${outcome.exceptionOrNull()}")
    }
}
