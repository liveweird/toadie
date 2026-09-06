package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.tags.TagCategoryList
import ch.nokillswit.tags.TagCategoryRequest
import ch.nokillswit.tags.TagCategoryResponse
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

/** Concurrency coverage for the service-enforced one-category-per-tag invariant. */
class TagCategoryConcurrencyTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun request(
        name: String,
        tags: List<String>,
        kinds: List<String> = listOf("Component"),
    ) = TagCategoryRequest(name = name, tags = tags, kinds = kinds)

    private suspend fun HttpClient.readCategories(): List<TagCategoryResponse> =
        get("/api/v1/tag-categories").body<TagCategoryList>().items

    /**
     * A granted SHARE lock lets the old ownership SELECTs pass but holds their later DML.
     * The fixed service instead waits immediately on SHARE ROW EXCLUSIVE, which is observable
     * without timing assertions and keeps both contenders behind the same starting barrier.
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
                WHERE relation = 'tag_categories'::regclass
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
                    holder.createStatement().use { it.execute("LOCK TABLE tag_categories IN SHARE MODE") }
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

    private suspend fun insertCapacityFillers(prefix: String, count: Int) =
        withContext(NonCancellable + Dispatchers.IO) {
            DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl,
                PostgresTestSupport.user,
                PostgresTestSupport.password,
            ).use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        "INSERT INTO tag_categories (name, allowed_kinds, tags) VALUES (?, ?, ?)",
                    ).use { statement ->
                        repeat(count) { index ->
                            statement.setString(1, "$prefix-filler-$index")
                            statement.setString(2, "[\"Component\"]")
                            statement.setString(3, "[\"$prefix-tag-$index\"]")
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    connection.commit()
                } catch (failure: Exception) {
                    runCatching { connection.rollback() }
                    throw failure
                }
            }
        }

    private suspend fun removeOwnedCategories(prefix: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl,
                PostgresTestSupport.user,
                PostgresTestSupport.password,
            ).use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        "UPDATE tag_categories SET marked_as_deleted = true WHERE name LIKE ?",
                    ).use {
                        it.setString(1, "$prefix%")
                        it.executeUpdate()
                    }
                    connection.commit()
                } catch (failure: Exception) {
                    runCatching { connection.rollback() }
                    throw failure
                }
            }
        }

    private suspend fun assertOneConflict(responses: List<HttpResponse>, success: HttpStatusCode): Int {
        assertEquals(listOf(success, HttpStatusCode.Conflict), responses.map { it.status }.sortedBy { it.value })
        val loser = responses.indexOfFirst { it.status == HttpStatusCode.Conflict }
        val conflict = responses[loser]
        assertTrue(
            conflict.headers["Content-Type"]?.startsWith("application/problem+json") == true,
            "the losing API response must use the RFC 7807 media type",
        )
        assertTrue(conflict.body<ProblemDetail>().detail!!.contains("already belongs to category"))
        return loser
    }

    @Test
    fun `concurrent creates serialize ownership and exactly one category claims the tag`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tc-concurrent-create", UserRole.ADMIN)
        val firstName = unique("tc-create-a")
        val secondName = unique("tc-create-b")
        val sharedTag = unique("tc-shared").lowercase()
        try {
            val responses = raceBehindBarrier(
                { admin.postJson("/api/v1/tag-categories", request(firstName, listOf(sharedTag))) },
                { admin.postJson("/api/v1/tag-categories", request(secondName, listOf(sharedTag))) },
            )
            assertOneConflict(responses, HttpStatusCode.Created)

            val owners = admin.readCategories().filter { sharedTag in it.tags }
            assertEquals(1, owners.size)
            assertTrue(owners.single().name == firstName || owners.single().name == secondName)
        } finally {
            TestTagCategories.remove(firstName, secondName)
        }
    }

    @Test
    fun `concurrent updates leave the losing category wholly unchanged`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tc-concurrent-update", UserRole.ADMIN)
        val firstName = unique("tc-update-a")
        val secondName = unique("tc-update-b")
        val firstOriginal = request(firstName, listOf(unique("tc-original-a").lowercase()), listOf("API"))
        val secondOriginal = request(secondName, listOf(unique("tc-original-b").lowercase()), listOf("System"))
        val firstId = TestTagCategories.ensure(firstName, firstOriginal.tags, firstOriginal.kinds)
        val secondId = TestTagCategories.ensure(secondName, secondOriginal.tags, secondOriginal.kinds)
        val sharedTag = unique("tc-update-shared").lowercase()
        val replacements = listOf(
            request(unique("tc-renamed-a"), listOf(sharedTag), listOf("Resource")),
            request(unique("tc-renamed-b"), listOf(sharedTag), listOf("Domain")),
        )
        try {
            val responses = raceBehindBarrier(
                { admin.putJson("/api/v1/tag-categories/$firstId", replacements[0]) },
                { admin.putJson("/api/v1/tag-categories/$secondId", replacements[1]) },
            )
            val loser = assertOneConflict(responses, HttpStatusCode.NoContent)

            val byId = admin.readCategories().associateBy { it.id }
            val loserId = listOf(firstId, secondId)[loser]
            val original = listOf(firstOriginal, secondOriginal)[loser]
            assertEquals(
                TagCategoryResponse(loserId, original.name, original.tags, original.kinds),
                byId.getValue(loserId),
            )
            assertEquals(1, byId.values.count { sharedTag in it.tags })
        } finally {
            TestTagCategories.remove(firstName, secondName, replacements[0].name, replacements[1].name)
        }
    }

    @Test
    fun `concurrent create and update preserve the losing update document`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tc-concurrent-mixed", UserRole.ADMIN)
        val existingName = unique("tc-mixed-existing")
        val createdName = unique("tc-mixed-created")
        val original = request(existingName, listOf(unique("tc-mixed-original").lowercase()), listOf("API"))
        val existingId = TestTagCategories.ensure(existingName, original.tags, original.kinds)
        val sharedTag = unique("tc-mixed-shared").lowercase()
        val replacement = request(unique("tc-mixed-renamed"), listOf(sharedTag), listOf("Resource"))
        try {
            val responses = raceBehindBarrier(
                { admin.postJson("/api/v1/tag-categories", request(createdName, listOf(sharedTag))) },
                { admin.putJson("/api/v1/tag-categories/$existingId", replacement) },
            )
            val loser = assertOneConflict(
                responses,
                if (responses.any { it.status == HttpStatusCode.Created }) HttpStatusCode.Created else HttpStatusCode.NoContent,
            )

            val categories = admin.readCategories()
            assertEquals(1, categories.count { sharedTag in it.tags })
            if (loser == 1) {
                assertEquals(
                    TagCategoryResponse(existingId, original.name, original.tags, original.kinds),
                    categories.single { it.id == existingId },
                )
            } else {
                assertTrue(categories.none { it.name == createdName }, "a losing create must store no partial row")
            }
        } finally {
            TestTagCategories.remove(existingName, createdName, replacement.name)
        }
    }

    @Test
    fun `delete joins the write lock while ordinary reads remain available`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tc-concurrent-delete", UserRole.ADMIN)
        val categoryName = unique("tc-delete")
        val id = TestTagCategories.ensure(categoryName, listOf(unique("tc-delete-tag").lowercase()), listOf("Component"))
        val barrier = RegistryWriteBarrier.acquire()
        try {
            coroutineScope {
                val deletion = async { admin.delete("/api/v1/tag-categories/$id") }
                try {
                    barrier.awaitWriters(1)
                    val visibleDuringDelete = withTimeout(5_000) { admin.readCategories() }
                    assertTrue(visibleDuringDelete.any { it.id == id })
                } finally {
                    barrier.release()
                }
                assertEquals(HttpStatusCode.NoContent, withTimeout(15_000) { deletion.await() }.status)
            }
        } finally {
            barrier.release()
            TestTagCategories.remove(categoryName)
        }
    }

    @Test
    fun `a failed write rolls back and releases the registry lock`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tc-concurrent-rollback", UserRole.ADMIN)
        val occupiedName = unique("tc-rollback-occupied")
        val validName = unique("tc-rollback-valid")
        TestTagCategories.ensure(
            occupiedName,
            listOf(unique("tc-rollback-existing").lowercase()),
            listOf("Component"),
        )
        try {
            val failed = admin.postJson(
                "/api/v1/tag-categories",
                request(occupiedName.uppercase(), listOf(unique("tc-rollback-free").lowercase())),
            )
            assertEquals(HttpStatusCode.Conflict, failed.status)

            val successful = withTimeout(5_000) {
                admin.postJson(
                    "/api/v1/tag-categories",
                    request(validName, listOf(unique("tc-after-rollback").lowercase())),
                )
            }
            assertEquals(HttpStatusCode.Created, successful.status)
        } finally {
            TestTagCategories.remove(occupiedName, validName)
        }
    }

    @Test
    fun `concurrent creates at the capacity boundary store only the two-hundredth category`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tc-concurrent-capacity", UserRole.ADMIN)
        val prefix = unique("tc-capacity")
        val initialCount = admin.readCategories().size
        check(initialCount < 199) { "tag-category fixtures leaked into the shared registry: $initialCount active rows" }
        try {
            insertCapacityFillers(prefix, 199 - initialCount)
            val responses = raceBehindBarrier(
                { admin.postJson("/api/v1/tag-categories", request("$prefix-a", listOf("$prefix-a-tag"))) },
                { admin.postJson("/api/v1/tag-categories", request("$prefix-b", listOf("$prefix-b-tag"))) },
            )

            assertEquals(
                listOf(HttpStatusCode.Created, HttpStatusCode.BadRequest),
                responses.map { it.status }.sortedBy { it.value },
            )
            val rejected = responses.single { it.status == HttpStatusCode.BadRequest }
            assertTrue(rejected.body<ProblemDetail>().detail!!.contains("registry is full"))
            assertEquals(200, admin.readCategories().size)
        } finally {
            removeOwnedCategories(prefix)
        }
    }

    @Test
    fun `a cancelled lock waiter rolls back after the blocker clears`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tc-concurrent-cancel", UserRole.ADMIN)
        val cancelledName = unique("tc-cancelled")
        val successfulName = unique("tc-after-cancel")
        val barrier = RegistryWriteBarrier.acquire()
        try {
            coroutineScope {
                val cancelled = async {
                    TestTagCategories.service.create(
                        request(cancelledName, listOf(unique("tc-cancelled-tag").lowercase())),
                    )
                }
                try {
                    barrier.awaitWriters(1)
                    cancelled.cancel()
                } finally {
                    barrier.release()
                }
                withTimeout(15_000) { cancelled.cancelAndJoin() }
                assertTrue(TestTagCategories.service.list().none { it.name == cancelledName })

                val successful = withTimeout(5_000) {
                    admin.postJson(
                        "/api/v1/tag-categories",
                        request(successfulName, listOf(unique("tc-after-cancel-tag").lowercase())),
                    )
                }
                assertEquals(HttpStatusCode.Created, successful.status)
            }
        } finally {
            barrier.release()
            TestTagCategories.remove(cancelledName, successfulName)
        }
    }
}
