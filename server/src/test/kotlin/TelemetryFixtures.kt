package ch.nokillswit

import ch.nokillswit.infra.db.buildFlyway
import ch.nokillswit.telemetry.ProcessIdentity
import ch.nokillswit.telemetry.SampleRow
import ch.nokillswit.telemetry.TelemetryStore
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteAll
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.util.UUID

/** A store over the shared test database (the telemetry tables are owned by the telemetry tests: the suite boots no sampler). */
internal fun telemetryStore(): TelemetryStore {
    schemaMigrated // a store is only useful over the migrated schema, whichever test class runs first
    return TelemetryStore(sharedDatabaseForTests())
}

/** An identity unique to the call, so two tests (or two samplers of one test) never share a process row unless they mean to. */
internal fun testIdentity(
    instance: String = "test-${UUID.randomUUID()}",
    startedAt: Long = System.currentTimeMillis(),
) = ProcessIdentity(instance, startedAt, buildVersion = "9.9.9", buildCommit = "abc1234", processors = 4, heapMaxBytes = 256L shl 20)

/**
 * The schema, migrated once per JVM: the store tests touch the telemetry tables before (or without) any application booting, and
 * only an application's own `configureFlyway` migrates the shared container - so a telemetry test class run alone on a fresh
 * container would otherwise find no tables. `migrate()` is idempotent and a no-op once an application has run.
 */
private val schemaMigrated: Unit by lazy {
    buildFlyway(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password, connectRetries = 0).migrate()
    Unit
}

/** Empties both telemetry tables: the telemetry tests own them (nothing else writes to them), and the hourly rollup reads them globally. */
internal suspend fun wipeTelemetry(store: TelemetryStore = telemetryStore()) {
    schemaMigrated
    store.transaction {
        TelemetryStore.Samples.deleteAll()
        TelemetryStore.Processes.deleteAll()
    }
}

internal suspend fun TelemetryStore.samples(
    processId: Int,
    resolution: Int? = null,
    metric: String? = null,
): List<SampleRow> = transaction {
    val s = TelemetryStore.Samples
    s.selectAll().where {
        var predicate = s.processId eq processId
        if (resolution != null) predicate = predicate and (s.resolution eq resolution)
        if (metric != null) predicate = predicate and (s.metric eq metric)
        predicate
    }.toList().map {
        SampleRow(it[s.resolution], it[s.ts], it[s.processId], it[s.metric], it[s.label], it[s.n], it[s.avg], it[s.max])
    }.sortedWith(compareBy({ it.resolution }, { it.metric }, { it.ts }, { it.label }))
}

/** The id of the row [identity] registered, or null before the first tick / after a delete. */
internal suspend fun TelemetryStore.processId(identity: ProcessIdentity): Int? = transaction {
    val p = TelemetryStore.Processes
    p.select(p.id).where { (p.instance eq identity.instance) and (p.startedAt eq identity.startedAt) }.toList().singleOrNull()
        ?.get(p.id)?.value
}

internal class ProcessView(val instance: String, val startedAt: Long, val limit: Long?, val lastSeen: Long)

/** The registry columns of one process row. */
internal suspend fun TelemetryStore.processView(id: Int): ProcessView = transaction {
    val p = TelemetryStore.Processes
    val row = p.selectAll().where { p.id eq id }.toList().single()
    ProcessView(row[p.instance], row[p.startedAt], row[p.containerLimitBytes], row[p.lastSeen])
}
