package ch.nokillswit

import ch.nokillswit.auth.PASSWORD_RESET_EMAIL_SUBJECT
import ch.nokillswit.auth.passwordResetEmailBody
import ch.nokillswit.auth.resetAppUrl
import ch.nokillswit.infra.mail.LocalizedText
import kotlin.test.*

class LocalizedEmailTest {
    @Test
    fun `localized reset links preserve the account until confirmation`() {
        val text = LocalizedText(en = "EN", pl = "PL")
        assertEquals("EN", text.of("en"))
        assertEquals("PL", text.of("pl"))
        assertEquals("EN", text.of("xx"))
        val token = "a".repeat(43)
        val en = passwordResetEmailBody("Alice", token, "https://toadie.example", 900, "en")
        assertTrue("Hi Alice," in en)
        assertTrue("Your password is unchanged" in en)
        assertTrue("minutes): 15" in en)
        assertTrue("https://toadie.example/reset-password/confirm#token=$token" in en)
        val pl = passwordResetEmailBody("Alice", token, "https://toadie.example", 901, "pl")
        assertTrue("Cześć Alice," in pl)
        assertTrue("pozostaje bez zmian" in pl)
        assertTrue("minuty): 16" in pl)
        assertFalse("Hi Alice" in pl)
        assertEquals("Reset your Toadie password", PASSWORD_RESET_EMAIL_SUBJECT.of("en"))
        assertEquals("Zresetuj hasło Toadie", PASSWORD_RESET_EMAIL_SUBJECT.of("pl"))
    }

    @Test
    fun `reset origins are configured and HTTPS outside development`() {
        for (value in listOf(null, "", " ", "https://x/".repeat(300), "not a url", "//x", "file:///tmp/x",
            "https://user:pass@x", "https://x/?redirect=bad", "https://x/#bad", "https://x:65536", "https:///path",
            "https://x/subpath")) {
            assertNull(resetAppUrl(value, true), value)
        }
        assertNull(resetAppUrl("http://localhost:8081", false))
        assertEquals("http://localhost:8081", resetAppUrl("http://localhost:8081/", true))
        assertEquals("https://toadie.example", resetAppUrl("https://toadie.example/", false))
    }
}
