package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.PasswordUpdateRequest
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every authenticated endpoint answers a uniform 401 to callers without a valid bearer. */
class AnonymousAccessTest {

    @Test
    fun `password change without a token is 401`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().putJson("/api/v1/users/1/password", PasswordUpdateRequest(password = "whatever-works"))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a forged token signed with the wrong secret is 401`() = testApplication {
        usePostgresTestcontainer()
        val forged = com.auth0.jwt.JWT.create()
            .withAudience("toadie-api")
            .withIssuer("http://0.0.0.0:8081/")
            .withClaim("email", "attacker@test")
            .withClaim("userId", 1L)
            .withArrayClaim("roles", arrayOf("ADMIN"))
            .withClaim("typ", "access")
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000))
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("not-the-server-secret"))

        val response = jsonClient().put("/api/v1/users/1/password") {
            header(HttpHeaders.Authorization, "Bearer $forged")
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "whatever-works"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a correctly signed access token without a jti is 401`() = testApplication {
        usePostgresTestcontainer()
        // Signed with the real dev secret but missing the jti — un-blocklistable, so the
        // verifier rejects it rather than skipping the revocation check.
        val jtiLess = com.auth0.jwt.JWT.create()
            .withAudience("toadie-api")
            .withIssuer("http://0.0.0.0:8081/")
            .withClaim("email", "nobody@test")
            .withClaim("userId", 1L)
            .withArrayClaim("roles", arrayOf<String>())
            .withClaim("typ", "access")
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000))
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("secret"))

        val response = jsonClient().put("/api/v1/users/1/password") {
            header(HttpHeaders.Authorization, "Bearer $jtiLess")
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "whatever-works"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    /**
     * Spec-driven sweep (2.13.1): parse the committed spec with the exact library
     * [OpenApiSpec] already loads it with (`OpenApiSpec.parsed`, the same object
     * [OpenApiConformance] validates every test-client interaction against), classify every
     * `/api/v1/` operation's EFFECTIVE security (global `security: [bearerAuth]` unless an
     * operation overrides it with an empty `security: []`), and assert the resulting PUBLIC
     * set is exactly the conscious allowlist below — adding a public operation to the spec
     * means editing this allowlist consciously, not growing it silently. Every remaining
     * (non-public) operation is then hit with NO Authorization header, no body, and its path
     * parameters substituted with a plausible literal, proving the JWT challenge answers a
     * uniform 401 problem detail before any body/query decoding runs — a body-less POST/PUT
     * must never leak a 400 ahead of the 401. [OpenApiConformance] (installed on [jsonClient])
     * additionally confirms 401 is declared on every operation it sees, so a probe answering
     * anything undeclared fails loudly on its own.
     */
    @Test
    fun `every non-public operation in the spec answers a uniform 401 with no bearer`() = testApplication {
        usePostgresTestcontainer()

        val operations = OpenApiSpec.parsed.paths
            .filterKeys { it.startsWith("/api/v1/") }
            .flatMap { (path, item) -> item.readOperationsMap().map { (method, op) -> Triple(method.name, path, op) } }

        val publicOperations = operations
            .filter { (_, _, op) -> op.security != null && op.security.isEmpty() }
            .map { (method, path, _) -> "$method $path" }
            .toSet()

        // The conscious public surface: everything else requires a bearer. `refresh` verifies
        // its own presented token internally, but the SPEC itself declares it public.
        val expectedPublic = setOf(
            "POST /api/v1/login",
            "POST /api/v1/login/mfa",
            "POST /api/v1/refresh",
            "POST /api/v1/password-reset",
            "POST /api/v1/password-reset/confirm",
        )
        assertEquals(expectedPublic, publicOperations, "the spec's public surface drifted from the conscious allowlist")

        val pathParamValues = mapOf("id" to "1", "dictionary" to "namespaces")
        fun concretePath(template: String): String =
            Regex("""\{([a-zA-Z]+)}""").replace(template) { match ->
                pathParamValues[match.groupValues[1]]
                    ?: error("no substitution value for path param {${match.groupValues[1]}} in $template")
            }

        val client = jsonClient()
        val findings = mutableListOf<String>()
        operations
            .filterNot { (method, path, _) -> "$method $path" in publicOperations }
            .forEach { (method, path, _) ->
                val response = client.request(concretePath(path)) { this.method = HttpMethod.parse(method) }
                if (response.status != HttpStatusCode.Unauthorized) {
                    findings += "$method $path -> ${response.status}: ${response.bodyAsText()}"
                    return@forEach
                }
                assertTrue(
                    response.headers[HttpHeaders.ContentType]?.startsWith("application/problem+json") == true,
                    "$method $path: expected application/problem+json, got ${response.headers[HttpHeaders.ContentType]}",
                )
                assertEquals(
                    "Missing or invalid bearer token",
                    response.body<ProblemDetail>().detail,
                    "$method $path: unexpected problem detail",
                )
            }
        assertTrue(findings.isEmpty(), "non-401 findings on non-public operations:\n${findings.joinToString("\n")}")
    }
}
