package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.EntityGraph
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
            assertTrue(response.body<ProblemDetail>().detail!!.contains("properties.nope"))
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
            assertTrue(response.body<ProblemDetail>().detail!!.contains("properties.language"))
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
            assertTrue(response.body<ProblemDetail>().detail!!.contains("relations.owner"))
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
    fun `graph - hierarchy is true end to end once the blueprint's hierarchyRelation is PUT`() = testApplication {
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
            assertTrue(before.edges.single { it.relation == "parent" }.hierarchy.not())

            val setHierarchy = client.putJson(
                "/api/v1/blueprints/${child.id}",
                BlueprintRequest(
                    identifier = childBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("parent" to RelationDefinition(title = "Parent", target = parentBp, required = false, many = false)),
                    hierarchyRelation = "parent",
                ),
            )
            assertEquals(HttpStatusCode.NoContent, setHierarchy.status)

            val after = client.get("/api/v1/entities/graph").body<EntityGraph>()
            assertTrue(after.edges.single { it.relation == "parent" }.hierarchy)

            assertFalse(client.get("/api/v1/entities/graph").bodyAsText().contains(":null"))
        } finally {
            TestEntities.remove(parentEnt, childEnt)
            TestBlueprints.remove(parentBp, childBp)
        }
    }
}
