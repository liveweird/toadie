package ch.nokillswit.telemetry

import ch.nokillswit.auth.TELEMETRY_RATE_LIMIT
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.entities.EntityServiceKey
import ch.nokillswit.infra.concurrency.READ_BUDGET_BYTES
import ch.nokillswit.infra.concurrency.ReadLedgerKey
import ch.nokillswit.infra.db.DatabaseKey
import ch.nokillswit.infra.fetch.UrlFetcher
import ch.nokillswit.infra.fetch.UrlFetcherKey
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.integration.IntegrationPressureKey
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/telemetry/current")
class TelemetryCurrentRoute

@Serializable
@Resource("/api/v1/telemetry/history")
class TelemetryHistoryRoute

/**
 * The telemetry read API (`.claude/docs/observability.md` "Telemetry"): `GET /api/v1/telemetry/current` and
 * `GET /api/v1/telemetry/history?group=&range=`, ADMIN only (operational data: process sizes, container limits, pressure and
 * workspace figures), read-only, no audit event of their own (a denial is the generic `authz.denied`). `requireAdmin` runs before
 * the query is read, so a non-admin learns nothing about the parameters (403 outranks 400). The samples are written by the
 * sampler (`configureTelemetry`); this module only reads them, whether or not this process samples (`telemetry.enabled`).
 * Registered with the feature routes in `application.yaml`.
 */
fun Application.configureTelemetryRoutes() {
    val config = readTelemetryConfig(environment.config)
    val store = TelemetryStore(attributes[DatabaseKey])
    val reads = TelemetryReads(store, config.sampleIntervalSeconds)
    val poolMaxSize = environment.config.property("postgres.pool.maxSize").getString().toInt()
    // UrlFetcherKey is a test seam only (as for the sampler): production builds a fresh fetcher over the one shared pool.
    val fetcher by lazy { attributes.getOrNull(UrlFetcherKey) ?: UrlFetcher() }
    // Resolved per request: the integration API is configured AFTER the feature routes (and may be absent), and the other seams
    // are only read, never cached, so the answer is always the live bound.
    val limits: () -> TelemetryLimits = {
        val pressure = attributes.getOrNull(EntityServiceKey)?.pressure()
        val integration = attributes.getOrNull(IntegrationPressureKey)?.invoke()
        val fetch = fetcher.poolStats()
        TelemetryLimits(
            readLedgerBytes = attributes.getOrNull(ReadLedgerKey)?.snapshot()?.capacity ?: READ_BUDGET_BYTES,
            entityQueryPermits = pressure?.queryPermits ?: 0,
            jqWorkers = pressure?.jq?.workers ?: 0,
            jqQueueCapacity = pressure?.jq?.queueCapacity ?: 0,
            fetchWorkers = fetch.workers,
            fetchQueueCapacity = fetch.queueCapacity,
            integrationEnabled = integration != null,
            integrationPermits = integration?.permits,
            integrationRetainedBytes = integration?.retainedCapacity,
            poolMaxSize = poolMaxSize,
        )
    }
    val current = TelemetryCurrentReport(reads, limits, config.sampleIntervalSeconds)
    val history = TelemetryHistoryReport(reads, config.sampleIntervalSeconds)
    // Read per request: a test replaces the clock on `application {}` after this module has run (TelemetryOverridesKey).
    val clock: () -> Long = { attributes.getOrNull(TelemetryOverridesKey)?.clock?.invoke() ?: System.currentTimeMillis() }

    routing {
        authenticate {
            // The per-user telemetry bucket (auth/AuthRoutes.kt), inside `authenticate` so its key is the verified caller.
            rateLimit(RateLimitName(TELEMETRY_RATE_LIMIT)) {
                get<TelemetryCurrentRoute> {
                    requireAdmin(call.caller())
                    call.respond(HttpStatusCode.OK, current.build(clock()))
                }
                get<TelemetryHistoryRoute> {
                    requireAdmin(call.caller())
                    val group = call.request.queryParameters.optionalString("group")?.let(HistoryGroup::parse)
                        ?: throw BadRequestException("group is required: one of ${HistoryGroup.entries.joinToString("|") { it.param }}")
                    val range = call.request.queryParameters.optionalString("range")?.let(HistoryRange::parse)
                        ?: throw BadRequestException("range is required: one of ${HistoryRange.entries.joinToString("|") { it.param }}")
                    call.respond(HttpStatusCode.OK, history.build(group, range, clock()))
                }
            }
        }
    }
}
