package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.infra.db.SEED_ADMIN_EMAIL
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Startup bootstrap (infra/db/Bootstrap.kt): ADMIN_INITIAL_PASSWORD rotates the V3 seed admin
 * away from the well-known "changeme", and outside development mode startup fails closed while
 * any active account still carries the seed password. Tests restore the shared container's
 * seed state afterwards (TestSeedState).
 */
class BootstrapTest {

    private fun assertProductionRejectsBootstrapPassword(password: String, messagePart: String) = testApplication {
        configureApp(
            "bootstrap.adminInitialPassword" to password,
            "jwt.secret" to "strong-${UUID.randomUUID()}",
            "mail.transport" to "disabled",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            assertStartupFails(messagePart) { startApplication() }
        }
    }

    @Test
    fun `ADMIN_INITIAL_PASSWORD rotates the seed admin so changeme stops working`() = testApplication {
        val newPassword = "rotated-${UUID.randomUUID()}"
        configureApp("bootstrap.adminInitialPassword" to newPassword)
        withSeedRestored {
            startApplication()
            val client = jsonClient()

            val withOld = client.login(SEED_ADMIN_EMAIL, "changeme")
            assertEquals(HttpStatusCode.Unauthorized, withOld.status)

            val withNew = client.login(SEED_ADMIN_EMAIL, newPassword)
            assertEquals(HttpStatusCode.OK, withNew.status)
            assertTrue(withNew.body<LoginResponse>().token.isNotBlank())
        }
    }

    @Test
    fun `rotation is idempotent - an admin-chosen password is never overwritten`() = testApplication {
        val chosen = "chosen-${UUID.randomUUID()}"
        // First boot rotates away from the seed hash…
        configureApp("bootstrap.adminInitialPassword" to chosen)
        withSeedRestored {
            startApplication()
            // …then simulate a later boot with a DIFFERENT initial password: the admin's password
            // no longer matches the seed hash, so nothing may change.
            val rotatedAgain = TestUsers.service.rotatePasswordIfHashMatches(
                email = SEED_ADMIN_EMAIL,
                expectedHash = ch.nokillswit.infra.db.SEED_PASSWORD_HASH,
                newHash = "never-applied",
            )
            assertEquals(0, rotatedAgain)

            val stillChosen = jsonClient().postJson("/api/v1/login", LoginRequest(SEED_ADMIN_EMAIL, chosen))
            assertEquals(HttpStatusCode.OK, stillChosen.status)
        }
    }

    @Test
    fun `production mode refuses to start while seed passwords are active`() = testApplication {
        // No ADMIN_INITIAL_PASSWORD; strong JWT secret so the failure is the seed check.
        configureApp(
            "jwt.secret" to "strong-${UUID.randomUUID()}",
            // The dev-default `log` mail transport is refused in production (see infra/mail).
            "mail.transport" to "disabled",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            assertStartupFails("seed password") { startApplication() }
        }
    }

    @Test
    fun `production mode boots once the admin is rotated`() = testApplication {
        val newPassword = "rotated-${UUID.randomUUID()}"
        configureApp(
            "bootstrap.adminInitialPassword" to newPassword,
            "jwt.secret" to "strong-${UUID.randomUUID()}",
            // The dev-default `log` mail transport is refused in production (see infra/mail).
            "mail.transport" to "disabled",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            startApplication() // must not throw: rotation happens before the fail-closed check
        }
    }

    @Test
    fun `production mode rejects the public seed password before hashing`() {
        assertProductionRejectsBootstrapPassword("changeme", "well-known seed or template")
    }

    @Test
    fun `production mode rejects the deployment template password before hashing`() {
        assertProductionRejectsBootstrapPassword("CHANGE-ME", "well-known seed or template")
    }

    @Test
    fun `production mode applies the normal minimum length to the bootstrap password`() {
        assertProductionRejectsBootstrapPassword("short", "at least 10 characters")
    }

    @Test
    fun `production mode applies the bcrypt UTF-8 byte ceiling to the bootstrap password`() {
        assertProductionRejectsBootstrapPassword("🐸".repeat(18), "at most 71 bytes in UTF-8")
    }
    @Test
    fun `production restart ignores obsolete invalid bootstrap values after rotation`() = runBlocking {
        withSeedRestored {
            var chosenHash: String? = null
            testApplication {
                configureApp("bootstrap.adminInitialPassword" to "chosen-${UUID.randomUUID()}")
                startApplication()
                chosenHash = TestUsers.service.findWithIdByEmail(SEED_ADMIN_EMAIL)?.second?.passwordHash
                assertTrue(!chosenHash.isNullOrEmpty())
            }
            for (obsolete in listOf("CHANGE-ME", "short", "🐸".repeat(18))) {
                testApplication {
                    configureApp(
                        "bootstrap.adminInitialPassword" to obsolete,
                        "jwt.secret" to "strong-${UUID.randomUUID()}",
                        "mail.transport" to "disabled",
                    )
                    serverConfig { developmentMode = false }
                    startApplication()
                    assertEquals(chosenHash, TestUsers.service.findWithIdByEmail(SEED_ADMIN_EMAIL)?.second?.passwordHash)
                }
            }
        }
    }

}
