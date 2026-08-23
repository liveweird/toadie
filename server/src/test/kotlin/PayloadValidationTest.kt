package ch.nokillswit

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Malformed input handling (plugins/ErrorHandling.kt): every rejection is a clean RFC 7807
 * 400 with fixed, client-safe vocabulary — never a 500, never internal class names.
 */
class PayloadValidationTest {

    @Test
    fun `malformed JSON is a 400 problem without internal class names`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().postJson("/api/v1/login", "{ not json")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertContains(body, "Request body is invalid")
        assertFalse("ch.nokillswit" in body, "internal class names must not leak into error bodies")
    }

    @Test
    fun `a body-less POST without a content type is a 400 problem`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().post("/api/v1/login")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "Request body is missing or not JSON")
    }

    @Test
    fun `a negative path id is rejected with 400 before routing`() = testApplication {
        usePostgresTestcontainer()
        // kotlinx UInt decoding would silently wrap /users/-1 to 4294967295 — the pre-routing
        // interceptor must reject it instead.
        val response = jsonClient().putJson("/api/v1/users/-1/password", """{"password":"whatever-works"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "non-negative")
    }

    @Test
    fun `a repeated scalar query parameter is a 400 problem`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("repeated")
        TestUsers.seed(email = email, password = "pw")
        val client = authedClient(email, "pw")
        // Repetition is reserved for per-endpoint documented IN semantics (API-LIST-004) —
        // silently first-winning would hide the caller's conflicting input.
        val paging = client.get("/api/v1/catalog-files?page=1&page=2")
        assertEquals(HttpStatusCode.BadRequest, paging.status)
        assertContains(paging.bodyAsText(), "Parameter 'page' must not be repeated")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/catalog-files?sort=id&sort=name").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/v1/catalog-files?name=a&name=b").status,
        )
    }

    @Test
    fun `a wrong method on an existing path is a 405 problem body`() = testApplication {
        usePostgresTestcontainer()
        // Deliberately the DEFAULT client: a wrong-method operation is outside the spec by
        // definition, and the conformance plugin would (correctly) flag it on jsonClient().
        val response = client.put("/api/v1/login")
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertContains(response.bodyAsText(), "Method not allowed")
    }
}
