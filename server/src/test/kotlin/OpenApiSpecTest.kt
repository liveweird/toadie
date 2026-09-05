package ch.nokillswit

import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Static sanity checks on the OpenAPI spec itself — no server or database boot. The runtime
 * conformance validation (every test-client /api/ interaction checked against the spec) lives in
 * [OpenApiConformance]; these tests pin the structural invariants that validation relies on.
 */
class OpenApiSpecTest {

    @Test
    fun `spec parses with no parser messages`() {
        val result = OpenAPIV3Parser().readContents(OpenApiSpec.rawYaml, null, ParseOptions().apply { isResolve = true })
        assertNotNull(result.openAPI, "spec failed to parse")
        assertEquals(emptyList(), result.messages ?: emptyList(), "spec should parse without warnings/errors")
    }

    @Test
    fun `the published spec honestly declares the dialect validated by our tools`() {
        assertEquals("openapi: 3.0.3", OpenApiSpec.rawYaml.lineSequence().first())
        assertEquals("3.0.3", OpenApiSpec.parsed.openapi)
    }

    @Test
    fun `published nullability accepts an unsynced file and rejects a wrong timestamp type`() {
        val request = SimpleRequest.Builder.get("/api/v1/files/1/sync").build()
        fun response(timestamp: String) = SimpleResponse.Builder.status(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""{"sourceUrl":null,"lastSyncedAt":$timestamp,"syncedDocument":null}""")
            .build()
        assertFalse(OpenApiSpec.validator.validate(request, response("0")).hasErrors())
        assertTrue(OpenApiSpec.validator.validate(request, response("\"invalid\"")).hasErrors())
    }

    /** A 3.1 upgrade must migrate the schemas and both consumers together, never just the label. */
    @Test
    fun `spec uses only 3_0-compatible constructs`() {
        val offenders = listOf(
            Regex("""type:\s*\["""),
            Regex("""\bconst:"""),
            Regex("""\bprefixItems:"""),
            Regex("""-\s+['"]?null['"]?\s*$""", RegexOption.MULTILINE),
            Regex("""\bpatternProperties:"""),
            Regex("""\bunevaluated(Properties|Items):"""),
            Regex("""\bjsonSchemaDialect:"""),
            Regex("""^webhooks:""", RegexOption.MULTILINE),
        ).mapNotNull { regex -> regex.find(OpenApiSpec.rawYaml)?.value }
        assertEquals(
            emptyList(), offenders,
            "documentation.yaml declares OpenAPI 3.0.3 — use the 3.0 idiom (e.g. nullable:) instead",
        )
    }

    @Test
    fun `every path is under api v1`() {
        val outside = OpenApiSpec.parsed.paths.keys.filterNot { it.startsWith("/api/v1/") }
        assertEquals(emptyList(), outside, "all API paths must live under /api/v1/")
    }

    @Test
    fun `every operation has a unique operationId`() {
        val ids = OpenApiSpec.parsed.paths.flatMap { (path, item) ->
            item.readOperationsMap().map { (method, op) -> Triple(path, method, op.operationId) }
        }
        val missing = ids.filter { it.third.isNullOrBlank() }.map { "${it.second} ${it.first}" }
        assertEquals(emptyList(), missing, "operations without an operationId")
        val duplicated = ids.groupBy { it.third }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet(), duplicated, "duplicated operationIds")
    }

    @Test
    fun `every operation declares a success status and a 500`() {
        val violations = OpenApiSpec.parsed.paths.flatMap { (path, item) ->
            item.readOperationsMap().mapNotNull { (method, op) ->
                val statuses = op.responses.keys
                val hasSuccess = statuses.any { it.startsWith("2") || it.startsWith("3") }
                when {
                    !hasSuccess -> "$method $path declares no 2xx/3xx response"
                    "500" !in statuses -> "$method $path declares no 500 response"
                    else -> null
                }
            }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `every secured operation declares a 401`() {
        val violations = OpenApiSpec.parsed.paths.flatMap { (path, item) ->
            item.readOperationsMap().mapNotNull { (method, op) ->
                // op.security == null → inherits the global bearerAuth; an explicit `security: []`
                // (login, refresh, password-reset) parses to an empty list and is exempt.
                val secured = op.security?.isNotEmpty() ?: true
                if (secured && "401" !in op.responses.keys) "$method $path is secured but declares no 401" else null
            }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `spec path templates are unambiguous for coverage resolution`() {
        // Every concrete path derivable from one template must not match another template of the
        // same shape — guard the most-literal-first resolution in OpenApiCoverage by asserting no
        // two templates collapse to the same normalized form.
        val normalized = OpenApiSpec.parsed.paths.keys.groupBy { it.replace(Regex("""\{[^}]+}"""), "{}") }
        val ambiguous = normalized.filterValues { it.size > 1 }.values.flatten()
        assertEquals(emptyList(), ambiguous, "path templates that differ only in parameter names")
    }
}
