package ch.nokillswit

import org.testcontainers.postgresql.PostgreSQLContainer

object PostgresTestSupport {
    /** The one PostgreSQL image every test container uses (the shared one and `DatabaseHangTest`'s dedicated one). */
    const val IMAGE = "postgres:18-alpine"

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(IMAGE).apply {
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
