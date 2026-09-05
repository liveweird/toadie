package ch.nokillswit

import ch.nokillswit.auth.hashPassword
import ch.nokillswit.auth.verifyPassword
import ch.nokillswit.users.UserRole
import io.ktor.server.testing.testApplication
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class PasswordResetServiceTest {
    @Test
    fun `only digests are stored and exact expiry and delivery revocation reject grants`() = testApplication {
        usePostgresTestcontainer()
        var now = System.currentTimeMillis()
        val resets = newPasswordResetService(100) { now }
        val id = TestUsers.seed(uniqueEmail("reset-store"), "old-password")
        val token = assertNotNull(resets.issue(id, 0))
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val hashes = resetTokenHashes(id)
        assertEquals(1, hashes.size)
        assertTrue(hashes.single().matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(token, hashes.single())
        assertTrue(resets.isUsable(token))
        assertFalse(resets.isUsable(hashes.single()))
        assertFalse(resets.isUsable("a".repeat(43)))
        assertNull(resets.complete("malformed", "hash"))
        assertNull(resets.complete("a".repeat(43), "hash"))
        now += 100
        assertFalse(resets.isUsable(token))
        assertNull(resets.complete(token, "hash"))
        val fresh = assertNotNull(resets.issue(id, 0))
        assertEquals(1, resetTokenHashes(id).size, "issuance prunes expired grants")
        resets.revoke(fresh)
        assertFalse(resets.isUsable(fresh))
        assertTrue(resetTokenHashes(id).isEmpty())
        assertTrue(verifyPassword("old-password", TestUsers.service.read(id)!!.passwordHash))
        assertFailsWith<IllegalArgumentException> { newPasswordResetService(0) }
        assertFailsWith<IllegalArgumentException> { newPasswordResetService(3_600_001) }
    }

    @Test
    fun `confirmation is single use across instances and invalidates sibling links`() = testApplication {
        usePostgresTestcontainer()
        val first = newPasswordResetService()
        val second = newPasswordResetService()
        for (sameToken in listOf(true, false)) {
            val id = TestUsers.seed(uniqueEmail("reset-race"), "old-password")
            val tokenA = assertNotNull(first.issue(id, 0))
            val tokenB = if (sameToken) tokenA else assertNotNull(second.issue(id, 0))
            val hash = hashPassword("new-password", cost = 4)
            val outcomes = coroutineScope {
                listOf(async { first.complete(tokenA, hash) }, async { second.complete(tokenB, hash) }).awaitAll()
            }
            assertEquals(listOf(id), outcomes.filterNotNull())
            assertEquals(1, outcomes.count { it == null })
            assertFalse(second.isUsable(tokenA))
            assertFalse(first.isUsable(tokenB))
            assertNull(second.complete(tokenA, hash))
            assertEquals(1L, TestUsers.service.read(id)!!.authVersion)
            assertTrue(verifyPassword("new-password", TestUsers.service.read(id)!!.passwordHash))
        }
    }

    @Test
    fun `credential identity changes and deletion invalidate both issued and in-flight grants`() = testApplication {
        usePostgresTestcontainer()
        val resets = newPasswordResetService()
        val id = TestUsers.seed(uniqueEmail("reset-epoch"), "old-password")
        val passwordLink = assertNotNull(resets.issue(id, 0))
        TestUsers.service.updatePassword(id, hashPassword("admin-password", cost = 4))
        assertFalse(resets.isUsable(passwordLink))
        assertNull(resets.complete(passwordLink, "hash"))
        assertNull(resets.issue(id, 0))
        val emailLink = assertNotNull(resets.issue(id, 1))
        val newEmail = uniqueEmail("reset-renamed")
        TestUsers.service.updateGuarded(id, "Name", newEmail, UserRole.ADMIN)
        assertFalse(resets.isUsable(emailLink))
        val roleLink = assertNotNull(resets.issue(id, 2))
        TestUsers.service.updateGuarded(id, "Name", newEmail, UserRole.USER)
        assertFalse(resets.isUsable(roleLink))
        val deletedLink = assertNotNull(resets.issue(id, 3))
        TestUsers.softDelete(id)
        assertFalse(resets.isUsable(deletedLink))
        assertNull(resets.complete(deletedLink, "hash"))
        assertNull(resets.issue(id, 3))
        assertNull(resets.issue(999999999u, 0))
    }

    @Test
    fun `competing self-password write cannot overwrite a completed reset`() = testApplication {
        usePostgresTestcontainer()
        val resets = newPasswordResetService()
        val id = TestUsers.seed(uniqueEmail("reset-cas"), "old-password")
        val token = assertNotNull(resets.issue(id, 0))
        val hash = hashPassword("new-password", cost = 4)
        val outcomes = coroutineScope {
            val reset = async { resets.complete(token, hash) }
            val change = async { TestUsers.service.updatePassword(id, hash, 0) }
            (reset.await() != null) to (change.await() == 1)
        }
        assertNotEquals(outcomes.first, outcomes.second)
        assertEquals(1L, TestUsers.service.read(id)!!.authVersion)
    }
}
