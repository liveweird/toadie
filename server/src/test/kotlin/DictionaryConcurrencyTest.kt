package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.dictionaries.DictionaryEntryInput
import ch.nokillswit.dictionaries.DictionaryEntryList
import ch.nokillswit.dictionaries.DictionaryUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The held-lock proof for the `HIERARCHY` dictionary's V27/V34 protocol
 * (`.claude/docs/persistence.md` "Blueprint targets under concurrency (V27)"): a dictionary
 * PUT removing a value and a blueprint PUT adding a `hierarchyRelations` entry naming that
 * SAME value both take the `blueprints` table's `SHARE ROW EXCLUSIVE` lock before deciding,
 * so exactly one of them wins and the final state is always consistent — the
 * [BlueprintConcurrencyTest] harness, retargeted at the dictionary/blueprint race V34 adds.
 */
class DictionaryConcurrencyTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}".lowercase()

    private fun plainBlueprint(id: String) = BlueprintRequest(
        identifier = id,
        title = "T",
        schema = BlueprintSchema(),
        relations = mapOf(
            "parent" to RelationDefinition(title = "Parent", target = id, required = false, many = false),
        ),
    )

    private fun withHierarchy(request: BlueprintRequest, value: String) =
        request.copy(hierarchyRelations = mapOf(value to "parent"))

    private suspend fun HttpClient.readHierarchies(): DictionaryEntryList =
        get("/api/v1/dictionaries/hierarchies").body()

    /**
     * A granted SHARE lock lets ordinary reads pass but holds every writer's later
     * SHARE ROW EXCLUSIVE acquisition — the [BlueprintConcurrencyTest] idiom, retargeted at
     * `blueprints` (the SAME table both `BlueprintService` and, since V34, the `HIERARCHY`
     * `DictionaryService.replace` lock).
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
    fun `a hierarchy removal racing a blueprint PUT naming it has exactly one winner and a consistent final state`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("hierconcurrent", UserRole.ADMIN)
            val value = unique("hcvalue")
            val blueprintId = unique("hcbp")
            TestHierarchies.ensure(value)
            val created = admin.postJson("/api/v1/blueprints", plainBlueprint(blueprintId)).body<BlueprintResponse>()
            try {
                val currentDictionary = admin.readHierarchies().items
                val withoutValue = currentDictionary
                    .filterNot { it.value == value }
                    .map { DictionaryEntryInput(it.id, it.value) }

                val responses = raceBehindBarrier(
                    { admin.putJson("/api/v1/dictionaries/hierarchies", DictionaryUpdateRequest(withoutValue)) },
                    { admin.putJson("/api/v1/blueprints/${created.id}", withHierarchy(plainBlueprint(blueprintId), value)) },
                )
                val statuses = responses.map { it.status }

                // Either the dictionary removal won (204) and the blueprint write lost (400,
                // the hierarchy having vanished under it), or the blueprint write won (204,
                // the value still active when it read) and the dictionary removal lost (409,
                // naming the newly-referencing blueprint) — never both succeeding.
                val removalSucceeded = statuses[0] == HttpStatusCode.NoContent
                val blueprintSucceeded = statuses[1] == HttpStatusCode.NoContent
                assertTrue(removalSucceeded != blueprintSucceeded, "exactly one contender must win: $statuses")
                if (removalSucceeded) {
                    assertEquals(HttpStatusCode.BadRequest, responses[1].status)
                } else {
                    assertEquals(HttpStatusCode.Conflict, responses[0].status)
                }

                // Consistent final state either way: the value is gone AND no blueprint names
                // it, or the value is present AND the blueprint names it — never a blueprint
                // left pointing at a hierarchy the dictionary no longer has.
                val finalDictionary = admin.readHierarchies().items
                val finalBlueprint = admin.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
                if (finalDictionary.any { it.value == value }) {
                    assertEquals(mapOf(value to "parent"), finalBlueprint.hierarchyRelations)
                } else {
                    assertTrue(
                        value !in finalBlueprint.hierarchyRelations.orEmpty(),
                        "a removed hierarchy value must never still be named by a blueprint",
                    )
                }
            } finally {
                TestBlueprints.remove(blueprintId)
                TestHierarchies.remove(value)
            }
        }
}
