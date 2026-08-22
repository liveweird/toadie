package ch.nokillswit

import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The JWT-secret fail-closed check (plugins/Security.kt): a blank, placeholder, or
 * repo-committed (burned) secret is tolerated in development but refuses to start in
 * production mode. The check runs before Flyway/Bootstrap, so no seed handling is needed.
 */
class SecurityConfigTest {

    private fun assertRefusedInProduction(vararg overrides: Pair<String, String>) = testApplication {
        configureApp(*overrides)
        serverConfig { developmentMode = false }
        val failure = runCatching { startApplication() }.exceptionOrNull()
        assertNotNull(failure, "startup must fail closed on a burned/placeholder JWT secret")
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue("JWT secret" in messages, "unexpected startup failure: $messages")
    }

    @Test
    fun `production mode refuses the placeholder secret`() {
        // application.yaml's default is the literal "secret" — burned by definition.
        assertRefusedInProduction()
    }

    @Test
    fun `production mode refuses a blank secret`() {
        assertRefusedInProduction("jwt.secret" to "")
    }

    @Test
    fun `production mode refuses the committed docker-compose demo key`() {
        assertRefusedInProduction(
            "jwt.secret" to "dev-only-00366d050fa2c920f7efb9a880b8b9c60e693b1797e782a33ddbfb51f88ea9d0",
        )
    }

    @Test
    fun `production mode refuses the k8s secret template placeholder`() {
        assertRefusedInProduction("jwt.secret" to "CHANGE-ME-openssl-rand-hex-32")
    }

    @Test
    fun `development mode tolerates the placeholder secret`() = testApplication {
        usePostgresTestcontainer() // boots with the "secret" default — no throw expected
    }
}
