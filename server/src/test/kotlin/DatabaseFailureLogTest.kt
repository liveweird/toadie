package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.withTimeout
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A database failure must never log the statement's bound values (`.claude/docs/observability.md`):
 * `ExposedR2dbcException.getMessage()` appends the failed SQL (`causedByQueries()`; Exposed 1.6.0 R2DBC
 * prints an unexpandable placeholder, which nothing pins) and a driver message can quote data, so
 * no database message reaches the ERROR line of a statement cancelled by `statement_timeout`
 * (57014). The incident shape: another session holds a table
 * lock on `catalog_files` and a request that touches it times out. The write case blocks an INSERT
 * (SHARE ROW EXCLUSIVE blocks only writers) and the read case a filtered SELECT (ACCESS EXCLUSIVE);
 * the SQLSTATE and the exception classes are logged instead, with the original stack trace.
 */
class DatabaseFailureLogTest {

    private val dbLogPrefix = "Unhandled exception while processing"

    /** Runs [request] while a second session holds [lockMode] on `catalog_files`; returns the one logged ERROR text. */
    private suspend fun ApplicationTestBuilder.loggedTextOfBlockedRequest(
        lockMode: String,
        request: suspend (HttpClient) -> HttpResponse,
    ): String {
        val client = seededClient("dbfail-log")
        val capture = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val locker = DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
        try {
            locker.autoCommit = false
            locker.createStatement().use { it.execute("LOCK TABLE catalog_files IN $lockMode MODE") }

            val response = withTimeout(20_000) { request(client) }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("An unexpected error occurred", response.body<ProblemDetail>().detail)

            val lines = capture.events.filter {
                it.level == ch.qos.logback.classic.Level.ERROR && it.formattedMessage.startsWith(dbLogPrefix)
            }
            assertEquals(1, lines.size, "exactly one ERROR line for the cancelled statement")
            val line = lines.single()
            assertTrue(line.throwableProxy != null, "a stand-in throwable with the original stack trace is attached")
            // Everything a logger prints: the message line plus each throwable's class, message and frames.
            return line.formattedMessage + generateSequence(line.throwableProxy) { it.cause }.joinToString(" ") { t ->
                t.className + " " + t.message.orEmpty() + " " + t.stackTraceElementProxyArray.joinToString { it.steAsString }
            }
        } finally {
            locker.rollback()
            locker.close()
            capture.detach()
        }
    }

    private fun ApplicationTestBuilder.bootWithShortStatementTimeout() {
        configureApp(
            "postgres.pool.applicationName" to "pool-dblog-${UUID.randomUUID()}",
            "postgres.statementTimeoutSeconds" to "1",
        )
    }

    @Test
    fun `a write cancelled by statement_timeout logs its SQLSTATE and classes, never the document`() = testApplication {
        bootWithShortStatementTimeout()
        startApplication()
        val marker = "SECRET-MARKER-${UUID.randomUUID()}"
        val file = componentFile(uniqueEntityName("dbfail"), title = marker)
            .let { it.copy(metadata = it.metadata.copy(description = marker)) }

        val logged = loggedTextOfBlockedRequest("SHARE ROW EXCLUSIVE") { it.postJson("$CATALOG_FILES_PATH?allowInvalid=true", file) }

        assertTrue("57014" in logged, "the SQLSTATE of the cancellation must be logged: $logged")
        assertTrue(marker !in logged, "the bound document text must never reach the log")
    }

    @Test
    fun `a read cancelled by statement_timeout logs its SQLSTATE and classes, never the bound filter`() = testApplication {
        bootWithShortStatementTimeout()
        startApplication()
        val marker = "secret-marker-${UUID.randomUUID()}"

        val logged = loggedTextOfBlockedRequest("ACCESS EXCLUSIVE") { it.get("$CATALOG_FILES_PATH?name=$marker") }

        assertTrue("57014" in logged, "the SQLSTATE of the cancellation must be logged: $logged")
        assertTrue(marker !in logged, "the bound filter value must never reach the log")
    }
}
