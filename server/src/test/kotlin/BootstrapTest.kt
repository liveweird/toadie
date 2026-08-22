package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.infra.db.SEED_ADMIN_EMAIL
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Startup bootstrap (infra/db/Bootstrap.kt): ADMIN_INITIAL_PASSWORD rotates the V3 seed admin
 * away from the well-known "changeme", and outside development mode startup fails closed while
 * any active account still carries the seed password. Tests restore the shared container's
 * seed state afterwards (TestSeedState).
 */
class BootstrapTest {

    @Test
    fun `ADMIN_INITIAL_PASSWORD rotates the seed admin so changeme stops working`() = testApplication {
        val newPassword = "rotated-${UUID.randomUUID()}"
        configureApp("bootstrap.adminInitialPassword" to newPassword)
        try {
            startApplication()
            val client = jsonClient()

            val withOld = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(SEED_ADMIN_EMAIL, "changeme"))
            }
            assertEquals(HttpStatusCode.Unauthorized, withOld.status)

            val withNew = client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(SEED_ADMIN_EMAIL, newPassword))
            }
            assertEquals(HttpStatusCode.OK, withNew.status)
            assertTrue(withNew.body<LoginResponse>().token.isNotBlank())
        } finally {
            TestSeedState.restoreSeedAccounts()
        }
    }

    @Test
    fun `rotation is idempotent - an admin-chosen password is never overwritten`() = testApplication {
        val chosen = "chosen-${UUID.randomUUID()}"
        // First boot rotates away from the seed hash…
        configureApp("bootstrap.adminInitialPassword" to chosen)
        try {
            startApplication()
            // …then simulate a later boot with a DIFFERENT initial password: the admin's password
            // no longer matches the seed hash, so nothing may change.
            val rotatedAgain = TestUsers.service.rotatePasswordIfHashMatches(
                email = SEED_ADMIN_EMAIL,
                expectedHash = ch.nokillswit.infra.db.SEED_PASSWORD_HASH,
                newHash = "never-applied",
            )
            assertEquals(0, rotatedAgain)

            val stillChosen = jsonClient().post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(SEED_ADMIN_EMAIL, chosen))
            }
            assertEquals(HttpStatusCode.OK, stillChosen.status)
        } finally {
            TestSeedState.restoreSeedAccounts()
        }
    }

    @Test
    fun `production mode refuses to start while seed passwords are active`() = testApplication {
        // No ADMIN_INITIAL_PASSWORD; strong JWT secret so the failure is the seed check.
        configureApp("jwt.secret" to "strong-${UUID.randomUUID()}")
        serverConfig { developmentMode = false }
        try {
            val failure = runCatching { startApplication() }.exceptionOrNull()
            assertNotNull(failure, "startup must fail closed while seed passwords are active")
            val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("seed password" in messages, "unexpected startup failure: $messages")
        } finally {
            TestSeedState.restoreSeedAccounts()
        }
    }

    @Test
    fun `production mode boots once the admin is rotated`() = testApplication {
        val newPassword = "rotated-${UUID.randomUUID()}"
        configureApp(
            "bootstrap.adminInitialPassword" to newPassword,
            "jwt.secret" to "strong-${UUID.randomUUID()}",
        )
        serverConfig { developmentMode = false }
        try {
            startApplication() // must not throw: rotation happens before the fail-closed check
        } finally {
            TestSeedState.restoreSeedAccounts()
        }
    }
}
