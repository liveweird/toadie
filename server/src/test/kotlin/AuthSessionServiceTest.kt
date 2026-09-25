package ch.nokillswit

import ch.nokillswit.auth.hashPassword
import ch.nokillswit.users.UserRole
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AuthSessionServiceTest {
    @Test
    fun `expiry cleanup and renewal are clock bounded and logout is visible across instances`() = testApplication {
        usePostgresTestcontainer()
        var now = System.currentTimeMillis()
        val first = newAuthSessionService { now }
        val second = newAuthSessionService { now }
        val userId = TestUsers.seed(uniqueEmail("session-store"), "pw")
        val otherId = TestUsers.seed(uniqueEmail("session-other"), "pw")
        val id = UUID.randomUUID().toString()
        assertTrue(first.create(id, userId, 0, now + 100))
        assertTrue(first.isActive(id, userId))
        assertFalse(first.isActive(id, otherId))
        assertFalse(first.renew(id, otherId, 0, now + 200))
        assertTrue(first.renew(id, userId, 0, now + 200))
        assertTrue(first.renew(id, userId, 0, now + 50))
        now += 100
        assertTrue(first.isActive(id, userId))
        first.revoke(id, otherId)
        assertTrue(first.isActive(id, userId))
        second.revoke(id, userId)
        assertFalse(first.isActive(id, userId))
        assertFalse(first.renew(id, userId, 0, now + 200))

        val expired = UUID.randomUUID().toString()
        assertTrue(first.create(expired, userId, 0, now + 1))
        now += 1
        assertFalse(first.isActive(expired, userId))
        assertFalse(first.renew(expired, userId, 0, now + 200))
        assertTrue(first.create(UUID.randomUUID().toString(), userId, 0, now + 100))
        assertFalse(first.isActive(expired, userId))
    }

    @Test
    fun `credential epochs prevent stale issuance renewal and competing password changes`() = testApplication {
        usePostgresTestcontainer()
        val now = System.currentTimeMillis()
        val sessions = newAuthSessionService { now }
        val userId = TestUsers.seed(uniqueEmail("session-epoch"), "pw")
        val id = UUID.randomUUID().toString()
        assertTrue(sessions.create(id, userId, 0, now + 60_000))
        val user = TestUsers.service.read(userId)!!
        TestUsers.service.updateGuarded(userId, "Renamed", user.email, UserRole.ADMIN)
        TestUsers.service.updateGuarded(userId, "Renamed", user.email, UserRole.ADMIN)
        assertTrue(sessions.isActive(id, userId), "cosmetic/no-op edits preserve sessions")
        val results = coroutineScope {
            (1..2).map { async { TestUsers.service.updatePassword(userId, hashPassword("new-password", cost = 4), 0) } }
                .awaitAll()
        }
        assertEquals(listOf(0, 1), results.sorted())
        assertEquals(1L, TestUsers.service.read(userId)!!.authVersion)
        assertFalse(sessions.isActive(id, userId))
        assertFalse(sessions.renew(id, userId, 0, now + 60_000))
        assertFalse(sessions.renew(id, userId, 1, now + 60_000))
        assertFalse(sessions.create(UUID.randomUUID().toString(), userId, 0, now + 60_000))
        assertTrue(sessions.create(UUID.randomUUID().toString(), userId, 1, now + 60_000))
        TestUsers.softDelete(userId)
        assertFalse(sessions.create(UUID.randomUUID().toString(), userId, 1, now + 60_000))
        assertFalse(sessions.create(UUID.randomUUID().toString(), 999999999u, 0, now + 60_000))
    }

    @Test
    fun `a service account can never register or renew a session, exactly like a deleted user`() =
        testApplication {
            usePostgresTestcontainer()
            val now = System.currentTimeMillis()
            val sessions = newAuthSessionService { now }
            val name = "svc-${UUID.randomUUID()}"
            val serviceAccountId = TestUsers.seedServiceAccount(name = name, email = "$name@toadie.invalid")

            // lockCurrentUser (V41's defense-in-depth predicate) refuses a service account
            // exactly like a soft-deleted or unknown user would — no session is ever minted,
            // so isActive/renew never see one to accept.
            assertFalse(sessions.create(UUID.randomUUID().toString(), serviceAccountId, 0, now + 60_000))
            assertFalse(sessions.renew(UUID.randomUUID().toString(), serviceAccountId, 0, now + 60_000))
        }

    @Test
    fun `renewal is refused once the session's user becomes a service account`() = testApplication {
        usePostgresTestcontainer()
        val now = System.currentTimeMillis()
        val sessions = newAuthSessionService { now }
        val userId = TestUsers.seed(uniqueEmail("session-svc-flip"), "pw", role = UserRole.USER)
        val id = UUID.randomUUID().toString()
        try {
            assertTrue(sessions.create(id, userId, 0, now + 60_000))
            assertTrue(sessions.renew(id, userId, 0, now + 120_000))
            // A raw flip past every guard (the CHECK only forbids ADMIN) — the live family must die
            // with it, because lockCurrentUser carries the V41 predicate on renewal too.
            TestUsers.forceServiceAccount(userId)
            assertFalse(sessions.renew(id, userId, 0, now + 180_000))
        } finally {
            TestUsers.softDelete(userId)
        }
    }

    @Test
    fun `concurrent renewal cannot resurrect a logged-out family`() = testApplication {
        usePostgresTestcontainer()
        val now = System.currentTimeMillis()
        val sessions = newAuthSessionService { now }
        val otherInstance = newAuthSessionService { now }
        val userId = TestUsers.seed(uniqueEmail("session-race"), "pw")
        repeat(5) {
            val id = UUID.randomUUID().toString()
            assertTrue(sessions.create(id, userId, 0, now + 60_000))
            coroutineScope {
                val renew = async { sessions.renew(id, userId, 0, now + 120_000) }
                val logout = async { otherInstance.revoke(id, userId) }
                renew.await()
                logout.await()
            }
            assertFalse(sessions.isActive(id, userId))
            assertFalse(sessions.renew(id, userId, 0, now + 120_000))
        }
    }
}
