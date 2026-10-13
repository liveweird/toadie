package ch.nokillswit

import ch.nokillswit.infra.db.jdbcUrlForLogging
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * infra/db/Flyway.kt logs the operator-supplied JDBC URL at startup. A
 * `jdbc:postgresql://host/db?user=...&password=...` form must never land a credential in the
 * log (.claude/docs/observability.md, "never log secrets"); only `host:port/db` is logged.
 */
class FlywayLogTest {

    @Test
    fun `a malformed URL renders as a fixed placeholder instead of crashing`() {
        assertEquals("<unparseable jdbc url>", jdbcUrlForLogging("not a url at all"))
        assertEquals("<unparseable jdbc url>", jdbcUrlForLogging("jdbc:postgresql://"))
    }

    @Test
    fun `a query string carrying credentials never reaches the pure helper's rendered value`() {
        val marker = "marker-${UUID.randomUUID().toString().take(8)}"
        val rendered = jdbcUrlForLogging("jdbc:postgresql://localhost:5433/toadie?user=admin&password=$marker")
        assertEquals("localhost:5433/toadie", rendered)
        assertFalse(rendered.contains(marker))
        assertFalse(rendered.contains("password"))
    }

    @Test
    fun `a semicolon-delimited parameter block carrying credentials never reaches the rendered value`() {
        // Some JDBC URLs append properties after a `;` rather than a `?query` — it sails straight
        // through java.net.URI's path component untouched (the leak this string-based form fixes).
        val marker = "marker-${UUID.randomUUID().toString().take(8)}"
        val rendered = jdbcUrlForLogging("jdbc:postgresql://db:5432/app;password=$marker")
        assertEquals("db:5432/app", rendered)
        assertFalse(rendered.contains(marker))
        assertFalse(rendered.contains("password"))
    }

    @Test
    fun `an unescaped slash in the userinfo password never leaks past the authority`() {
        // A password containing a raw '/' can confuse java.net.URI's authority parsing outright;
        // taking the text after the last '@' strips the userinfo without relying on strict URI syntax.
        val rendered = jdbcUrlForLogging("jdbc:postgresql://user:5678/abc@db:5432/app")
        assertEquals("db:5432/app", rendered)
        assertFalse(rendered.contains("5678"))
        assertFalse(rendered.contains("abc"))
    }

    @Test
    fun `a userinfo password containing a question mark or semicolon never leaks`() {
        for (url in listOf(
            "jdbc:postgresql://app:hun?ter2@db/toadie",
            "jdbc:postgresql://app:hun;ter2@db/toadie",
            "jdbc:postgresql://app:hunter2@db/toadie?x=a@b",
        )) {
            val rendered = jdbcUrlForLogging(url)
            assertFalse(rendered.contains("hun"), "'$rendered' leaks a password fragment of $url")
            assertFalse(rendered.contains("ter2"), "'$rendered' leaks a password fragment of $url")
            assertFalse(rendered.contains("app"), "'$rendered' leaks the user of $url")
        }
        // The unambiguous shapes still render the target.
        assertEquals("db/toadie", jdbcUrlForLogging("jdbc:postgresql://app:hunter2@db/toadie"))
    }

    @Test
    fun `a double slash that is not the authority is never read as one`() {
        // pgjdbc's host-less form: the `//` sits inside a parameter value, not after the scheme.
        val rendered = jdbcUrlForLogging("jdbc:postgresql:toadie?password=ab//cd")
        assertEquals("<unparseable jdbc url>", rendered)
        assertFalse(rendered.contains("cd"))
        assertFalse(rendered.contains("ab//"))
    }

    @Test
    fun `anything that is not a plain host and database prints the placeholder`() {
        assertEquals("<unparseable jdbc url>", jdbcUrlForLogging("jdbc:postgresql://user:pass word@/db"))
        assertEquals("<unparseable jdbc url>", jdbcUrlForLogging("jdbc:postgresql://db:5432/a%20b"))
        assertEquals("[::1]:5432/app", jdbcUrlForLogging("jdbc:postgresql://[::1]:5432/app"))
        assertEquals("db", jdbcUrlForLogging("jdbc:postgresql://db"))
    }

    @Test
    fun `userinfo with a port-less host and no database still renders only the host`() {
        assertEquals("db", jdbcUrlForLogging("jdbc:postgresql://u:p@db"))
        assertEquals("<unparseable jdbc url>", jdbcUrlForLogging("jdbc:postgresql:///app"))
    }

    @Test
    fun `startup logs the host and database but never a credential riding the JDBC URL's query string`() =
        testApplication {
            val marker = "marker-${UUID.randomUUID().toString().take(8)}"
            val separator = if (PostgresTestSupport.jdbcUrl.contains("?")) "&" else "?"
            val urlWithMarker = "${PostgresTestSupport.jdbcUrl}${separator}ApplicationName=$marker"
            configureApp("postgres.jdbcUrl" to urlWithMarker)
            // The Flyway module logs on Application.log, whose NAME the test engine chooses
            // ("io.ktor.test" under testApplication, "io.ktor.server.Application" under EngineMain):
            // capture the ROOT logger, which every child's INFO event propagates to.
            val appLog = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME)
            try {
                startApplication()
                val event = appLog.events.firstOrNull { it.formattedMessage.startsWith("Running Flyway migrations") }
                assertNotNull(event, "expected the Flyway startup log line")
                assertFalse(
                    event.formattedMessage.contains(marker),
                    "the query-string marker must never reach the log line",
                )
                assertTrue(
                    event.formattedMessage.contains(jdbcUrlForLogging(PostgresTestSupport.jdbcUrl)),
                    "expected the host/db to still be logged, got '${event.formattedMessage}'",
                )
            } finally {
                appLog.detach()
            }
        }
}
