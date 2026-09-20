package ch.nokillswit.infra.db

import ch.nokillswit.integration.IntegrationClientService
import ch.nokillswit.integration.IntegrationClientServiceKey

import ch.nokillswit.annotations.AnnotationKeyService
import ch.nokillswit.annotations.AnnotationKeyServiceKey
import ch.nokillswit.blueprints.BlueprintService
import ch.nokillswit.blueprints.BlueprintServiceKey
import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.auth.AuthSessionService
import ch.nokillswit.auth.AuthSessionServiceKey
import ch.nokillswit.auth.PasswordResetService
import ch.nokillswit.auth.PasswordResetServiceKey
import ch.nokillswit.catalog.CatalogFileEventService
import ch.nokillswit.catalog.CatalogFileEventServiceKey
import ch.nokillswit.catalog.CatalogFileService
import ch.nokillswit.catalog.CatalogFileServiceKey
import ch.nokillswit.dictionaries.DictionaryService
import ch.nokillswit.dictionaries.DictionaryServiceKey
import ch.nokillswit.entities.EntityService
import ch.nokillswit.entities.EntityServiceKey
import ch.nokillswit.entities.JqEvaluator
import ch.nokillswit.entities.MAX_JQ_DEADLINE_MILLIS
import ch.nokillswit.entityquery.MAX_ENTITY_QUERY_DEADLINE_MILLIS
import ch.nokillswit.entityquery.SavedEntityQueryService
import ch.nokillswit.entityquery.SavedEntityQueryServiceKey
import ch.nokillswit.labels.LabelService
import ch.nokillswit.labels.LabelServiceKey
import ch.nokillswit.lenses.LensService
import ch.nokillswit.lenses.LensServiceKey
import ch.nokillswit.tags.TagCategoryService
import ch.nokillswit.tags.TagCategoryServiceKey
import ch.nokillswit.types.EntityTypesService
import ch.nokillswit.types.EntityTypesServiceKey
import ch.nokillswit.users.EntityGraphLayoutServiceKey
import ch.nokillswit.users.GraphLayoutService
import ch.nokillswit.users.GraphLayoutServiceKey
import ch.nokillswit.users.UserService
import ch.nokillswit.users.UserServiceKey
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import java.time.Duration

/**
 * The pooled [R2dbcDatabase] itself, published for introspection (today: `ConnectionPoolTest`,
 * which opens transactions directly against the SAME pool the app's services use, to observe
 * `pg_stat_activity` bounded by `postgres.pool.maxSize`).
 */
val DatabaseKey = AttributeKey<R2dbcDatabase>("Database")

/**
 * The bounded pool sizing read from `postgres.pool.*` (`.claude/docs/persistence.md`
 * "Connection pool") — boot-validated the `security.passwordReset.tokenTtlSeconds` way (a
 * range failure throws [IllegalArgumentException] before any connection is attempted).
 */
private data class PoolBounds(
    val maxSize: Int,
    val initialSize: Int,
    val maxAcquireTimeSeconds: Long,
    val maxIdleTimeSeconds: Long,
    val applicationName: String,
)

private fun readPoolBounds(config: ApplicationConfig): PoolBounds {
    val maxSize = config.property("postgres.pool.maxSize").getString().toInt()
        .also { require(it in 1..1000) { "postgres.pool.maxSize must be between 1 and 1000" } }
    val initialSize = config.property("postgres.pool.initialSize").getString().toInt()
        .also { require(it in 0..maxSize) { "postgres.pool.initialSize must be between 0 and postgres.pool.maxSize ($maxSize)" } }
    val maxAcquireTimeSeconds = config.property("postgres.pool.maxAcquireTimeSeconds").getString().toLong()
        .also { require(it in 1..600) { "postgres.pool.maxAcquireTimeSeconds must be between 1 and 600" } }
    val maxIdleTimeSeconds = config.property("postgres.pool.maxIdleTimeSeconds").getString().toLong()
        .also { require(it in 1..86400) { "postgres.pool.maxIdleTimeSeconds must be between 1 and 86400" } }
    // application_name = toadie on every pooled connection by default (deliberate — ops can
    // count Toadie's own connections in pg_stat_activity, and ConnectionPoolTest relies on
    // it); overridable per test application instance so overlapping test apps sharing the
    // Testcontainer never share one count.
    val applicationName = config.propertyOrNull("postgres.pool.applicationName")?.getString() ?: "toadie"
    return PoolBounds(maxSize, initialSize, maxAcquireTimeSeconds, maxIdleTimeSeconds, applicationName)
}

/**
 * Connects Exposed to a bounded R2DBC [ConnectionPool] instead of a raw per-transaction
 * connection factory (`.claude/docs/persistence.md` "Connection pool"): a plain
 * `r2dbc:postgresql://` connect (the pre-2.11 shape) opens one PostgreSQL backend per
 * `suspendTransaction` with nothing capping how many run at once — measured against the
 * compose stack, 120 parallel `GET /api/v1/entities/graph` requests produced 81 concurrent
 * backends against PostgreSQL's default `max_connections = 100`.
 *
 * [ConnectionFactoryOptions.parse] plus the user/password/application-name mutations produce
 * [options], from which [ConnectionFactories.get] resolves the PLAIN (unpooled) PostgreSQL
 * factory that [ConnectionPool] then wraps. Exposed's
 * `R2dbcDatabase.connect(connectionFactory, databaseConfig, ...)` overload derives its SQL
 * dialect and reported URL from `databaseConfig.connectionFactoryOptions` alone — verified
 * against `exposed-r2dbc-1.5.0`'s `R2dbcDatabase.Companion.doConnect` bytecode: it only calls
 * `ConnectionFactories.get(options)` itself when the `connectionFactory` argument is null, and
 * otherwise reads `getDialectName`/`getUrlString` straight off `options` — so [options] (still
 * carrying `driver=postgresql`) is threaded into `databaseConfig` unchanged even though actual
 * traffic goes through the pool. The pool is disposed on [ApplicationStopped] so the hundreds
 * of `testApplication`s the suite boots each release their connections.
 */
private fun Application.connectPooled(): R2dbcDatabase {
    val config = environment.config
    val bounds = readPoolBounds(config)
    val options = ConnectionFactoryOptions.parse(config.property("postgres.r2dbcUrl").getString())
        .mutate()
        .option(ConnectionFactoryOptions.USER, config.property("postgres.user").getString())
        .option(ConnectionFactoryOptions.PASSWORD, config.property("postgres.password").getString())
        .option(PostgresqlConnectionFactoryProvider.APPLICATION_NAME, bounds.applicationName)
        .build()
    val rawFactory = ConnectionFactories.get(options)
    val pool = ConnectionPool(
        ConnectionPoolConfiguration.builder(rawFactory)
            .maxSize(bounds.maxSize)
            .initialSize(bounds.initialSize)
            .maxAcquireTime(Duration.ofSeconds(bounds.maxAcquireTimeSeconds))
            .maxIdleTime(Duration.ofSeconds(bounds.maxIdleTimeSeconds))
            .build(),
    )
    monitor.subscribe(ApplicationStopped) { pool.dispose() }
    val databaseConfig = R2dbcDatabaseConfig.Builder().apply {
        connectionFactoryOptions = options
        // ONE attempt per suspendTransaction: Exposed's default of three retries any R2dbcException,
        // and the pool's acquire timeout is one — retrying a saturated pool three times would turn
        // the 10-second acquire budget into 30 s of queueing per request exactly when the pool is
        // already full. Toadie's writes serialize on table locks (READ COMMITTED), never on
        // serialization failures, so nothing here relied on the retry.
        defaultMaxAttempts = 1
    }
    return R2dbcDatabase.connect(connectionFactory = pool, databaseConfig = databaseConfig)
}

/**
 * The DI composition root: connects the one R2DBC database (over the bounded pool built by
 * [connectPooled]) and publishes every service into [Application.attributes]. Feature
 * modules read their services back via the AttributeKey — application.yaml runs this module
 * before any route module, so the keys are always present.
 */
suspend fun Application.configureDatabase() {
    val database = connectPooled()
    attributes.put(DatabaseKey, database)
    attributes.put(UserServiceKey, UserService(database))
    attributes.put(IntegrationClientServiceKey, IntegrationClientService(database))
    attributes.put(GraphLayoutServiceKey, GraphLayoutService(database, GraphLayoutService.GraphLayouts))
    attributes.put(EntityGraphLayoutServiceKey, GraphLayoutService(database, GraphLayoutService.EntityGraphLayouts))
    val catalogFileEventService = CatalogFileEventService(database)
    attributes.put(CatalogFileServiceKey, CatalogFileService(database, catalogFileEventService))
    attributes.put(CatalogFileEventServiceKey, catalogFileEventService)
    attributes.put(DictionaryServiceKey, DictionaryService(database))
    attributes.put(LabelServiceKey, LabelService(database))
    attributes.put(LensServiceKey, LensService(database))
    attributes.put(SavedEntityQueryServiceKey, SavedEntityQueryService(database))
    attributes.put(TagCategoryServiceKey, TagCategoryService(database))
    attributes.put(EntityTypesServiceKey, EntityTypesService(database))
    attributes.put(AnnotationKeyServiceKey, AnnotationKeyService(database))
    attributes.put(BlueprintServiceKey, BlueprintService(database))
    val jqDeadlineMillis = environment.config.property("computed.jq.deadlineMillis").getString().toLong()
        .also { require(it in 1..MAX_JQ_DEADLINE_MILLIS) { "computed.jq.deadlineMillis must be between 1 and $MAX_JQ_DEADLINE_MILLIS" } }
    val entityQueryDeadlineMillis = environment.config.property("entityQuery.deadlineMillis").getString().toLong()
        .also {
            require(it in 1..MAX_ENTITY_QUERY_DEADLINE_MILLIS) {
                "entityQuery.deadlineMillis must be between 1 and $MAX_ENTITY_QUERY_DEADLINE_MILLIS"
            }
        }
    attributes.put(
        EntityServiceKey,
        EntityService(database, JqEvaluator(deadlineMillis = jqDeadlineMillis), queryDeadlineMillis = entityQueryDeadlineMillis),
    )
    attributes.put(TokenBlocklistServiceKey, TokenBlocklistService(database))
    attributes.put(AuthSessionServiceKey, AuthSessionService(database))
    attributes.put(PasswordResetServiceKey, PasswordResetService(
        database,
        ttlMillis = environment.config.property("security.passwordReset.tokenTtlSeconds").getString().toLong()
            .also { require(it in 1..3600) { "Password reset TTL must be between 1 and 3600 seconds" } } * 1000,
    ))
}
