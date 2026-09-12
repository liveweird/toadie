package ch.nokillswit

import ch.nokillswit.blueprints.AggregationCalculationSpec
import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.MirrorPropertyDefinition
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.entities.EntityGraph
import ch.nokillswit.entities.EntityInvalidProblem
import ch.nokillswit.entities.EntityPageResponse
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The entity route surface (Port migration phase 2): CRUD, authz (any authenticated user, no
 * admin gate), the shared-workspace posture, list filter/q/sort/paging, findings/strict-save
 * 400s, per-blueprint identifier uniqueness, the PUT blueprint-immutability rule, delete-
 * referrer 409, rename cascade, blueprint-delete-with-entities 409, and the raw-body no-`null`
 * pin. Every test mints unique `bp-<uuid8>` blueprints and `ent-<uuid8>` entity identifiers and
 * cleans up via [TestEntities]/[TestBlueprints] in `finally`.
 */
class EntityTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private suspend fun HttpClient.createBlueprint(request: BlueprintRequest) =
        postJson("/api/v1/blueprints", request).body<BlueprintResponse>()

    private fun simpleBlueprint(id: String) = BlueprintRequest(identifier = id, title = "T", schema = BlueprintSchema())

    private fun microserviceBlueprint(id: String) = BlueprintRequest(
        identifier = id,
        title = "Microservice",
        schema = BlueprintSchema(
            properties = mapOf(
                "language" to PropertyDefinition(type = "string", title = "Language"),
                "tier" to PropertyDefinition(type = "number", title = "Tier"),
            ),
            required = listOf("language"),
        ),
    )

    private fun entityRequest(
        blueprint: String,
        identifier: String,
        properties: kotlinx.serialization.json.JsonObject = buildJsonObject { },
    ) =
        EntityRequest(blueprint = blueprint, identifier = identifier, title = "Title $identifier", properties = properties)

    private fun teamEntity(identifier: String) =
        EntityRequest(blueprint = SYSTEM_TEAM_BLUEPRINT, identifier = identifier, title = identifier)

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/entities").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/entities/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/entities").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/entities/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/entities/1").status)
    }

    @Test
    fun `a non-admin user has full CRUD - no isAdmin gate anywhere in this feature`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-user", UserRole.USER)
        val admin = seededClient("ent-user-admin", UserRole.ADMIN)
        val bpId = unique("bp-crud")
        val entId = unique("ent-crud")
        try {
            admin.createBlueprint(simpleBlueprint(bpId))

            val create = client.postJson("/api/v1/entities", entityRequest(bpId, entId))
            assertEquals(HttpStatusCode.Created, create.status)
            val created = create.body<EntityResponse>()
            assertEquals(bpId, created.blueprint)
            assertEquals(entId, created.identifier)
            assertTrue(created.findings.isEmpty())
            assertNotNull(create.headers["Location"])

            val read = client.get("/api/v1/entities/${created.id}")
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals(created, read.body<EntityResponse>())

            val replace = client.putJson("/api/v1/entities/${created.id}", entityRequest(bpId, entId).copy(title = "Renamed"))
            assertEquals(HttpStatusCode.NoContent, replace.status)
            val replaced = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
            assertEquals("Renamed", replaced.title)

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/entities/${created.id}").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/entities/${created.id}").status)
            val raw = TestEntities.rawRows().single { it.id == created.id }
            assertTrue(raw.markedAsDeleted, "delete must soft-delete, not remove")
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `unknown blueprint on create is 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-unknown-bp")
        val response = client.postJson("/api/v1/entities", entityRequest(unique("bp-missing"), unique("ent")))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `unknown property key is a 400 naming the field`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-unknown-key", UserRole.ADMIN)
        val bpId = unique("bp-unk")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            val response = client.postJson(
                "/api/v1/entities",
                entityRequest(bpId, unique("ent"), buildJsonObject { put("nope", "x") }),
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.body<EntityInvalidProblem>().detail!!.contains("properties.nope"))
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a missing required property is a 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-required", UserRole.ADMIN)
        val bpId = unique("bp-req")
        try {
            client.createBlueprint(microserviceBlueprint(bpId))
            val response = client.postJson("/api/v1/entities", entityRequest(bpId, unique("ent")))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.body<EntityInvalidProblem>().detail!!.contains("properties.language"))
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `an unresolved relation target is a 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-target-missing", UserRole.ADMIN)
        val bpId = unique("bp-rel")
        try {
            val related = client.createBlueprint(
                BlueprintRequest(
                    identifier = bpId,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("owner" to RelationDefinition(title = "Owner", target = bpId, required = false, many = false)),
                ),
            )
            val request = entityRequest(related.identifier, unique("ent"))
                .copy(relations = buildJsonObject { put("owner", "does-not-exist") })
            val response = client.postJson("/api/v1/entities", request)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.body<EntityInvalidProblem>().detail!!.contains("relations.owner"))
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `identifier is unique per blueprint case-insensitively but reusable across blueprints`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-uniq", UserRole.ADMIN)
        val bp1 = unique("bp-a")
        val bp2 = unique("bp-b")
        val entId = unique("ent-shared")
        try {
            client.createBlueprint(simpleBlueprint(bp1))
            client.createBlueprint(simpleBlueprint(bp2))
            assertEquals(HttpStatusCode.Created, client.postJson("/api/v1/entities", entityRequest(bp1, entId)).status)
            val clash = client.postJson("/api/v1/entities", entityRequest(bp1, entId.uppercase()))
            assertEquals(HttpStatusCode.Conflict, clash.status)
            // Same identifier, DIFFERENT blueprint: allowed.
            assertEquals(HttpStatusCode.Created, client.postJson("/api/v1/entities", entityRequest(bp2, entId)).status)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bp1, bp2)
        }
    }

    @Test
    fun `PUT cannot move an entity to a different blueprint`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-move", UserRole.ADMIN)
        val bp1 = unique("bp-move-a")
        val bp2 = unique("bp-move-b")
        val entId = unique("ent-move")
        try {
            client.createBlueprint(simpleBlueprint(bp1))
            client.createBlueprint(simpleBlueprint(bp2))
            val created = client.postJson("/api/v1/entities", entityRequest(bp1, entId)).body<EntityResponse>()
            val response = client.putJson("/api/v1/entities/${created.id}", entityRequest(bp2, entId))
            assertEquals(HttpStatusCode.BadRequest, response.status)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bp1, bp2)
        }
    }

    @Test
    fun `PUT 404s before validating an invalid body`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-put-404")
        val response = client.putJson("/api/v1/entities/999999", entityRequest("does-not-exist", ""))
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `delete of a targeted entity is 409 naming the referrer, self-relation never blocks`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-del-ref", UserRole.ADMIN)
        val targetBp = unique("bp-target")
        val referrerBp = unique("bp-referrer")
        val targetEnt = unique("ent-target")
        val referrerEnt = unique("ent-referrer")
        val selfEnt = unique("ent-self")
        val selfBpId = unique("bp-self-rel")
        try {
            client.createBlueprint(simpleBlueprint(targetBp))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = referrerBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("target" to RelationDefinition(title = "Target", target = targetBp, required = false, many = false)),
                ),
            )
            val target = client.postJson("/api/v1/entities", entityRequest(targetBp, targetEnt)).body<EntityResponse>()
            client.postJson(
                "/api/v1/entities",
                entityRequest(referrerBp, referrerEnt).copy(relations = buildJsonObject { put("target", targetEnt) }),
            )

            val delete = client.delete("/api/v1/entities/${target.id}")
            assertEquals(HttpStatusCode.Conflict, delete.status)
            assertTrue(delete.body<ProblemDetail>().detail!!.contains("$referrerBp/$referrerEnt"))

            // A self-relation on the SAME entity must never block its own deletion.
            val selfBp = client.createBlueprint(
                BlueprintRequest(
                    identifier = selfBpId,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("self" to RelationDefinition(title = "Self", target = selfBpId, required = false, many = false)),
                ),
            )
            val selfEntity = client.postJson("/api/v1/entities", entityRequest(selfBp.identifier, selfEnt)).body<EntityResponse>()
            client.putJson(
                "/api/v1/entities/${selfEntity.id}",
                entityRequest(selfBp.identifier, selfEnt).copy(relations = buildJsonObject { put("self", selfEnt) }),
            )
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/entities/${selfEntity.id}").status)
        } finally {
            TestEntities.remove(targetEnt, referrerEnt, selfEnt)
            TestBlueprints.remove(targetBp, referrerBp, selfBpId)
        }
    }

    @Test
    fun `rename cascades into the referrer's stored relation, observed on its GET`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-rename-cascade", UserRole.ADMIN)
        val targetBp = unique("bp-rename-target")
        val referrerBp = unique("bp-rename-referrer")
        val oldId = unique("ent-old")
        val newId = unique("ent-new")
        val referrerEnt = unique("ent-ref")
        try {
            client.createBlueprint(simpleBlueprint(targetBp))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = referrerBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("target" to RelationDefinition(title = "Target", target = targetBp, required = false, many = false)),
                ),
            )
            val target = client.postJson("/api/v1/entities", entityRequest(targetBp, oldId)).body<EntityResponse>()
            val referrer = client.postJson(
                "/api/v1/entities",
                entityRequest(referrerBp, referrerEnt).copy(relations = buildJsonObject { put("target", oldId) }),
            ).body<EntityResponse>()

            val replace = client.putJson("/api/v1/entities/${target.id}", entityRequest(targetBp, newId))
            assertEquals(HttpStatusCode.NoContent, replace.status)

            val updatedReferrer = client.get("/api/v1/entities/${referrer.id}").body<EntityResponse>()
            assertEquals(newId, updatedReferrer.relations.getValue("target").jsonPrimitive.content)
        } finally {
            TestEntities.remove(oldId, newId, referrerEnt)
            TestBlueprints.remove(targetBp, referrerBp)
        }
    }

    @Test
    fun `rename plus a self-relation naming the NEW identifier in the same PUT is accepted`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-rename-self-rel", UserRole.ADMIN)
        val selfBpId = unique("bp-rename-self")
        val oldId = unique("ent-rename-self-old")
        val newId = unique("ent-rename-self-new")
        try {
            val selfBp = client.createBlueprint(
                BlueprintRequest(
                    identifier = selfBpId,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("self" to RelationDefinition(title = "Self", target = selfBpId, required = false, many = false)),
                ),
            )
            val entity = client.postJson("/api/v1/entities", entityRequest(selfBp.identifier, oldId)).body<EntityResponse>()

            // Rename e1 -> e2 AND set the self-relation to e2 (the row's OWN new identifier) in
            // the SAME PUT: targetExists must not be built from a pre-rename snapshot alone.
            val response = client.putJson(
                "/api/v1/entities/${entity.id}",
                entityRequest(selfBp.identifier, newId).copy(relations = buildJsonObject { put("self", newId) }),
            )
            assertEquals(HttpStatusCode.NoContent, response.status)

            val updated = client.get("/api/v1/entities/${entity.id}").body<EntityResponse>()
            assertEquals(newId, updated.identifier)
            assertEquals(newId, updated.relations.getValue("self").jsonPrimitive.content)
            assertTrue(updated.findings.isEmpty())
        } finally {
            TestEntities.remove(oldId, newId)
            TestBlueprints.remove(selfBpId)
        }
    }

    @Test
    fun `deleting a blueprint with active entities is 409 naming the count`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ent-bp-delete", UserRole.ADMIN)
        val bpId = unique("bp-has-entities")
        val entId = unique("ent-holds-bp")
        try {
            val blueprint = admin.createBlueprint(simpleBlueprint(bpId))
            admin.postJson("/api/v1/entities", entityRequest(bpId, entId))
            val delete = admin.delete("/api/v1/blueprints/${blueprint.id}")
            assertEquals(HttpStatusCode.Conflict, delete.status)
            assertTrue(delete.body<ProblemDetail>().detail!!.contains("1 active entities"))
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `list filters by blueprint - unknown blueprint is an empty page, not 404`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-list-filter", UserRole.ADMIN)
        val bpId = unique("bp-list")
        val otherBp = unique("bp-list-other")
        val entId = unique("ent-in-bp")
        val otherEnt = unique("ent-in-other")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            client.createBlueprint(simpleBlueprint(otherBp))
            client.postJson("/api/v1/entities", entityRequest(bpId, entId))
            client.postJson("/api/v1/entities", entityRequest(otherBp, otherEnt))

            val filtered = client.get("/api/v1/entities?blueprint=$bpId").body<EntityPageResponse>()
            assertEquals(listOf(entId), filtered.items.map { it.identifier })

            val unknown = client.get("/api/v1/entities?blueprint=${unique("bp-never")}").body<EntityPageResponse>()
            assertTrue(unknown.items.isEmpty())
            assertEquals(0, unknown.total)
        } finally {
            TestEntities.remove(entId, otherEnt)
            TestBlueprints.remove(bpId, otherBp)
        }
    }

    @Test
    fun `list filters by blueprint case-insensitively`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-list-filter-case", UserRole.ADMIN)
        val bpId = unique("bp-list-case")
        val entId = unique("ent-in-bp-case")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            client.postJson("/api/v1/entities", entityRequest(bpId, entId))

            val filtered = client.get("/api/v1/entities?blueprint=${bpId.uppercase()}").body<EntityPageResponse>()
            assertEquals(listOf(entId), filtered.items.map { it.identifier })
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `list q matches identifier or title, substring, case-insensitive`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-list-q", UserRole.ADMIN)
        val bpId = unique("bp-q")
        val entId = unique("findme-ent")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            client.postJson("/api/v1/entities", entityRequest(bpId, entId))
            val found = client.get("/api/v1/entities?q=FINDME").body<EntityPageResponse>()
            assertTrue(found.items.any { it.identifier == entId })
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `list sorts by identifier ascending by default and supports paging`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-list-sort", UserRole.ADMIN)
        val bpId = unique("bp-sort")
        val entA = unique("aaa-ent")
        val entB = unique("zzz-ent")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            client.postJson("/api/v1/entities", entityRequest(bpId, entB))
            client.postJson("/api/v1/entities", entityRequest(bpId, entA))
            val page = client.get("/api/v1/entities?blueprint=$bpId&pageSize=1&page=1&sort=identifier").body<EntityPageResponse>()
            assertEquals(1, page.items.size)
            assertEquals(entA, page.items.single().identifier)
            assertEquals(2, page.total)
        } finally {
            TestEntities.remove(entA, entB)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `list filters by team`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-list-team", UserRole.ADMIN)
        val bpId = unique("bp-team")
        val t1 = unique("team1")
        val t2 = unique("team2")
        val e1 = unique("ent-team-e1")
        val e2 = unique("ent-team-e2")
        val e3 = unique("ent-team-e3")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            client.postJson("/api/v1/entities", teamEntity(t1))
            client.postJson("/api/v1/entities", teamEntity(t2))
            client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = bpId, identifier = e1, title = "T", team = JsonPrimitive(t1)),
            )
            client.postJson(
                "/api/v1/entities",
                EntityRequest(
                    blueprint = bpId, identifier = e2, title = "T",
                    team = JsonArray(listOf(JsonPrimitive(t2), JsonPrimitive(t1))),
                ),
            )
            client.postJson("/api/v1/entities", entityRequest(bpId, e3))

            // Case-folded: an uppercased team value still matches the lowercase-stored one.
            val byT1 = client.get("/api/v1/entities?blueprint=$bpId&team=${t1.uppercase()}").body<EntityPageResponse>()
            assertEquals(setOf(e1, e2), byT1.items.map { it.identifier }.toSet())
            assertEquals(byT1.items.size.toLong(), byT1.total)

            val byT2 = client.get("/api/v1/entities?blueprint=$bpId&team=$t2").body<EntityPageResponse>()
            assertEquals(listOf(e2), byT2.items.map { it.identifier })
            assertEquals(byT2.items.size.toLong(), byT2.total)

            val none = client.get("/api/v1/entities?blueprint=$bpId&team=${unique("nope")}").body<EntityPageResponse>()
            assertTrue(none.items.isEmpty())
            assertEquals(0, none.total)

            val repeated = client.get("/api/v1/entities?team=$t1&team=$t2")
            assertEquals(HttpStatusCode.BadRequest, repeated.status)
        } finally {
            TestEntities.remove(e1, e2, e3, t1, t2)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a response body carries no explicit null members`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-no-null", UserRole.ADMIN)
        val bpId = unique("bp-no-null")
        val entId = unique("ent-no-null")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            val create = client.postJson("/api/v1/entities", entityRequest(bpId, entId))
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(create.bodyAsText()).jsonObject
            assertFalse(obj.containsKey("icon"))
            assertFalse(obj.containsKey("team"))
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `stale - a blueprint edit adding a required property flags existing entities until fixed`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-stale", UserRole.ADMIN)
        val bpId = unique("bp-stale")
        val entId = unique("ent-stale")
        try {
            val blueprint = client.createBlueprint(simpleBlueprint(bpId))
            val entity = client.postJson("/api/v1/entities", entityRequest(bpId, entId)).body<EntityResponse>()
            assertTrue(entity.findings.isEmpty())

            val stricter = BlueprintRequest(
                identifier = bpId,
                title = "T",
                schema = BlueprintSchema(
                    properties = mapOf("language" to PropertyDefinition(type = "string")),
                    required = listOf("language"),
                ),
            )
            assertEquals(HttpStatusCode.NoContent, client.putJson("/api/v1/blueprints/${blueprint.id}", stricter).status)

            val staleGet = client.get("/api/v1/entities/${entity.id}").body<EntityResponse>()
            assertEquals(listOf("REQUIRED_MISSING"), staleGet.findings.map { it.code })

            val staleList = client.get("/api/v1/entities?blueprint=$bpId").body<EntityPageResponse>()
            assertTrue(staleList.items.single { it.id == entity.id }.findings.isNotEmpty())

            // A strict save is refused until the missing property is supplied.
            val rejected = client.putJson("/api/v1/entities/${entity.id}", entityRequest(bpId, entId))
            assertEquals(HttpStatusCode.BadRequest, rejected.status)

            val fixed = client.putJson(
                "/api/v1/entities/${entity.id}",
                entityRequest(bpId, entId, buildJsonObject { put("language", "kotlin") }),
            )
            assertEquals(HttpStatusCode.NoContent, fixed.status)
            assertTrue(client.get("/api/v1/entities/${entity.id}").body<EntityResponse>().findings.isEmpty())
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `the graph endpoint requires authentication and dodges the id route`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/entities/graph").status)
        // Would be a 400 ("id must be a UInt") if {id} captured the literal segment.
        assertEquals(HttpStatusCode.OK, seededClient("ent-graph-auth").get("/api/v1/entities/graph").status)
    }

    @Test
    fun `graph - no filter returns the test's own entities as nodes, with a relation edge`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-graph-basic", UserRole.ADMIN)
        val teamBp = unique("bp-graph-team")
        val personBp = unique("bp-graph-person")
        val teamEnt = unique("ent-graph-team")
        val personEnt = unique("ent-graph-person")
        try {
            client.createBlueprint(simpleBlueprint(personBp))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = teamBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("owner" to RelationDefinition(title = "Owner", target = personBp, required = false, many = false)),
                ),
            )
            client.postJson("/api/v1/entities", entityRequest(personBp, personEnt))
            client.postJson(
                "/api/v1/entities",
                entityRequest(teamBp, teamEnt).copy(relations = buildJsonObject { put("owner", personEnt) }),
            )

            val graph = client.get("/api/v1/entities/graph").body<EntityGraph>()
            val nodeIds = graph.nodes.map { it.id }.toSet()
            assertTrue(nodeIds.containsAll(listOf("$personBp|$personEnt", "$teamBp|$teamEnt")))
            val ownerEdge = graph.edges.single { it.relation == "owner" }
            assertEquals("$teamBp|$teamEnt", ownerEdge.sourceId)
            assertEquals("$personBp|$personEnt", ownerEdge.targetId)
            assertTrue(graph.nodes.none { it.id.contains(":null") })
        } finally {
            TestEntities.remove(teamEnt, personEnt)
            TestBlueprints.remove(teamBp, personBp)
        }
    }

    @Test
    fun `graph - blueprint filter is IN semantics over repeated params, unknown resolves to an empty graph`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-graph-filter", UserRole.ADMIN)
        val bpA = unique("bp-graph-a")
        val bpB = unique("bp-graph-b")
        val bpC = unique("bp-graph-c")
        val entA = unique("ent-graph-a")
        val entB = unique("ent-graph-b")
        val entC = unique("ent-graph-c")
        try {
            client.createBlueprint(simpleBlueprint(bpA))
            client.createBlueprint(simpleBlueprint(bpB))
            client.createBlueprint(simpleBlueprint(bpC))
            client.postJson("/api/v1/entities", entityRequest(bpA, entA))
            client.postJson("/api/v1/entities", entityRequest(bpB, entB))
            client.postJson("/api/v1/entities", entityRequest(bpC, entC))

            val filtered = client.get("/api/v1/entities/graph?blueprint=$bpA&blueprint=$bpB").body<EntityGraph>()
            assertEquals(setOf("$bpA|$entA", "$bpB|$entB"), filtered.nodes.map { it.id }.toSet())

            val unknown = client.get("/api/v1/entities/graph?blueprint=${unique("bp-graph-never")}").body<EntityGraph>()
            assertTrue(unknown.nodes.isEmpty())
            assertTrue(unknown.edges.isEmpty())
        } finally {
            TestEntities.remove(entA, entB, entC)
            TestBlueprints.remove(bpA, bpB, bpC)
        }
    }

    @Test
    fun `graph - q folds accents and case`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-graph-q", UserRole.ADMIN)
        val bpId = unique("bp-graph-q")
        val entId = unique("ent-graph-q")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            client.postJson("/api/v1/entities", entityRequest(bpId, entId).copy(title = "Żółw"))

            val found = client.get("/api/v1/entities/graph?q=zolw").body<EntityGraph>()
            assertTrue(found.nodes.any { it.id == "$bpId|$entId" })
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `graph - hierarchies lists the ids once the blueprint's hierarchyRelations is PUT`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-graph-hierarchy", UserRole.ADMIN)
        val parentBp = unique("bp-graph-hp")
        val childBp = unique("bp-graph-hc")
        val parentEnt = unique("ent-graph-hp")
        val childEnt = unique("ent-graph-hc")
        try {
            client.createBlueprint(simpleBlueprint(parentBp))
            val child = client.createBlueprint(
                BlueprintRequest(
                    identifier = childBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("parent" to RelationDefinition(title = "Parent", target = parentBp, required = false, many = false)),
                ),
            )
            client.postJson("/api/v1/entities", entityRequest(parentBp, parentEnt))
            client.postJson(
                "/api/v1/entities",
                entityRequest(childBp, childEnt).copy(relations = buildJsonObject { put("parent", parentEnt) }),
            )

            val before = client.get("/api/v1/entities/graph").body<EntityGraph>()
            assertTrue(before.edges.single { it.relation == "parent" }.hierarchies.isEmpty())

            val setHierarchy = client.putJson(
                "/api/v1/blueprints/${child.id}",
                BlueprintRequest(
                    identifier = childBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("parent" to RelationDefinition(title = "Parent", target = parentBp, required = false, many = false)),
                    hierarchyRelations = mapOf("composition" to "parent"),
                ),
            )
            assertEquals(HttpStatusCode.NoContent, setHierarchy.status)

            val after = client.get("/api/v1/entities/graph").body<EntityGraph>()
            assertEquals(listOf("composition"), after.edges.single { it.relation == "parent" }.hierarchies)

            assertFalse(client.get("/api/v1/entities/graph").bodyAsText().contains(":null"))
        } finally {
            TestEntities.remove(parentEnt, childEnt)
            TestBlueprints.remove(parentBp, childBp)
        }
    }

    @Test
    fun `graph keeps the _team node under a team filter`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-graph-team", UserRole.ADMIN)
        val bpId = unique("bp-graph-team-f")
        val t1 = unique("team-g1")
        val t2 = unique("team-g2")
        val e1 = unique("ent-graph-team-e1")
        val e2 = unique("ent-graph-team-e2")
        val e3 = unique("ent-graph-team-e3")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            client.postJson("/api/v1/entities", teamEntity(t1))
            client.postJson("/api/v1/entities", teamEntity(t2))
            client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = bpId, identifier = e1, title = "T", team = JsonPrimitive(t1)),
            )
            client.postJson(
                "/api/v1/entities",
                EntityRequest(
                    blueprint = bpId, identifier = e2, title = "T",
                    team = JsonArray(listOf(JsonPrimitive(t2), JsonPrimitive(t1))),
                ),
            )
            client.postJson("/api/v1/entities", entityRequest(bpId, e3))

            val graph = client.get("/api/v1/entities/graph?blueprint=$bpId&blueprint=$SYSTEM_TEAM_BLUEPRINT&team=$t1")
                .body<EntityGraph>()

            assertEquals(setOf("$bpId|$e1", "$bpId|$e2", "$SYSTEM_TEAM_BLUEPRINT|$t1"), graph.nodes.map { it.id }.toSet())

            val ownershipEdges = graph.edges.filter { it.ownership }
            assertEquals(
                setOf("$bpId|$e1" to "$SYSTEM_TEAM_BLUEPRINT|$t1", "$bpId|$e2" to "$SYSTEM_TEAM_BLUEPRINT|$t1"),
                ownershipEdges.map { it.sourceId to it.targetId }.toSet(),
            )
            // t2 is not shown (excluded by the team filter), so e2 -> t2 must not appear.
            assertTrue(ownershipEdges.none { it.targetId == "$SYSTEM_TEAM_BLUEPRINT|$t2" })
        } finally {
            TestEntities.remove(e1, e2, e3, t1, t2)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `list and graph filter by the effective team of Inherited entities`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-inh-team", UserRole.ADMIN)
        val pBp = unique("bp-inh2-p")
        val cBp = unique("bp-inh2-c")
        val gBp = unique("bp-inh2-g")
        val teamA = unique("team-inh2-a")
        val teamB = unique("team-inh2-b")
        val p1 = unique("ent-inh2-p1")
        val p2 = unique("ent-inh2-p2")
        val c1 = unique("ent-inh2-c1")
        val c2 = unique("ent-inh2-c2")
        val c3 = unique("ent-inh2-c3")
        val g1 = unique("ent-inh2-g1")
        try {
            client.createBlueprint(
                BlueprintRequest(
                    identifier = pBp, title = "T", schema = BlueprintSchema(),
                    ownership = OwnershipDefinition(type = "Direct"),
                ),
            )
            client.createBlueprint(
                BlueprintRequest(
                    identifier = cBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("parent" to RelationDefinition(title = "Parent", target = pBp, required = false, many = false)),
                    ownership = OwnershipDefinition(type = "Inherited", path = "parent"),
                ),
            )
            client.createBlueprint(
                BlueprintRequest(
                    identifier = gBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("owner" to RelationDefinition(title = "Owner", target = cBp, required = false, many = false)),
                    ownership = OwnershipDefinition(type = "Inherited", path = "owner.parent"),
                ),
            )
            client.postJson("/api/v1/entities", teamEntity(teamA))
            client.postJson("/api/v1/entities", teamEntity(teamB))
            client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = pBp, identifier = p1, title = "T", team = JsonPrimitive(teamA)),
            )
            client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = pBp, identifier = p2, title = "T", team = JsonPrimitive(teamB)),
            )
            client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = cBp, identifier = c1, title = "T", relations = buildJsonObject { put("parent", p1) }),
            )
            client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = cBp, identifier = c2, title = "T", relations = buildJsonObject { put("parent", p2) }),
            )
            client.postJson("/api/v1/entities", EntityRequest(blueprint = cBp, identifier = c3, title = "T"))
            client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = gBp, identifier = g1, title = "T", relations = buildJsonObject { put("owner", c1) }),
            )

            val expected = setOf(teamA, p1, c1, g1)

            val byTeamA = client.get("/api/v1/entities?team=$teamA").body<EntityPageResponse>()
            assertEquals(expected, byTeamA.items.map { it.identifier }.toSet())
            assertEquals(4L, byTeamA.total)

            // Case-folded.
            val byTeamAUpper = client.get("/api/v1/entities?team=${teamA.uppercase()}").body<EntityPageResponse>()
            assertEquals(expected, byTeamAUpper.items.map { it.identifier }.toSet())

            // Every returned Inherited row's team field equals the filter value.
            listOf(c1, g1).forEach { id ->
                val item = byTeamA.items.single { it.identifier == id }
                assertEquals(teamA, item.team?.jsonPrimitive?.content)
            }

            val byTeamAndC = client.get("/api/v1/entities?team=$teamA&blueprint=$cBp").body<EntityPageResponse>()
            assertEquals(listOf(c1), byTeamAndC.items.map { it.identifier })

            val byTeamAndP = client.get("/api/v1/entities?team=$teamA&blueprint=$pBp").body<EntityPageResponse>()
            assertEquals(listOf(p1), byTeamAndP.items.map { it.identifier })

            assertTrue(byTeamA.items.none { it.identifier == c2 || it.identifier == c3 })

            val page1 = client.get("/api/v1/entities?team=$teamA&pageSize=2&page=1").body<EntityPageResponse>()
            val page2 = client.get("/api/v1/entities?team=$teamA&pageSize=2&page=2").body<EntityPageResponse>()
            assertEquals(4L, page1.total)
            assertEquals(4L, page2.total)
            assertEquals(expected, (page1.items + page2.items).map { it.identifier }.toSet())

            val graph = client.get("/api/v1/entities/graph?team=$teamA").body<EntityGraph>()
            assertEquals(
                setOf("$pBp|$p1", "$cBp|$c1", "$gBp|$g1", "$SYSTEM_TEAM_BLUEPRINT|$teamA"),
                graph.nodes.map { it.id }.toSet(),
            )
            assertTrue(graph.edges.any { it.sourceId == "$cBp|$c1" && it.targetId == "$pBp|$p1" && !it.ownership })
            assertTrue(graph.edges.any { it.sourceId == "$gBp|$g1" && it.targetId == "$cBp|$c1" && !it.ownership })
            assertTrue(
                graph.edges.any {
                    it.sourceId == "$cBp|$c1" && it.targetId == "$SYSTEM_TEAM_BLUEPRINT|$teamA" && it.ownership
                },
            )

            // The filter follows the walk: once p1's team changes, c1/g1 no longer match teamA.
            client.putJson(
                "/api/v1/entities/${byTeamA.items.single { it.identifier == p1 }.id}",
                EntityRequest(blueprint = pBp, identifier = p1, title = "T", team = JsonPrimitive(teamB)),
            )
            val afterChange = client.get("/api/v1/entities?team=$teamA").body<EntityPageResponse>()
            assertTrue(afterChange.items.none { it.identifier == c1 || it.identifier == g1 })
        } finally {
            TestEntities.remove(g1, c1, c2, c3, p1, p2, teamA, teamB)
            TestBlueprints.remove(gBp, cBp, pBp)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Phase 5 (v1.27.0): computed properties (`.claude/docs/port-data-model.md`)
    // -----------------------------------------------------------------------------------------

    @Test
    fun `computed properties appear on create-GET-list, follow a related-entity change, ignore q and sort`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-computed", UserRole.ADMIN)
        val bpId = unique("bp-computed-b")
        val tBp = unique("bp-computed-t")
        val tRefId = unique("ent-computed-ref")
        val subjId = unique("ent-computed-subj")
        val child1 = unique("ent-computed-c1")
        val child2 = unique("ent-computed-c2")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = tBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("owner" to RelationDefinition(title = "Owner", target = bpId, required = false, many = false)),
                ),
            )
            val bUpdated = BlueprintRequest(
                identifier = bpId,
                title = "T",
                schema = BlueprintSchema(),
                relations = mapOf("ref" to RelationDefinition(title = "Ref", target = tBp, required = false, many = false)),
                mirrorProperties = mapOf("targetTitle" to MirrorPropertyDefinition(title = "Target title", path = "ref.\$title")),
                calculationProperties = mapOf(
                    "greeting" to CalculationPropertyDefinition(title = "Greeting", type = "string", calculation = ".title"),
                ),
                aggregationProperties = mapOf(
                    "childCount" to AggregationPropertyDefinition(
                        title = "Child count",
                        target = tBp,
                        calculationSpec = AggregationCalculationSpec(calculationBy = "entities", func = "count"),
                    ),
                ),
            )
            val blueprintList = client.get("/api/v1/blueprints").body<ch.nokillswit.blueprints.BlueprintList>()
            val bResponse = blueprintList.items.single { it.identifier == bpId }
            assertEquals(HttpStatusCode.NoContent, client.putJson("/api/v1/blueprints/${bResponse.id}", bUpdated).status)

            client.postJson("/api/v1/entities", entityRequest(tBp, tRefId).copy(title = "Target Title"))
            val create = client.postJson(
                "/api/v1/entities",
                entityRequest(bpId, subjId).copy(title = "Subject Title", relations = buildJsonObject { put("ref", tRefId) }),
            )
            assertEquals(HttpStatusCode.Created, create.status)
            val created = create.body<EntityResponse>()
            assertFalse(create.bodyAsText().contains(":null"))
            assertEquals(JsonPrimitive("Target Title"), created.properties["targetTitle"])
            assertEquals(JsonPrimitive("Subject Title"), created.properties["greeting"])

            client.postJson("/api/v1/entities", entityRequest(tBp, child1).copy(relations = buildJsonObject { put("owner", subjId) }))
            client.postJson("/api/v1/entities", entityRequest(tBp, child2).copy(relations = buildJsonObject { put("owner", subjId) }))

            val get = client.get("/api/v1/entities/${created.id}")
            val entity = get.body<EntityResponse>()
            assertFalse(get.bodyAsText().contains(":null"))
            assertEquals(JsonPrimitive("Target Title"), entity.properties["targetTitle"])
            assertEquals(JsonPrimitive("Subject Title"), entity.properties["greeting"])
            // childCount = every T entity naming the subject via "owner" (child1, child2) UNION
            // every T entity the subject's own "ref" relation names (tRefId) — 3 distinct rows.
            assertEquals(JsonPrimitive(3L), entity.properties["childCount"])

            val list = client.get("/api/v1/entities?blueprint=$bpId").body<EntityPageResponse>()
            val listed = list.items.single { it.id == created.id }
            assertEquals(JsonPrimitive(3L), listed.properties["childCount"])

            // q matches identifier/title only — a computed value never becomes a match target.
            val qMiss = client.get("/api/v1/entities?blueprint=$bpId&q=Target").body<EntityPageResponse>()
            assertTrue(qMiss.items.none { it.id == created.id })
            // Sorting by identifier still returns the entity with its computed values intact.
            val sorted = client.get("/api/v1/entities?blueprint=$bpId&sort=identifier").body<EntityPageResponse>()
            assertEquals(JsonPrimitive(3L), sorted.items.single { it.id == created.id }.properties["childCount"])

            // Computed values follow a change to the related entity, not a cached snapshot.
            val tRef = client.get("/api/v1/entities?blueprint=$tBp&q=$tRefId").body<EntityPageResponse>().items.single()
            client.putJson("/api/v1/entities/${tRef.id}", entityRequest(tBp, tRefId).copy(title = "Renamed Target"))
            val afterRename = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
            assertEquals(JsonPrimitive("Renamed Target"), afterRename.properties["targetTitle"])
        } finally {
            TestEntities.remove(tRefId, subjId, child1, child2)
            TestBlueprints.remove(bpId, tBp)
        }
    }

    @Test
    fun `a computed property id is still rejected as write input on POST and PUT`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-computed-reject", UserRole.ADMIN)
        val bpId = unique("bp-computed-reject")
        val entId = unique("ent-computed-reject")
        try {
            client.createBlueprint(
                BlueprintRequest(
                    identifier = bpId,
                    title = "T",
                    schema = BlueprintSchema(),
                    calculationProperties = mapOf(
                        "risk" to CalculationPropertyDefinition(title = "Risk", type = "string", calculation = "\"low\""),
                    ),
                ),
            )
            val badCreate = client.postJson(
                "/api/v1/entities",
                entityRequest(bpId, entId, buildJsonObject { put("risk", "x") }),
            )
            assertEquals(HttpStatusCode.BadRequest, badCreate.status)
            val createFindings = badCreate.body<EntityInvalidProblem>().findings
            assertTrue(createFindings.any { it.code == "COMPUTED_PROPERTY" && it.field == "properties.risk" })

            val created = client.postJson("/api/v1/entities", entityRequest(bpId, entId)).body<EntityResponse>()
            val badUpdate = client.putJson(
                "/api/v1/entities/${created.id}",
                entityRequest(bpId, entId, buildJsonObject { put("risk", "x") }),
            )
            assertEquals(HttpStatusCode.BadRequest, badUpdate.status)
            assertTrue(badUpdate.body<EntityInvalidProblem>().findings.any { it.code == "COMPUTED_PROPERTY" })
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `graph nodes never carry computed property values`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-computed-graph", UserRole.ADMIN)
        val bpId = unique("bp-computed-graph")
        val entId = unique("ent-computed-graph")
        try {
            client.createBlueprint(
                BlueprintRequest(
                    identifier = bpId,
                    title = "T",
                    schema = BlueprintSchema(),
                    calculationProperties = mapOf(
                        "distinctiveComputedValue" to
                            CalculationPropertyDefinition(title = "D", type = "string", calculation = "\"marker-value\""),
                    ),
                ),
            )
            client.postJson("/api/v1/entities", entityRequest(bpId, entId))
            val graphBody = client.get("/api/v1/entities/graph?blueprint=$bpId").bodyAsText()
            assertFalse(graphBody.contains("distinctiveComputedValue"))
            assertFalse(graphBody.contains("marker-value"))
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `entity creation audits the STORED properties count, not the computed properties count`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("ent-computed-audit")
        val userId = TestUsers.seed(email = email, password = "pw", role = UserRole.USER)
        val adminEmail = uniqueEmail("ent-computed-audit-admin")
        TestUsers.seed(email = adminEmail, password = "pw", role = UserRole.ADMIN)
        val bpId = unique("bp-computed-audit")
        val entId = unique("ent-computed-audit")
        try {
            withAuditCapture { capture ->
                val client = authedClient(email, "pw")
                // Blueprints are ADMIN-only (phase 1); entities are not (phase 2) — the USER
                // above only ever mutates the entity, so byUserId below is unambiguous.
                authedClient(adminEmail, "pw").createBlueprint(
                    BlueprintRequest(
                        identifier = bpId,
                        title = "T",
                        schema = BlueprintSchema(properties = mapOf("real" to PropertyDefinition(type = "string"))),
                        calculationProperties = mapOf(
                            "a" to CalculationPropertyDefinition(title = "A", type = "string", calculation = "\"a\""),
                            "b" to CalculationPropertyDefinition(title = "B", type = "string", calculation = "\"b\""),
                        ),
                    ),
                )
                val created = client.postJson(
                    "/api/v1/entities",
                    entityRequest(bpId, entId, buildJsonObject { put("real", "x") }),
                ).body<EntityResponse>()
                // The response carries 1 stored + 2 computed = 3 properties, but the audit trail
                // records only what the REQUEST stored.
                assertEquals(3, created.properties.size)
                val event = capture.awaitEvent { it.message == "entity.created" && it.hasKeyValue("entityId", created.id.toLong()) }
                assertNotNull(event)
                assertTrue(event.hasKeyValue("byUserId", userId.toLong()))
                assertTrue(event.hasKeyValue("properties", 1))
            }
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `an aggregation counts correctly across roughly 200 target entities`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("ent-computed-scale")
        val userId = TestUsers.seed(email = email, password = "pw", role = UserRole.USER)
        val adminEmail = uniqueEmail("ent-computed-scale-admin")
        TestUsers.seed(email = adminEmail, password = "pw", role = UserRole.ADMIN)
        val admin = authedClient(adminEmail, "pw")
        val client = authedClient(email, "pw")
        val childBp = unique("bp-computed-scale-c")
        val subjBp = unique("bp-computed-scale-s")
        val subjId = unique("ent-computed-scale-s")
        val childIds = (0 until 200).map { unique("ent-computed-scale-c$it") }
        try {
            admin.createBlueprint(simpleBlueprint(childBp))
            admin.createBlueprint(
                BlueprintRequest(
                    identifier = subjBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("kids" to RelationDefinition(title = "Kids", target = childBp, required = false, many = true)),
                    aggregationProperties = mapOf(
                        "total" to AggregationPropertyDefinition(
                            title = "Total",
                            target = childBp,
                            calculationSpec = AggregationCalculationSpec(calculationBy = "entities", func = "count"),
                        ),
                    ),
                ),
            )
            childIds.forEach { id -> TestEntities.service.create(EntityRequest(blueprint = childBp, identifier = id, title = id), userId) }
            val kidsValue = JsonArray(childIds.map { JsonPrimitive(it) })
            val subj = client.postJson(
                "/api/v1/entities",
                entityRequest(subjBp, subjId).copy(relations = buildJsonObject { put("kids", kidsValue) }),
            ).body<EntityResponse>()
            assertEquals(JsonPrimitive(childIds.size.toLong()), subj.properties["total"])
        } finally {
            TestEntities.remove(*(childIds + subjId).toTypedArray())
            TestBlueprints.remove(childBp, subjBp)
        }
    }
}
