package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.ENUM_COLORS
import ch.nokillswit.blueprints.OBJECT_FORMATS
import ch.nokillswit.blueprints.PROPERTY_TYPES
import ch.nokillswit.blueprints.SPEC_VALUES
import ch.nokillswit.blueprints.STRING_FORMATS
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loads the numbered JSON files under `sample-data/blueprints` (the browsable Port-style set, v1.23.1) through the
 * real API in dependency order, proving every file is a valid `POST /api/v1/blueprints` body
 * under the EXACT validator ([ch.nokillswit.blueprints.validateBlueprintRequest] +
 * [ch.nokillswit.blueprints.validateProperty]) and that the stored/returned document round
 * trips byte-for-structure through [blueprintJson]. A second pass pins the "showcase" union
 * over the whole set — property types, string/object formats, specs, the 14 enum colours, both
 * ownership types, a self-relation, a `many` relation, a `required` relation, and enough
 * aggregations to cover both `calculationBy` modes — so the set can never silently lose a
 * feature it claims to demonstrate. The set is executable documentation for `sample-data/`.
 *
 * Test cwd is `server/` (the Gradle test task's default working directory), so the fixture
 * files are read via `../sample-data/blueprints`. The set's identifiers (`team`, `domain`, …)
 * are plain, but the shared Testcontainers database is fine: this test removes every one of
 * them in `finally`, and no other test mints those exact identifiers.
 */
class SampleBlueprintsTest {

    private fun blueprintFiles(): List<File> =
        File("../sample-data/blueprints").listFiles { f -> f.name.matches(Regex("[0-9]{2}-.*\\.json")) }
            ?.sortedBy { it.name }
            ?: error("sample-data/blueprints not found relative to the test working directory")

    /** [text] decoded as a request and re-encoded via [blueprintJson] — the canonical, defaults-expanded form. */
    private fun canonicalForm(text: String): kotlinx.serialization.json.JsonElement =
        Json.parseToJsonElement(blueprintJson.encodeToString(blueprintJson.decodeFromString<BlueprintRequest>(text)))

    /** The definitional fields of a stored [BlueprintResponse], reshaped back into a [BlueprintRequest]. */
    private fun BlueprintResponse.asRequest() = BlueprintRequest(
        identifier = identifier,
        title = title,
        description = description,
        icon = icon,
        schema = schema,
        relations = relations,
        mirrorProperties = mirrorProperties,
        calculationProperties = calculationProperties,
        aggregationProperties = aggregationProperties,
        ownership = ownership,
    )

    private suspend fun HttpClient.postRaw(text: String): HttpResponse =
        post("/api/v1/blueprints") {
            contentType(ContentType.Application.Json)
            setBody(text)
        }

    @Test
    fun `the sample set loads in order and round-trips through the API`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpsample", UserRole.ADMIN)
        val files = blueprintFiles()
        assertEquals(8, files.size, "expected the eight numbered sample files")

        val identifiers = mutableListOf<String>()
        val requests = mutableListOf<BlueprintRequest>()
        try {
            files.forEach { file ->
                val text = file.readText()
                val request = blueprintJson.decodeFromString<BlueprintRequest>(text)
                requests += request
                identifiers += request.identifier

                val create = admin.postRaw(text)
                assertEquals(HttpStatusCode.Created, create.status, "POST ${file.name}: ${create.bodyAsText()}")
                val created = create.body<BlueprintResponse>()
                assertEquals(request.identifier, created.identifier, "${file.name} identifier mismatch")

                val get = admin.get("/api/v1/blueprints/${created.id}")
                assertEquals(HttpStatusCode.OK, get.status)
                val reread = get.body<BlueprintResponse>()

                assertEquals(
                    canonicalForm(text),
                    Json.parseToJsonElement(blueprintJson.encodeToString(reread.asRequest())),
                    "${file.name} did not round-trip byte-for-structure through blueprintJson",
                )
            }

            assertShowcaseCoverage(requests)
        } finally {
            TestBlueprints.remove(*identifiers.toTypedArray())
        }
    }

    /** Pins the union of Port features the set claims to demonstrate (`sample-data/README.md`'s table). */
    private fun assertShowcaseCoverage(requests: List<BlueprintRequest>) {
        val properties = requests.flatMap { it.schema.properties.values }

        val types = properties.map { it.type }.toSet()
        assertEquals(PROPERTY_TYPES, types, "every property type must appear at least once")

        val stringFormats = properties.filter { it.type == "string" }.mapNotNull { it.format }.toSet()
        assertEquals(STRING_FORMATS, stringFormats, "every string format must appear at least once")

        val objectFormats = properties.filter { it.type == "object" }.mapNotNull { it.format }.toSet()
        assertEquals(OBJECT_FORMATS, objectFormats, "the object labeled-url format must appear")

        val specs = properties.mapNotNull { it.spec }.toSet()
        assertEquals(SPEC_VALUES, specs, "every spec value must appear at least once")
        assertTrue(properties.any { it.specAuthentication != null }, "specAuthentication must be used on the embedded-url property")

        val colours = properties.flatMap { it.enumColors?.values.orEmpty() } +
            requests.flatMap { it.calculationProperties.values }.flatMap { it.colors?.values.orEmpty() }
        assertEquals(ENUM_COLORS, colours.toSet(), "all 14 enum colours must appear at least once")

        val ownershipTypes = requests.mapNotNull { it.ownership?.type }.toSet()
        assertEquals(setOf("Direct", "Inherited"), ownershipTypes, "both ownership types must appear")

        val relations = requests.flatMap { req -> req.relations.values.map { req.identifier to it } }
        assertTrue(relations.any { (owner, rel) -> rel.target == owner }, "a self-relation must appear (service.depends_on)")
        assertTrue(relations.any { (_, rel) -> rel.many }, "a many relation must appear")
        assertTrue(relations.any { (_, rel) -> rel.required }, "a required relation must appear")

        val aggregations = requests.flatMap { it.aggregationProperties.values }
        assertTrue(aggregations.size >= 5, "at least 5 aggregations must appear across the set")
        val calculationByModes = aggregations.map { it.calculationSpec.calculationBy }.toSet()
        assertEquals(setOf("entities", "property"), calculationByModes, "both calculationBy modes must appear")
    }
}
