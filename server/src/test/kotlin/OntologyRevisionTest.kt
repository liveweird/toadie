package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.EntityImportRequest
import ch.nokillswit.entities.EntityImportResponse
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.integration.GraphQLHttpRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The V39 monotonic ontology-revision counter (`.claude/docs/persistence.md` "V39",
 * `.claude/docs/integration-api.md` "Ontology revision"): every committed blueprint write,
 * entity write, and `HIERARCHY`-dictionary replace bumps it exactly once; a rejected write, a
 * NAMESPACE/LIFECYCLE dictionary replace, and every pure read never do. `TestOntologyRevision`
 * (`TestEnvironment.kt`) reads the counter directly — it has no REST surface of its own, only a
 * GraphQL `revision` field on `BlueprintPage`/`EntityPage`/`OntologyErrors`.
 *
 * Sync is deliberately NOT exercised with a real HTTP fixture here: `BlueprintSync.syncFromSource`
 * and `EntitySync.syncFromSource` both call straight into `applyUpdate` — the SAME function an
 * ordinary PUT calls, and case 1/2 below already prove that function bumps exactly once — so a
 * sync-specific case would only re-prove the same code path through a heavier fixture.
 */
class OntologyRevisionTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun simpleBlueprint(id: String, title: String = "T") =
        BlueprintRequest(identifier = id, title = title, schema = BlueprintSchema())

    private fun entityRequest(blueprint: String, identifier: String, title: String = "T") =
        EntityRequest(blueprint = blueprint, identifier = identifier, title = title)

    private suspend fun HttpClient.graphql(key: String, query: String): HttpResponse = post("/integration/graphql") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $key")
        setBody(GraphQLHttpRequest(query))
    }

    @Test
    fun `blueprint create update and delete each bump the ontology revision exactly once`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("rev-bp-crud", UserRole.ADMIN)
        val id = unique("rev-bp")
        try {
            val beforeCreate = TestOntologyRevision.current()
            val created = admin.postJson("/api/v1/blueprints", simpleBlueprint(id)).body<BlueprintResponse>()
            assertEquals(beforeCreate + 1, TestOntologyRevision.current())

            val beforeUpdate = TestOntologyRevision.current()
            val update = admin.putJson("/api/v1/blueprints/${created.id}", simpleBlueprint(id, title = "T2"))
            assertEquals(HttpStatusCode.NoContent, update.status)
            assertEquals(beforeUpdate + 1, TestOntologyRevision.current())

            val beforeDelete = TestOntologyRevision.current()
            val delete = admin.delete("/api/v1/blueprints/${created.id}")
            assertEquals(HttpStatusCode.NoContent, delete.status)
            assertEquals(beforeDelete + 1, TestOntologyRevision.current())
        } finally {
            TestBlueprints.remove(id)
        }
    }

    @Test
    fun `entity create update and delete each bump the ontology revision exactly once, including a byte-identical PUT`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("rev-ent-crud", UserRole.ADMIN)
            val bpId = unique("rev-bp-owner")
            val entId = unique("rev-ent")
            try {
                admin.postJson("/api/v1/blueprints", simpleBlueprint(bpId))

                val beforeCreate = TestOntologyRevision.current()
                val created = admin.postJson("/api/v1/entities", entityRequest(bpId, entId)).body<EntityResponse>()
                assertEquals(beforeCreate + 1, TestOntologyRevision.current())

                // A byte-identical resubmission still bumps: `updatedAt` bumps on every entity/
                // blueprint PUT regardless of whether the document changed (`.claude/docs/
                // persistence.md`'s "D2" rule) — this counter follows the SAME posture rather than
                // diffing the document first, since it answers "did a write commit", not "did
                // anything change".
                val beforeNoop = TestOntologyRevision.current()
                val noop = admin.putJson("/api/v1/entities/${created.id}", entityRequest(bpId, entId))
                assertEquals(HttpStatusCode.NoContent, noop.status)
                assertEquals(beforeNoop + 1, TestOntologyRevision.current())

                val beforeDelete = TestOntologyRevision.current()
                assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/entities/${created.id}").status)
                assertEquals(beforeDelete + 1, TestOntologyRevision.current())
            } finally {
                TestEntities.remove(entId)
                TestBlueprints.remove(bpId)
            }
        }

    @Test
    fun `an entity import batch with no deferrals bumps the ontology revision exactly once per row`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("rev-ent-import", UserRole.ADMIN)
        val bpId = unique("rev-bp-import")
        val rowIdentifiers = (1..3).map { unique("rev-ent-import-row") }
        try {
            admin.postJson("/api/v1/blueprints", simpleBlueprint(bpId))
            val documents: List<JsonObject> = rowIdentifiers.map { blueprintJson.encodeToJsonElement(entityRequest(bpId, it)).jsonObject }

            val before = TestOntologyRevision.current()
            val response = admin.postJson("/api/v1/entities/import", EntityImportRequest(documents = documents))
                .body<EntityImportResponse>()
            assertTrue(response.results.all { it.status == OntologyImportStatus.CREATED }, response.results.toString())
            // No relation cycle, so every row lands on pass one — exactly one bump per row, never
            // a deferred pass-2 restoration for this batch.
            assertEquals(before + rowIdentifiers.size, TestOntologyRevision.current())
        } finally {
            TestEntities.remove(*rowIdentifiers.toTypedArray())
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a rejected blueprint or entity write never bumps the ontology revision`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("rev-rejected", UserRole.ADMIN)
        val bpId = unique("rev-bp-reject")
        try {
            admin.postJson("/api/v1/blueprints", simpleBlueprint(bpId))

            val beforeConflict = TestOntologyRevision.current()
            val duplicate = admin.postJson("/api/v1/blueprints", simpleBlueprint(bpId))
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertEquals(beforeConflict, TestOntologyRevision.current())

            val beforeBadEntity = TestOntologyRevision.current()
            val badEntity = admin.postJson(
                "/api/v1/entities",
                entityRequest(blueprint = "does-not-exist-$bpId", identifier = unique("rev-ent-reject")),
            )
            assertEquals(HttpStatusCode.BadRequest, badEntity.status)
            assertEquals(beforeBadEntity, TestOntologyRevision.current())
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a NAMESPACE replace never bumps while a HIERARCHY replace adding a value bumps once`() = testApplication {
        usePostgresTestcontainer()
        val namespaceValue = unique("rev-ns")
        val hierarchyValue = unique("rev-hier")
        try {
            val beforeNamespace = TestOntologyRevision.current()
            TestNamespaces.ensure(namespaceValue)
            assertEquals(beforeNamespace, TestOntologyRevision.current())

            val beforeHierarchy = TestOntologyRevision.current()
            TestHierarchies.ensure(hierarchyValue)
            assertEquals(beforeHierarchy + 1, TestOntologyRevision.current())
        } finally {
            TestNamespaces.remove(namespaceValue)
            TestHierarchies.remove(hierarchyValue)
        }
    }

    @Test
    fun `the GraphQL entities page carries the ontology revision, which advances by one after a PUT`() = testApplication {
        configureApp("integration.enabled" to "true")
        startApplication()
        TestRefTargets.ensure()
        val admin = seededClient("rev-gql", UserRole.ADMIN)
        val bpId = unique("rev-gql-bp")
        val entId = unique("rev-gql-ent")
        var clientId: UInt? = null
        try {
            admin.postJson("/api/v1/blueprints", simpleBlueprint(bpId))
            val created = admin.postJson("/api/v1/entities", entityRequest(bpId, entId)).body<EntityResponse>()
            val creator = TestUsers.seed(uniqueEmail("rev-gql-key"), "pw")
            val (id, key) = TestIntegrationClients.service.create("rev-gql-${UUID.randomUUID()}", creator)
            clientId = id
            val plain = jsonClient()
            val query = "{ entities(blueprint: \"$bpId\") { items { id } revision } }"

            val first = plain.graphql(key, query).body<JsonObject>()["data"]!!.jsonObject["entities"]!!.jsonObject
            val firstRevision = first["revision"]!!.jsonPrimitive.content.toLong()
            assertEquals(TestOntologyRevision.current(), firstRevision)

            admin.putJson("/api/v1/entities/${created.id}", entityRequest(bpId, entId, title = "Renamed"))

            val second = plain.graphql(key, query).body<JsonObject>()["data"]!!.jsonObject["entities"]!!.jsonObject
            val secondRevision = second["revision"]!!.jsonPrimitive.content.toLong()
            assertEquals(firstRevision + 1, secondRevision)
            assertEquals(TestOntologyRevision.current(), secondRevision)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
            clientId?.let { TestIntegrationClients.service.revoke(it) }
        }
    }

    /**
     * The uncommitted-writer case: a raw JDBC connection performs the SAME two writes a real
     * committed entity PUT would (the row's own column plus the counter), under the SAME
     * `entities` SHARE ROW EXCLUSIVE lock the V28 protocol takes, but withholds the COMMIT —
     * `BlueprintConcurrencyTest`/`EntityConcurrencyTest`'s held-lock idiom retargeted from "does
     * a second writer wait" to "what does a concurrent reader see". It pins the observable
     * contract: while the writer is open a GraphQL page answers the OLD rows with the OLD
     * revision, and after the commit the NEW rows with the NEW revision.
     *
     * What it deliberately does NOT prove: that rows and revision ride ONE statement. A
     * two-statement implementation would pass this case too, because nothing commits BETWEEN a
     * single reader call's two statements here — that window is a few microseconds wide and has
     * no seam to hold it open without production timing hooks (which `.claude/docs/testing.md`
     * forbids). The same-statement property is enforced by code shape instead:
     * `ontologyRevisionExpression()` is a column of the row SELECT in `entityPageRowsWithRevision`
     * (`entities/EntityFilter.kt`) and `BlueprintService.listPage`, and review keeps it there.
     */
    @Test
    fun `a concurrent GraphQL read never sees a torn mix of old rows with the new revision or vice versa`() = testApplication {
        configureApp("integration.enabled" to "true")
        startApplication()
        TestRefTargets.ensure()
        val admin = seededClient("rev-torn", UserRole.ADMIN)
        val bpId = unique("rev-torn-bp")
        val entId = unique("rev-torn-ent")
        val originalTitle = "original"
        val heldTitle = "held-${UUID.randomUUID()}"
        var clientId: UInt? = null
        var holder: Connection? = null
        try {
            admin.postJson("/api/v1/blueprints", simpleBlueprint(bpId))
            val created = admin.postJson("/api/v1/entities", entityRequest(bpId, entId, title = originalTitle)).body<EntityResponse>()
            val creator = TestUsers.seed(uniqueEmail("rev-torn-key"), "pw")
            val (id, key) = TestIntegrationClients.service.create("rev-torn-${UUID.randomUUID()}", creator)
            clientId = id
            val plain = jsonClient()
            val query = "{ entities(blueprint: \"$bpId\") { items { id title } revision } }"

            val before = TestOntologyRevision.current()

            val connection = withContext(NonCancellable + Dispatchers.IO) {
                DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).apply {
                    autoCommit = false
                    createStatement().use { it.execute("LOCK TABLE entities IN SHARE ROW EXCLUSIVE MODE") }
                    prepareStatement("UPDATE entities SET title = ? WHERE id = ?").use { statement ->
                        statement.setString(1, heldTitle)
                        statement.setLong(2, created.id.toLong())
                        statement.executeUpdate()
                    }
                    createStatement().use { it.execute("UPDATE ontology_revision SET revision = revision + 1 WHERE id = 1") }
                }
            }
            holder = connection

            // The writer holds its lock and its write UNCOMMITTED — a plain read (ACCESS SHARE,
            // compatible with SHARE ROW EXCLUSIVE) proceeds without waiting, and MVCC guarantees
            // it sees the PRE-transaction snapshot: the old title together with the old revision.
            val duringWrite = plain.graphql(key, query).body<JsonObject>()["data"]!!.jsonObject["entities"]!!.jsonObject
            val itemDuring = duringWrite.getValue("items").jsonArray
                .single { it.jsonObject["id"]!!.jsonPrimitive.content == created.id.toString() }
            assertEquals(originalTitle, itemDuring.jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(before, duringWrite["revision"]!!.jsonPrimitive.content.toLong())

            withContext(NonCancellable + Dispatchers.IO) { connection.commit() }

            val afterCommit = plain.graphql(key, query).body<JsonObject>()["data"]!!.jsonObject["entities"]!!.jsonObject
            val itemAfter = afterCommit.getValue("items").jsonArray
                .single { it.jsonObject["id"]!!.jsonPrimitive.content == created.id.toString() }
            assertEquals(heldTitle, itemAfter.jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(before + 1, afterCommit["revision"]!!.jsonPrimitive.content.toLong())
        } finally {
            holder?.let { conn -> withContext(NonCancellable + Dispatchers.IO) { runCatching { conn.rollback() }; conn.close() } }
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
            clientId?.let { TestIntegrationClients.service.revoke(it) }
        }
    }
}
