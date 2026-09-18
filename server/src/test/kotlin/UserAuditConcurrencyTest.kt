package ch.nokillswit

import ch.nokillswit.users.Feature
import ch.nokillswit.users.UserFeaturesUpdateRequest
import ch.nokillswit.users.UserLanguageUpdateRequest
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserUpdateRequest
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A held real write lets the old route read stale state, then blocks its later mutation. */
class UserAuditConcurrencyTest {
    private enum class Change { IDENTITY, FEATURES, LANGUAGE }

    @Test
    fun `identity and role audits describe the committed predecessor after an overlapping write`() =
        checkTransition(Change.IDENTITY)

    @Test
    fun `feature audit describes the committed predecessor after an overlapping write`() =
        checkTransition(Change.FEATURES)

    @Test
    fun `language audit describes the committed predecessor after an overlapping write`() =
        checkTransition(Change.LANGUAGE)

    private fun checkTransition(change: Change) = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("audit-contender", role = UserRole.ADMIN)
        val email = uniqueEmail("audit-target")
        val concurrentEmail = uniqueEmail("audit-predecessor")
        val id = TestUsers.seed(email, "pw-123456789", name = "Original", role = UserRole.ADMIN)
        val holder = withContext(NonCancellable + Dispatchers.IO) { connect().apply { autoCommit = false } }
        try {
            val holderPid = withContext(Dispatchers.IO) {
                holder.createStatement().use { statement ->
                    statement.execute("SELECT id FROM users WHERE id = $id FOR UPDATE")
                    when (change) {
                        Change.IDENTITY -> holder.prepareStatement(
                            "UPDATE users SET name = 'Intervening', email = ?, role = 'USER', auth_version = auth_version + 1 WHERE id = ?",
                        ).use {
                            it.setString(1, concurrentEmail)
                            it.setLong(2, id.toLong())
                            it.executeUpdate()
                        }
                        Change.FEATURES -> statement.execute("DELETE FROM user_disabled_features WHERE user_id = $id")
                        Change.LANGUAGE -> statement.execute("UPDATE users SET language = 'pl' WHERE id = $id")
                    }
                    statement.executeQuery("SELECT pg_backend_pid()").use { it.next(); it.getInt(1) }
                }
            }
            withAuditCapture { capture ->
                coroutineScope {
                    val mutation = async {
                        when (change) {
                            Change.IDENTITY -> client.putJson(
                                "/api/v1/users/$id", UserUpdateRequest("Original", email, listOf(UserRole.ADMIN)),
                            )
                            Change.FEATURES -> client.putJson(
                                "/api/v1/users/$id/features", UserFeaturesUpdateRequest(listOf(Feature.MFA)),
                            )
                            Change.LANGUAGE -> client.putJson(
                                "/api/v1/users/$id/language", UserLanguageUpdateRequest("en"),
                            )
                        }
                    }
                    try {
                        awaitBlockedWriter(holderPid)
                        withContext(Dispatchers.IO) { holder.commit() }
                        assertEquals(HttpStatusCode.NoContent, mutation.await().status)
                    } finally {
                        // Always release before structured concurrency waits for the blocked child.
                        withContext(NonCancellable + Dispatchers.IO) { holder.rollback() }
                    }
                }
                val expected = when (change) {
                    Change.IDENTITY -> listOf(
                        "user.updated" to listOf(
                            "nameFrom" to "Intervening", "nameTo" to "Original",
                            "emailFrom" to concurrentEmail, "emailTo" to email,
                        ),
                        "user.roles_changed" to listOf("rolesFrom" to "", "rolesTo" to "ADMIN"),
                    )
                    Change.FEATURES -> listOf("user.features_changed" to listOf("featuresFrom" to "", "featuresTo" to "MFA"))
                    Change.LANGUAGE -> listOf("user.language_changed" to listOf("from" to "pl", "to" to "en"))
                }
                for ((name, fields) in expected) {
                    val event = capture.events.singleOrNull { it.message == name && it.hasKeyValue("targetUserId", id.toLong()) }
                    assertNotNull(event, "expected exactly one $name for $id")
                    fields.forEach { (key, value) -> assertTrue(event.hasKeyValue(key, value), "$name: $key=$value") }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { holder.rollback() } finally { holder.close() }
            }
            TestUsers.softDelete(id)
        }
    }

    @Test
    fun `demotion after an overlapping promotion retains the other administrator`() = checkPromotion(delete = false)

    @Test
    fun `deletion after an overlapping promotion retains the other administrator`() = checkPromotion(delete = true)

    private fun checkPromotion(delete: Boolean) = testApplication {
        usePostgresTestcontainer()
        val actorEmail = uniqueEmail("promotion-actor")
        val actor = TestUsers.seed(actorEmail, "pw-123456789", role = UserRole.ADMIN)
        val client = authedClient(actorEmail, "pw-123456789")
        val email = uniqueEmail("promotion-target")
        val target = TestUsers.seed(email, "pw-123456789", role = UserRole.USER)
        try {
            TestUsers.withSoloAdmins(setOf(actor)) {
                val holder = withContext(NonCancellable + Dispatchers.IO) { connect().apply { autoCommit = false } }
                try {
                    val pid = withContext(Dispatchers.IO) {
                        holder.createStatement().use { statement ->
                            statement.execute("UPDATE users SET role = 'ADMIN', auth_version = auth_version + 1 WHERE id = $target")
                            statement.executeQuery("SELECT pg_backend_pid()").use { it.next(); it.getInt(1) }
                        }
                    }
                    coroutineScope {
                        val mutation = async {
                            if (delete) client.delete("/api/v1/users/$target")
                            else client.putJson("/api/v1/users/$target", UserUpdateRequest("Test", email, emptyList()))
                        }
                        try {
                            awaitBlockedWriter(pid)
                            withContext(Dispatchers.IO) { holder.commit() }
                            assertEquals(HttpStatusCode.NoContent, mutation.await().status)
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) { holder.rollback() }
                        }
                    }
                    assertEquals(UserRole.ADMIN, TestUsers.service.read(actor)?.role)
                    assertEquals(if (delete) null else UserRole.USER, TestUsers.service.read(target)?.role)
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { holder.rollback() } finally { holder.close() }
                    }
                }
            }
        } finally {
            TestUsers.softDelete(target)
            TestUsers.softDelete(actor)
        }
    }

    private suspend fun awaitBlockedWriter(holderPid: Int) = withContext(Dispatchers.IO) {
        connect().use { observer ->
            withTimeout(15_000) {
                observer.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                ).use { query ->
                    query.setInt(1, holderPid)
                    while (!query.executeQuery().use { it.next(); it.getBoolean(1) }) delay(25)
                }
            }
        }
    }

    private fun connect(): Connection = DriverManager.getConnection(
        PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password,
    )
}
