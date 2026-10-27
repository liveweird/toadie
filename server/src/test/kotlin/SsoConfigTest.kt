package ch.nokillswit

import ch.nokillswit.auth.sso.LoginOptionsResponse
import ch.nokillswit.auth.sso.SsoConfig
import ch.nokillswit.auth.sso.SsoConfigException
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SSO configuration and the flag: what `sso.*` must contain before the app agrees to start, what a flag-off deployment
 * exposes, and the construction-site pin for the operator-endpoint client. Rules: `.claude/docs/security.md` "SSO login (OIDC)"
 * and "Operator-configured outbound HTTP".
 */
class SsoConfigTest {

    private val base = arrayOf(
        "sso.enabled" to "true",
        "sso.issuer" to "https://tenant.okta.com/oauth2/default",
        "sso.clientId" to "client-1",
        "sso.clientSecret" to "super-secret-value",
    )

    private fun config(
        vararg overrides: Pair<String, String>,
        development: Boolean = true,
        mailAppUrl: String? = "http://localhost:8081",
    ): SsoConfig? = SsoConfig.fromConfig(MapApplicationConfig(*base, *overrides), development, mailAppUrl)

    private fun refused(
        vararg overrides: Pair<String, String>,
        development: Boolean = true,
        mailAppUrl: String? = "http://localhost:8081",
    ): String = assertFailsWith<SsoConfigException> {
        config(*overrides, development = development, mailAppUrl = mailAppUrl)
    }.message.orEmpty()

    // ---- the configuration -------------------------------------------------------------------------------------------------

    @Test
    fun `the flag off reads nothing, however incomplete the rest is`() {
        assertNull(SsoConfig.fromConfig(MapApplicationConfig(), development = false, mailAppUrl = null))
        assertNull(SsoConfig.fromConfig(MapApplicationConfig("sso.enabled" to "false", "sso.issuer" to "not a url"), false, null))
        assertNull(SsoConfig.fromConfig(MapApplicationConfig("sso.enabled" to "", "sso.clientSecret" to ""), false, null))
    }

    @Test
    fun `a minimal enabled configuration takes the documented defaults and derives the redirect URI from MAIL_APP_URL`() {
        val sso = assertNotNull(config(mailAppUrl = "http://localhost:8081/"))
        assertEquals("Okta", sso.label)
        assertEquals("openid email profile", sso.scopes)
        assertEquals("http://localhost:8081/api/v1/login/sso/callback", sso.redirectUri)
        assertTrue(sso.allowedEmailDomains.isEmpty())
        assertTrue(sso.allowHttp)
        assertEquals("https://tenant.okta.com/oauth2/default", sso.issuer)
    }

    @Test
    fun `an explicit redirect URI, scopes, label and domains are honoured and normalised`() {
        val sso = assertNotNull(
            config(
                "sso.redirectUri" to "https://toadie.example.com/api/v1/login/sso/callback",
                "sso.scopes" to "  openid   profile email groups ",
                "sso.label" to " Acme ID ",
                "sso.allowedEmailDomains" to " @Example.com, ,corp.example.com ",
                development = false,
            ),
        )
        assertEquals("https://toadie.example.com/api/v1/login/sso/callback", sso.redirectUri)
        assertEquals("openid profile email groups", sso.scopes)
        assertEquals("Acme ID", sso.label)
        assertEquals(setOf("example.com", "corp.example.com"), sso.allowedEmailDomains)
        assertTrue(sso.isEmailAllowed("a@example.com") && sso.isEmailAllowed("a@corp.example.com"))
        assertFalse(sso.isEmailAllowed("a@evil.com") || sso.isEmailAllowed("a@sub.example.com") || sso.isEmailAllowed("noat"))
        assertTrue(assertNotNull(config()).isEmailAllowed("anyone@anywhere.test"))
    }

    @Test
    fun `every missing required value refuses the boot, naming the property and never the secret`() {
        assertTrue("sso.issuer" in refused("sso.issuer" to " "))
        assertTrue("sso.clientId" in refused("sso.clientId" to ""))
        val noSecret = refused("sso.clientSecret" to "  ")
        assertTrue("sso.clientSecret" in noSecret)
        // Neither a missing redirect source nor an unusable MAIL_APP_URL lets the app guess one.
        assertTrue("sso.redirectUri" in refused(mailAppUrl = null))
        assertTrue("sso.redirectUri" in refused(mailAppUrl = "not a url"))
        assertTrue("sso.redirectUri" in refused(mailAppUrl = "http://toadie.example.com", development = false))
    }

    @Test
    fun `production requires an https issuer and an https redirect URI, development allows http`() {
        val httpsOrigin = "https://toadie.example.com"
        val httpIssuer = "sso.issuer" to "http://tenant.okta.com/oauth2/default"
        assertTrue("https" in refused(httpIssuer, development = false, mailAppUrl = httpsOrigin))
        assertTrue(
            "https" in refused(
                "sso.redirectUri" to "http://toadie.example.com/api/v1/login/sso/callback",
                development = false,
                mailAppUrl = "https://toadie.example.com",
            ),
        )
        val production = assertNotNull(config(development = false, mailAppUrl = "https://toadie.example.com"))
        assertEquals("https://toadie.example.com/api/v1/login/sso/callback", production.redirectUri)
        assertFalse(production.allowHttp)
        assertNotNull(config("sso.issuer" to "http://127.0.0.1:9000/realms/x", development = true))
        assertTrue("https" in refused("sso.issuer" to "ftp://tenant.okta.com/x"))
    }

    @Test
    fun `an issuer or redirect URI with credentials, a query or a fragment, or a redirect off the callback path, is refused`() {
        refused("sso.issuer" to "https://user:pw@tenant.okta.com/x")
        refused("sso.issuer" to "https://tenant.okta.com/x?a=b")
        refused("sso.issuer" to "https://tenant.okta.com/x#f")
        refused("sso.issuer" to "https:///nohost")
        refused("sso.issuer" to "https://tenant.okta.com:99999/x")
        refused("sso.issuer" to "https://bad host/x")
        refused("sso.redirectUri" to "https://toadie.example.com/api/v1/login/sso/callback?x=1")
        assertTrue(SSO_CALLBACK_HINT in refused("sso.redirectUri" to "https://toadie.example.com/elsewhere"))
        // The path must EQUAL the callback path: no proxy prefix (the cookie is Path=/ and the Location root-relative), no trailing slash.
        assertTrue(SSO_CALLBACK_HINT in refused("sso.redirectUri" to "https://toadie.example.com/toadie/api/v1/login/sso/callback"))
        refused("sso.redirectUri" to "https://toadie.example.com/api/v1/login/sso/callback/")
        refused("sso.redirectUri" to "https://toadie.example.com/api/v1/login/sso/callback/extra")
        assertNotNull(config("sso.redirectUri" to "https://toadie.example.com/api/v1/login/sso/callback"))
    }

    @Test
    fun `an issuer over the 512 character column is refused, one at the limit is not`() {
        val prefix = "https://tenant.okta.com/"
        assertTrue("512" in refused("sso.issuer" to prefix + "x".repeat(513 - prefix.length)))
        assertNotNull(config("sso.issuer" to prefix + "x".repeat(512 - prefix.length)))
    }

    @Test
    fun `the flag is read like every other boolean flag and the client secret is trimmed like its siblings`() {
        assertNotNull(config("sso.enabled" to "TRUE"))
        assertNotNull(config("sso.enabled" to "True"))
        assertNull(SsoConfig.fromConfig(MapApplicationConfig("sso.enabled" to "yes", "sso.clientSecret" to ""), false, null))
        assertEquals("padded", assertNotNull(config("sso.clientSecret" to "  padded \n")).clientSecret)
    }

    @Test
    fun `a blank allowed-domain list is a boot warning, a configured one is not`() {
        val open = assertNotNull(config()).bootWarnings()
        assertEquals(1, open.size)
        assertTrue("allowedEmailDomains" in open.single())
        assertFalse("super-secret-value" in open.single())
        assertTrue(assertNotNull(config("sso.allowedEmailDomains" to "example.com")).bootWarnings().isEmpty())
    }

    @Test
    fun `scopes must be well-formed tokens including openid, domains must look like domains, the label must be plain`() {
        refused("sso.scopes" to "email profile")
        refused("sso.scopes" to "openid \"quoted\"")
        refused("sso.scopes" to "openid back\\slash")
        refused("sso.allowedEmailDomains" to "example.com,not a domain")
        refused("sso.allowedEmailDomains" to "-bad.example")
        refused("sso.label" to "x".repeat(51))
        refused("sso.label" to "Line\nBreak")
    }

    @Test
    fun `the client secret never appears in the configuration's string form`() {
        val sso = assertNotNull(config())
        assertFalse("super-secret-value" in sso.toString())
        assertEquals("SsoConfig(label=Okta, issuerHost=tenant.okta.com)", sso.toString())
    }

    // ---- the flag off ------------------------------------------------------------------------------------------------------

    @Test
    fun `with the flag off the three flow routes do not exist and options answers sso null`() = testApplication {
        usePostgresTestcontainer()
        val options = jsonClient().get("/api/v1/login/options")
        assertEquals(HttpStatusCode.OK, options.status)
        assertNull(options.body<LoginOptionsResponse>().sso)
        // Plain client: the spec declares these operations, so the conformance client would rightly flag the 404.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/login/sso/start").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/login/sso/callback?state=x").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/login/sso/exchange").status)
    }

    @Test
    fun `an enabled flag with an incomplete configuration refuses to start`() = testApplication {
        configureApp("sso.enabled" to "true", "sso.clientId" to "c", "sso.clientSecret" to "s")
        assertStartupFails("sso.issuer") { startApplication() }
    }

    @Test
    fun `an enabled flag in production refuses an http issuer at boot`() = testApplication {
        configureApp(
            "sso.enabled" to "true",
            "sso.issuer" to "http://tenant.example.com/oauth2/default",
            "sso.clientId" to "c",
            "sso.clientSecret" to "s",
            "sso.redirectUri" to "https://toadie.example.com/api/v1/login/sso/callback",
            "bootstrap.adminInitialPassword" to "rotated-${java.util.UUID.randomUUID()}",
            "jwt.secret" to "strong-${java.util.UUID.randomUUID()}",
            "mail.transport" to "disabled",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            assertStartupFails("sso.issuer") { startApplication() }
        }
    }

    // ---- the construction-site pin -----------------------------------------------------------------------------------------

    @Test
    fun `OperatorHttp is constructed only under auth sso and in its own file`() {
        val root = Path.of("src/main/kotlin")
        val construction = Regex("""\bOperatorHttp\s*\(""")
        val sites = Files.walk(root).use { stream ->
            stream.filter { it.name.endsWith(".kt") }
                .filter { path -> path.readText().lineSequence().filterNot(::isComment).any { construction.containsMatchIn(it) } }
                .map { root.relativize(it).invariantSeparatorsPathString }
                .toList()
        }
        val stray = sites.filterNot { it == "infra/fetch/OperatorHttp.kt" || it.startsWith("auth/sso/") }
        assertEquals(emptyList(), stray, "OperatorHttp may be constructed only under auth/sso/ (security.md)")
        assertTrue(sites.any { it.startsWith("auth/sso/") }, "the SSO wiring itself constructs the client")
    }

    /** A KDoc or line comment line: prose that names the class is not a construction. */
    private fun isComment(line: String): Boolean = line.trimStart().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }

    private companion object {
        const val SSO_CALLBACK_HINT = "/api/v1/login/sso/callback"
    }
}
