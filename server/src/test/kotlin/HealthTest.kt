package ch.nokillswit

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Kubernetes health endpoints (plugins/Health.kt). Both are unauthenticated and outside
 * `/api/`, so the OpenApiConformance plugin skips them and `jsonClient()` is fine. Tests run in
 * development mode, so there is no HTTPS redirect to satisfy.
 */
class HealthTest {

    @Test
    fun `healthz reports liveness`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `readyz reports ready when the database answers`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().get("/readyz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }
}
