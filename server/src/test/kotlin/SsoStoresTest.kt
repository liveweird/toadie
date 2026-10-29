package ch.nokillswit

import ch.nokillswit.auth.sso.MAX_SSO_HANDOFF_CODE_LENGTH
import ch.nokillswit.auth.sso.SsoAccountRefusedException
import ch.nokillswit.auth.sso.SsoAccounts
import ch.nokillswit.auth.sso.SsoHandoffs
import ch.nokillswit.auth.sso.SsoOutcome
import ch.nokillswit.auth.sso.SsoSeenStates
import ch.nokillswit.auth.sso.SsoStateCookie
import ch.nokillswit.auth.sso.VerifiedIdToken
import ch.nokillswit.auth.sso.ssoCookieName
import ch.nokillswit.users.SsoLinkVerdict
import ch.nokillswit.users.SsoLookup
import ch.nokillswit.users.User
import ch.nokillswit.users.ssoLinkVerdict
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Pure units (no server, no database) of the SSO attempt cookie, the hand-off store and the account naming rules. */
class SsoStoresTest {

    // ---- the attempt cookie ------------------------------------------------------------------------------------------------

    @Test
    fun `a sealed attempt opens until it expires, and carries random secrets`() {
        val clock = MovableClock()
        val cookie = SsoStateCookie("a-secret", clock)
        val attempt = cookie.newAttempt()
        val opened = assertNotNull(cookie.open(cookie.seal(attempt)))
        assertEquals(attempt.state, opened.state)
        assertEquals(attempt.nonce, opened.nonce)
        assertEquals(attempt.verifier, opened.verifier)
        assertEquals(attempt.bind, opened.bind)
        assertEquals(setOf(attempt.state, attempt.nonce, attempt.verifier, attempt.bind).size, 4)
        assertNotEquals(attempt.state, cookie.newAttempt().state)
        clock.advanceSeconds(599)
        assertNotNull(cookie.open(cookie.seal(attempt)))
        clock.advanceSeconds(2)
        assertNull(cookie.open(cookie.seal(attempt)))
    }

    @Test
    fun `a cookie only opens under the secret that sealed it and every malformed value opens as null`() {
        val cookie = SsoStateCookie("a-secret")
        val sealed = cookie.seal(cookie.newAttempt())
        assertNull(SsoStateCookie("another-secret").open(sealed))
        assertNull(SsoStateCookie("").open(sealed))
        assertNotNull(SsoStateCookie("").run { open(seal(newAttempt())) }, "an empty secret still works (development) without throwing")
        listOf(null, "", "x", "a.b.c", ".", "a.", ".b", "!!!.@@@", sealed + "x", sealed.substringBefore('.') + ".", "x".repeat(5000))
            .forEach { assertNull(cookie.open(it), "value: ${it?.take(20)}") }
        // A validly SIGNED payload that is not an attempt (the key derivation is internal, so forge by re-signing is impossible):
        // the signature check alone rejects any edit of the payload.
        val (payload, signature) = sealed.split('.')
        val edited = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray())
        assertNull(cookie.open("$edited.$signature"))
        assertNotNull(cookie.open("$payload.$signature"))
    }

    // ---- the hand-off store -----------------------------------------------------------------------------------------------

    @Test
    fun `a hand-off is single-use, expires, and keeps its binding`() {
        var now = 1_000L
        val handoffs = SsoHandoffs(ttlMillis = 60_000, clock = { now })
        val bind = byteArrayOf(1, 2, 3)
        val code = handoffs.issue(7u, 3, bind, SsoOutcome.LINKED)
        assertEquals(MAX_SSO_HANDOFF_CODE_LENGTH, code.length, "the exchange's length bound is exactly the issued length")
        val got = assertNotNull(handoffs.consume(code))
        assertEquals(7u, got.userId)
        assertEquals(3L, got.authVersion)
        assertEquals(SsoOutcome.LINKED, got.outcome)
        assertTrue(got.bindHash.contentEquals(bind))
        assertNull(handoffs.consume(code), "consumed once")
        assertNull(handoffs.consume("unknown"))

        val late = handoffs.issue(8u, 0, bind, SsoOutcome.EXISTING)
        now += 60_001
        assertNull(handoffs.consume(late), "expired")
        assertNotEquals(handoffs.issue(8u, 0, bind, SsoOutcome.CREATED), handoffs.issue(8u, 0, bind, SsoOutcome.CREATED))
    }

    @Test
    fun `admission is capped, and expiry frees the space`() {
        var now = 0L
        val handoffs = SsoHandoffs(ttlMillis = 1_000, maxTracked = 2, clock = { now })
        handoffs.issue(1u, 0, byteArrayOf(), SsoOutcome.CREATED)
        val second = handoffs.issue(2u, 0, byteArrayOf(), SsoOutcome.CREATED)
        assertFailsWith<SsoHandoffs.CapacityExceededException> { handoffs.issue(3u, 0, byteArrayOf(), SsoOutcome.CREATED) }
        assertNotNull(handoffs.consume(second), "existing hand-offs stay usable at capacity")
        handoffs.issue(3u, 0, byteArrayOf(), SsoOutcome.CREATED)
        now += 1_001
        handoffs.issue(4u, 0, byteArrayOf(), SsoOutcome.CREATED)
        handoffs.issue(5u, 0, byteArrayOf(), SsoOutcome.CREATED)
    }

    // ---- single-use state ----------------------------------------------------------------------------------------------------

    @Test
    fun `a state is accepted once, forgotten when its cookie expires, and the set is bounded by evicting the oldest`() {
        var now = 0L
        val seen = SsoSeenStates(maxTracked = 3, clock = { now })
        assertTrue(seen.firstUse("a", 600))
        assertFalse(seen.firstUse("a", 600), "a repeat is refused")
        assertTrue(seen.firstUse("b", 601))
        assertTrue(seen.firstUse("c", 602))
        // Full: the next distinct state evicts the oldest ("a") instead of failing; eviction never refuses a login.
        assertTrue(seen.firstUse("d", 603))
        assertTrue(seen.firstUse("a", 604), "the evicted entry is the oldest")
        assertFalse(seen.firstUse("d", 603))
        now = 700
        assertTrue(seen.firstUse("d", 1300), "every entry expired with its cookie")
    }

    @Test
    fun `an expired state queued behind a live one is swept, so the live one is not evicted and cannot replay`() {
        var now = 0L
        val seen = SsoSeenStates(maxTracked = 2, clock = { now })
        assertTrue(seen.firstUse("a", 1_000))
        assertTrue(seen.firstUse("b", 100))
        now = 200
        assertTrue(seen.firstUse("c", 1_000))
        assertFalse(seen.firstUse("a", 1_000), "the live state is still remembered")
    }

    @Test
    fun `the cookie is host-prefixed in production only`() {
        assertEquals("__Host-toadie_sso", ssoCookieName(production = true))
        assertEquals("toadie_sso", ssoCookieName(production = false))
    }

    // ---- the email-matched account -------------------------------------------------------------------------------------------

    @Test
    fun `a same-email account already holding the incoming identity is that account, any other link is a conflict`() {
        fun verdict(storedIssuer: String?, storedSubject: String?, role: String = "USER", mfa: Boolean = false) =
            ssoLinkVerdict(storedIssuer, storedSubject, role, mfa, "https://idp.test", "sub-1")
        // The concurrent first sign-in (two browsers) that committed between the identity lookup and the email lock.
        assertEquals(SsoLinkVerdict.SAME_IDENTITY, verdict("https://idp.test", "sub-1"))
        assertEquals(SsoLinkVerdict.SAME_IDENTITY, verdict("https://idp.test", "sub-1", role = "ADMIN", mfa = true))
        assertEquals(SsoLinkVerdict.SUBJECT_CONFLICT, verdict("https://idp.test", "sub-2"))
        assertEquals(SsoLinkVerdict.SUBJECT_CONFLICT, verdict("https://other.test", "sub-1"))
        assertEquals(SsoLinkVerdict.REFUSED, verdict(null, null, role = "ADMIN"))
        assertEquals(SsoLinkVerdict.REFUSED, verdict(null, null, mfa = true))
        assertEquals(SsoLinkVerdict.LINK, verdict(null, null))
    }

    // ---- account creation rules --------------------------------------------------------------------------------------------

    private fun accounts(created: MutableList<User>, creator: suspend (User) -> UInt = { created += it; 42u }) =
        SsoAccounts({ _, _, _ -> SsoLookup.Unknown }, creator)

    private fun resolve(accounts: SsoAccounts, email: String = "ada.lovelace@example.test", name: String?) =
        runBlocking { accounts.resolve("https://idp.test", VerifiedIdToken("sub-1", email, name)) }

    @Test
    fun `a created account is a passwordless USER named from the claim, or the email local part`() {
        val created = mutableListOf<User>()
        val accounts = accounts(created)
        val resolved = resolve(accounts, name = "  Ada Lovelace  ")
        assertEquals(42u, resolved.userId)
        assertEquals(SsoOutcome.CREATED, resolved.outcome)
        with(created.single()) {
            assertEquals("Ada Lovelace", name)
            assertNull(passwordHash)
            assertEquals("https://idp.test", ssoIssuer)
            assertEquals("sub-1", ssoSubject)
            assertEquals("ada.lovelace@example.test", email)
        }
        listOf(null, "", "   ", "Bad\u0000Name", "Tab\there").forEach { resolve(accounts, name = it) }
        assertEquals(List(5) { "ada.lovelace" }, created.drop(1).map { it.name })
        resolve(accounts, name = "N".repeat(80))
        assertEquals(50, created.last().name.length)
        resolve(accounts, email = "${"l".repeat(80)}@example.test", name = null)
        assertEquals(50, created.last().name.length)
    }

    @Test
    fun `a reserved address or a rejected account is a refusal, and each lookup verdict maps to its audit reason`() {
        val reserved = "integration-client-1@toadie.invalid"
        val refusal = assertFailsWith<SsoAccountRefusedException> { resolve(accounts(mutableListOf()), email = reserved, name = null) }
        assertEquals("domain_not_allowed", refusal.reason)
        val invalid = assertFailsWith<SsoAccountRefusedException> {
            resolve(accounts(mutableListOf()) { throw BadRequestException("Name must not be blank") }, name = null)
        }
        assertEquals("account_invalid", invalid.reason)
        mapOf(
            SsoLookup.Deleted(7u) to "account_deleted",
            SsoLookup.LinkRefused to "link_refused",
            SsoLookup.SubjectConflict to "subject_conflict",
        ).forEach { (verdict, reason) ->
            val failure = assertFailsWith<SsoAccountRefusedException> {
                resolve(SsoAccounts({ _, _, _ -> verdict }) { error("must not create") }, name = null)
            }
            assertEquals(reason, failure.reason)
            // Only the deleted-account refusal names its row (the id an admin releases with DELETE /users/{id}/sso).
            assertEquals(if (verdict is SsoLookup.Deleted) 7u else null, failure.userId)
        }
        // The hand-off's version is the one the lookup returned, never a later read.
        resolve(SsoAccounts({ _, _, _ -> SsoLookup.Existing(1u, 5L) }) { error("no") }, name = null).let {
            assertEquals(SsoOutcome.EXISTING, it.outcome)
            assertEquals(5L, it.authVersion)
        }
        resolve(SsoAccounts({ _, _, _ -> SsoLookup.Linked(1u, 6L) }) { error("no") }, name = null).let {
            assertEquals(SsoOutcome.LINKED, it.outcome)
            assertEquals(6L, it.authVersion)
        }
    }
}
