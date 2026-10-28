package ch.nokillswit

import ch.nokillswit.auth.RefreshRequest
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.SsoLookup
import ch.nokillswit.users.UserResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `DELETE /api/v1/users/{id}/sso` — the administrator's release of an SSO identity — over the real SSO login flow
 * ([SsoTestSupport.kt]). Rules: `.claude/docs/security.md` "SSO login (OIDC)" (Unlinking) and `authorization.md`.
 */
class SsoUnlinkTest {

    private fun ssoPath(id: UInt) = "/api/v1/users/$id/sso"

    private suspend fun ApplicationTestBuilder.adminClient(): Pair<HttpClient, UInt> {
        val email = uniqueEmail("sso-unlink-admin")
        val id = TestUsers.seed(email = email, password = "password-1234", role = UserRole.ADMIN)
        return authedClient(email, "password-1234") to id
    }

    private suspend fun HttpClient.userOf(id: UInt): UserResponse = get("/api/v1/users/$id").body()

    @Test
    fun `the status matrix - anonymous 401, non-admin 403, self 403, unknown 404, service account 404, not linked 404`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val anonymous = jsonClient()
            val (admin, adminId) = adminClient()
            val user = seededClient("sso-unlink-plain")
            val targetIssuer = stub.issuer
            val linked = TestUsers.seedPasswordless(uniqueEmail("sso-unlink-target"), issuer = targetIssuer)
            withAuditCapture { audit ->
                assertEquals(HttpStatusCode.Unauthorized, anonymous.delete(ssoPath(linked)).status)
                // 403 wins over everything else, including an unknown id, and leaves the link alone.
                assertEquals(HttpStatusCode.Forbidden, user.delete(ssoPath(linked)).status)
                assertEquals(HttpStatusCode.Forbidden, user.delete(ssoPath(2_000_000_000u)).status)
                assertNotNull(assertNotNull(TestUsers.service.read(linked)).ssoSubject)

                // Self: decided before any read, so even an admin whose own row carries a link is refused and keeps it.
                TestUsers.forceSsoIdentity(adminId, targetIssuer, "admin-${UUID.randomUUID()}")
                assertEquals(HttpStatusCode.Forbidden, admin.delete(ssoPath(adminId)).status)
                assertNotNull(assertNotNull(TestUsers.service.read(adminId)).ssoSubject)

                val unknown = admin.delete(ssoPath(2_000_000_000u))
                assertEquals(HttpStatusCode.NotFound, unknown.status)
                assertEquals("User not found", unknown.body<ProblemDetail>().detail)

                val serviceAccount = TestUsers.seedServiceAccount("svc-${UUID.randomUUID()}", "svc-${UUID.randomUUID()}@toadie.invalid")
                val svc = admin.delete(ssoPath(serviceAccount))
                assertEquals(HttpStatusCode.NotFound, svc.status)
                assertEquals("User not found", svc.body<ProblemDetail>().detail)

                val deletedUnlinked =
                    TestUsers.seed(email = uniqueEmail("sso-unlink-gone"), password = "password-1234", role = UserRole.USER)
                TestUsers.softDelete(deletedUnlinked)
                val gone = admin.delete(ssoPath(deletedUnlinked))
                assertEquals(HttpStatusCode.NotFound, gone.status)
                assertEquals("User not found", gone.body<ProblemDetail>().detail)

                val plain = TestUsers.seed(email = uniqueEmail("sso-unlink-nolink"), password = "password-1234", role = UserRole.USER)
                val notLinked = admin.delete(ssoPath(plain))
                assertEquals(HttpStatusCode.NotFound, notLinked.status)
                assertEquals("No single sign-on identity is linked to this user", notLinked.body<ProblemDetail>().detail)

                assertTrue(audit.events.none { it.message == "user.sso_unlinked" }, "a refusal audits nothing")
            }
        }
    }

    @Test
    fun `an unlink ends the account's sessions, clears the link, audits the host and cannot be repeated`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val (admin, adminId) = adminClient()
            val email = uniqueEmail("sso-unlink")
            val claims = mapOf("email" to email, "sub" to "sub-${UUID.randomUUID()}")
            val session = browser.signInAndExchange(stub, claims)
            val id = session.userId
            val bearer = createClient { toadieTestClientDefaults() }
            suspend fun readAsSso(): HttpStatusCode =
                bearer.get("/api/v1/users/$id") { header(HttpHeaders.Authorization, "Bearer ${session.token}") }.status
            assertEquals(HttpStatusCode.OK, readAsSso())
            assertTrue(admin.userOf(id).sso)
            val refresh = jsonClient()
            suspend fun refreshAsSso(): HttpStatusCode =
                refresh.postJson("/api/v1/refresh", RefreshRequest(refreshToken = session.refreshToken)).status

            withAuditCapture { audit ->
                assertEquals(HttpStatusCode.NoContent, admin.delete(ssoPath(id)).status)

                // The credential epoch advanced: the access token minted before is dead.
                assertEquals(HttpStatusCode.Unauthorized, readAsSso())
                // ... and so is the refresh token that would have renewed it.
                assertEquals(HttpStatusCode.Unauthorized, refreshAsSso())
                val after = admin.userOf(id)
                assertFalse(after.sso)
                assertFalse(after.hasPassword, "the account stays passwordless: unlinking touches nothing else")
                assertEquals(email, after.email)
                val stored = assertNotNull(TestUsers.service.read(id))
                assertNull(stored.ssoIssuer)
                assertNull(stored.ssoSubject)

                val event = assertNotNull(audit.events.singleOrNull { it.message == "user.sso_unlinked" })
                assertTrue(event.hasKeyValue("byUserId", adminId.toLong()))
                assertTrue(event.hasKeyValue("targetUserId", id.toLong()))
                assertTrue(event.hasKeyValue("issuerHost", "127.0.0.1"))
                assertTrue(event.hasKeyValue("deleted", false))
                assertTrue(event.keyValuePairs.none { it.value.toString().contains("https://") }, "the host only, never the URL")

                // A second unlink is a 404, not a silent success, and audits nothing more.
                assertEquals(HttpStatusCode.NotFound, admin.delete(ssoPath(id)).status)
                assertEquals(1, audit.events.count { it.message == "user.sso_unlinked" })
            }
        }
    }

    @Test
    fun `after an unlink the same identity signs in again and is linked by email, not recreated`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val (admin, _) = adminClient()
            val claims = mapOf("email" to uniqueEmail("sso-relink"), "sub" to "sub-${UUID.randomUUID()}")
            val first = browser.signInAndExchange(stub, claims)
            assertEquals(HttpStatusCode.NoContent, admin.delete(ssoPath(first.userId)).status)
            withAuditCapture { audit ->
                val again = browser.signInAndExchange(stub, claims)
                assertEquals(first.userId, again.userId)
                assertTrue(assertNotNull(audit.events.firstOrNull { it.message == "login.sso_success" }).hasKeyValue("outcome", "linked"))
                assertTrue(admin.userOf(first.userId).sso)
            }
        }
    }

    @Test
    fun `a sign-in already past its callback cannot complete once the account is unlinked`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val (admin, _) = adminClient()
            val email = uniqueEmail("sso-inflight")
            val subject = "sub-${UUID.randomUUID()}"
            val first = browser.signInAndExchange(stub, mapOf("email" to email, "sub" to subject))
            // The callback has resolved the account and issued its hand-off ...
            val pending = browser.signIn(stub, mapOf("email" to email, "sub" to subject))
            val code = assertNotNull(pending.code)
            // ... the administrator releases the identity before the browser exchanges it.
            assertEquals(HttpStatusCode.NoContent, admin.delete(ssoPath(first.userId)).status)
            withAuditCapture { audit ->
                assertEquals(HttpStatusCode.Unauthorized, browser.exchange(code, pending.attempt.cookie).status)
                assertTrue(audit.events.any { it.message == "login.sso_failure" && it.hasKeyValue("reason", "handoff_invalid") })
            }
        }
    }

    @Test
    fun `a soft-deleted account can be released - its identity stays refused until then and signs in as a new account after`() =
        testApplication {
            StubIdp().use { stub ->
                ssoApp(stub)
                val browser = browser()
                val (admin, adminId) = adminClient()
                val email = uniqueEmail("sso-released")
                val subject = "sub-${UUID.randomUUID()}"
                val claims = mapOf("email" to email, "sub" to subject)
                val id = TestUsers.seedPasswordless(email, issuer = stub.issuer, subject = subject)
                TestUsers.softDelete(id)
                withAuditCapture { audit ->
                    assertRefusedNotAllowed(browser, stub, claims)
                    // The refusal names the row an administrator releases.
                    val refusal = assertNotNull(failureOf(audit, "account_deleted"))
                    assertTrue(refusal.hasKeyValue("userId", id.toLong()))

                    assertEquals(HttpStatusCode.NoContent, admin.delete(ssoPath(id)).status)
                    val event = assertNotNull(audit.events.singleOrNull { it.message == "user.sso_unlinked" })
                    assertTrue(event.hasKeyValue("targetUserId", id.toLong()) && event.hasKeyValue("byUserId", adminId.toLong()))
                    assertTrue(event.hasKeyValue("deleted", true))
                    assertTrue(event.hasKeyValue("issuerHost", "127.0.0.1"))
                    // The deleted row stays invisible on the ordinary surface.
                    assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/users/$id").status)
                    // Released, the deleted row is just a deleted row: a plain "User not found", never the "not linked" detail,
                    // so the deleted-row exception only ever reveals or affects LINKED deleted rows.
                    val again = admin.delete(ssoPath(id))
                    assertEquals(HttpStatusCode.NotFound, again.status)
                    assertEquals("User not found", again.body<ProblemDetail>().detail)

                    val fresh = browser.signInAndExchange(stub, claims)
                    assertNotEquals(id, fresh.userId)
                    assertTrue(audit.events.any { it.message == "login.sso_success" && it.hasKeyValue("outcome", "created") })
                }
            }
        }

    @Test
    fun `the route works with SSO switched off, for a link made while it was on`() = testApplication {
        usePostgresTestcontainer()
        val (admin, _) = adminClient()
        val id = TestUsers.seedPasswordless(uniqueEmail("sso-off"))
        assertTrue(admin.userOf(id).sso)
        assertEquals(HttpStatusCode.NoContent, admin.delete(ssoPath(id)).status)
        assertFalse(admin.userOf(id).sso)
    }

    @Test
    fun `a passwordless ADMIN or MFA-enabled account is refused with 409 password-not-set, a passwordless plain user is not`() =
        testApplication {
            StubIdp().use { stub ->
                ssoApp(stub)
                val (admin, _) = adminClient()
                withAuditCapture { audit ->
                    suspend fun assertPasswordRequired(id: UInt) {
                        val response = admin.delete(ssoPath(id))
                        assertEquals(HttpStatusCode.Conflict, response.status)
                        assertEquals("urn:toadie:password-not-set", response.body<ProblemDetail>().type)
                        assertNotNull(assertNotNull(TestUsers.service.read(id)).ssoSubject, "the refused unlink changes nothing")
                    }
                    val passwordlessAdmin =
                        TestUsers.seedPasswordless(uniqueEmail("sso-unlink-padmin"), role = UserRole.ADMIN, issuer = stub.issuer)
                    assertPasswordRequired(passwordlessAdmin)

                    val mfaUser = TestUsers.seedPasswordless(uniqueEmail("sso-unlink-pmfa"), issuer = stub.issuer)
                    TestUsers.service.setDisabledFeatures(mfaUser, emptySet())
                    assertPasswordRequired(mfaUser)

                    // A plain passwordless USER (MFA off) re-links by email, so it is releasable.
                    val plain = TestUsers.seedPasswordless(uniqueEmail("sso-unlink-pplain"), issuer = stub.issuer)
                    assertEquals(HttpStatusCode.NoContent, admin.delete(ssoPath(plain)).status)
                    assertEquals(1, audit.events.count { it.message == "user.sso_unlinked" }, "only the allowed unlink audits")
                }
            }
        }

    @Test
    fun `a soft-deleted linked passwordless ADMIN or MFA-enabled row is still released with 204`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val (admin, _) = adminClient()
            withAuditCapture { audit ->
                suspend fun assertReleased(id: UInt) {
                    TestUsers.softDelete(id)
                    assertEquals(HttpStatusCode.NoContent, admin.delete(ssoPath(id)).status)
                    assertNull(TestUsers.rawSsoSubject(id), "the deleted row's link is released")
                }
                // The same rows an ACTIVE account answers 409 for (previous test): deletion exempts them, no password is needed.
                assertReleased(TestUsers.seedPasswordless(uniqueEmail("sso-unlink-dadmin"), role = UserRole.ADMIN, issuer = stub.issuer))
                val mfaUser = TestUsers.seedPasswordless(uniqueEmail("sso-unlink-dmfa"), issuer = stub.issuer)
                TestUsers.service.setDisabledFeatures(mfaUser, emptySet())
                assertReleased(mfaUser)
                assertEquals(2, audit.events.count { it.message == "user.sso_unlinked" && it.hasKeyValue("deleted", true) })
            }
        }
    }

    @Test
    fun `an administrator with a password can be unlinked, and its next SSO sign-in is link_refused`() = testApplication {
        StubIdp().use { stub ->
            ssoApp(stub)
            val browser = browser()
            val (admin, _) = adminClient()
            val email = uniqueEmail("sso-unlink-padmin2")
            val subject = "sub-${UUID.randomUUID()}"
            val id = TestUsers.seed(email = email, password = "password-1234", role = UserRole.ADMIN)
            TestUsers.forceSsoIdentity(id, stub.issuer, subject)
            withAuditCapture { audit ->
                assertEquals(HttpStatusCode.NoContent, admin.delete(ssoPath(id)).status)
                // The consequence, documented: an ADMIN never re-links by email, so the identity is simply refused now.
                assertRefusedNotAllowed(browser, stub, mapOf("email" to email, "sub" to subject))
                assertNotNull(failureOf(audit, "link_refused"))
                assertFalse(admin.userOf(id).sso)
            }
        }
    }

    @Test
    fun `the identity lookup locks its row and reads the credential version in its own transaction`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("sso-lookup-lock")
        val issuer = "https://idp.test/${UUID.randomUUID()}"
        val subject = "sub-${UUID.randomUUID()}"
        val id = TestUsers.seedPasswordless(email, issuer = issuer, subject = subject)
        val before = assertNotNull(TestUsers.service.read(id)).authVersion
        // The version the hand-off is issued from is the one the lookup itself saw.
        assertEquals(SsoLookup.Existing(id, before), TestUsers.service.resolveSsoIdentity(issuer, subject, email))

        val locked = CompletableDeferred<Int>()
        val release = CompletableDeferred<Unit>()
        withContext(Dispatchers.Default) {
            // An unlink (the same row-level steps as UserService.unlinkSso), held open mid-transaction.
            val unlink = async {
                TestUsers.unlinkHoldingLock(id) { holderPid ->
                    locked.complete(holderPid)
                    release.await()
                }
            }
            val holderPid = locked.await()
            val lookup = async { TestUsers.service.resolveSsoIdentity(issuer, subject, email) }
            // Observed, not timed: PostgreSQL reports a backend waiting on the unlink's lock (fails without the lookup's FOR UPDATE).
            awaitBlockedWriter(holderPid)
            assertFalse(lookup.isCompleted, "the lookup waits for the unlink's row lock")
            release.complete(Unit)
            unlink.await()
            // The unlink won: the identity no longer matches, so the callback links by the ordinary rule at the NEW version -
            // it can never pair the old hand-off version with an unlinked row.
            assertEquals(SsoLookup.Linked(id, before + 1), lookup.await())
        }
        assertNotNull(assertNotNull(TestUsers.service.read(id)).ssoSubject)
    }

    private suspend fun awaitBlockedWriter(holderPid: Int) = withContext(Dispatchers.IO) {
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { observer ->
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

    private suspend fun assertRefusedNotAllowed(browser: HttpClient, stub: StubIdp, claims: Map<String, Any?>) =
        browser.assertRefused(browser.signIn(stub, claims), "not_allowed")
}
