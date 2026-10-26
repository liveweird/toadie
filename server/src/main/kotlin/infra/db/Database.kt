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
import ch.nokillswit.conversion.ConversionExportService
import ch.nokillswit.conversion.ConversionExportServiceKey
import ch.nokillswit.conversion.ConversionMappingService
import ch.nokillswit.conversion.ConversionMappingServiceKey
import ch.nokillswit.dictionaries.DictionaryService
import ch.nokillswit.dictionaries.DictionaryServiceKey
import ch.nokillswit.entities.EntityService
import ch.nokillswit.entities.EntityServiceKey
import ch.nokillswit.entities.JqEvaluator
import ch.nokillswit.entities.MAX_JQ_DEADLINE_MILLIS
import ch.nokillswit.entityquery.MAX_ENTITY_QUERY_DEADLINE_MILLIS
import ch.nokillswit.entityquery.SavedEntityQueryService
import ch.nokillswit.entityquery.SavedEntityQueryServiceKey
import ch.nokillswit.infra.concurrency.ReadLedger
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
import io.r2dbc.spi.Option
import io.r2dbc.spi.ValidationDepth
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
 * The [ConnectionPool] beneath [DatabaseKey], published for introspection (today: the hung-database regressions in
 * `ConnectionPoolTest`, which read `pool.metrics` to prove a frozen server's slot is back while it is still frozen).
 */
val ConnectionPoolKey = AttributeKey<ConnectionPool>("ConnectionPool")

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
    val maxLifeTimeSeconds: Long,
    val idleInTransactionTimeoutSeconds: Long,
    val maxValidationTimeSeconds: Long,
    val maxCreateConnectionTimeSeconds: Long,
    val connectTimeoutSeconds: Long,
    val statementTimeoutSeconds: Long,
    val responseTimeoutSeconds: Long,
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
    val maxLifeTimeSeconds = config.property("postgres.pool.maxLifeTimeSeconds").getString().toLong()
        .also { require(it in 1..86400) { "postgres.pool.maxLifeTimeSeconds must be between 1 and 86400" } }
    // 0 = off (PostgreSQL's own meaning); see DEFAULT_IDLE_IN_TRANSACTION_TIMEOUT for the default and why.
    val idleInTransactionTimeoutSeconds = config.property("postgres.pool.idleInTransactionTimeoutSeconds").getString().toLong()
        .also { require(it in 0..86400) { "postgres.pool.idleInTransactionTimeoutSeconds must be between 0 and 86400" } }
    // The hung-database bounds (application.yaml explains each; persistence.md "A hung database is bounded").
    val maxValidationTimeSeconds = config.property("postgres.pool.maxValidationTimeSeconds").getString().toLong()
        .also { require(it in 1..60) { "postgres.pool.maxValidationTimeSeconds must be between 1 and 60" } }
    val maxCreateConnectionTimeSeconds = config.property("postgres.pool.maxCreateConnectionTimeSeconds").getString().toLong()
        .also { require(it in 1..600) { "postgres.pool.maxCreateConnectionTimeSeconds must be between 1 and 600" } }
    val connectTimeoutSeconds = config.property("postgres.connectTimeoutSeconds").getString().toLong()
        .also { require(it in 1..600) { "postgres.connectTimeoutSeconds must be between 1 and 600" } }
    val statementTimeoutSeconds = config.property("postgres.statementTimeoutSeconds").getString().toLong()
        .also { require(it in 0..3600) { "postgres.statementTimeoutSeconds must be between 0 and 3600" } }
    // The client-side response deadline (ResponseDeadline.kt); 0 = off. It must out-wait the server's own statement_timeout, or a
    // healthy long statement would be discarded as a hung one before the server cancels it; and with statement_timeout off a LIVE
    // lock wait longer than the deadline would be discarded while its backend keeps waiting, the freed slot opening another
    // backend (a lock convoy past maxSize), so a non-zero deadline requires a non-zero statement timeout.
    val responseTimeoutSeconds = config.property("postgres.responseTimeoutSeconds").getString().toLong()
        .also { require(it in 0..3600) { "postgres.responseTimeoutSeconds must be between 0 and 3600" } }
        .also {
            require(it == 0L || (statementTimeoutSeconds > 0L && it > statementTimeoutSeconds)) {
                "postgres.responseTimeoutSeconds ($it) must be 0 (off) or greater than a non-zero " +
                    "postgres.statementTimeoutSeconds ($statementTimeoutSeconds)"
            }
        }
    // application_name = toadie on every pooled connection by default (deliberate — ops can
    // count Toadie's own connections in pg_stat_activity, and ConnectionPoolTest relies on
    // it); overridable per test application instance so overlapping test apps sharing the
    // Testcontainer never share one count.
    val applicationName = config.propertyOrNull("postgres.pool.applicationName")?.getString() ?: "toadie"
    return PoolBounds(
        maxSize, initialSize, maxAcquireTimeSeconds, maxIdleTimeSeconds, maxLifeTimeSeconds, idleInTransactionTimeoutSeconds,
        maxValidationTimeSeconds, maxCreateConnectionTimeSeconds, connectTimeoutSeconds, statementTimeoutSeconds,
        responseTimeoutSeconds, applicationName,
    )
}

/**
 * PostgreSQL's `idle_in_transaction_session_timeout` for every pooled connection
 * (`postgres.pool.idleInTransactionTimeoutSeconds`, sent as a startup parameter, so it is the session default — it also
 * overrides an `ALTER ROLE/DATABASE … SET` of the same setting — and no `SET` ever touches it): the server ends a session
 * that sits idle INSIDE a transaction for longer, rolling the transaction back and releasing its locks. It is the backstop
 * for a session whose client stopped talking to it with a transaction open (a frozen worker, a coroutine stuck
 * mid-transaction). An idle gap inside a transaction can never be longer than the
 * transaction, and Toadie's longest one is ~0.5 s (per-document import holds are ~100 ms; no transaction spans an outbound
 * call or a mail send; jq and entity-query evaluation run outside transactions), so 5 minutes is a very wide margin. It ends
 * only an IDLE session — a statement stuck `active` is `statement_timeout`'s job (and, against a frozen server, the response deadline's).
 * `0` switches it off (PostgreSQL's own meaning).
 */
internal val DEFAULT_IDLE_IN_TRANSACTION_TIMEOUT: Duration = Duration.ofMinutes(5)

private const val IDLE_IN_TRANSACTION_TIMEOUT = "idle_in_transaction_session_timeout"

/**
 * [options] plus the `idle_in_transaction_session_timeout` startup parameter, unless the URL already set one (an operator's
 * `options=` wins). The driver takes `options` as a map or as its `key=value;key=value` string form.
 */
internal fun withIdleInTransactionTimeout(options: ConnectionFactoryOptions, timeout: Duration): ConnectionFactoryOptions {
    val millis = timeout.toMillis().toString()
    val existing = if (options.hasOption(STARTUP_OPTIONS)) options.getValue(STARTUP_OPTIONS) else null
    val alreadySet = when (existing) {
        null -> false
        is Map<*, *> -> existing.containsKey(IDLE_IN_TRANSACTION_TIMEOUT)
        else -> IDLE_IN_TRANSACTION_TIMEOUT in existing.toString()
    }
    if (alreadySet) return options
    val merged: Any = when (existing) {
        null -> mapOf(IDLE_IN_TRANSACTION_TIMEOUT to millis)
        is Map<*, *> -> existing + (IDLE_IN_TRANSACTION_TIMEOUT to millis)
        else -> "$existing;$IDLE_IN_TRANSACTION_TIMEOUT=$millis"
    }
    return options.mutate().option(STARTUP_OPTIONS, merged).build()
}

// The driver's `options` option is typed Map<String, String>, but a URL's `?options=` arrives as its string form.
@Suppress("UNCHECKED_CAST") // reading and writing the one option whose declared type the URL form does not honour
private val STARTUP_OPTIONS = PostgresqlConnectionFactoryProvider.OPTIONS as Option<Any>

/**
 * Connects Exposed to a bounded R2DBC [ConnectionPool] instead of a raw per-transaction
 * connection factory (`.claude/docs/persistence.md` "Connection pool"): a plain
 * `r2dbc:postgresql://` connect (the pre-2.11 shape) opens one PostgreSQL backend per
 * `suspendTransaction` with nothing capping how many run at once — measured against the
 * compose stack, 120 parallel `GET /api/v1/entities/graph` requests produced 81 concurrent
 * backends against PostgreSQL's default `max_connections = 100`.
 *
 * [ConnectionFactoryOptions.parse] plus the user/password/application-name mutations produce
 * [options], from which [ConnectionFactories.get] resolves the plain (unpooled) PostgreSQL
 * factory, which [StatementTimeoutCachingConnectionFactory] and then [ResponseDeadlineConnectionFactory] decorate and
 * [ConnectionPool] then wraps. Exposed's
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
        // The driver bounds it CAN offer: the TCP connect and TCP keepalive. Keepalive uses the OS
        // defaults (about 2 h before the first probe), so it is a slow fuse; the acquire-time REMOTE
        // validation below is what actually detects a dead connection. The driver has no socket READ
        // timeout, so a hung database is bounded at acquire time by the pool below and, for an exchange already on the wire,
        // by ResponseDeadline.kt. The statement timeout is NOT a driver option here — see defaultQueryTimeout in the Exposed
        // config below.
        .option(ConnectionFactoryOptions.CONNECT_TIMEOUT, Duration.ofSeconds(bounds.connectTimeoutSeconds))
        .option(PostgresqlConnectionFactoryProvider.TCP_KEEPALIVE, true)
        .build()
    // BELOW the pool: the remembered statement_timeout belongs to one physical connection (StatementTimeoutCache.kt), and the
    // response deadline poisons one physical connection (ResponseDeadline.kt), outermost so it also bounds the cache's own SET.
    val unpooledFactory = ResponseDeadlineConnectionFactory(
        StatementTimeoutCachingConnectionFactory(
            ConnectionFactories.get(
                withIdleInTransactionTimeout(options, Duration.ofSeconds(bounds.idleInTransactionTimeoutSeconds)),
            ),
        ),
        Duration.ofSeconds(bounds.responseTimeoutSeconds),
    )
    val pool = ConnectionPool(
        ConnectionPoolConfiguration.builder(unpooledFactory)
            .maxSize(bounds.maxSize)
            .initialSize(bounds.initialSize)
            .maxAcquireTime(Duration.ofSeconds(bounds.maxAcquireTimeSeconds))
            .maxIdleTime(Duration.ofSeconds(bounds.maxIdleTimeSeconds))
            // No connection outlives this, so a long-lived backend cannot linger past a server-side
            // restart/failover/idle-killer even when it is never validated against a frozen peer.
            .maxLifeTime(Duration.ofSeconds(bounds.maxLifeTimeSeconds))
            // Every acquire validates with a bounded round trip: a frozen or server-killed connection fails
            // validation and is discarded (r2dbc-pool invalidates it; reactor-pool returns the slot before
            // the driver's close(), which is local, so the slot is free while the frozen socket lingers
            // until the thaw), and the pool creates a new one, itself bounded by maxCreateConnectionTime.
            // r2dbc-pool retries a failed timed acquire once (acquireRetry = 1, its default), so a
            // request waits at most about 2 × maxAcquireTime for a hung database instead of forever.
            // Each acquire gets two validation attempts: ONE dropped connection is replaced
            // transparently, but after a PostgreSQL restart (every idle connection dead at once) a
            // request whose two picks are both dead still fails fast until the pool has refreshed.
            // Costs one round trip (SELECT 1) per acquire. A statement ALREADY in flight when the network
            // black-holes is bounded by ResponseDeadline.kt, which fails the exchange after responseTimeoutSeconds and
            // makes validate(LOCAL) answer false, so the release below invalidates the connection and returns the slot.
            .validationDepth(ValidationDepth.REMOTE)
            .maxValidationTime(Duration.ofSeconds(bounds.maxValidationTimeSeconds))
            .maxCreateConnectionTime(Duration.ofSeconds(bounds.maxCreateConnectionTimeSeconds))
            // r2dbc-pool 1.0.2 evicts on acquire/release regardless; its background sweep defaults to
            // an interval of maxIdleTime (10 min here) — pinned to at most a minute so an idle,
            // expired connection is released promptly instead of waiting for the next acquire.
            .backgroundEvictionInterval(Duration.ofSeconds(minOf(60L, bounds.maxIdleTimeSeconds, bounds.maxLifeTimeSeconds)))
            .build(),
    )
    monitor.subscribe(ApplicationStopped) { pool.dispose() }
    attributes.put(ConnectionPoolKey, pool)
    val databaseConfig = R2dbcDatabaseConfig.Builder().apply {
        connectionFactoryOptions = options
        // ONE attempt per suspendTransaction: Exposed's default of three retries any R2dbcException,
        // and the pool's acquire timeout is one — retrying a saturated pool three times would turn
        // the 10-second acquire budget into 30 s of queueing per request exactly when the pool is
        // already full. Toadie's writes serialize on table locks (READ COMMITTED), never on
        // serialization failures, so nothing here relied on the retry.
        defaultMaxAttempts = 1
        // PostgreSQL's server-side statement_timeout: a statement a LIVE but stuck server runs
        // longer (a lock wait, a runaway query) is cancelled (SQLSTATE 57014; the transaction rolls
        // back, the connection stays valid). It MUST be set here: Exposed's statement executor
        // re-SETs statement_timeout from the transaction's queryTimeout before every statement
        // (default 0 = no limit), so a driver startup option or a post-allocate SET would be silently
        // overwritten with 0 — nothing else may SET statement_timeout. Seconds, as Exposed takes them. The driver
        // sends the SET only when the connection's value differs: StatementTimeoutCachingConnectionFactory above.
        defaultQueryTimeout = bounds.statementTimeoutSeconds.toInt()
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
    // ONE process-wide read ledger for every whole-workspace content read (catalog + entities).
    val readLedger = ReadLedger()
    val catalogFileEventService = CatalogFileEventService(database)
    val catalogFileService = CatalogFileService(database, catalogFileEventService, readLedger)
    attributes.put(CatalogFileServiceKey, catalogFileService)
    attributes.put(CatalogFileEventServiceKey, catalogFileEventService)
    attributes.put(DictionaryServiceKey, DictionaryService(database))
    attributes.put(LabelServiceKey, LabelService(database))
    attributes.put(LensServiceKey, LensService(database))
    attributes.put(SavedEntityQueryServiceKey, SavedEntityQueryService(database))
    attributes.put(TagCategoryServiceKey, TagCategoryService(database))
    attributes.put(EntityTypesServiceKey, EntityTypesService(database))
    attributes.put(AnnotationKeyServiceKey, AnnotationKeyService(database))
    val blueprintService = BlueprintService(database)
    attributes.put(BlueprintServiceKey, blueprintService)
    val conversionMappingService = ConversionMappingService(database, blueprintService)
    attributes.put(ConversionMappingServiceKey, conversionMappingService)
    val jqDeadlineMillis = environment.config.property("computed.jq.deadlineMillis").getString().toLong()
        .also { require(it in 1..MAX_JQ_DEADLINE_MILLIS) { "computed.jq.deadlineMillis must be between 1 and $MAX_JQ_DEADLINE_MILLIS" } }
    val entityQueryDeadlineMillis = environment.config.property("entityQuery.deadlineMillis").getString().toLong()
        .also {
            require(it in 1..MAX_ENTITY_QUERY_DEADLINE_MILLIS) {
                "entityQuery.deadlineMillis must be between 1 and $MAX_ENTITY_QUERY_DEADLINE_MILLIS"
            }
        }
    val entityService = EntityService(
        database,
        JqEvaluator(deadlineMillis = jqDeadlineMillis),
        queryDeadlineMillis = entityQueryDeadlineMillis,
        readLedger = readLedger,
    )
    attributes.put(EntityServiceKey, entityService)
    attributes.put(
        ConversionExportServiceKey,
        ConversionExportService(conversionMappingService, entityService, catalogFileService, readLedger),
    )
    attributes.put(TokenBlocklistServiceKey, TokenBlocklistService(database))
    attributes.put(AuthSessionServiceKey, AuthSessionService(database))
    attributes.put(PasswordResetServiceKey, PasswordResetService(
        database,
        ttlMillis = environment.config.property("security.passwordReset.tokenTtlSeconds").getString().toLong()
            .also { require(it in 1..3600) { "Password reset TTL must be between 1 and 3600 seconds" } } * 1000,
    ))
}
