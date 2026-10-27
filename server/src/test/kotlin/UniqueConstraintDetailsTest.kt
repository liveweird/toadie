package ch.nokillswit

import ch.nokillswit.plugins.uniqueViolationDetail
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The central 23505 -> 409 wording (`plugins/ErrorHandling.kt`): Postgres names the violated unique
 * index in its message and the handler maps it to a user-facing detail. The indexes below are
 * practically unreachable through the API (a duplicated SHA-256 key hash, a service account reused
 * by a second client, a racing first SSO login), so the mapping is pinned directly, without a
 * database.
 */
class UniqueConstraintDetailsTest {
    private fun detailFor(index: String): String =
        R2dbcDataIntegrityViolationException(
            "duplicate key value violates unique constraint \"$index\"",
            "23505",
        ).uniqueViolationDetail()

    @Test
    fun `a duplicate SSO identity names the clash`() =
        assertEquals("This single sign-on identity is already linked to an account", detailFor("uq_users_sso_identity"))

    @Test
    fun `a duplicate integration key hash names the clash`() =
        assertEquals("An integration client with this key already exists", detailFor("uq_integration_clients_key_hash"))

    @Test
    fun `a service account reused by a second integration client names the clash`() =
        assertEquals(
            "This service account already belongs to an integration client",
            detailFor("uq_integration_clients_service_user"),
        )

    @Test
    fun `an existing mapping keeps its wording and an unmapped index falls back to the generic detail`() {
        assertEquals("A user with this email already exists", detailFor("uq_users_email_active"))
        assertEquals("Resource already exists", detailFor("uq_something_future"))
    }
}
