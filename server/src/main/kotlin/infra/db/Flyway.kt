package ch.nokillswit.infra.db

import io.ktor.server.application.*
import org.flywaydb.core.Flyway

private const val JDBC_PLACEHOLDER = "<unparseable jdbc url>"

/** `jdbc:<subprotocol>:[<sub-subprotocol>:]//` — the ONLY place a `//` authority marker counts. */
private val JDBC_AUTHORITY_PREFIX = Regex("^jdbc:[a-z0-9]+:(?:[a-z0-9]+:)?//", RegexOption.IGNORE_CASE)

/** A hostname, IPv4 address or bracketed IPv6 literal, with an optional numeric port. */
private val HOST_AND_PORT = Regex("""^(?:[A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_])?|\[[0-9A-Fa-f:.]+])(?::\d{1,5})?$""")

/** A database name: nothing that could be a credential fragment or parameter syntax. */
private val DATABASE_NAME = Regex("^[A-Za-z0-9_.$-]+$")

/**
 * Reduces an operator-supplied JDBC URL to `host[:port]/database` for logging (shaped after
 * Lettuce's `jdbcTarget` — `.claude/docs/observability.md`, "never log secrets"). Deliberately
 * string-based rather than `java.net.URI`: a `;`-delimited parameter block (`...app;password=x`)
 * sails through `URI`'s path component, and a password with an unescaped `@` or `/` confuses its
 * authority parsing. Fail-closed rules, in order:
 *  1. A `//` authority counts only straight after `jdbc:<subprotocol>:` — a host-less form such
 *     as `jdbc:postgresql:app?password=ab//cd` must not have its parameters read as an authority.
 *  2. Userinfo ends at the LAST `@`, and is stripped BEFORE the authority is cut at the first
 *     `/`, `?` or `;` (a password may itself contain them). If a `?` or `;` precedes that last
 *     `@` the text is ambiguous — a password containing them, or a parameter value containing an
 *     `@` — and the placeholder is printed rather than guess which side is the credential.
 *  3. What remains must be a strict host[:port] and (if present) a plain database name; anything
 *     else prints the fixed placeholder, so nothing of an unparseable value is ever echoed.
 */
internal fun jdbcUrlForLogging(url: String): String {
    val prefix = JDBC_AUTHORITY_PREFIX.find(url) ?: return JDBC_PLACEHOLDER
    val rest = url.substring(prefix.range.last + 1)
    val lastAt = rest.lastIndexOf('@')
    if (lastAt >= 0 && rest.substring(0, lastAt).any { it == '?' || it == ';' }) return JDBC_PLACEHOLDER
    val afterUserinfo = rest.substring(lastAt + 1)
    val authorityEnd = afterUserinfo.indexOfFirst { it == '/' || it == '?' || it == ';' }
        .let { if (it < 0) afterUserinfo.length else it }
    val hostAndPort = afterUserinfo.substring(0, authorityEnd)
    if (!HOST_AND_PORT.matches(hostAndPort)) return JDBC_PLACEHOLDER
    val tail = afterUserinfo.substring(authorityEnd)
    if (!tail.startsWith("/")) return hostAndPort
    val database = tail.substring(1).substringBefore('?').substringBefore(';')
    return when {
        database.isEmpty() -> hostAndPort
        DATABASE_NAME.matches(database) -> "$hostAndPort/$database"
        else -> JDBC_PLACEHOLDER
    }
}

fun Application.configureFlyway() {
    val url = environment.config.property("postgres.jdbcUrl").getString()
    val user = environment.config.property("postgres.user").getString()
    val password = environment.config.property("postgres.password").getString()

    log.info("Running Flyway migrations against ${jdbcUrlForLogging(url)}")
    val result = Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    log.info("Flyway applied ${result.migrationsExecuted} migration(s); schema at version ${result.targetSchemaVersion}")
}
