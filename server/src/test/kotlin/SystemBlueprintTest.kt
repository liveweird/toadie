package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintList
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_BLUEPRINT_BASES
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.SYSTEM_USER_BLUEPRINT
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `_team`/`_user` (V31, Phase 4 v1.26.0 — `.claude/docs/port-data-model.md` "System
 * blueprints"): present from a fresh database, flagged `system: true`, never deletable,
 * never renamable, and never strippable of their base shape — while everything else about
 * them (titles, extra properties/relations, `hierarchyRelation`, `ownership`) stays an
 * ordinary admin edit. Every test that extends a base shape restores it in `finally` via
 * [TestBlueprints.restoreSystemBlueprints] (`remove` refuses `_`-prefixed identifiers).
 */
class SystemBlueprintTest {

    private suspend fun HttpClient.readBlueprints(): BlueprintList = get("/api/v1/blueprints").body()

    private fun BlueprintResponse.asRequest(identifier: String = this.identifier) = BlueprintRequest(
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
        hierarchyRelations = hierarchyRelations,
    )

    @Test
    fun `the two system blueprints are present with system true from a fresh database`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("sysbppresent", UserRole.ADMIN)
        try {
            val list = admin.readBlueprints().items
            val team = list.single { it.identifier == SYSTEM_TEAM_BLUEPRINT }
            val user = list.single { it.identifier == SYSTEM_USER_BLUEPRINT }
            assertTrue(team.system, "_team must be flagged system")
            assertTrue(user.system, "_user must be flagged system")

            assertTrue(admin.get("/api/v1/blueprints/${team.id}").body<BlueprintResponse>().system)
            assertTrue(admin.get("/api/v1/blueprints/${user.id}").body<BlueprintResponse>().system)
        } finally {
            TestBlueprints.restoreSystemBlueprints()
        }
    }

    @Test
    fun `the seeded system blueprints' definitions equal SYSTEM_BLUEPRINT_BASES`() = testApplication {
        usePostgresTestcontainer()
        TestBlueprints.restoreSystemBlueprints()
        val admin = seededClient("sysbpbase", UserRole.ADMIN)
        val list = admin.readBlueprints().items
        SYSTEM_BLUEPRINT_BASES.forEach { (identifier, base) ->
            val row = list.single { it.identifier == identifier }
            val definition = BlueprintDefinition(
                schema = row.schema,
                relations = row.relations,
                mirrorProperties = row.mirrorProperties,
                calculationProperties = row.calculationProperties,
                aggregationProperties = row.aggregationProperties,
                ownership = row.ownership,
            )
            assertEquals(base, definition, "the seeded row for '$identifier' must equal its Kotlin base shape")
        }
    }

    @Test
    fun `the seeded _team row's hierarchyRelations backfilled to composition by V34`() = testApplication {
        usePostgresTestcontainer()
        TestBlueprints.restoreSystemBlueprints()
        val admin = seededClient("sysbphier", UserRole.ADMIN)
        val team = admin.readBlueprints().items.single { it.identifier == SYSTEM_TEAM_BLUEPRINT }
        val user = admin.readBlueprints().items.single { it.identifier == SYSTEM_USER_BLUEPRINT }
        assertEquals(mapOf("composition" to "parent"), team.hierarchyRelations)
        assertEquals(null, user.hierarchyRelations)
    }

    @Test
    fun `DELETE on a system blueprint is 409 and leaves it intact`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("sysbpdel", UserRole.ADMIN)
        try {
            val team = admin.readBlueprints().items.single { it.identifier == SYSTEM_TEAM_BLUEPRINT }
            val response = admin.delete("/api/v1/blueprints/${team.id}")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.body<ProblemDetail>().detail!!.contains("system blueprint"))
            assertEquals(HttpStatusCode.OK, admin.get("/api/v1/blueprints/${team.id}").status)
        } finally {
            TestBlueprints.restoreSystemBlueprints()
        }
    }

    @Test
    fun `PUT renaming a system blueprint's identifier is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("sysbpren", UserRole.ADMIN)
        try {
            val user = admin.readBlueprints().items.single { it.identifier == SYSTEM_USER_BLUEPRINT }
            val response = admin.putJson("/api/v1/blueprints/${user.id}", user.asRequest(identifier = "_userRenamed"))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.body<ProblemDetail>().detail!!.contains("cannot be renamed"))
        } finally {
            TestBlueprints.restoreSystemBlueprints()
        }
    }

    @Test
    fun `PUT dropping or retyping _user's base email property is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("sysbpemail", UserRole.ADMIN)
        try {
            val user = admin.readBlueprints().items.single { it.identifier == SYSTEM_USER_BLUEPRINT }

            val dropped = admin.putJson("/api/v1/blueprints/${user.id}", user.asRequest().copy(schema = BlueprintSchema()))
            assertEquals(HttpStatusCode.BadRequest, dropped.status)
            assertTrue(dropped.body<ProblemDetail>().detail!!.contains("requires property 'email'"))

            val retyped = admin.putJson(
                "/api/v1/blueprints/${user.id}",
                user.asRequest().copy(
                    schema = user.schema.copy(
                        properties = user.schema.properties + ("email" to PropertyDefinition(type = "number")),
                    ),
                ),
            )
            assertEquals(HttpStatusCode.BadRequest, retyped.status)
            assertTrue(retyped.body<ProblemDetail>().detail!!.contains("must keep type 'string'"))
        } finally {
            TestBlueprints.restoreSystemBlueprints()
        }
    }

    @Test
    fun `PUT dropping _user's team relation or flipping its many flag is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("sysbprel", UserRole.ADMIN)
        try {
            val user = admin.readBlueprints().items.single { it.identifier == SYSTEM_USER_BLUEPRINT }

            val dropped = admin.putJson("/api/v1/blueprints/${user.id}", user.asRequest().copy(relations = emptyMap()))
            assertEquals(HttpStatusCode.BadRequest, dropped.status)
            assertTrue(dropped.body<ProblemDetail>().detail!!.contains("requires relation 'team'"))

            val flipped = admin.putJson(
                "/api/v1/blueprints/${user.id}",
                user.asRequest().copy(
                    relations = mapOf("team" to user.relations.getValue("team").copy(many = false)),
                ),
            )
            assertEquals(HttpStatusCode.BadRequest, flipped.status)
            assertTrue(flipped.body<ProblemDetail>().detail!!.contains("must keep target '$SYSTEM_TEAM_BLUEPRINT' and many=true"))
        } finally {
            TestBlueprints.restoreSystemBlueprints()
        }
    }

    @Test
    fun `PUT extending a system blueprint with an extra property, relation and hierarchyRelations succeeds`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("sysbpext", UserRole.ADMIN)
        try {
            val user = admin.readBlueprints().items.single { it.identifier == SYSTEM_USER_BLUEPRINT }
            val extended = user.asRequest().copy(
                schema = user.schema.copy(
                    properties = user.schema.properties + ("nickname" to PropertyDefinition(type = "string", title = "Nickname")),
                ),
                relations = user.relations + (
                    "manager" to RelationDefinition(
                        title = "Manager",
                        target = SYSTEM_USER_BLUEPRINT,
                        required = false,
                        many = false,
                    )
                ),
                hierarchyRelations = mapOf("composition" to "manager"),
            )
            val response = admin.putJson("/api/v1/blueprints/${user.id}", extended)
            assertEquals(HttpStatusCode.NoContent, response.status)

            val reread = admin.get("/api/v1/blueprints/${user.id}").body<BlueprintResponse>()
            assertTrue(reread.system)
            assertTrue(reread.schema.properties.containsKey("nickname"))
            assertTrue(reread.relations.containsKey("manager"))
            assertEquals(mapOf("composition" to "manager"), reread.hierarchyRelations)
            // The base shape survives the extension.
            assertEquals("string", reread.schema.properties.getValue("email").type)
            assertEquals(SYSTEM_TEAM_BLUEPRINT, reread.relations.getValue("team").target)
        } finally {
            TestBlueprints.restoreSystemBlueprints()
        }
    }

    @Test
    fun `POST with a reserved system-prefixed identifier is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("sysbppost", UserRole.ADMIN)
        val response = admin.postJson("/api/v1/blueprints", BlueprintRequest(identifier = "_foo", title = "Foo"))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.body<ProblemDetail>().detail!!.contains("reserved"))
    }

    @Test
    fun `updating a system blueprint audits system true`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("sysbpaudit", UserRole.ADMIN)
        try {
            val team = admin.readBlueprints().items.single { it.identifier == SYSTEM_TEAM_BLUEPRINT }
            withAuditCapture { capture ->
                val response = admin.putJson("/api/v1/blueprints/${team.id}", team.asRequest())
                assertEquals(HttpStatusCode.NoContent, response.status)
                val event = capture.awaitEvent { it.message == "blueprint.updated" }
                assertNotNull(event, "update must audit")
                assertTrue(event.hasKeyValue("system", true))
            }
        } finally {
            TestBlueprints.restoreSystemBlueprints()
        }
    }
}
