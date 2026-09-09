package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.EntityPageResponse
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Concurrency coverage for `EntityService.writeTransaction`'s two-table lock protocol
 * (`.claude/docs/persistence.md`, "one global order: blueprints before entities") — the
 * `BlueprintConcurrencyTest` harness retargeted at (a) a relation create racing its target
 * entity's delete, and (b) a blueprint delete racing an entity create under it, so no live
 * instance can validate against a definition mid-change nor attach to a vanishing blueprint.
 */
class EntityConcurrencyTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun simpleBlueprint(id: String) = BlueprintRequest(identifier = id, title = "T", schema = BlueprintSchema())

    private fun relatedBlueprint(id: String, target: String) = BlueprintRequest(
        identifier = id,
        title = "T",
        schema = BlueprintSchema(),
        relations = mapOf("rel" to RelationDefinition(title = "Rel", target = target, required = false, many = false)),
    )

    private fun entityRequest(blueprint: String, identifier: String) =
        EntityRequest(blueprint = blueprint, identifier = identifier, title = "T")

    /**
     * A granted SHARE lock on `entities` lets ordinary reads pass but holds a writer's SHARE
     * ROW EXCLUSIVE — observable without timing assertions (the `RegistryWriteBarrier` idiom
     * from `BlueprintConcurrencyTest`/`TagCategoryConcurrencyTest`, retargeted at `entities`).
     */
    private class EntityWriteBarrier private constructor(
        private val holder: Connection,
        private val observer: Connection,
    ) {
        private var released = false

        suspend fun awaitWriters(count: Int) {
            withTimeout(15_000) { while (waitingWriters() != count) delay(25) }
        }

        private suspend fun waitingWriters(): Int = withContext(Dispatchers.IO) {
            observer.prepareStatement(
                """
                SELECT COUNT(*)
                FROM pg_locks
                WHERE relation = 'entities'::regclass
                  AND mode = 'ShareRowExclusiveLock'
                  AND NOT granted
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }

        suspend fun release() {
            if (released) return
            released = true
            withContext(NonCancellable + Dispatchers.IO) {
                var committed = false
                try {
                    holder.commit()
                    committed = true
                } finally {
                    if (!committed) runCatching { holder.rollback() }
                    try {
                        holder.close()
                    } finally {
                        observer.close()
                    }
                }
            }
        }

        companion object {
            suspend fun acquire(): EntityWriteBarrier = withContext(NonCancellable + Dispatchers.IO) {
                var holder: Connection? = null
                var observer: Connection? = null
                try {
                    holder = DriverManager.getConnection(
                        PostgresTestSupport.jdbcUrl,
                        PostgresTestSupport.user,
                        PostgresTestSupport.password,
                    )
                    observer = DriverManager.getConnection(
                        PostgresTestSupport.jdbcUrl,
                        PostgresTestSupport.user,
                        PostgresTestSupport.password,
                    )
                    holder.autoCommit = false
                    holder.createStatement().use { it.execute("LOCK TABLE entities IN SHARE MODE") }
                    EntityWriteBarrier(holder, observer)
                } catch (failure: Exception) {
                    runCatching { holder?.close() }
                    runCatching { observer?.close() }
                    throw failure
                }
            }
        }
    }

    /** A granted SHARE lock on `blueprints` (the [BlueprintConcurrencyTest] shape, reused as-is). */
    private class BlueprintWriteBarrier private constructor(
        private val holder: Connection,
        private val observer: Connection,
    ) {
        private var released = false

        suspend fun awaitWriters(count: Int) {
            withTimeout(15_000) { while (waitingWriters() != count) delay(25) }
        }

        private suspend fun waitingWriters(): Int = withContext(Dispatchers.IO) {
            observer.prepareStatement(
                """
                SELECT COUNT(*)
                FROM pg_locks
                WHERE relation = 'blueprints'::regclass
                  AND mode = 'ShareRowExclusiveLock'
                  AND NOT granted
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }

        suspend fun release() {
            if (released) return
            released = true
            withContext(NonCancellable + Dispatchers.IO) {
                var committed = false
                try {
                    holder.commit()
                    committed = true
                } finally {
                    if (!committed) runCatching { holder.rollback() }
                    try {
                        holder.close()
                    } finally {
                        observer.close()
                    }
                }
            }
        }

        companion object {
            suspend fun acquire(): BlueprintWriteBarrier = withContext(NonCancellable + Dispatchers.IO) {
                var holder: Connection? = null
                var observer: Connection? = null
                try {
                    holder = DriverManager.getConnection(
                        PostgresTestSupport.jdbcUrl,
                        PostgresTestSupport.user,
                        PostgresTestSupport.password,
                    )
                    observer = DriverManager.getConnection(
                        PostgresTestSupport.jdbcUrl,
                        PostgresTestSupport.user,
                        PostgresTestSupport.password,
                    )
                    holder.autoCommit = false
                    holder.createStatement().use { it.execute("LOCK TABLE blueprints IN SHARE MODE") }
                    BlueprintWriteBarrier(holder, observer)
                } catch (failure: Exception) {
                    runCatching { holder?.close() }
                    runCatching { observer?.close() }
                    throw failure
                }
            }
        }
    }

    private suspend fun raceBehindEntityBarrier(
        first: suspend () -> HttpResponse,
        second: suspend () -> HttpResponse,
    ): List<HttpResponse> = coroutineScope {
        val barrier = EntityWriteBarrier.acquire()
        val firstResponse = async { first() }
        val secondResponse = async { second() }
        try {
            barrier.awaitWriters(2)
        } finally {
            barrier.release()
        }
        withTimeout(15_000) { listOf(firstResponse.await(), secondResponse.await()) }
    }

    @Test
    fun `a relation create racing its target entity's delete has exactly one winner, never a dangling target`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ent-concurrent-create-delete", UserRole.ADMIN)
        val targetBp = unique("bpc-target")
        val referrerBp = unique("bpc-referrer")
        val targetEntId = unique("entc-target")
        val referrerEntId = unique("entc-referrer")
        admin.postJson("/api/v1/blueprints", simpleBlueprint(targetBp))
        admin.postJson("/api/v1/blueprints", relatedBlueprint(referrerBp, targetBp))
        val target = admin.postJson("/api/v1/entities", entityRequest(targetBp, targetEntId)).body<EntityResponse>()
        try {
            val referrerBody = entityRequest(referrerBp, referrerEntId)
                .copy(relations = buildJsonObject { put("rel", targetEntId) })
            val responses = raceBehindEntityBarrier(
                { admin.postJson("/api/v1/entities", referrerBody) },
                { admin.delete("/api/v1/entities/${target.id}") },
            )
            val statuses = responses.map { it.status }
            val createSucceeded = statuses[0] == HttpStatusCode.Created
            val deleteSucceeded = statuses[1] == HttpStatusCode.NoContent
            assertTrue(createSucceeded != deleteSucceeded, "exactly one contender must win: $statuses")

            val remaining = admin.get("/api/v1/entities?blueprint=$referrerBp").body<EntityPageResponse>()
            val referrer = remaining.items.singleOrNull { it.identifier == referrerEntId }
            if (referrer != null) {
                // The referrer only exists when its create won — its relation target must still
                // be a live, active entity (never dangling).
                val liveTargets = admin.get("/api/v1/entities?blueprint=$targetBp").body<EntityPageResponse>().items
                assertTrue(liveTargets.any { it.identifier == targetEntId })
            }
        } finally {
            TestEntities.remove(targetEntId, referrerEntId)
            TestBlueprints.remove(targetBp, referrerBp)
        }
    }

    @Test
    fun `a blueprint delete racing an entity create under it never leaves an orphan`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ent-concurrent-bp-delete", UserRole.ADMIN)
        val bpId = unique("bpc-holder")
        val entId = unique("entc-holder")
        val blueprint = admin.postJson("/api/v1/blueprints", simpleBlueprint(bpId)).body<BlueprintResponse>()
        val barrier = BlueprintWriteBarrier.acquire()
        try {
            coroutineScope {
                val deletion = async { admin.delete("/api/v1/blueprints/${blueprint.id}") }
                val creation = async { admin.postJson("/api/v1/entities", entityRequest(bpId, entId)) }
                try {
                    // The delete's SHARE ROW EXCLUSIVE request is always blocked by the
                    // barrier's SHARE lock; the create's own SHARE request on `blueprints`
                    // (self-compatible with the barrier) may or may not ALSO queue behind it
                    // (PostgreSQL's anti-starvation fairness), so only the delete's wait is
                    // guaranteed observable here — awaiting more would be a flaky race on
                    // scheduling order, not on the invariant this test actually proves.
                    barrier.awaitWriters(1)
                } finally {
                    barrier.release()
                }
                val deleteResponse = withTimeout(15_000) { deletion.await() }
                val createResponse = withTimeout(15_000) { creation.await() }

                // The blueprint delete only succeeds when there are zero active entities at that
                // instant; the create only succeeds against a still-active blueprint. Both must
                // never succeed together (that would strand an entity under a deleted blueprint).
                val bothSucceeded = deleteResponse.status == HttpStatusCode.NoContent && createResponse.status == HttpStatusCode.Created
                assertTrue(!bothSucceeded, "a blueprint delete and an entity create under it must never both succeed")
            }
        } finally {
            barrier.release()
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }
}
