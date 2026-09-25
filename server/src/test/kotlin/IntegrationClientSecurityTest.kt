package ch.nokillswit

import ch.nokillswit.integration.GraphQLHttpRequest
import ch.nokillswit.integration.RevokeOutcome
import ch.nokillswit.integration.apiKeyHash
import ch.nokillswit.integration.generateApiKey
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserService.GuardedMutation
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
    /**
     * Reverse-credential pair (2.13.1): each credential type opens exactly one door. Neither
     * direction existed before — [IntegrationGraphQlTest] only pinned a login JWT rejected on
     * the schema GET, without asserting the audit reason, and no test had tried an integration
     * key against the ordinary REST API.
     */
    @Test
    fun `a login JWT opens no integration door and an integration key opens no REST door`() = testApplication {
        configureApp("integration.enabled" to "true")
        startApplication()
        TestRefTargets.ensure()

        val email = uniqueEmail("int-reverse")
        val owner = TestUsers.seed(email, "pw")
        val (_, key) = TestIntegrationClients.service.create("Reverse-credential probe", owner)
        val jwtClient = authedClient(email, "pw")
        val plainClient = jsonClient()

        withAuditCapture { capture ->
            val graphqlWithJwt = jwtClient.post("/integration/graphql") {
                contentType(ContentType.Application.Json)
                setBody(GraphQLHttpRequest("{ __typename }"))
            }
            assertEquals(HttpStatusCode.Unauthorized, graphqlWithJwt.status)
            assertEquals("Missing or invalid integration API key", graphqlWithJwt.body<ProblemDetail>().detail)
            val authFailed = assertNotNull(
                capture.awaitEvent { it.message == "integration.auth_failed" },
                "expected an integration.auth_failed audit event",
            )
            // A JWT never matches the `toadie_int_...` bearer grammar at all, so the caller is
            // classified the same as an absent/malformed key — never `unknown_or_revoked` (that
            // reason is reserved for a well-formed key the store doesn't recognize).
            assertTrue(
                authFailed.hasKeyValue("reason", "missing_or_malformed"),
                "unexpected audit reason: ${authFailed.keyValuePairs}",
            )
        }

        val restWithIntegrationKey = plainClient.get("/api/v1/blueprints") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }
        assertEquals(HttpStatusCode.Unauthorized, restWithIntegrationKey.status)
        assertEquals(
            "Missing or invalid bearer token",
            restWithIntegrationKey.body<ProblemDetail>().detail,
            "an integration key must fail the ordinary JWT challenge, not a feature-specific check",
        )
    }

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
        assertEquals(RevokeOutcome.REVOKED, service.revoke(id).outcome)
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
        assertEquals(RevokeOutcome.REVOKED, service.revoke(id).outcome)
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
            assertEquals(RevokeOutcome.ALREADY_REVOKED, service.revoke(id).outcome)
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
