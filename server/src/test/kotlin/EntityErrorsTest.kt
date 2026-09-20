package ch.nokillswit

import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.MirrorPropertyDefinition
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.entities.EntityErrorsReport
import ch.nokillswit.entities.EntityGraphFilter
import ch.nokillswit.entities.EntityReadLedger
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.entities.JqEvaluator
import ch.nokillswit.entities.SavedQueryErrorRow
import ch.nokillswit.entities.errors
import ch.nokillswit.entityquery.SavedEntityQuery
import ch.nokillswit.entityquery.SavedEntityQueryRequest
import ch.nokillswit.entityquery.SavedEntityQueryVisibility
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Port-world Errors report's route surface (2.5.0 — `GET /api/v1/entities/errors`, the
 * `catalog/Errors.kt`/`GET /api/v1/files/errors` twin one level over): any authenticated user,
 * no admin gate, never audited. See `EntityErrorsCheckTest` for the pure checker coverage this
 * file does not repeat. Every test mints unique `bp-<uuid8>`/`ent-<uuid8>`/`eq-<uuid8>`
 * identifiers and cleans up via [TestEntities]/[TestBlueprints]/[TestSavedEntityQueries] in
 * `finally`.
 */
class EntityErrorsTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    /**
     * Every fixture blueprint carries a `sourceUrl` by default (2.10.0) so pre-existing
     * exact-findings and "stays clean" assertions in this file are unaffected by
     * `SOURCE_MISSING` — pass `sourceUrl = null` explicitly on the request to build a
     * source-less fixture for the dedicated SOURCE_MISSING case below.
     */
    private suspend fun HttpClient.createBlueprint(request: BlueprintRequest) = postJson(
        "/api/v1/blueprints",
        request.copy(sourceUrl = request.sourceUrl ?: "https://example.com/blueprints/${request.identifier}.json"),
    ).body<BlueprintResponse>()

    /**
     * Every fixture entity carries a `sourceUrl` by default (2.9.1) so pre-existing exact-findings
     * and "stays clean" assertions in this file are unaffected by `SOURCE_MISSING` — pass
     * `sourceUrl = null` to build a source-less fixture for the dedicated SOURCE_MISSING case below.
     */
    private fun entityRequest(
        blueprint: String,
        identifier: String,
        properties: kotlinx.serialization.json.JsonObject = buildJsonObject { },
        sourceUrl: String? = "https://example.com/entities/$identifier.json",
    ) = EntityRequest(
        blueprint = blueprint, identifier = identifier, title = "Title $identifier", properties = properties, sourceUrl = sourceUrl,
    )

    private fun teamEntity(identifier: String) = EntityRequest(
        blueprint = SYSTEM_TEAM_BLUEPRINT, identifier = identifier, title = identifier,
        sourceUrl = "https://example.com/entities/$identifier.json",
    )

    private suspend fun HttpClient.readErrors(query: String = ""): EntityErrorsReport =
        get("/api/v1/entities/errors$query").body()

    /** A syntax-valid query that never resolves — for saved-query fixtures that need a stale but PARSEABLE text. */
    private fun ghostQuery() = "MATCH (n:`ghost-${UUID.randomUUID()}`) RETURN n"

    /** Models jackson-jq's own indifference to interruption — the `JqCalculationTest` idiom. */
    private fun awaitLatchIgnoringInterrupts(latch: CountDownLatch) {
        while (latch.count > 0) {
            try {
                latch.await(20, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // Deliberately model an uninterruptible worker.
            }
        }
    }

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/entities/errors").status)
    }

    @Test
    fun `a USER - no admin needed - is served, and the literal errors segment beats the id route`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-user", UserRole.USER)
        // Would 400 ("id must be a UInt") if {id} swallowed the literal "errors" segment.
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/entities/errors").status)
    }

    @Test
    fun `a stale entity reports REQUIRED_MISSING with its effective team and blueprintTitle, clearing once fixed`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-stale", UserRole.ADMIN)
        val bp = unique("bp-ee-stale")
        val team = unique("team-ee-stale")
        val ent = unique("ent-ee-stale")
        try {
            val blueprint = client.createBlueprint(BlueprintRequest(identifier = bp, title = "Stale Title", schema = BlueprintSchema()))
            client.postJson("/api/v1/entities", teamEntity(team))
            val created = client.postJson(
                "/api/v1/entities",
                entityRequest(bp, ent).copy(team = JsonPrimitive(team)),
            ).body<EntityResponse>()
            assertTrue(client.readErrors("?blueprint=$bp").entities.none { it.identifier == ent })

            val stricter = BlueprintRequest(
                identifier = bp, title = "Stale Title",
                schema = BlueprintSchema(
                    properties = mapOf("language" to PropertyDefinition(type = "string")),
                    required = listOf("language"),
                ),
            )
            assertEquals(HttpStatusCode.NoContent, client.putJson("/api/v1/blueprints/${blueprint.id}", stricter).status)

            val report = client.readErrors("?blueprint=$bp")
            val row = report.entities.single { it.identifier == ent }
            assertEquals(listOf("REQUIRED_MISSING"), row.findings.map { it.code })
            assertEquals(bp, row.blueprint)
            assertEquals("Stale Title", row.blueprintTitle)
            assertEquals(listOf(team), row.team)

            val fixed = entityRequest(bp, ent, buildJsonObject { put("language", "kotlin") }).copy(team = JsonPrimitive(team))
            assertEquals(HttpStatusCode.NoContent, client.putJson("/api/v1/entities/${created.id}", fixed).status)
            assertTrue(client.readErrors("?blueprint=$bp").entities.none { it.identifier == ent })
        } finally {
            TestEntities.remove(ent)
            TestBlueprints.remove(bp)
        }
    }

    @Test
    fun `an Inherited entity with an unresolved team is OWNERSHIP_UNRESOLVED until the relation is set`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-own-unresolved", UserRole.ADMIN)
        val directBp = unique("bp-ee-ownd")
        val inheritedBp = unique("bp-ee-owni")
        val team = unique("team-ee-own")
        val owner = unique("ent-ee-owner")
        val child = unique("ent-ee-child")
        try {
            client.createBlueprint(
                BlueprintRequest(
                    identifier = directBp, title = "T", schema = BlueprintSchema(),
                    ownership = OwnershipDefinition(type = "Direct"),
                ),
            )
            client.createBlueprint(
                BlueprintRequest(
                    identifier = inheritedBp, title = "T", schema = BlueprintSchema(),
                    relations = mapOf(
                        "owner" to RelationDefinition(title = "Owner", target = directBp, required = false, many = false),
                    ),
                    ownership = OwnershipDefinition(type = "Inherited", path = "owner"),
                ),
            )
            client.postJson("/api/v1/entities", teamEntity(team))
            client.postJson(
                "/api/v1/entities",
                EntityRequest(blueprint = directBp, identifier = owner, title = "T", team = JsonPrimitive(team)),
            )
            val childEntity = client.postJson("/api/v1/entities", entityRequest(inheritedBp, child)).body<EntityResponse>()

            val unresolved = client.readErrors("?blueprint=$inheritedBp").entities.single { it.identifier == child }
            assertEquals(listOf("OWNERSHIP_UNRESOLVED"), unresolved.findings.map { it.code })
            assertTrue(unresolved.team.isEmpty())

            val resolved = entityRequest(inheritedBp, child).copy(relations = buildJsonObject { put("owner", owner) })
            assertEquals(HttpStatusCode.NoContent, client.putJson("/api/v1/entities/${childEntity.id}", resolved).status)
            assertTrue(client.readErrors("?blueprint=$inheritedBp").entities.none { it.identifier == child })
        } finally {
            TestEntities.remove(owner, child)
            TestBlueprints.remove(directBp, inheritedBp)
        }
    }

    @Test
    fun `a mirror path broken by editing the TARGET blueprint is MIRROR_PATH_STALE on the mirroring blueprint`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-mirror", UserRole.ADMIN)
        val targetBp = unique("bp-ee-mirror-t")
        val ownBp = unique("bp-ee-mirror-o")
        try {
            val target = client.createBlueprint(
                BlueprintRequest(
                    identifier = targetBp, title = "Target",
                    schema = BlueprintSchema(properties = mapOf("name" to PropertyDefinition(type = "string"))),
                ),
            )
            val own = client.createBlueprint(
                BlueprintRequest(
                    identifier = ownBp, title = "Own", schema = BlueprintSchema(),
                    relations = mapOf(
                        "rel" to RelationDefinition(title = "Rel", target = targetBp, required = false, many = false),
                    ),
                    mirrorProperties = mapOf("m" to MirrorPropertyDefinition("Mirror", "rel.name")),
                ),
            )
            assertTrue(client.readErrors("?blueprint=$ownBp").blueprints.none { it.identifier == ownBp })

            // Removing "name" from the TARGET breaks own's mirror path without own's OWN
            // definition ever being touched — exactly what no rename/delete cascade covers.
            val strippedTarget = BlueprintRequest(identifier = targetBp, title = "Target", schema = BlueprintSchema())
            assertEquals(
                HttpStatusCode.NoContent,
                client.putJson("/api/v1/blueprints/${target.id}", strippedTarget).status,
            )

            val row = client.readErrors("?blueprint=$ownBp").blueprints.single { it.identifier == ownBp }
            assertEquals(listOf("MIRROR_PATH_STALE"), row.findings.map { it.code })
            assertEquals("mirrorProperties.m", row.findings.single().field)
            assertEquals(own.id, row.id)
        } finally {
            TestBlueprints.remove(ownBp, targetBp)
        }
    }

    @Test
    fun `an ownership path broken on a target blueprint reports once and suppresses the per-entity finding`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-ownpath", UserRole.ADMIN)
        val orgBp = unique("bp-ee-op-org")
        val midBp = unique("bp-ee-op-mid")
        val childBp = unique("bp-ee-op-child")
        val childEnt = unique("ent-ee-op-child")
        try {
            client.createBlueprint(
                BlueprintRequest(
                    identifier = orgBp, title = "T", schema = BlueprintSchema(),
                    ownership = OwnershipDefinition(type = "Direct"),
                ),
            )
            val mid = client.createBlueprint(
                BlueprintRequest(
                    identifier = midBp, title = "Mid", schema = BlueprintSchema(),
                    relations = mapOf(
                        "org" to RelationDefinition(title = "Org", target = orgBp, required = false, many = false),
                        "grandOrg" to RelationDefinition(title = "GrandOrg", target = orgBp, required = false, many = false),
                    ),
                    // midBp's OWN ownership chain uses "grandOrg" — independent of the "org"
                    // relation childBp's 2-hop path below reaches through it.
                    ownership = OwnershipDefinition(type = "Inherited", path = "grandOrg"),
                ),
            )
            client.createBlueprint(
                BlueprintRequest(
                    identifier = childBp, title = "Child", schema = BlueprintSchema(),
                    relations = mapOf(
                        "mid" to RelationDefinition(title = "Mid", target = midBp, required = false, many = false),
                    ),
                    ownership = OwnershipDefinition(type = "Inherited", path = "mid.org"),
                ),
            )
            client.postJson("/api/v1/entities", entityRequest(childBp, childEnt))

            // "org" removed from midBp — a DIFFERENT blueprint's write — leaves childBp's own
            // (untouched) ownership.path unable to resolve its second hop.
            val grandOrgRelation = mapOf(
                "grandOrg" to RelationDefinition(title = "GrandOrg", target = orgBp, required = false, many = false),
            )
            val strippedMid = BlueprintRequest(
                identifier = midBp, title = "Mid", schema = BlueprintSchema(),
                relations = grandOrgRelation,
                ownership = OwnershipDefinition(type = "Inherited", path = "grandOrg"),
            )
            assertEquals(HttpStatusCode.NoContent, client.putJson("/api/v1/blueprints/${mid.id}", strippedMid).status)

            val report = client.readErrors("?blueprint=$childBp")
            val blueprintRow = report.blueprints.single { it.identifier == childBp }
            assertEquals(listOf("OWNERSHIP_PATH_STALE"), blueprintRow.findings.map { it.code })
            assertEquals("ownership.path", blueprintRow.findings.single().field)
            // Suppression: no per-entity OWNERSHIP_UNRESOLVED row for childEnt, even though its
            // own effective team is unresolved too.
            assertTrue(report.entities.none { it.identifier == childEnt })
        } finally {
            TestEntities.remove(childEnt)
            TestBlueprints.remove(childBp, midBp, orgBp)
        }
    }

    @Test
    fun `a calculation that fails to compile is CALCULATION_COMPILE_FAILED with jackson-jq's own message`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-compile", UserRole.ADMIN)
        val bp = unique("bp-ee-compile")
        try {
            val broken = CalculationPropertyDefinition("Broken", "string", calculation = "not valid jq ((")
            val blueprint = client.createBlueprint(
                BlueprintRequest(
                    identifier = bp, title = "T", schema = BlueprintSchema(),
                    calculationProperties = mapOf("broken" to broken),
                ),
            )
            val row = client.readErrors("?blueprint=$bp").blueprints.single { it.identifier == bp }
            assertEquals(listOf("CALCULATION_COMPILE_FAILED"), row.findings.map { it.code })
            assertEquals("calculationProperties.broken", row.findings.single().field)
            assertTrue(row.findings.single().message.isNotBlank())
            assertEquals(blueprint.id, row.id)
        } finally {
            TestBlueprints.remove(bp)
        }
    }

    @Test
    fun `a calculation quarantined by a deadline miss is CALCULATION_QUARANTINED via the jq seam`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-quarantine", UserRole.ADMIN)
        val callerId = TestUsers.seed(uniqueEmail("ee-quarantine-caller"), "pw")
        val bp = unique("bp-ee-quarantine")
        val ent = unique("ent-ee-quarantine")
        val releaseWorker = CountDownLatch(1)
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
        val jq = JqEvaluator(
            deadline = Duration.ofMillis(50),
            executor = executor,
            beforeEvaluate = { awaitLatchIgnoringInterrupts(releaseWorker) },
        )
        try {
            val slow = CalculationPropertyDefinition("Slow", "string", calculation = ".identifier")
            client.createBlueprint(
                BlueprintRequest(
                    identifier = bp, title = "T", schema = BlueprintSchema(),
                    calculationProperties = mapOf("slow" to slow),
                ),
            )
            val created = client.postJson("/api/v1/entities", entityRequest(bp, ent)).body<EntityResponse>()

            val tuned = TestEntities.tunedService(jq = jq)
            // A read through the TUNED service evaluates "slow" on the held pool, missing its
            // 50ms deadline (the worker never returns before the test releases the latch) and
            // quarantining the expression — the SAME instance's [errors] then sees it.
            tuned.read(created.id)

            val report = tuned.errors(EntityGraphFilter(listOf(bp), q = null), callerId)
            val row = report.blueprints.single { it.identifier == bp }
            assertEquals(listOf("CALCULATION_QUARANTINED"), row.findings.map { it.code })
        } finally {
            releaseWorker.countDown()
            executor.shutdownNow()
            TestEntities.remove(ent)
            TestBlueprints.remove(bp)
        }
    }

    @Test
    fun `saved queries - own PRIVATE broken reported, foreign PRIVATE not, foreign PUBLIC reported with creator`() = testApplication {
        usePostgresTestcontainer()
        val alice = seededClient("ee-eq-alice")
        val bob = seededClient("ee-eq-bob")
        val ids = mutableListOf<UInt>()
        try {
            fun eqName() = "eq-${UUID.randomUUID().toString().substring(0, 8)}"

            val ownPrivateName = eqName()
            val ownPrivate = alice.postJson(
                "/api/v1/entity-queries",
                SavedEntityQueryRequest(ownPrivateName, SavedEntityQueryVisibility.PRIVATE, ghostQuery()),
            ).body<SavedEntityQuery>()
            ids += ownPrivate.id

            val foreignPrivateName = eqName()
            val foreignPrivate = bob.postJson(
                "/api/v1/entity-queries",
                SavedEntityQueryRequest(foreignPrivateName, SavedEntityQueryVisibility.PRIVATE, ghostQuery()),
            ).body<SavedEntityQuery>()
            ids += foreignPrivate.id

            val foreignPublicName = eqName()
            val foreignPublic = bob.postJson(
                "/api/v1/entity-queries",
                SavedEntityQueryRequest(foreignPublicName, SavedEntityQueryVisibility.PUBLIC, ghostQuery()),
            ).body<SavedEntityQuery>()
            ids += foreignPublic.id

            val validName = eqName()
            val valid = alice.postJson(
                "/api/v1/entity-queries",
                SavedEntityQueryRequest(validName, SavedEntityQueryVisibility.PRIVATE, "MATCH (a) RETURN a"),
            ).body<SavedEntityQuery>()
            ids += valid.id

            val report = alice.readErrors()
            val names = report.savedQueries.map { it.name }.toSet()
            assertTrue(ownPrivateName in names)
            assertFalse(foreignPrivateName in names)
            assertTrue(foreignPublicName in names)
            assertFalse(validName in names)

            val foreignRow: SavedQueryErrorRow = report.savedQueries.single { it.name == foreignPublicName }
            assertEquals(foreignPublic.createdBy, foreignRow.createdBy)
            assertTrue(foreignRow.diagnostics.isNotEmpty())
        } finally {
            ids.forEach { TestSavedEntityQueries.remove(it) }
        }
    }

    @Test
    fun `filters narrow entity-blueprint rows, never saved queries, and an all-unknown blueprint keeps saved queries`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("ee-filters", UserRole.ADMIN)
            val directBp = unique("bp-ee-f-direct")
            val inheritedBp = unique("bp-ee-f-inh")
            val teamA = unique("team-ee-f-a")
            val teamB = unique("team-ee-f-b")
            val p1 = unique("ent-ee-f-p1")
            val p2 = unique("ent-ee-f-p2")
            val c1 = unique("ent-ee-f-c1")
            val queryIds = mutableListOf<UInt>()
            try {
                client.createBlueprint(
                    BlueprintRequest(
                        identifier = directBp, title = "T", schema = BlueprintSchema(),
                        ownership = OwnershipDefinition(type = "Direct"),
                    ),
                )
                client.createBlueprint(
                    BlueprintRequest(
                        identifier = inheritedBp, title = "T", schema = BlueprintSchema(),
                        relations = mapOf(
                            "owner" to RelationDefinition(title = "Owner", target = directBp, required = false, many = false),
                        ),
                        ownership = OwnershipDefinition(type = "Inherited", path = "owner"),
                    ),
                )
                client.postJson("/api/v1/entities", teamEntity(teamA))
                client.postJson("/api/v1/entities", teamEntity(teamB))
                client.postJson(
                    "/api/v1/entities",
                    EntityRequest(blueprint = directBp, identifier = p1, title = "T", team = JsonPrimitive(teamA)),
                )
                client.postJson(
                    "/api/v1/entities",
                    EntityRequest(blueprint = directBp, identifier = p2, title = "T", team = JsonPrimitive(teamB)),
                )
                client.postJson(
                    "/api/v1/entities",
                    entityRequest(inheritedBp, c1).copy(relations = buildJsonObject { put("owner", p1) }),
                )

                // Force a required-property REQUIRED_MISSING finding on p1/p2 (both directBp)
                // so filtering narrows something OBSERVABLE on top of the base team/q filters;
                // c1 stays clean (its Inherited team resolves fine through p1) — omitted from
                // `entities` (zero findings), but still counted in `checkedEntities`, proving
                // the team/blueprint filters narrowed the CANDIDATE set the report considered,
                // not merely which rows happened to carry a finding.
                val stricter = BlueprintRequest(
                    identifier = directBp, title = "T", ownership = OwnershipDefinition(type = "Direct"),
                    schema = BlueprintSchema(
                        properties = mapOf("x" to PropertyDefinition(type = "string")),
                        required = listOf("x"),
                    ),
                )
                val allBlueprints = client.get("/api/v1/blueprints").body<ch.nokillswit.blueprints.BlueprintList>().items
                val direct = allBlueprints.single { it.identifier == directBp }
                assertEquals(HttpStatusCode.NoContent, client.putJson("/api/v1/blueprints/${direct.id}", stricter).status)

                val byTeamA = client.readErrors("?team=$teamA")
                assertEquals(setOf(p1), byTeamA.entities.map { it.identifier }.toSet())
                // p1, c1, AND the "_team" node named teamA itself (the list/graph self-match rule
                // — a team-filtered view keeps that team's own node).
                assertEquals(3, byTeamA.checkedEntities, "team=$teamA must consider p1, c1, and the team's own node, findings or not")

                val byTeamAAndBlueprint = client.readErrors("?team=$teamA&blueprint=$inheritedBp")
                assertTrue(byTeamAAndBlueprint.entities.isEmpty(), "c1 is clean — narrowed to it alone, reported nothing")
                assertEquals(1, byTeamAAndBlueprint.checkedEntities)

                val byQ = client.readErrors("?q=$p2")
                assertEquals(setOf(p2), byQ.entities.map { it.identifier }.toSet())

                // A saved query survives an all-unknown blueprint filter that empties everything else.
                val eqName = "eq-${UUID.randomUUID().toString().substring(0, 8)}"
                val savedQuery = client.postJson(
                    "/api/v1/entity-queries",
                    SavedEntityQueryRequest(eqName, SavedEntityQueryVisibility.PRIVATE, ghostQuery()),
                ).body<SavedEntityQuery>()
                queryIds += savedQuery.id

                val allUnknown = client.readErrors("?blueprint=${unique("bp-ee-f-unknown")}")
                assertTrue(allUnknown.entities.isEmpty())
                assertTrue(allUnknown.blueprints.isEmpty())
                assertTrue(allUnknown.savedQueries.any { it.name == eqName })
            } finally {
                queryIds.forEach { TestSavedEntityQueries.remove(it) }
                TestEntities.remove(p1, p2, c1)
                TestBlueprints.remove(directBp, inheritedBp)
            }
        }

    @Test
    fun `the report is never audited`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-noaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/entities/errors").status)
            assertTrue(capture.events.none { it.formattedMessage.contains("entity") })
        }
    }

    @Test
    fun `the read budget refuses this call's own oversized workspace with 400 and sibling contention with 429`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-budget", UserRole.ADMIN)
        val callerId = TestUsers.seed(uniqueEmail("ee-budget-caller"), "pw")
        val bp = unique("bp-ee-budget")
        val id = unique("ent-ee-budget")
        try {
            val selfRelation = RelationDefinition(title = "Self", target = bp, required = false, many = false)
            client.createBlueprint(BlueprintRequest(identifier = bp, title = "T", relations = mapOf("self" to selfRelation)))
            client.postJson("/api/v1/entities", entityRequest(bp, id))

            val tinyLedger = EntityReadLedger(capacity = 1)
            val tuned = TestEntities.tunedService(readLedger = tinyLedger)
            assertFailsWith<BadRequestException> { tuned.errors(EntityGraphFilter(listOf(bp), q = null), callerId) }

            val capacity = 8L * 1024 * 1024
            val busyLedger = EntityReadLedger(capacity = capacity)
            val other = busyLedger.open().apply { charge(capacity - 1) }
            try {
                val busy = TestEntities.tunedService(readLedger = busyLedger)
                assertFailsWith<TooManyRequestsException> { busy.errors(EntityGraphFilter(listOf(bp), q = null), callerId) }
            } finally {
                other.close()
            }
        } finally {
            TestEntities.remove(id)
            TestBlueprints.remove(bp)
        }
    }

    @Test
    fun `a source-less entity reports SOURCE_MISSING, cleared once a sourceUrl is set`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-source", UserRole.ADMIN)
        val bp = unique("bp-ee-source")
        val ent = unique("ent-ee-source")
        try {
            client.createBlueprint(BlueprintRequest(identifier = bp, title = "T", schema = BlueprintSchema()))
            val created = client.postJson("/api/v1/entities", entityRequest(bp, ent, sourceUrl = null)).body<EntityResponse>()

            val report = client.readErrors("?blueprint=$bp")
            val row = report.entities.single { it.identifier == ent }
            assertEquals(listOf("SOURCE_MISSING"), row.findings.map { it.code })
            assertEquals("source", row.findings.single().field)

            val withSource = entityRequest(bp, ent, sourceUrl = "https://example.com/entities/$ent.json")
            assertEquals(HttpStatusCode.NoContent, client.putJson("/api/v1/entities/${created.id}", withSource).status)
            assertTrue(client.readErrors("?blueprint=$bp").entities.none { it.identifier == ent })
        } finally {
            TestEntities.remove(ent)
            TestBlueprints.remove(bp)
        }
    }

    @Test
    fun `a source-less blueprint reports SOURCE_MISSING last, cleared once a sourceUrl is PUT`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-bp-source", UserRole.ADMIN)
        val bp = unique("bp-ee-bp-source")
        try {
            // Bypasses createBlueprint's own default-source idiom: this fixture must start
            // source-less, which the route helper above can no longer produce.
            val created = TestBlueprints.service.create(
                BlueprintRequest(
                    identifier = bp, title = "T",
                    calculationProperties = mapOf("broken" to CalculationPropertyDefinition("Broken", "string", calculation = "((")),
                ),
                callerId = 1u,
            )

            val report = client.readErrors("?blueprint=$bp")
            val row = report.blueprints.single { it.identifier == bp }
            assertEquals(listOf("CALCULATION_COMPILE_FAILED", "SOURCE_MISSING"), row.findings.map { it.code })
            assertEquals("source", row.findings.last().field)

            assertEquals(
                HttpStatusCode.NoContent,
                client.putJson(
                    "/api/v1/blueprints/${created.id}",
                    BlueprintRequest(
                        identifier = bp, title = "T",
                        calculationProperties = mapOf("broken" to CalculationPropertyDefinition("Broken", "string", calculation = "((")),
                        sourceUrl = "https://example.com/blueprints/$bp.json",
                    ),
                ).status,
            )
            val cleared = client.readErrors("?blueprint=$bp").blueprints.single()
            assertEquals(listOf("CALCULATION_COMPILE_FAILED"), cleared.findings.map { it.code })
        } finally {
            TestBlueprints.remove(bp)
        }
    }

    @Test
    fun `the response body carries no null members`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("ee-nonull", UserRole.ADMIN)
        val bp = unique("bp-ee-nonull")
        try {
            client.createBlueprint(
                BlueprintRequest(
                    identifier = bp, title = "T", schema = BlueprintSchema(),
                    calculationProperties = mapOf("broken" to CalculationPropertyDefinition("Broken", "string", calculation = "((")),
                ),
            )
            val response = client.get("/api/v1/entities/errors?blueprint=$bp")
            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(response.bodyAsText().contains(":null"))
        } finally {
            TestBlueprints.remove(bp)
        }
    }
}
