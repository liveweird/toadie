package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.conversion.ConversionMapping
import ch.nokillswit.conversion.ConversionMappingInvalidProblem
import ch.nokillswit.conversion.ConversionMappingList
import ch.nokillswit.conversion.ConversionMappingRequest
import ch.nokillswit.conversion.MAX_CONVERSION_MAPPINGS
import ch.nokillswit.conversion.MappingDriftCode
import ch.nokillswit.conversion.MappingFindingCode
import ch.nokillswit.entities.EntityErrorsReport
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `/api/v1/conversion-mappings` (V45): the ADMIN-curated mapping registry — guard before
 * receive, 404 before 400 on PUT, findings-bearing 400s, case-insensitive name uniqueness, the
 * cap and size limits, soft delete, audit parity, and findings/drift RECOMPUTED on every read
 * (including the Errors report). The pure validator has its own suite ([MappingValidationTest])
 * and the real default mapping is proven by [SampleBlueprintsTest].
 */
class ConversionMappingTest {

    private val minimal: JsonElement = Json.parseToJsonElement(
        """{"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": []}""",
    )

    private fun uniqueName() = "cm-${UUID.randomUUID().toString().take(8)}"

    private suspend fun HttpClient.createMapping(name: String = uniqueName(), document: JsonElement = minimal): ConversionMapping {
        val response = postJson("/api/v1/conversion-mappings", ConversionMappingRequest(name, document))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.allMappings(): List<ConversionMapping> =
        get("/api/v1/conversion-mappings").body<ConversionMappingList>().items

    @Test
    fun `an admin stores, reads, replaces and deletes a mapping - and a deleted name is reusable`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cm-crud", UserRole.ADMIN)
        val name = uniqueName()
        var createdId: UInt? = null
        try {
            val response = admin.postJson("/api/v1/conversion-mappings", ConversionMappingRequest(name, minimal))
            assertEquals(HttpStatusCode.Created, response.status)
            val created = response.body<ConversionMapping>()
            createdId = created.id
            assertEquals("/api/v1/conversion-mappings/${created.id}", response.headers[HttpHeaders.Location])
            assertEquals(name, created.name)
            assertEquals(minimal, created.document)
            assertEquals(emptyList(), created.findings)
            assertEquals(emptyList(), created.drift)

            assertTrue(admin.allMappings().any { it.id == created.id })
            assertEquals(created.document, admin.get("/api/v1/conversion-mappings/${created.id}").body<ConversionMapping>().document)

            val renamed = "$name-renamed"
            val put = admin.putJson("/api/v1/conversion-mappings/${created.id}", ConversionMappingRequest(renamed, minimal))
            assertEquals(HttpStatusCode.NoContent, put.status)
            assertEquals(renamed, admin.get("/api/v1/conversion-mappings/${created.id}").body<ConversionMapping>().name)

            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/conversion-mappings/${created.id}").status)
            assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/conversion-mappings/${created.id}").status)
            assertFalse(admin.allMappings().any { it.id == created.id })
            assertTrue(TestConversionMappings.rawRows().single { it.id == created.id }.markedAsDeleted, "soft delete keeps the row")
            assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/conversion-mappings/${created.id}").status)

            // The renamed row freed its (new) name, and the original name was freed by the rename.
            val again = admin.createMapping(renamed)
            TestConversionMappings.remove(again.id)
        } finally {
            createdId?.let { TestConversionMappings.remove(it) }
        }
    }

    @Test
    fun `the document is stored as compact JSON - duplicate keys collapse to the last, and the stored text is byte-stable`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("cm-bytes", UserRole.ADMIN)
            val raw = """{ "name": "${uniqueName()}", "document": {
                "schemaVersion": 7, "schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1",   "pairs":  [ ] } }"""
            val response = admin.post("/api/v1/conversion-mappings") {
                contentType(ContentType.Application.Json)
                setBody(raw)
            }
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val created = response.body<ConversionMapping>()
            try {
                // kotlinx keeps the LAST of a duplicated key (it cannot report duplicates), so the 7 never existed.
                assertEquals(minimal, created.document)
                val stored = TestConversionMappings.rawDocument(created.id)
                assertEquals("""{"schemaVersion":1,"apiVersion":"backstage.io/v1alpha1","pairs":[]}""", stored)
                // Replacing with the document just read back stores the identical bytes again.
                admin.putJson("/api/v1/conversion-mappings/${created.id}", ConversionMappingRequest(created.name, created.document))
                assertEquals(stored, TestConversionMappings.rawDocument(created.id))
            } finally {
                TestConversionMappings.remove(created.id)
            }
        }

    @Test
    fun `every response is free of null members`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cm-nonull", UserRole.ADMIN)
        val created = admin.createMapping()
        try {
            val bodies = listOf(
                admin.get("/api/v1/conversion-mappings").bodyAsText(),
                admin.get("/api/v1/conversion-mappings/${created.id}").bodyAsText(),
                admin.postJson(
                    "/api/v1/conversion-mappings",
                    ConversionMappingRequest(uniqueName(), Json.parseToJsonElement("""{"schemaVersion": 2}""")),
                ).bodyAsText(),
            )
            bodies.forEach { assertFalse(Regex(""":\s*null\b""").containsMatchIn(it), "no null member on the wire: $it") }
        } finally {
            TestConversionMappings.remove(created.id)
        }
    }

    @Test
    fun `a non-admin reads but every mutation is 403 - before the body is read and before the id is looked up`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cm-guard-admin", UserRole.ADMIN)
        val user = seededClient("cm-guard-user", UserRole.USER)
        val created = admin.createMapping()
        try {
            assertEquals(HttpStatusCode.OK, user.get("/api/v1/conversion-mappings").status)
            assertEquals(HttpStatusCode.OK, user.get("/api/v1/conversion-mappings/${created.id}").status)

            // A garbage body would be a 400 for an admin; the guard answers first.
            val garbage = user.post("/api/v1/conversion-mappings") {
                contentType(ContentType.Application.Json)
                setBody("{not json")
            }
            assertEquals(HttpStatusCode.Forbidden, garbage.status)
            assertEquals(
                HttpStatusCode.Forbidden,
                user.postJson("/api/v1/conversion-mappings", ConversionMappingRequest(uniqueName(), minimal)).status,
            )
            // An unknown id is 403 too, not 404 — a non-admin probe learns nothing about existence.
            assertEquals(
                HttpStatusCode.Forbidden,
                user.putJson("/api/v1/conversion-mappings/999999", ConversionMappingRequest("", minimal)).status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                user.putJson("/api/v1/conversion-mappings/${created.id}", ConversionMappingRequest(uniqueName(), minimal)).status,
            )
            assertEquals(HttpStatusCode.Forbidden, user.delete("/api/v1/conversion-mappings/999999").status)
            assertEquals(HttpStatusCode.Forbidden, user.delete("/api/v1/conversion-mappings/${created.id}").status)
            assertEquals(HttpStatusCode.OK, admin.get("/api/v1/conversion-mappings/${created.id}").status, "the row is untouched")
        } finally {
            TestConversionMappings.remove(created.id)
        }
    }

    @Test
    fun `a document with blocking findings is a 400 carrying every finding, and nothing is stored`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cm-400", UserRole.ADMIN)
        val before = TestConversionMappings.rawRows().size
        val document = Json.parseToJsonElement(
            """{"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
                 {"blueprint": "no-such-blueprint", "kind": "Component", "rules": []},
                 {"blueprint": "no-such-either", "kind": "Widget", "rules": []}]}""",
        )
        val response = admin.postJson("/api/v1/conversion-mappings", ConversionMappingRequest(uniqueName(), document))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/problem+json"))
        val problem = response.body<ConversionMappingInvalidProblem>()
        assertEquals(400, problem.status)
        val codes = problem.findings.map { it.code to it.path }
        assertTrue(MappingFindingCode.UNKNOWN_BLUEPRINT to "/pairs/0/blueprint" in codes, "$codes")
        assertTrue(MappingFindingCode.UNKNOWN_BLUEPRINT to "/pairs/1/blueprint" in codes, "$codes")
        assertTrue(MappingFindingCode.UNKNOWN_KIND to "/pairs/1/kind" in codes, "$codes")
        assertEquals(before, TestConversionMappings.rawRows().size, "a refused write stores nothing")
    }

    @Test
    fun `a document that is not an object and a malformed body are 400s`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cm-shape", UserRole.ADMIN)
        val notObject = admin.postJson(
            "/api/v1/conversion-mappings",
            ConversionMappingRequest(uniqueName(), Json.parseToJsonElement("[1, 2]")),
        )
        assertEquals(HttpStatusCode.BadRequest, notObject.status)
        assertEquals("", notObject.body<ConversionMappingInvalidProblem>().findings.single().path)
        // jsonClient is conformance-checked, so the genuinely malformed body goes through a bare request body.
        val malformed = admin.post("/api/v1/conversion-mappings") {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertNotNull(malformed.body<ProblemDetail>().detail)
    }

    @Test
    fun `PUT answers 404 before 400 - an unknown id wins over an invalid name and an invalid document`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cm-put", UserRole.ADMIN)
        val badDocument = Json.parseToJsonElement("""{"schemaVersion": 42}""")
        assertEquals(
            HttpStatusCode.NotFound,
            admin.putJson("/api/v1/conversion-mappings/999999", ConversionMappingRequest("   ", badDocument)).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            admin.putJson("/api/v1/conversion-mappings/999999", ConversionMappingRequest("bad\u0007name", minimal)).status,
        )
        val created = admin.createMapping()
        try {
            val bad = admin.putJson("/api/v1/conversion-mappings/${created.id}", ConversionMappingRequest(created.name, badDocument))
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertTrue(bad.body<ConversionMappingInvalidProblem>().findings.isNotEmpty())
            val unchanged = admin.get("/api/v1/conversion-mappings/${created.id}").body<ConversionMapping>()
            assertEquals(created.document, unchanged.document, "a refused replace changes nothing")
            // A soft-deleted id is 404 as well.
            admin.delete("/api/v1/conversion-mappings/${created.id}")
            assertEquals(
                HttpStatusCode.NotFound,
                admin.putJson("/api/v1/conversion-mappings/${created.id}", ConversionMappingRequest(uniqueName(), badDocument)).status,
            )
        } finally {
            TestConversionMappings.remove(created.id)
        }
    }

    @Test
    fun `names are unique case-insensitively among active mappings and validated`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cm-name", UserRole.ADMIN)
        val first = admin.createMapping("CM-Name-${UUID.randomUUID().toString().take(6)}")
        val second = admin.createMapping()
        try {
            val clash = admin.postJson("/api/v1/conversion-mappings", ConversionMappingRequest(first.name.lowercase(), minimal))
            assertEquals(HttpStatusCode.Conflict, clash.status)
            assertEquals("A conversion mapping with this name already exists", clash.body<ProblemDetail>().detail)
            assertEquals(
                HttpStatusCode.Conflict,
                admin.putJson("/api/v1/conversion-mappings/${second.id}", ConversionMappingRequest(first.name.uppercase(), minimal)).status,
            )
            // Replacing a mapping with its own name is not a clash.
            assertEquals(
                HttpStatusCode.NoContent,
                admin.putJson("/api/v1/conversion-mappings/${first.id}", ConversionMappingRequest(first.name, minimal)).status,
            )

            for (invalid in listOf("   ", "x".repeat(101), "tab\there")) {
                assertEquals(
                    HttpStatusCode.BadRequest,
                    admin.postJson("/api/v1/conversion-mappings", ConversionMappingRequest(invalid, minimal)).status,
                    "name '${invalid.take(12)}'",
                )
            }
        } finally {
            TestConversionMappings.remove(first.id, second.id)
        }
    }

    @Test
    fun `a document over 256 KiB is refused before it is judged`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cm-size", UserRole.ADMIN)
        // Structurally fine, semantically junk — the size gate must answer first with a plain 400.
        val rule = """{"backstage": {"spec": "type"}, "codec": "constant", "value": "${"x".repeat(1900)}"}"""
        val rules = List(150) { rule }.joinToString(",")
        val document = Json.parseToJsonElement(
            """{"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1",
                "pairs": [{"blueprint": "a", "kind": "Component", "rules": [$rules]}]}""",
        )
        val response = admin.postJson("/api/v1/conversion-mappings", ConversionMappingRequest(uniqueName(), document))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("document must be at most 256 KiB", response.body<ProblemDetail>().detail)
    }

    @Test
    fun `the registry is capped at 20 active mappings`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cm-cap", UserRole.ADMIN)
        // Fill only the free slots with this test's own rows (a leftover active row elsewhere just means fewer to create).
        val free = MAX_CONVERSION_MAPPINGS - TestConversionMappings.rawRows().count { !it.markedAsDeleted }
        val ids = mutableListOf<UInt>()
        try {
            repeat(free) { ids += admin.createMapping().id }
            val over = admin.postJson("/api/v1/conversion-mappings", ConversionMappingRequest(uniqueName(), minimal))
            assertEquals(HttpStatusCode.BadRequest, over.status)
            assertEquals("The conversion mapping registry is full ($MAX_CONVERSION_MAPPINGS mappings)", over.body<ProblemDetail>().detail)
            // Deleting one frees a slot.
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/conversion-mappings/${ids.first()}").status)
            ids += admin.createMapping().id
        } finally {
            TestConversionMappings.remove(*ids.toUIntArray())
        }
    }

    @Test
    fun `mutations emit audit events carrying the acting admin, the mapping id, its name and pair count`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("cm-audit")
        val userId = TestUsers.seed(email = email, password = "pw", role = UserRole.ADMIN)
        val admin = authedClient(email, "pw")
        val name = uniqueName()
        var id: UInt? = null
        try {
            withAuditCapture { capture ->
                val created = admin.createMapping(name)
                id = created.id
                admin.putJson("/api/v1/conversion-mappings/${created.id}", ConversionMappingRequest("$name-v2", minimal))
                admin.delete("/api/v1/conversion-mappings/${created.id}")
                for (event in listOf("conversion_mapping.created", "conversion_mapping.updated", "conversion_mapping.deleted")) {
                    val logged = capture.awaitEvent { it.message == event && it.hasKeyValue("mappingId", created.id.toLong()) }
                    assertNotNull(logged, "expected a $event audit event")
                    assertTrue(logged.hasKeyValue("byUserId", userId.toLong()))
                }
                val updated = capture.awaitEvent {
                    it.message == "conversion_mapping.updated" && it.hasKeyValue("mappingId", created.id.toLong())
                }
                assertNotNull(updated)
                assertTrue(updated.hasKeyValue("name", "$name-v2"), "the audited name is the stored one")
                assertTrue(updated.hasKeyValue("pairs", 0))
            }
        } finally {
            id?.let { TestConversionMappings.remove(it) }
        }
    }

    @Test
    fun `findings and drift are recomputed on every read, and the Errors report lists stale mappings`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cm-stale", UserRole.ADMIN)
        val blueprint = "cm-bp-${UUID.randomUUID().toString().take(8)}"
        val missingLabel = "cm-label-${UUID.randomUUID().toString().take(8)}"
        val document = Json.parseToJsonElement(
            """{"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
                 {"blueprint": "$blueprint", "kind": "Resource", "rules": [
                   {"port": {"property": "flavour"}, "backstage": {"spec": "type"}, "codec": "copy"},
                   {"port": {"property": "grade"}, "backstage": {"label": "$missingLabel"}, "codec": "copy"},
                   {"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"}],
                  "lossy": {"backstage": ["metadata.title", "metadata.description", "spec.system",
                                          "spec.dependsOn", "spec.dependencyOf"]}},
                 {"blueprint": "_team", "kind": "Group",
                  "rules": [{"backstage": {"spec": "type"}, "codec": "constant", "value": "team"}],
                  "lossy": {"relations": ["parent"], "backstage": ["metadata.title", "metadata.description", "spec.parent",
                                          "spec.children", "spec.members", "profile.displayName", "profile.email",
                                          "profile.picture"]}}]}""",
        )
        var mappingId: UInt? = null
        try {
            val created = admin.postJson(
                "/api/v1/blueprints",
                BlueprintRequest(
                    identifier = blueprint,
                    title = "CM probe",
                    schema = BlueprintSchema(
                        properties = mapOf(
                            "flavour" to PropertyDefinition(type = "string"),
                            "grade" to PropertyDefinition(type = "string"),
                        ),
                    ),
                    ownership = OwnershipDefinition(type = "Direct"),
                ),
            ).body<BlueprintResponse>()
            val mapping = admin.createMapping(document = document)
            mappingId = mapping.id
            assertEquals(emptyList(), mapping.findings)
            assertEquals(
                setOf(MappingDriftCode.REGISTRY_KEY_MISSING, MappingDriftCode.VOCABULARY_DRIFT),
                mapping.drift.map { it.code }.toSet(),
                "the unregistered label and the one-value Group type are drift, not blockers",
            )

            // The Errors report lists it (drift only), and counts it as checked.
            val report = admin.get("/api/v1/entities/errors").body<EntityErrorsReport>()
            val row = report.mappings.single { it.id == mapping.id }
            assertEquals(emptyList(), row.findings)
            assertEquals(mapping.drift, row.drift)
            assertTrue(report.checkedMappings >= 1)

            // Deleting the blueprint afterwards makes the STORED document stale: findings are recomputed.
            TestBlueprints.remove(created.identifier)
            val stale = admin.get("/api/v1/conversion-mappings/${mapping.id}").body<ConversionMapping>()
            assertTrue(
                stale.findings.any { it.code == MappingFindingCode.UNKNOWN_BLUEPRINT && it.path == "/pairs/0/blueprint" },
                "${stale.findings}",
            )
            assertEquals(mapping.document, stale.document, "the stored document is never rewritten behind the admin's back")
            assertTrue(admin.allMappings().single { it.id == mapping.id }.findings.isNotEmpty())
            val staleRow = admin.get("/api/v1/entities/errors").body<EntityErrorsReport>().mappings.single { it.id == mapping.id }
            assertTrue(staleRow.findings.any { it.code == MappingFindingCode.UNKNOWN_BLUEPRINT })
        } finally {
            mappingId?.let { TestConversionMappings.remove(it) }
            TestBlueprints.remove(blueprint)
        }
    }

    @Test
    fun `the Errors report omits a clean mapping and the registry is quiet for an empty document`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cm-clean", UserRole.ADMIN)
        val created = admin.createMapping()
        try {
            val report = admin.get("/api/v1/entities/errors").body<EntityErrorsReport>()
            assertTrue(report.mappings.none { it.id == created.id })
            assertTrue(report.checkedMappings >= 1)
        } finally {
            TestConversionMappings.remove(created.id)
        }
    }
}
