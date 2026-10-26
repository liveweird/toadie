package ch.nokillswit

import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.conversion.CatalogDocumentsResponse
import ch.nokillswit.conversion.ConversionImportService
import ch.nokillswit.conversion.ConversionMapping
import ch.nokillswit.conversion.ConversionMappingRequest
import ch.nokillswit.conversion.ConversionRowStatus
import ch.nokillswit.conversion.EntityDocumentsRequest
import ch.nokillswit.conversion.EntityDocumentsResponse
import ch.nokillswit.entities.EntityImportRequest
import ch.nokillswit.entities.EntityImportResponse
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.infra.concurrency.ReadLedger
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `POST /api/v1/conversion-mappings/{id}/entity-documents` (conversion phase 4) end to end over the
 * real stack: a throwaway mapping over two throwaway blueprints plus the system `_team`, unique
 * identifiers per test. The pure engine is [PortImportTest]/[ImportCodecsTest]; the real sample is
 * [SampleConversionTest]. OpenAPI conformance rides every `authedClient` call.
 */
class ConversionImportRoutesTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"
    private val json = Json { ignoreUnknownKeys = true }

    private inner class World(val client: HttpClient, val admin: HttpClient) {
        val svc = unique("ci-svc")
        val db = unique("ci-db")
        val boss = unique("ci-boss")
        val kid = unique("ci-kid")
        val b1 = unique("ci-b1")
        val a1 = unique("ci-a1")
        val a2 = unique("ci-a2")
        var mappingId: UInt = 0u
        val entityIds = mutableMapOf<String, UInt>()
        val path get() = "/api/v1/conversion-mappings/$mappingId/entity-documents"

        private fun mappingDocument() = """
            {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
              {"blueprint": "$svc", "kind": "Component", "rules": [
                {"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "copy"},
                {"backstage": {"spec": "lifecycle"}, "codec": "constant", "value": "production"},
                {"port": {"meta": "title"}, "backstage": {"metadata": "title"}, "codec": "copy"},
                {"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"},
                {"port": {"property": "pii"}, "backstage": {"label": "gdpr"}, "codec": "valueMap", "map": {"true": "yes", "false": "no"}},
                {"port": {"relation": "uses"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}
              ], "lossy": {"properties": ["note"], "backstage": ["metadata.description", "spec.system", "spec.subcomponentOf", "spec.providesApis", "spec.consumesApis", "spec.dependencyOf"]}},
              {"blueprint": "$db", "kind": "Resource", "rules": [
                {"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "copy"},
                {"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"}
              ], "lossy": {"backstage": ["metadata.title", "metadata.description", "spec.system", "spec.dependsOn", "spec.dependencyOf"]}},
              {"blueprint": "_team", "kind": "Group", "rules": [
                {"backstage": {"spec": "type"}, "codec": "constant", "value": "team"},
                {"port": {"relation": "parent"}, "backstage": {"spec": "parent"}, "codec": "ref"},
                {"port": {"reverse": {"blueprint": "_team", "relation": "parent"}}, "backstage": {"spec": "children"}, "codec": "derive"}
              ], "lossy": {"backstage": ["metadata.title", "metadata.description", "spec.members", "profile.displayName", "profile.email", "profile.picture"]}}
            ]}
        """.trimIndent()

        private suspend fun post(path: String, body: String) {
            val response = admin.post(path) { contentType(ContentType.Application.Json); setBody(body) }
            assertTrue(response.status.value in 200..299, "POST $path: ${response.status} ${response.bodyAsText()}")
        }

        suspend fun create(): World {
            post(
                "/api/v1/blueprints",
                """{"identifier": "$db", "title": "DB", "schema": {"properties": {"type": {"type": "string", "enum": ["database", "cache"]}}},
                    "ownership": {"type": "Direct"}}""",
            )
            post(
                "/api/v1/blueprints",
                """{"identifier": "$svc", "title": "Svc", "schema": {"properties": {"type": {"type": "string", "enum": ["service", "website"]},
                      "pii": {"type": "boolean"}, "note": {"type": "string"}}},
                    "relations": {"uses": {"title": "Uses", "target": "$db", "required": false, "many": true}},
                    "ownership": {"type": "Direct"}}""",
            )
            entity("_team", boss, "Boss")
            entity("_team", kid, "Kid", relations = """"parent": "$boss"""")
            entity(db, b1, "Database one", team = boss, properties = """"type": "database"""")
            val alpha = """"type": "service", "pii": true, "note": "port-only""""
            entity(svc, a1, "Alpha", team = boss, properties = alpha, relations = """"uses": ["$b1"]""")
            entity(svc, a2, "Beta", team = kid, properties = """"type": "website", "pii": false""")
            val created = admin.postJson(
                "/api/v1/conversion-mappings",
                ConversionMappingRequest(unique("ci-map"), Json.parseToJsonElement(mappingDocument())),
            )
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            mappingId = created.body<ConversionMapping>().id
            return this
        }

        private suspend fun entity(
            blueprint: String,
            identifier: String,
            title: String,
            team: String? = null,
            properties: String = "",
            relations: String = "",
        ) {
            val response = admin.post("/api/v1/entities") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"blueprint": "$blueprint", "identifier": "$identifier", "title": "$title"
                       ${team?.let { ", \"team\": \"$it\"" }.orEmpty()}, "properties": {$properties}, "relations": {$relations}}""",
                )
            }
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            entityIds[identifier] = (json.parseToJsonElement(response.bodyAsText()) as JsonObject).getValue("id").toString().toUInt()
        }

        /** The Backstage documents of this world, exported through the phase-2 endpoint (without merge). */
        suspend fun exported(): List<CatalogFile> {
            val names = entityIds.keys
            val query = "merge=false&blueprint=$svc&blueprint=$db&blueprint=_team"
            val response = admin.get("/api/v1/conversion-mappings/$mappingId/catalog-documents?$query")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return response.body<CatalogDocumentsResponse>().files.filter { it.metadata.name in names }
        }

        suspend fun convert(files: List<CatalogFile>, merge: Boolean = true, who: HttpClient = client): EntityDocumentsResponse {
            val response = who.postJson(path, EntityDocumentsRequest(files, merge))
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return response.body()
        }

        suspend fun state(identifier: String): EntityResponse = admin.get("/api/v1/entities/${entityIds.getValue(identifier)}").body()

        suspend fun cleanup() {
            if (mappingId != 0u) TestConversionMappings.remove(mappingId)
            TestEntities.remove(a1, a2, b1, kid, boss)
            TestBlueprints.remove(svc, db)
        }
    }

    @Test
    fun `an export converts back to its entities, importing them changes nothing and a second conversion is identical`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ci-roundtrip", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            val files = world.exported()
            assertEquals(5, files.size)
            val before = world.entityIds.keys.associateWith { world.state(it) }

            val first = world.convert(files)
            assertEquals(5, first.documents.size)
            assertTrue(first.rows.all { it.status == ConversionRowStatus.CONVERTED }, first.rows.toString())
            assertTrue(first.rows.all { it.existingId == world.entityIds[it.identifier] }, "every row merged over its stored entity")
            // The Port-only `note` survives the merge; without merge it is not in the document.
            val a1Doc = first.documents.single { (it.getValue("identifier") as JsonPrimitive).content == world.a1 }
            assertEquals(JsonPrimitive("port-only"), (a1Doc.getValue("properties") as JsonObject)["note"])
            val bare = world.convert(files, merge = false)
            val bareDoc = bare.documents.single { (it.getValue("identifier") as JsonPrimitive).content == world.a1 }
            assertTrue("note" !in (bareDoc.getValue("properties") as JsonObject))

            val check = admin.postJson("/api/v1/entities/import/check", EntityImportRequest(first.documents, replaceExisting = true))
            assertEquals(HttpStatusCode.OK, check.status, check.bodyAsText())
            assertTrue(check.body<EntityImportResponse>().results.none { it.status == OntologyImportStatus.INVALID })
            val imported = admin.postJson("/api/v1/entities/import", EntityImportRequest(first.documents, replaceExisting = true))
            assertTrue(imported.body<EntityImportResponse>().results.all { it.status == OntologyImportStatus.UPDATED })

            world.entityIds.keys.forEach { id ->
                val after = world.state(id)
                val was = before.getValue(id)
                assertEquals(
                    listOf(was.title, was.team, was.properties, was.relations),
                    listOf(after.title, after.team, after.properties, after.relations),
                    "importing the merged conversion changes nothing of $id",
                )
            }
            assertEquals(first, world.convert(files), "a second conversion is identical")
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `any authenticated user may convert, an anonymous caller may not, and nothing is audited`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ci-auth-admin", UserRole.ADMIN)
        val user = seededClient("ci-auth-user")
        val world = World(user, admin).create()
        try {
            withAuditCapture { capture ->
                val result = world.convert(world.exported(), who = user)
                assertEquals(5, result.documents.size)
                assertEquals(HttpStatusCode.Unauthorized, jsonClient().postJson(world.path, EntityDocumentsRequest(emptyList())).status)
                assertTrue(capture.events.none { it.formattedMessage.contains("conversion") }, "a pure computation emits no audit event")
            }
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `an unknown mapping is 404, a malformed body or an over-cap batch is 400, and a stale mapping is 409`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ci-errors", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            assertEquals(
                HttpStatusCode.NotFound,
                admin.postJson("/api/v1/conversion-mappings/2000000000/entity-documents", EntityDocumentsRequest(emptyList())).status,
            )
            val malformed = admin.post(world.path) { contentType(ContentType.Application.Json); setBody("""{"files": "nope"}""") }
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            val file = CatalogFile(
                kind = "Group",
                metadata = CatalogFileMetadata(name = "g"),
                spec = EntitySpec(type = "team", children = emptyList()),
            )
            val tooMany = admin.postJson(world.path, EntityDocumentsRequest(List(201) { file }))
            assertEquals(HttpStatusCode.BadRequest, tooMany.status)
            // The cap is judged before the mapping is looked up: an over-cap body for an unknown mapping is a 400, not a 404.
            val unknownPath = "/api/v1/conversion-mappings/2000000000/entity-documents"
            val overCapUnknown = admin.postJson(unknownPath, EntityDocumentsRequest(List(201) { file }))
            assertEquals(HttpStatusCode.BadRequest, overCapUnknown.status)
            assertEquals(HttpStatusCode.OK, admin.postJson(world.path, EntityDocumentsRequest(List(200) { file })).status)

            val blueprints = admin.get("/api/v1/blueprints").body<ch.nokillswit.blueprints.BlueprintList>().items
            val dbBlueprint = blueprints.single { it.identifier == world.db }
            val dropped = admin.put("/api/v1/blueprints/${dbBlueprint.id}") {
                contentType(ContentType.Application.Json)
                setBody("""{"identifier": "${world.db}", "title": "DB", "schema": {"properties": {}}, "ownership": {"type": "Direct"}}""")
            }
            assertEquals(HttpStatusCode.NoContent, dropped.status, dropped.bodyAsText())
            val stale = admin.postJson(world.path, EntityDocumentsRequest(emptyList()))
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertTrue("blocking finding" in stale.body<ProblemDetail>().detail.orEmpty(), stale.bodyAsText())
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `the merge read is admitted through the read ledger and every reservation is released`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ci-ledger", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            val files = world.exported()
            val mappings = TestConversionMappings.service
            fun service(entityLedger: ReadLedger) =
                ConversionImportService(mappings, TestEntities.tunedService(readLedger = entityLedger), TestCatalogFiles.tunedService())
            val request = EntityDocumentsRequest(files)

            val tiny = ReadLedger(capacity = 1)
            assertFailsWith<BadRequestException> { service(tiny).entityDocumentsBody(world.mappingId, request) }
            assertEquals(0L, tiny.inFlightBytes)

            val capacity = 8L * 1024 * 1024
            val busy = ReadLedger(capacity = capacity)
            val other = busy.open().apply { reserve(capacity - 1) }
            try {
                assertFailsWith<TooManyRequestsException> { service(busy).entityDocumentsBody(world.mappingId, request) }
            } finally {
                other.close()
            }
            assertEquals(0L, busy.inFlightBytes)

            // Without merge no stored document is read, so even a 1-byte ledger answers.
            val bare = EntityDocumentsRequest(files, merge = false)
            val noMerge = service(ReadLedger(capacity = 1)).entityDocumentsBody(world.mappingId, bare)
            assertTrue(noMerge != null && noMerge.contains("documents"))
            val ledger = ReadLedger()
            assertTrue(service(ledger).entityDocumentsBody(world.mappingId, request) != null)
            assertEquals(0L, ledger.inFlightBytes, "released once the body is built")
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `the response carries no null member`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ci-nonull", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            val text = admin.postJson(world.path, EntityDocumentsRequest(world.exported())).bodyAsText()
            assertTrue("null" !in text, "no JSON null member on the wire: $text")
        } finally {
            world.cleanup()
        }
    }
}
