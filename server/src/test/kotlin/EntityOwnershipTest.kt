package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.SYSTEM_USER_BLUEPRINT
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.EntityInvalidProblem
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.entities.MAX_OWNERSHIP_HOPS
import ch.nokillswit.entities.OwnedRow
import ch.nokillswit.entities.effectiveTeam
import ch.nokillswit.entities.inheritedTeam
import ch.nokillswit.entities.isInherited
import ch.nokillswit.entities.ownershipPathBlueprints
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 ownership (`.claude/docs/port-data-model.md` "Ownership"): pure coverage of
 * `entities/EntityOwnership.kt` (the Inherited walk) plus the end-to-end `$team` behavior over
 * HTTP — the strict-save 400's `findings` member, the `_team`/`_user` rename cascade into the
 * `team` column and `format: team|user` properties, the delete-with-referrer 409, and the
 * Inherited-blueprint read path. Every test mints its own uniquely named `_team`/`_user`
 * ENTITIES and blueprints and removes them in `finally`; the system blueprints themselves are
 * never reshaped without a `TestBlueprints.restoreSystemBlueprints()` restore.
 */
class EntityOwnershipTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    // -------------------------------------------------------------------------------------
    // Pure: isInherited / inheritedTeam / effectiveTeam / ownershipPathBlueprints
    // -------------------------------------------------------------------------------------

    private fun relation(target: String, many: Boolean = false) =
        RelationDefinition(title = "Rel", target = target, required = false, many = many)

    private fun doc(relations: Map<String, JsonElement> = emptyMap()) =
        EntityDocument(properties = buildJsonObject { }, relations = buildJsonObject { relations.forEach { (k, v) -> put(k, v) } })

    @Test
    fun `isInherited is true only for ownership type Inherited`() {
        assertFalse(isInherited(BlueprintDefinition()))
        assertFalse(isInherited(BlueprintDefinition(ownership = OwnershipDefinition(type = "Direct"))))
        assertTrue(isInherited(BlueprintDefinition(ownership = OwnershipDefinition(type = "Inherited", path = "service"))))
    }

    @Test
    fun `inheritedTeam is absent for a non-Inherited or path-less blueprint`() {
        assertNull(inheritedTeam(doc(), BlueprintDefinition(), emptyMap()) { _, _ -> null })
        val noPath = BlueprintDefinition(ownership = OwnershipDefinition(type = "Inherited"))
        assertNull(inheritedTeam(doc(), noPath, emptyMap()) { _, _ -> null })
    }

    @Test
    fun `inheritedTeam follows a single hop to a Direct blueprint's stored team`() {
        val workload = BlueprintDefinition(
            relations = mapOf("service" to relation("service")),
            ownership = OwnershipDefinition(type = "Inherited", path = "service"),
        )
        val serviceDef = BlueprintDefinition(ownership = OwnershipDefinition(type = "Direct"))
        val document = doc(mapOf("service" to JsonPrimitive("checkout")))
        val lookup = { bp: String, id: String ->
            if (bp == "service" && id == "checkout") OwnedRow("service", "checkout", doc(), JsonPrimitive("payments")) else null
        }
        assertEquals(JsonPrimitive("payments"), inheritedTeam(document, workload, mapOf("service" to serviceDef), lookup))
    }

    @Test
    fun `inheritedTeam continues past an Inherited-again hop to the next blueprint`() {
        val workload = BlueprintDefinition(
            relations = mapOf("service" to relation("service")),
            ownership = OwnershipDefinition(type = "Inherited", path = "service.domain"),
        )
        val serviceDef = BlueprintDefinition(
            relations = mapOf("domain" to relation("domain")),
            ownership = OwnershipDefinition(type = "Inherited", path = "domain"),
        )
        val domainDef = BlueprintDefinition(ownership = OwnershipDefinition(type = "Direct"))
        val document = doc(mapOf("service" to JsonPrimitive("checkout")))
        val serviceRow = OwnedRow("service", "checkout", doc(mapOf("domain" to JsonPrimitive("commerce"))), null)
        val domainRow = OwnedRow("domain", "commerce", doc(), JsonPrimitive("platform"))
        val lookup = { bp: String, id: String ->
            when (bp to id) {
                "service" to "checkout" -> serviceRow
                "domain" to "commerce" -> domainRow
                else -> null
            }
        }
        val defs = mapOf("service" to serviceDef, "domain" to domainDef)
        assertEquals(JsonPrimitive("platform"), inheritedTeam(document, workload, defs, lookup))
    }

    @Test
    fun `inheritedTeam is absent on a many hop, a missing link, or an unknown target blueprint`() {
        val manyHop = BlueprintDefinition(
            relations = mapOf("service" to relation("service", many = true)),
            ownership = OwnershipDefinition(type = "Inherited", path = "service"),
        )
        assertNull(inheritedTeam(doc(), manyHop, emptyMap()) { _, _ -> error("must not be called") })

        val missingLink = BlueprintDefinition(
            relations = mapOf("service" to relation("service")),
            ownership = OwnershipDefinition(type = "Inherited", path = "service"),
        )
        assertNull(inheritedTeam(doc(), missingLink, mapOf("service" to BlueprintDefinition())) { _, _ -> null })

        val unknownRelation = BlueprintDefinition(ownership = OwnershipDefinition(type = "Inherited", path = "nope"))
        assertNull(inheritedTeam(doc(), unknownRelation, emptyMap()) { _, _ -> null })

        val unresolvedTarget = BlueprintDefinition(
            relations = mapOf("service" to relation("service")),
            ownership = OwnershipDefinition(type = "Inherited", path = "service"),
        )
        val row = OwnedRow("service", "checkout", doc(), null)
        assertNull(
            inheritedTeam(doc(mapOf("service" to JsonPrimitive("checkout"))), unresolvedTarget, emptyMap()) { _, _ -> row },
        )
    }

    @Test
    fun `inheritedTeam is absent when the path is exhausted while still Inherited`() {
        val def = BlueprintDefinition(
            relations = mapOf("service" to relation("service")),
            ownership = OwnershipDefinition(type = "Inherited", path = "service"),
        )
        val serviceDef = BlueprintDefinition(ownership = OwnershipDefinition(type = "Inherited", path = "domain"))
        val row = OwnedRow("service", "checkout", doc(), null)
        val lookup = { bp: String, id: String -> if (bp == "service" && id == "checkout") row else null }
        assertNull(inheritedTeam(doc(mapOf("service" to JsonPrimitive("checkout"))), def, mapOf("service" to serviceDef), lookup))
    }

    @Test
    fun `inheritedTeam gives up once the hop budget is exhausted`() {
        // A chain longer than MAX_OWNERSHIP_HOPS, every hop staying Inherited: the walk must
        // give up rather than loop forever or throw.
        val path = (1..MAX_OWNERSHIP_HOPS + 2).joinToString(".") { "r$it" }
        var def = BlueprintDefinition(
            relations = mapOf("r1" to relation("bp1")),
            ownership = OwnershipDefinition(type = "Inherited", path = path),
        )
        val defsByIdentifier = mutableMapOf<String, BlueprintDefinition>()
        val rowsByKey = mutableMapOf<Pair<String, String>, OwnedRow>()
        for (hop in 1..MAX_OWNERSHIP_HOPS + 2) {
            val bpId = "bp$hop"
            val nextRelationId = "r${hop + 1}"
            val nextBpId = "bp${hop + 1}"
            val hopDef = BlueprintDefinition(
                relations = mapOf(nextRelationId to relation(nextBpId)),
                ownership = OwnershipDefinition(type = "Inherited", path = nextRelationId),
            )
            defsByIdentifier[bpId] = hopDef
            rowsByKey[bpId to "e$hop"] = OwnedRow(bpId, "e$hop", doc(mapOf(nextRelationId to JsonPrimitive("e${hop + 1}"))), null)
        }
        val lookup = { bp: String, id: String -> rowsByKey[bp to id] }
        val document = doc(mapOf("r1" to JsonPrimitive("e1")))
        assertNull(inheritedTeam(document, def, defsByIdentifier, lookup))
    }

    @Test
    fun `effectiveTeam is the stored value for Direct-absent ownership, computed for Inherited`() {
        val direct = BlueprintDefinition(ownership = OwnershipDefinition(type = "Direct"))
        assertEquals(JsonPrimitive("stored"), effectiveTeam(JsonPrimitive("stored"), doc(), direct, emptyMap()) { _, _ -> null })

        val inherited = BlueprintDefinition(
            relations = mapOf("service" to relation("service")),
            ownership = OwnershipDefinition(type = "Inherited", path = "service"),
        )
        val serviceDef = BlueprintDefinition(ownership = OwnershipDefinition(type = "Direct"))
        val row = OwnedRow("service", "checkout", doc(), JsonPrimitive("payments"))
        val lookup = { bp: String, id: String -> if (bp == "service" && id == "checkout") row else null }
        val document = doc(mapOf("service" to JsonPrimitive("checkout")))
        // The STORED team on an Inherited entity (should there be one) is irrelevant — computed wins.
        assertEquals(
            JsonPrimitive("payments"),
            effectiveTeam(JsonPrimitive("ignored"), document, inherited, mapOf("service" to serviceDef), lookup),
        )
    }

    @Test
    fun `ownershipPathBlueprints is empty for non-Inherited or path-less definitions`() {
        assertEquals(emptySet(), ownershipPathBlueprints(BlueprintDefinition(), emptyMap()))
        val noPath = BlueprintDefinition(ownership = OwnershipDefinition(type = "Inherited"))
        assertEquals(emptySet(), ownershipPathBlueprints(noPath, emptyMap()))
    }

    @Test
    fun `ownershipPathBlueprints follows the chain while blueprints stay Inherited, stopping at the first non-Inherited or missing one`() {
        val def = BlueprintDefinition(
            relations = mapOf("service" to relation("service")),
            ownership = OwnershipDefinition(type = "Inherited", path = "service.domain"),
        )
        val serviceDef = BlueprintDefinition(
            relations = mapOf("domain" to relation("domain")),
            ownership = OwnershipDefinition(type = "Inherited", path = "domain"),
        )
        val domainDef = BlueprintDefinition(ownership = OwnershipDefinition(type = "Direct"))
        assertEquals(
            setOf("service", "domain"),
            ownershipPathBlueprints(def, mapOf("service" to serviceDef, "domain" to domainDef)),
        )
        // A target blueprint that doesn't resolve is still added (a harmless extra lookup) but stops the walk.
        assertEquals(setOf("service", "domain"), ownershipPathBlueprints(def, mapOf("service" to serviceDef)))
        val manyHopDef = BlueprintDefinition(
            relations = mapOf("service" to relation("service", many = true)),
            ownership = OwnershipDefinition(type = "Inherited", path = "service"),
        )
        assertEquals(emptySet(), ownershipPathBlueprints(manyHopDef, emptyMap()))
    }

    // -------------------------------------------------------------------------------------
    // HTTP: create/save findings, rename cascade, delete referrer, Inherited read path
    // -------------------------------------------------------------------------------------

    private suspend fun HttpClient.createBlueprint(request: BlueprintRequest) =
        postJson("/api/v1/blueprints", request).body<BlueprintResponse>()

    private fun teamEntity(identifier: String) =
        EntityRequest(blueprint = SYSTEM_TEAM_BLUEPRINT, identifier = identifier, title = identifier)

    private fun userEntity(identifier: String) = EntityRequest(
        blueprint = SYSTEM_USER_BLUEPRINT,
        identifier = identifier,
        title = identifier,
        properties = buildJsonObject { put("email", identifier) },
    )

    @Test
    fun `create with an unknown team is a 400 whose findings carry TEAM_TARGET_MISSING`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-own-unknown-team", UserRole.ADMIN)
        val bpId = unique("bp-own")
        try {
            client.createBlueprint(BlueprintRequest(identifier = bpId, title = "T", schema = BlueprintSchema()))
            val response = client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = bpId, identifier = unique("ent"), title = "T", team = JsonPrimitive("ghost")),
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val problem = response.body<EntityInvalidProblem>()
            assertEquals(listOf("TEAM_TARGET_MISSING"), problem.findings.map { it.code })
            assertEquals("team", problem.findings.single().field)
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `create with a real _team entity is 201, string and array shapes both work`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-own-real-team", UserRole.ADMIN)
        val bpId = unique("bp-own")
        val teamId = unique("team")
        val teamId2 = unique("team2")
        val entId = unique("ent")
        val entId2 = unique("ent2")
        try {
            client.createBlueprint(BlueprintRequest(identifier = bpId, title = "T", schema = BlueprintSchema()))
            client.postJson("/api/v1/entities", teamEntity(teamId))
            client.postJson("/api/v1/entities", teamEntity(teamId2))

            val single = client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = bpId, identifier = entId, title = "T", team = JsonPrimitive(teamId)),
            )
            assertEquals(HttpStatusCode.Created, single.status)
            assertEquals(teamId, single.body<EntityResponse>().team?.jsonPrimitive?.content)

            val array = client.postJson(
                "/api/v1/entities",
                EntityRequest(
                    blueprint = bpId, identifier = entId2, title = "T",
                    team = JsonArray(listOf(JsonPrimitive(teamId), JsonPrimitive(teamId2))),
                ),
            )
            assertEquals(HttpStatusCode.Created, array.status)
            assertEquals(listOf(teamId, teamId2), array.body<EntityResponse>().team?.jsonArray?.map { it.jsonPrimitive.content })
        } finally {
            TestEntities.remove(entId, entId2, teamId, teamId2)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `renaming a _team entity cascades into a referrer's team column and format-team property, audited cascaded`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-own-cascade-team", UserRole.ADMIN)
        val bpId = unique("bp-own-cascade")
        val oldTeamId = unique("team-old")
        val newTeamId = unique("team-new")
        val ownerId = unique("ent-owner")
        try {
            client.createBlueprint(
                BlueprintRequest(
                    identifier = bpId,
                    title = "T",
                    schema = BlueprintSchema(properties = mapOf("owningTeam" to PropertyDefinition(type = "string", format = "team"))),
                ),
            )
            val team = client.postJson("/api/v1/entities", teamEntity(oldTeamId)).body<EntityResponse>()
            client.postJson(
                "/api/v1/entities",
                EntityRequest(
                    blueprint = bpId,
                    identifier = ownerId,
                    title = "T",
                    team = JsonPrimitive(oldTeamId),
                    properties = buildJsonObject { put("owningTeam", oldTeamId) },
                ),
            )

            withAuditCapture { capture ->
                val rename = client.putJson("/api/v1/entities/${team.id}", teamEntity(newTeamId))
                assertEquals(HttpStatusCode.NoContent, rename.status)
                val event = capture.awaitEvent { it.message == "entity.updated" && it.hasKeyValue("entityId", team.id.toLong()) }
                assertTrue(event != null && event.hasKeyValue("cascaded", 1), "expected a cascaded=1 entity.updated audit event")
            }

            val owner = client.get("/api/v1/entities?blueprint=$bpId").body<ch.nokillswit.entities.EntityPageResponse>()
                .items.single { it.identifier == ownerId }
            assertEquals(newTeamId, owner.team?.jsonPrimitive?.content)
            assertEquals(newTeamId, owner.properties.getValue("owningTeam").jsonPrimitive.content)
        } finally {
            TestEntities.remove(ownerId, oldTeamId, newTeamId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `deleting a referenced _team entity is 409 naming the referrer, then 204 once dropped`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-own-delete-team", UserRole.ADMIN)
        val bpId = unique("bp-own-del")
        val teamId = unique("team-del")
        val ownerId = unique("ent-owner-del")
        try {
            client.createBlueprint(BlueprintRequest(identifier = bpId, title = "T", schema = BlueprintSchema()))
            val team = client.postJson("/api/v1/entities", teamEntity(teamId)).body<EntityResponse>()
            val owner = client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = bpId, identifier = ownerId, title = "T", team = JsonPrimitive(teamId)),
            ).body<EntityResponse>()

            val blocked = client.delete("/api/v1/entities/${team.id}")
            assertEquals(HttpStatusCode.Conflict, blocked.status)
            assertTrue(blocked.body<ch.nokillswit.plugins.ProblemDetail>().detail!!.contains("$bpId/$ownerId"))

            val cleared = client.putJson("/api/v1/entities/${owner.id}", EntityRequest(blueprint = bpId, identifier = ownerId, title = "T"))
            assertEquals(HttpStatusCode.NoContent, cleared.status)
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/entities/${team.id}").status)
        } finally {
            TestEntities.remove(ownerId, teamId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `format user must resolve to an active _user entity, distinct from _team`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-own-format-user", UserRole.ADMIN)
        val bpId = unique("bp-own-user")
        val userId = uniqueEmail("owner")
        try {
            client.createBlueprint(
                BlueprintRequest(
                    identifier = bpId,
                    title = "T",
                    schema = BlueprintSchema(properties = mapOf("owner" to PropertyDefinition(type = "string", format = "user"))),
                ),
            )
            val missing = client.postJson(
                "/api/v1/entities",
                EntityRequest(
                    blueprint = bpId, identifier = unique("ent"), title = "T",
                    properties = buildJsonObject { put("owner", userId) },
                ),
            )
            assertEquals(HttpStatusCode.BadRequest, missing.status)
            assertEquals(listOf("USER_TARGET_MISSING"), missing.body<EntityInvalidProblem>().findings.map { it.code })

            client.postJson("/api/v1/entities", userEntity(userId))
            val ok = client.postJson(
                "/api/v1/entities",
                EntityRequest(
                    blueprint = bpId, identifier = unique("ent"), title = "T",
                    properties = buildJsonObject { put("owner", userId) },
                ),
            )
            assertEquals(HttpStatusCode.Created, ok.status)
        } finally {
            TestEntities.remove(userId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `Inherited blueprint - supplied team is 400, absent team resolves and follows the source`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-own-inherited", UserRole.ADMIN)
        val serviceBp = unique("bp-own-service")
        val workloadBp = unique("bp-own-workload")
        val teamId = unique("team-inh")
        val newTeamId = unique("team-inh-2")
        val serviceId = unique("ent-service")
        val workloadId = unique("ent-workload")
        try {
            client.createBlueprint(BlueprintRequest(identifier = serviceBp, title = "T", schema = BlueprintSchema()))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = workloadBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf(
                        "service" to RelationDefinition(title = "Service", target = serviceBp, required = false, many = false),
                    ),
                    ownership = OwnershipDefinition(type = "Inherited", path = "service"),
                ),
            )
            val team = client.postJson("/api/v1/entities", teamEntity(teamId)).body<EntityResponse>()
            client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = serviceBp, identifier = serviceId, title = "T", team = JsonPrimitive(teamId)),
            )

            val rejected = client.postJson(
                "/api/v1/entities",
                EntityRequest(
                    blueprint = workloadBp, identifier = workloadId, title = "T", team = JsonPrimitive(teamId),
                    relations = buildJsonObject { put("service", serviceId) },
                ),
            )
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertEquals(listOf("TEAM_NOT_ALLOWED"), rejected.body<EntityInvalidProblem>().findings.map { it.code })

            val created = client.postJson(
                "/api/v1/entities",
                EntityRequest(
                    blueprint = workloadBp, identifier = workloadId, title = "T",
                    relations = buildJsonObject { put("service", serviceId) },
                ),
            )
            assertEquals(HttpStatusCode.Created, created.status)
            val createdBody = created.body<EntityResponse>()
            assertEquals(teamId, createdBody.team?.jsonPrimitive?.content)
            assertFalse(created.bodyAsText().contains("\"team\":null"))

            val read = client.get("/api/v1/entities/${createdBody.id}").body<EntityResponse>()
            assertEquals(teamId, read.team?.jsonPrimitive?.content)
            val listed = client.get("/api/v1/entities?blueprint=$workloadBp").body<ch.nokillswit.entities.EntityPageResponse>()
                .items.single { it.identifier == workloadId }
            assertEquals(teamId, listed.team?.jsonPrimitive?.content)

            // The source's team changes: the workload's effective team follows, unstored.
            client.putJson("/api/v1/entities/${team.id}", teamEntity(newTeamId))
            val followed = client.get("/api/v1/entities/${createdBody.id}").body<EntityResponse>()
            assertEquals(newTeamId, followed.team?.jsonPrimitive?.content)
        } finally {
            TestEntities.remove(serviceId, workloadId, teamId, newTeamId)
            TestBlueprints.remove(serviceBp, workloadBp)
        }
    }

    @Test
    fun `Inherited blueprint - a missing link never surfaces a team key in the raw body`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-own-inherited-missing", UserRole.ADMIN)
        val serviceBp = unique("bp-own-service-m")
        val workloadBp = unique("bp-own-workload-m")
        val workloadId = unique("ent-workload-m")
        try {
            client.createBlueprint(BlueprintRequest(identifier = serviceBp, title = "T", schema = BlueprintSchema()))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = workloadBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf(
                        "service" to RelationDefinition(title = "Service", target = serviceBp, required = false, many = false),
                    ),
                    ownership = OwnershipDefinition(type = "Inherited", path = "service"),
                ),
            )
            // No `service` relation value supplied at all: a missing link, not an unresolved one.
            val created = client.postJson("/api/v1/entities", EntityRequest(blueprint = workloadBp, identifier = workloadId, title = "T"))
            assertEquals(HttpStatusCode.Created, created.status)
            assertFalse(created.bodyAsText().contains("\"team\""))
        } finally {
            TestEntities.remove(workloadId)
            TestBlueprints.remove(serviceBp, workloadBp)
        }
    }

    @Test
    fun `stale - switching a blueprint to Inherited flags a stored team, and blocks the next save until fixed`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ent-own-stale", UserRole.ADMIN)
        val serviceBp = unique("bp-own-stale-service")
        val bpId = unique("bp-own-stale")
        val teamId = unique("team-stale")
        val serviceId = unique("ent-service-stale")
        val entId = unique("ent-stale")
        try {
            val blueprint = client.createBlueprint(BlueprintRequest(identifier = bpId, title = "T", schema = BlueprintSchema()))
            client.createBlueprint(BlueprintRequest(identifier = serviceBp, title = "T", schema = BlueprintSchema()))
            client.postJson("/api/v1/entities", teamEntity(teamId))
            client.postJson("/api/v1/entities", EntityRequest(blueprint = serviceBp, identifier = serviceId, title = "T"))
            val created = client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = bpId, identifier = entId, title = "T", team = JsonPrimitive(teamId)),
            ).body<EntityResponse>()

            // Switch the blueprint to Inherited AFTER the entity already stored a Direct team.
            client.putJson(
                "/api/v1/blueprints/${blueprint.id}",
                BlueprintRequest(
                    identifier = bpId,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf(
                        "service" to RelationDefinition(title = "Service", target = serviceBp, required = false, many = false),
                    ),
                    ownership = OwnershipDefinition(type = "Inherited", path = "service"),
                ),
            )

            val stale = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
            assertEquals(listOf("TEAM_NOT_ALLOWED"), stale.findings.map { it.code })

            val nextSave = client.putJson(
                "/api/v1/entities/${created.id}",
                EntityRequest(blueprint = bpId, identifier = entId, title = "T", team = JsonPrimitive(teamId)),
            )
            assertEquals(HttpStatusCode.BadRequest, nextSave.status)
            assertEquals(listOf("TEAM_NOT_ALLOWED"), nextSave.body<EntityInvalidProblem>().findings.map { it.code })
        } finally {
            TestEntities.remove(entId, serviceId, teamId)
            TestBlueprints.remove(bpId, serviceBp)
        }
    }
}
