package ch.nokillswit

import ch.nokillswit.integration.RevokeOutcome
import ch.nokillswit.integration.apiKeyHash
import ch.nokillswit.integration.generateApiKey
import ch.nokillswit.users.UserService.GuardedMutation
import ch.nokillswit.users.UserRole
import io.ktor.server.testing.testApplication
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegrationClientSecurityTest {
    @Test
    fun `machine credentials outlive their creating login account until explicitly revoked`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("int-independent")
        val owner = TestUsers.seed(email, "pw", name = "Former administrator")
        val service = TestIntegrationClients.service
        val (id, key) = service.create("Independent machine identity", owner)
        TestUsers.service.updateGuarded(owner, "Former administrator", email, UserRole.USER)
        assertEquals(id, assertNotNull(service.authenticate(key)).clientId)
        assertEquals(GuardedMutation.DONE, TestUsers.service.deleteGuarded(owner))
        assertEquals(id, assertNotNull(service.authenticate(key)).clientId)
        assertEquals("Former administrator", service.read(id)?.createdByName)
        assertEquals(RevokeOutcome.REVOKED, service.revoke(id))
        assertNull(service.authenticate(key))
    }

    @Test
    fun `keys have independent random entropy and only their digests persist`() = testApplication {
        usePostgresTestcontainer()
        val service = TestIntegrationClients.service
        val owner = TestUsers.seed(uniqueEmail("int-key"), "pw")
        val (id, key) = service.create("Digest test", owner)
        assertTrue(Regex("^toadie_int_[A-Za-z0-9_-]{43}$").matches(key))
        assertEquals(100, (1..100).map { generateApiKey() }.toSet().size)
        withContext(Dispatchers.IO) {
            connection().use { db ->
                db.prepareStatement("SELECT key_hash FROM integration_clients WHERE id = ?").use { query ->
                    query.setLong(1, id.toLong())
                    query.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(apiKeyHash(key), rows.getString(1))
                        assertFalse(rows.getString(1).contains(key))
                    }
                }
            }
        }
        assertNull(service.authenticate("unknown"))
        assertEquals(id, assertNotNull(service.authenticate(key)).clientId)
        assertNotNull(service.read(id)?.lastUsedAt)
        assertEquals(RevokeOutcome.REVOKED, service.revoke(id))
        assertNull(service.authenticate(key))
    }

    @Test
    fun `a revoke winning the conditional authentication write refuses the key`() = testApplication {
        usePostgresTestcontainer()
        val service = TestIntegrationClients.service
        val owner = TestUsers.seed(uniqueEmail("int-race"), "pw")
        val (id, key) = service.create("Racing revoke", owner)
        val holder = withContext(NonCancellable + Dispatchers.IO) { connection().apply { autoCommit = false } }
        try {
            val holderPid = withContext(Dispatchers.IO) {
                holder.createStatement().use { statement ->
                    statement.executeUpdate("UPDATE integration_clients SET revoked_at = 123 WHERE id = ${id.toLong()}")
                    statement.executeQuery("SELECT pg_backend_pid()").use { result -> result.next(); result.getInt(1) }
                }
            }
            coroutineScope {
                // The plain read sees the pre-commit active row; its UPDATE then waits on the holder.
                val authentication = async { service.authenticate(key) }
                try {
                    withTimeout(10_000) {
                        while (!blockedBy(holderPid)) delay(10)
                    }
                } finally {
                    // Release before structured concurrency waits for the blocked R2DBC child,
                    // including timeout/cancellation: cancelling that child may itself await SQL.
                    withContext(NonCancellable + Dispatchers.IO) { holder.commit() }
                }
                assertNull(authentication.await())
            }
            assertNull(service.read(id)?.lastUsedAt)
            assertEquals(RevokeOutcome.ALREADY_REVOKED, service.revoke(id))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { holder.rollback(); holder.close() }
        }
    }

    private suspend fun blockedBy(pid: Int): Boolean = withContext(Dispatchers.IO) {
        connection().use { db ->
            db.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))")
                .use { query ->
                    query.setInt(1, pid)
                    query.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
                }
        }
    }

    private fun connection() = DriverManager.getConnection(
        PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password,
    )
}
