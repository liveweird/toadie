package ch.nokillswit

import org.testcontainers.postgresql.PostgreSQLContainer

object PostgresTestSupport {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:18-alpine").apply {
            withDatabaseName("toadie_test")
            withUsername("toadie")
            withPassword("toadie")
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }

    val jdbcUrl: String get() = container.jdbcUrl
    val user: String get() = container.username
    val password: String get() = container.password
    val r2dbcUrl: String
        get() = "r2dbc:postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}"
}
