package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintList
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Concurrency coverage for the service-enforced target-existence/rename-cascade/delete-409
 * invariants (`BlueprintService.writeTransaction`'s `SHARE ROW EXCLUSIVE` lock on
 * `blueprints`) — the tag-category concurrency test's harness, retargeted at a create-of-a-
 * relation-to-X racing a delete-of-X: exactly one contender must win, and no row may ever be
 * left holding a relation to a target that does not exist.
 */
class BlueprintConcurrencyTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun simpleRequest(id: String) = BlueprintRequest(identifier = id, title = "T", schema = BlueprintSchema())

    private fun relatedRequest(id: String, target: String) = BlueprintRequest(
        identifier = id,
        title = "T",
        schema = BlueprintSchema(),
        relations = mapOf("rel" to RelationDefinition(title = "Rel", target = target, required = false, many = false)),
    )

    private suspend fun HttpClient.readBlueprints(): List<BlueprintResponse> =
        get("/api/v1/blueprints").body<BlueprintList>().items

    /**
     * A granted SHARE lock lets the old ownership/target SELECTs pass but holds their later
     * DML — the fixed service instead waits immediately on SHARE ROW EXCLUSIVE, observable
     * without timing assertions (the [TagCategoryConcurrencyTest] idiom, retargeted at
     * `blueprints`).
     */
    private class RegistryWriteBarrier private constructor(
        private val holder: Connection,
        private val observer: Connection,
    ) {
        private var released = false

        suspend fun awaitWriters(count: Int) {
            withTimeout(15_000) {
                while (waitingWriters() != count) delay(25)
            }
        }

        private suspend fun waitingWriters(): Int = withContext(Dispatchers.IO) {
            observer.prepareStatement(
                """
                SELECT COUNT(*)
                FROM pg_locks
                WHERE relation = 'blueprints'::regclass
                  AND mode IN ('ShareRowExclusiveLock', 'RowExclusiveLock')
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
            suspend fun acquire(): RegistryWriteBarrier = withContext(NonCancellable + Dispatchers.IO) {
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
                    RegistryWriteBarrier(holder, observer)
                } catch (failure: Exception) {
                    runCatching { holder?.close() }
                    runCatching { observer?.close() }
                    throw failure
                }
            }
        }
    }

    private suspend fun raceBehindBarrier(
        first: suspend () -> HttpResponse,
        second: suspend () -> HttpResponse,
    ): List<HttpResponse> = coroutineScope {
        val barrier = RegistryWriteBarrier.acquire()
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
    fun `a create relating to X racing a delete of X has exactly one winner and never a dangling target`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bp-concurrent-create-delete", UserRole.ADMIN)
        val targetId = unique("bpc-target")
        val referrerId = unique("bpc-referrer")
        val target = admin.postJson("/api/v1/blueprints", simpleRequest(targetId)).body<BlueprintResponse>()
        try {
            val responses = raceBehindBarrier(
                { admin.postJson("/api/v1/blueprints", relatedRequest(referrerId, targetId)) },
                { admin.delete("/api/v1/blueprints/${target.id}") },
            )
            val statuses = responses.map { it.status }

            // Either the create lost the race (target already gone → the "unknown target" 400)
            // and the delete won (204), or the create won (201, the target still active when it
            // read) and the delete lost (409, naming the new referrer) — never both succeeding,
            // and never a stored relation to a target that no longer exists.
            val createSucceeded = statuses[0] == HttpStatusCode.Created
            val deleteSucceeded = statuses[1] == HttpStatusCode.NoContent
            assertTrue(createSucceeded != deleteSucceeded, "exactly one contender must win: $statuses")
            if (createSucceeded) {
                assertEquals(HttpStatusCode.Conflict, responses[1].status)
                assertTrue(responses[1].body<ProblemDetail>().detail!!.contains(targetId))
            } else {
                assertEquals(HttpStatusCode.BadRequest, responses[0].status)
            }

            val blueprints = admin.readBlueprints()
            val referrer = blueprints.singleOrNull { it.identifier == referrerId }
            if (referrer != null) {
                // The referrer only exists when its create won — its target must still be a
                // live, active blueprint (never dangling).
                assertTrue(blueprints.any { it.identifier == referrer.relations.getValue("rel").target })
            }
        } finally {
            TestBlueprints.remove(targetId, referrerId)
        }
    }

    @Test
    fun `delete joins the write lock while ordinary reads remain available`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bp-concurrent-delete-reads", UserRole.ADMIN)
        val id = unique("bpc-delete")
        val created = admin.postJson("/api/v1/blueprints", simpleRequest(id)).body<BlueprintResponse>()
        val barrier = RegistryWriteBarrier.acquire()
        try {
            coroutineScope {
                val deletion = async { admin.delete("/api/v1/blueprints/${created.id}") }
                try {
                    barrier.awaitWriters(1)
                    val visibleDuringDelete = withTimeout(5_000) { admin.readBlueprints() }
                    assertTrue(visibleDuringDelete.any { it.id == created.id })
                } finally {
                    barrier.release()
                }
                assertEquals(HttpStatusCode.NoContent, withTimeout(15_000) { deletion.await() }.status)
            }
        } finally {
            barrier.release()
            TestBlueprints.remove(id)
        }
    }

    @Test
    fun `a cancelled lock waiter rolls back after the blocker clears`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bp-concurrent-cancel", UserRole.ADMIN)
        val cancelledId = unique("bpc-cancelled")
        val successfulId = unique("bpc-after-cancel")
        val barrier = RegistryWriteBarrier.acquire()
        try {
            coroutineScope {
                val cancelled = async { TestBlueprints.service.create(simpleRequest(cancelledId), callerId = 1u) }
                try {
                    barrier.awaitWriters(1)
                    cancelled.cancel()
                } finally {
                    barrier.release()
                }
                withTimeout(15_000) { cancelled.cancelAndJoin() }
                assertTrue(TestBlueprints.service.list().none { it.identifier == cancelledId })

                val successful = withTimeout(5_000) {
                    admin.postJson("/api/v1/blueprints", simpleRequest(successfulId))
                }
                assertEquals(HttpStatusCode.Created, successful.status)
            }
        } finally {
            barrier.release()
            TestBlueprints.remove(cancelledId, successfulId)
        }
    }
}
