package ch.nokillswit

import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.conversion.CatalogDocumentsResponse
import ch.nokillswit.conversion.ConversionExportService
import ch.nokillswit.conversion.ConversionMapping
import ch.nokillswit.conversion.ConversionMappingRequest
import ch.nokillswit.conversion.ConversionRowStatus
import ch.nokillswit.entities.EntityGraphFilter
import ch.nokillswit.infra.concurrency.ReadLedger
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
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
import java.net.URLEncoder
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/v1/conversion-mappings/{id}/catalog-documents` (conversion phase 2) end to end over the
 * real stack: a throwaway mapping over two throwaway blueprints (one Component pair, one Resource
 * pair) plus the system `_team` blueprint's base shape, with unique identifiers per test so the
 * shared workspace is never asserted absolutely. The pure engine is [BackstageExportTest]/[CodecsTest];
 * the real sample is [SampleConversionTest]. OpenAPI conformance rides every `authedClient` call.
 */
class ConversionRoutesTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"
    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private val json = Json { ignoreUnknownKeys = true }

    /** The throwaway world of one test: ids are unique, everything is removed in [cleanup]. */
    private inner class World(val client: HttpClient, val admin: HttpClient) {
        val svc = unique("cv-svc")
        val db = unique("cv-db")
        val boss = unique("cv-boss")
        val kid = unique("cv-kid")
        val b1 = unique("cv-b1")
        val a1 = unique("cv-a1")
        val a2 = unique("cv-a2")
        var mappingId: UInt = 0u
        val entityIds = mutableMapOf<String, UInt>()
        val files = mutableListOf<UInt>()

        fun mappingDocument(): String = """
            {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
              {"blueprint": "$svc", "kind": "Component", "rules": [
                {"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "copy"},
                {"backstage": {"spec": "lifecycle"}, "codec": "constant", "value": "production"},
                {"port": {"meta": "title"}, "backstage": {"metadata": "title"}, "codec": "copy"},
                {"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"},
                {"port": {"property": "pii"}, "backstage": {"label": "gdpr"}, "codec": "valueMap", "map": {"true": "yes", "false": "no"}},
                {"port": {"relation": "uses"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}
              ], "lossy": {"backstage": ["metadata.description", "spec.system", "spec.subcomponentOf", "spec.providesApis", "spec.consumesApis", "spec.dependencyOf"]}},
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

        private suspend fun post(path: String, body: String): HttpStatusCode =
            admin.post(path) { contentType(ContentType.Application.Json); setBody(body) }.also {
                assertTrue(it.status.value in 200..299, "POST $path: ${it.status} ${it.bodyAsText()}")
            }.status

        suspend fun create(): World {
            post(
                "/api/v1/blueprints",
                """{"identifier": "$db", "title": "DB", "schema": {"properties": {"type": {"type": "string", "enum": ["database", "cache"]}}},
                    "ownership": {"type": "Direct"}}""",
            )
            post(
                "/api/v1/blueprints",
                """{"identifier": "$svc", "title": "Svc", "schema": {"properties": {"type": {"type": "string", "enum": ["service", "website"]},
                      "pii": {"type": "boolean"}}},
                    "relations": {"uses": {"title": "Uses", "target": "$db", "required": false, "many": true}},
                    "ownership": {"type": "Direct"}}""",
            )
            entity("_team", boss, "Boss")
            entity("_team", kid, "Kid", relations = """"parent": "$boss"""")
            entity(db, b1, "Database one", team = boss, properties = """"type": "database"""")
            entity(svc, a1, "Alpha", team = boss, properties = """"type": "service", "pii": true""", relations = """"uses": ["$b1"]""")
            entity(svc, a2, "Beta", team = kid, properties = """"type": "website", "pii": false""")
            val request = ConversionMappingRequest(unique("cv-map"), Json.parseToJsonElement(mappingDocument()))
            val created = admin.postJson("/api/v1/conversion-mappings", request)
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
            val stored = json.parseToJsonElement(response.bodyAsText()) as kotlinx.serialization.json.JsonObject
            entityIds[identifier] = stored.getValue("id").toString().toUInt()
        }

        suspend fun documents(query: String = ""): CatalogDocumentsResponse {
            val response = client.get("/api/v1/conversion-mappings/$mappingId/catalog-documents$query")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return response.body()
        }

        suspend fun cleanup() {
            files.forEach { admin.delete("$CATALOG_FILES_PATH/$it") }
            if (mappingId != 0u) TestConversionMappings.remove(mappingId)
            TestEntities.remove(a1, a2, b1, kid, boss)
            TestBlueprints.remove(svc, db)
        }
    }

    /** The rows this test's entities produced, keyed by identifier (the workspace may hold other tests' entities too). */
    private fun CatalogDocumentsResponse.mine(world: World) =
        rows.filter { it.identifier in world.entityIds.keys }.associateBy { it.identifier }

    @Test
    fun `every selected entity converts, references resolve across blueprints and derive fills the group's children`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cv-all", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            val result = world.documents()
            val mine = result.mine(world)
            assertEquals(setOf(world.boss, world.kid, world.b1, world.a1, world.a2), mine.keys)
            assertTrue(mine.values.all { it.status == ConversionRowStatus.CONVERTED }, mine.values.toString())

            val a1 = result.files.single { it.metadata.name == world.a1 }
            assertEquals("Component", a1.kind)
            assertEquals(
                EntitySpec(
                    type = "service", lifecycle = "production", owner = "group:default/${world.boss}",
                    dependsOn = listOf("resource:default/${world.b1}"),
                ),
                a1.spec,
            )
            assertEquals(mapOf("gdpr" to "yes"), a1.metadata.labels)
            assertEquals("Alpha", a1.metadata.title)
            val boss = result.files.single { it.metadata.name == world.boss }
            assertEquals(listOf("group:default/${world.kid}"), boss.spec.children)
            assertEquals(emptyList(), result.files.single { it.metadata.name == world.kid }.spec.children)
            assertEquals("group:default/${world.boss}", result.files.single { it.metadata.name == world.kid }.spec.parent)
            assertEquals(world.entityIds.getValue(world.a1), mine.getValue(world.a1).entityId)
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `the blueprint, q, team, entityId and query filters narrow what is converted but never what resolves`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cv-filter", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            val byBlueprint = world.documents("?blueprint=${world.svc.enc()}").mine(world)
            assertEquals(setOf(world.a1, world.a2), byBlueprint.keys)
            // The owner team is not selected, yet the reference to it renders (context rows are always loaded).
            val owned = world.documents("?blueprint=${world.svc.enc()}")
            assertEquals("group:default/${world.boss}", owned.files.single { it.metadata.name == world.a1 }.spec.owner)

            assertEquals(setOf(world.a2), world.documents("?blueprint=${world.svc.enc()}&q=${world.a2.enc()}").mine(world).keys)
            // `team` keeps the named _team's own row as well as what it owns.
            assertEquals(setOf(world.kid, world.a2), world.documents("?team=${world.kid.enc()}").mine(world).keys)
            assertEquals(setOf(world.b1), world.documents("?entityId=${world.entityIds.getValue(world.b1)}").mine(world).keys)

            val query = "MATCH (a:`${world.svc}`)-[:uses]->(b:`${world.db}`) RETURN a"
            assertEquals(setOf(world.a1), world.documents("?query=${query.enc()}").mine(world).keys)
            // An unknown blueprint filter selects nothing (the graph's rule), it is not "no filter".
            assertEquals(emptyList(), world.documents("?blueprint=${unique("nope")}").rows)
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `a refused query and malformed parameters are 400s, and the unknown mapping is 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cv-400", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            val base = "/api/v1/conversion-mappings/${world.mappingId}/catalog-documents"
            assertEquals(HttpStatusCode.BadRequest, admin.get("$base?query=${"MATCH nonsense".enc()}").status)
            assertEquals(HttpStatusCode.BadRequest, admin.get("$base?entityId=abc").status)
            assertEquals(HttpStatusCode.BadRequest, admin.get("$base?entityId=0").status)
            assertEquals(HttpStatusCode.BadRequest, admin.get("$base?merge=maybe").status)
            assertEquals(HttpStatusCode.BadRequest, admin.get("$base?q=a&q=b").status)
            // 400 before 404: a malformed parameter is refused before the mapping is looked up.
            val unknown = "/api/v1/conversion-mappings/2000000000/catalog-documents"
            assertEquals(HttpStatusCode.BadRequest, admin.get("$unknown?entityId=abc").status)
            assertEquals(HttpStatusCode.BadRequest, admin.get("$unknown?merge=maybe").status)
            assertEquals(HttpStatusCode.BadRequest, admin.get("$base?query=${"x".repeat(2001)}").status)
            val missing = admin.get("/api/v1/conversion-mappings/2000000000/catalog-documents")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertNotNull(missing.body<ProblemDetail>().title)
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `any authenticated user may convert, an anonymous caller may not, and nothing is audited`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cv-auth-admin", UserRole.ADMIN)
        val user = seededClient("cv-auth-user")
        val world = World(user, admin).create()
        try {
            withAuditCapture { capture ->
                val result = world.documents("?blueprint=${world.svc.enc()}")
                assertEquals(setOf(world.a1, world.a2), result.mine(world).keys)
                val path = "/api/v1/conversion-mappings/${world.mappingId}/catalog-documents"
                assertEquals(HttpStatusCode.Unauthorized, jsonClient().get(path).status)
                val messages = capture.events.map { it.formattedMessage }
                assertTrue(messages.none { it.contains("conversion") }, "a pure read emits no audit event: $messages")
            }
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `merge preserves what the pair does not own and names the stored file, and merge=false does neither`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cv-merge", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            val foreignLabel = uniqueLabel("cv-foreign", listOf("v1"))
            // allowInvalid: the stored file's owner and template reference are deliberately unresolvable.
            val stored = admin.postJson("$CATALOG_FILES_PATH?allowInvalid=true",
                CatalogFile(
                    metadata = CatalogFileMetadata(
                        name = world.a1, namespace = "default", labels = mapOf(foreignLabel to "v1", "gdpr" to "no"),
                    ),
                    spec = EntitySpec(
                        type = "service", lifecycle = "experimental", owner = "group:default/someone",
                        dependsOn = listOf("template:default/starter", "resource:default/${world.b1}"),
                    ),
                ),
            ).body<ch.nokillswit.catalog.CatalogFileResponse>()
            world.files += stored.id

            val merged = world.documents("?blueprint=${world.svc.enc()}")
            assertEquals(stored.id, merged.rows.single { it.identifier == world.a1 }.existingId)
            assertEquals(stored.revision, merged.rows.single { it.identifier == world.a1 }.existingRevision)
            assertNull(merged.rows.single { it.identifier == world.a2 }.existingId)
            assertNull(merged.rows.single { it.identifier == world.a2 }.existingRevision)
            val file = merged.files.single { it.metadata.name == world.a1 }
            assertEquals(mapOf(foreignLabel to "v1", "gdpr" to "yes"), file.metadata.labels)
            assertEquals(listOf("template:default/starter", "resource:default/${world.b1}"), file.spec.dependsOn)
            assertEquals("group:default/${world.boss}", file.spec.owner)
            assertEquals("production", file.spec.lifecycle)

            val bare = world.documents("?blueprint=${world.svc.enc()}&merge=false")
            // The counterpart is named merge or not (the identity-only revision read); only the document differs.
            assertEquals(stored.id, bare.rows.single { it.identifier == world.a1 }.existingId)
            assertEquals(stored.revision, bare.rows.single { it.identifier == world.a1 }.existingRevision)
            assertEquals(mapOf("gdpr" to "yes"), bare.files.single { it.metadata.name == world.a1 }.metadata.labels)
            assertEquals(listOf("resource:default/${world.b1}"), bare.files.single { it.metadata.name == world.a1 }.spec.dependsOn)
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `a mapping that went stale is a 409, and the conversion runs again once it is repaired`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cv-stale", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            val blueprints = admin.get("/api/v1/blueprints").body<ch.nokillswit.blueprints.BlueprintList>().items
            val dbBlueprint = blueprints.single { it.identifier == world.db }
            // Drop the `type` property the mapping reads: UNKNOWN_PROPERTY + REQUIRED_UNMAPPED findings appear on the next read.
            val dropped = admin.put("/api/v1/blueprints/${dbBlueprint.id}") {
                contentType(ContentType.Application.Json)
                setBody("""{"identifier": "${world.db}", "title": "DB", "schema": {"properties": {}}, "ownership": {"type": "Direct"}}""")
            }
            assertEquals(HttpStatusCode.NoContent, dropped.status, dropped.bodyAsText())
            val stale = admin.get("/api/v1/conversion-mappings/${world.mappingId}/catalog-documents")
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertTrue("blocking finding" in stale.body<ProblemDetail>().detail.orEmpty(), stale.bodyAsText())
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `ONE reservation spans the entity read, the catalog read and the conversion - the ledger sees the sum`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cv-ledger", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            // A stored file the merge will read, so the catalog read has a non-zero cost.
            val stored = admin.postJson(
                "$CATALOG_FILES_PATH?allowInvalid=true",
                CatalogFile(
                    metadata = CatalogFileMetadata(name = world.a1, namespace = "default"),
                    spec = EntitySpec(type = "service", lifecycle = "production", owner = "group:default/someone"),
                ),
            ).body<ch.nokillswit.catalog.CatalogFileResponse>()
            world.files += stored.id
            val filter = EntityGraphFilter(blueprints = listOf(world.svc), q = null)
            fun service(ledger: ReadLedger) = ConversionExportService(
                TestConversionMappings.service,
                TestEntities.tunedService(readLedger = ReadLedger()),
                TestCatalogFiles.tunedService(readLedger = ReadLedger()),
                ledger,
            )
            suspend fun ConversionExportService.documents(merge: Boolean, id: UInt = world.mappingId) =
                catalogDocuments(id, filter, null, merge)

            // The peaks: the entity read alone (E), and with the merge on top (P). The catalog read's own cost (C), measured on a
            // reservation of its own, must be exactly the difference - the two reads ADD on one reservation.
            val entityOnly = ReadLedger()
            service(entityOnly).documents(merge = false)
            val withMerge = ReadLedger()
            service(withMerge).documents(merge = true)
            val wanted = setOf(a1Identity(world), a2Identity(world))
            val probe = ReadLedger()
            val catalogCost = probe.open().use { reservation ->
                TestCatalogFiles.tunedService(readLedger = probe).readActiveSources(reservation, wanted)
                reservation.reserved
            }
            assertTrue(catalogCost > 0, "the stored file must cost something")
            assertEquals(entityOnly.peak + catalogCost, withMerge.peak, "the catalog read is charged on top of the entity read")
            assertEquals(0L, withMerge.inFlightBytes, "everything is released when the answer is built")

            // The consequence: a ledger that fits the larger read but not their sum refuses the merge and still allows the plain export.
            val tight = ReadLedger(capacity = withMerge.peak - 1)
            assertNotNull(service(tight).documents(merge = false))
            assertFailsWith<BadRequestException> { service(tight).documents(merge = true) }
            assertEquals(0L, tight.inFlightBytes)
            assertNotNull(service(ReadLedger(capacity = withMerge.peak)).documents(merge = true))

            // An over-large entity read is the named 400 and a contended one the 429, nothing left reserved.
            val tiny = ReadLedger(capacity = 1)
            assertFailsWith<BadRequestException> { service(tiny).documents(merge = false) }
            assertEquals(0L, tiny.inFlightBytes)
            val capacity = 8L * 1024 * 1024
            val busy = ReadLedger(capacity = capacity)
            val other = busy.open().apply { reserve(capacity - 1) }
            try {
                assertFailsWith<TooManyRequestsException> { service(busy).documents(merge = false) }
            } finally {
                other.close()
            }
            assertEquals(0L, busy.inFlightBytes)
            assertNull(service(ReadLedger()).documents(merge = false, id = 4_000_000_000u))
        } finally {
            world.cleanup()
        }
    }

    @Test
    fun `a narrow export reads and reserves only the stored files it can merge over`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cv-narrow", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            val stored = admin.postJson(
                "$CATALOG_FILES_PATH?allowInvalid=true",
                CatalogFile(
                    metadata = CatalogFileMetadata(name = world.a1, namespace = "default"),
                    spec = EntitySpec(type = "service", lifecycle = "production", owner = "group:default/someone"),
                ),
            ).body<ch.nokillswit.catalog.CatalogFileResponse>()
            world.files += stored.id
            val ledger = ReadLedger()
            val none = ledger.open().use { r ->
                val sources = TestCatalogFiles.tunedService(readLedger = ledger).readActiveSources(r, setOf(a2Identity(world)))
                sources to r.reserved
            }
            assertEquals(emptyList(), none.first, "a2 has no stored file, so nothing is read")
            assertEquals(0L, none.second, "and nothing is reserved")
            val one = ledger.open().use { r ->
                TestCatalogFiles.tunedService(readLedger = ledger).readActiveSources(r, setOf(a1Identity(world))).map { it.id }
            }
            assertEquals(listOf(stored.id), one)
        } finally {
            world.cleanup()
        }
    }

    private fun a1Identity(world: World) = ch.nokillswit.catalog.EntityIdentity("component", "default", world.a1.lowercase())
    private fun a2Identity(world: World) = ch.nokillswit.catalog.EntityIdentity("component", "default", world.a2.lowercase())

    @Test
    fun `the response carries no null member`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cv-nonull", UserRole.ADMIN)
        val world = World(admin, admin).create()
        try {
            val path = "/api/v1/conversion-mappings/${world.mappingId}/catalog-documents?blueprint=${world.svc.enc()}"
            val text = admin.get(path).bodyAsText()
            assertTrue("null" !in text, "no JSON null member on the wire: $text")
        } finally {
            world.cleanup()
        }
    }
}
