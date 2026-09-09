package ch.nokillswit

import ch.nokillswit.blueprints.AggregationCalculationSpec
import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.AggregationQuery
import ch.nokillswit.blueprints.ArrayItems
import ch.nokillswit.blueprints.BlueprintList
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.MirrorPropertyDefinition
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SpecAuthentication
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
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The blueprint-registry route surface: CRUD, authz (guard-before-read), the Port-shape
 * round trip (no `null` members, unknown top-level keys rejected), identifier uniqueness,
 * self-relations, the rename cascade, the delete-409, the 200-blueprint cap, and audits.
 * A Toadie-first feature (no shared seed state, unlike labels/tags/entity-types) — every
 * test mints a unique `bp-<uuid8>` identifier and removes what it created via
 * [TestBlueprints.remove].
 */
class BlueprintTest {

    private fun identifier(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    /** A minimal but valid blueprint: no properties, no relations. */
    private fun simpleRequest(id: String, title: String = "Simple") =
        BlueprintRequest(identifier = id, title = title, schema = BlueprintSchema())

    /** The Port docs' `microservice` example: a required string + a url-format string. */
    private fun microserviceRequest(id: String, title: String = "Microservice") = BlueprintRequest(
        identifier = id,
        title = title,
        description = "A deployable unit of software owned by a team.",
        icon = "Microservice",
        schema = BlueprintSchema(
            properties = mapOf(
                "language" to PropertyDefinition(type = "string", title = "Language"),
                "repository" to PropertyDefinition(type = "string", format = "url", title = "Repository"),
            ),
            required = listOf("language"),
        ),
    )

    private fun relationTo(target: String, required: Boolean = false, many: Boolean = false) =
        RelationDefinition(title = "Depends on", target = target, required = required, many = many)

    private suspend fun HttpClient.readBlueprints(): BlueprintList = get("/api/v1/blueprints").body()

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/blueprints").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/blueprints/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/blueprints").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/blueprints/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/blueprints/1").status)
    }

    @Test
    fun `non-admin may read but not write - uniformly 403 even on an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpuser")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/blueprints").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/blueprints/999999").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.postJson("/api/v1/blueprints", simpleRequest(identifier("bpu"))).status,
        )
        // Guard-before-read: the probe cannot distinguish real from unknown ids.
        assertEquals(
            HttpStatusCode.Forbidden,
            client.putJson("/api/v1/blueprints/999999", simpleRequest(identifier("bpu"))).status,
        )
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/blueprints/999999").status)
    }

    @Test
    fun `admin CRUD round-trips through a Port-shaped microservice blueprint`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpcrud", UserRole.ADMIN)
        val id = identifier("bpcrud")
        var renamedId = id
        try {
            val create = admin.postJson("/api/v1/blueprints", microserviceRequest(id))
            assertEquals(HttpStatusCode.Created, create.status)
            val created = create.body<BlueprintResponse>()
            assertEquals(id, created.identifier)
            assertEquals("Microservice", created.title)
            assertEquals(listOf("language"), created.schema.required)
            assertEquals(setOf("language", "repository"), created.schema.properties.keys)
            assertNotNull(create.headers["Location"])

            val listed = admin.readBlueprints().items.single { it.identifier == id }
            assertEquals(created, listed)

            renamedId = identifier("bpcrud2")
            val replace = admin.putJson(
                "/api/v1/blueprints/${created.id}",
                microserviceRequest(renamedId, title = "Renamed Microservice"),
            )
            assertEquals(HttpStatusCode.NoContent, replace.status)
            val replaced = admin.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
            assertEquals(renamedId, replaced.identifier)
            assertEquals("Renamed Microservice", replaced.title)

            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/blueprints/${created.id}").status)
            assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/blueprints/${created.id}").status)
            val raw = TestBlueprints.rawRows().single { it.id == created.id }
            assertTrue(raw.markedAsDeleted, "delete must soft-delete, not remove")
        } finally {
            TestBlueprints.remove(id, renamedId)
        }
    }

    @Test
    fun `a kitchen-sink blueprint touching every optional field round-trips byte-for-byte`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpsink", UserRole.ADMIN)
        val targetId = identifier("bpsinktarget")
        val id = identifier("bpsink")
        try {
            admin.postJson("/api/v1/blueprints", simpleRequest(targetId))

            val properties = mapOf(
                "textProp" to PropertyDefinition(
                    type = "string",
                    title = "Text",
                    description = "A text property",
                    icon = "Text",
                    default = JsonPrimitive("a"),
                    format = "user",
                    pattern = "^[a-z]+$",
                    minLength = 1,
                    maxLength = 50,
                    enum = listOf(JsonPrimitive("a"), JsonPrimitive("b")),
                    enumColors = mapOf("a" to "gold", "b" to "red"),
                    spec = "open-api",
                    specAuthentication = SpecAuthentication(
                        authorizationUrl = "https://auth.example.test/authorize",
                        tokenUrl = "https://auth.example.test/token",
                        clientId = "client-id",
                        authorizationScope = listOf("read", "write"),
                    ),
                ),
                "dateProp" to PropertyDefinition(type = "string", format = "date-time", dateFormat = "relative"),
                "numProp" to PropertyDefinition(
                    type = "number",
                    default = JsonPrimitive(1),
                    minimum = 1.0,
                    maximum = 10.0,
                    enum = listOf(
                        JsonPrimitive(1),
                        JsonPrimitive(2),
                        JsonPrimitive(3),
                    ),
                    enumColors = mapOf("1" to "gold"),
                ),
                "numExclusiveProp" to PropertyDefinition(type = "number", exclusiveMinimum = 0.5, exclusiveMaximum = 9.5),
                "boolProp" to PropertyDefinition(type = "boolean", default = JsonPrimitive(true)),
                "arrayProp" to PropertyDefinition(
                    type = "array",
                    items = ArrayItems(
                        type = "string",
                        format = "email",
                        enum = listOf(
                            JsonPrimitive("a@example.test"),
                            JsonPrimitive("b@example.test"),
                        ),
                        enumColors = mapOf("a@example.test" to "green"),
                    ),
                    minItems = 1,
                    maxItems = 5,
                    uniqueItems = true,
                    default = JsonArray(
                        listOf(JsonPrimitive("a@example.test")),
                    ),
                ),
                "objectProp" to PropertyDefinition(
                    type = "object",
                    format = "labeled-url",
                    spec = "open-api",
                    properties = buildJsonObject {
                        put("myKey", buildJsonObject { put("type", "number") })
                    },
                    patternProperties = buildJsonObject {
                        put("^S_", buildJsonObject { put("type", "string") })
                    },
                    additionalProperties = JsonPrimitive(true),
                    default = buildJsonObject {
                        put("url", "https://example.test")
                        put("displayText", "Example")
                    },
                ),
            )
            val request = BlueprintRequest(
                identifier = id,
                title = "Kitchen Sink",
                description = "Touches every optional field",
                icon = "Sink",
                schema = BlueprintSchema(properties = properties, required = listOf("textProp")),
                relations = mapOf("dependsOn" to relationTo(targetId, required = true).copy(description = "A dependency")),
                mirrorProperties = mapOf(
                    "mirroredTitle" to MirrorPropertyDefinition(
                        title = "Mirrored",
                        path = "dependsOn.someProp",
                    ),
                ),
                calculationProperties = mapOf(
                    "calcStatus" to CalculationPropertyDefinition(
                        title = "Status",
                        type = "string",
                        calculation = ".properties.rawStatus",
                        colorized = true,
                        colors = mapOf("OK" to "green", "WARNING" to "yellow"),
                    ),
                ),
                aggregationProperties = mapOf(
                    "aggAvg" to AggregationPropertyDefinition(
                        title = "Average",
                        target = targetId,
                        calculationSpec = AggregationCalculationSpec(
                            calculationBy = "entities",
                            func = "average",
                            averageOf = "week",
                            measureTimeBy = "\$createdAt",
                        ),
                        query = AggregationQuery(
                            combinator = "and",
                            rules = listOf(
                                buildJsonObject {
                                    put("property", "status")
                                    put("operator", "!=")
                                    put("value", "Done")
                                },
                            ),
                        ),
                        pathFilter = listOf(buildJsonObject { put("k", "v") }),
                    ),
                ),
                ownership = OwnershipDefinition(
                    type = "Inherited",
                    title = "Owner",
                    path = "dependsOn",
                ),
            )

            val create = admin.postJson("/api/v1/blueprints", request)
            assertEquals(HttpStatusCode.Created, create.status)
            val created = create.body<BlueprintResponse>()

            val reread = admin.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
            assertEquals(created, reread)
            assertEquals(properties, reread.schema.properties)
            assertEquals(request.relations, reread.relations)
            assertEquals(request.mirrorProperties, reread.mirrorProperties)
            assertEquals(request.calculationProperties, reread.calculationProperties)
            assertEquals(request.aggregationProperties, reread.aggregationProperties)
            assertEquals(request.ownership, reread.ownership)
        } finally {
            TestBlueprints.remove(id, targetId)
        }
    }

    @Test
    fun `the raw response body carries no null members and omits unset optional keys`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpvoid", UserRole.ADMIN)
        val id = identifier("bpvoid")
        try {
            val create = admin.postJson("/api/v1/blueprints", simpleRequest(id))
            val text = create.bodyAsText()
            assertFalse(text.contains(":null"), "the response must never carry an explicit null member: $text")
            assertFalse(text.contains("\"description\""), "an unset description key must be ABSENT, not null: $text")
            assertFalse(text.contains("\"icon\""), "an unset icon key must be ABSENT, not null: $text")
            assertFalse(text.contains("\"ownership\""), "an unset ownership key must be ABSENT, not null: $text")

            val get = admin.get("/api/v1/blueprints/${create.body<BlueprintResponse>().id}")
            val getText = get.bodyAsText()
            assertFalse(getText.contains(":null"))
            assertFalse(getText.contains("\"description\""))
        } finally {
            TestBlueprints.remove(id)
        }
    }

    @Test
    fun `hierarchyRelation round-trips, is absent when unset, and is validated`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bphier", UserRole.ADMIN)
        val id = identifier("bphier")
        try {
            // Absent when unset: no key at all on the raw body, not an explicit null.
            val plain = admin.postJson("/api/v1/blueprints", simpleRequest(id))
            assertFalse(plain.bodyAsText().contains("hierarchyRelation"))
            val plainCreated = plain.body<BlueprintResponse>()
            assertEquals(null, plainCreated.hierarchyRelation)

            // Must name a relation of this blueprint's own request.
            val unknown = admin.putJson(
                "/api/v1/blueprints/${plainCreated.id}",
                simpleRequest(id).copy(hierarchyRelation = "nope"),
            )
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertTrue(unknown.body<ProblemDetail>().detail!!.contains("hierarchyRelation must name a relation"))

            // Must be single-valued (many = false).
            val withRelations = simpleRequest(id).copy(
                relations = mapOf(
                    "parent" to relationTo(id, many = false),
                    "peers" to relationTo(id, many = true),
                ),
            )
            val many = admin.putJson("/api/v1/blueprints/${plainCreated.id}", withRelations.copy(hierarchyRelation = "peers"))
            assertEquals(HttpStatusCode.BadRequest, many.status)
            assertTrue(many.body<ProblemDetail>().detail!!.contains("must name a single relation"))

            // Round trip: names the single relation.
            val ok = admin.putJson("/api/v1/blueprints/${plainCreated.id}", withRelations.copy(hierarchyRelation = "parent"))
            assertEquals(HttpStatusCode.NoContent, ok.status)
            val read = admin.get("/api/v1/blueprints/${plainCreated.id}")
            assertEquals("parent", read.body<BlueprintResponse>().hierarchyRelation)
            assertTrue(read.bodyAsText().contains("\"hierarchyRelation\":\"parent\""))

            // Clearing it (omitted from the PUT body) drops it again.
            val cleared = admin.putJson("/api/v1/blueprints/${plainCreated.id}", withRelations)
            assertEquals(HttpStatusCode.NoContent, cleared.status)
            assertFalse(admin.get("/api/v1/blueprints/${plainCreated.id}").bodyAsText().contains("hierarchyRelation"))
        } finally {
            TestBlueprints.remove(id)
        }
    }

    @Test
    fun `an unknown top-level key such as teamInheritance is rejected as 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpunknown", UserRole.ADMIN)
        val response = admin.post("/api/v1/blueprints") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"identifier":"${identifier("bpu")}","title":"T","schema":{"properties":{},"required":[]},
                    |"relations":{},"mirrorProperties":{},"calculationProperties":{},"aggregationProperties":{},
                    |"teamInheritance":{"enabled":true}}
                """.trimMargin(),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `mutations on a missing or deleted id are 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bp404", UserRole.ADMIN)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.putJson("/api/v1/blueprints/999999", simpleRequest(identifier("bp404"))).status,
        )
        // Existence wins over body validation: a missing id 404s even for an invalid body
        // (blank identifier) — the LensRoutes PUT precedent, no route-side validation.
        assertEquals(
            HttpStatusCode.NotFound,
            admin.putJson("/api/v1/blueprints/999999", simpleRequest("")).status,
        )
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/blueprints/999999").status)
        val id = identifier("bp404gone")
        val created = admin.postJson("/api/v1/blueprints", simpleRequest(id)).body<BlueprintResponse>()
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/blueprints/${created.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/blueprints/${created.id}").status)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.putJson("/api/v1/blueprints/${created.id}", simpleRequest(identifier("bp404b"))).status,
        )
    }

    @Test
    fun `an active identifier clash is 409 case-insensitively, and a soft-deleted blueprint frees it`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpdup", UserRole.ADMIN)
        val id = identifier("bpdup")
        try {
            val first = admin.postJson("/api/v1/blueprints", simpleRequest(id)).body<BlueprintResponse>()
            assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/blueprints", simpleRequest(id)).status)
            assertEquals(
                HttpStatusCode.Conflict,
                admin.postJson("/api/v1/blueprints", simpleRequest(id.uppercase())).status,
            )
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/blueprints/${first.id}").status)
            val second = admin.postJson("/api/v1/blueprints", simpleRequest(id))
            assertEquals(HttpStatusCode.Created, second.status)
            assertTrue(second.body<BlueprintResponse>().id != first.id, "re-adding a freed identifier mints a NEW id")
        } finally {
            TestBlueprints.remove(id)
        }
    }

    @Test
    fun `renaming onto an identifier an active blueprint holds is 409`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpren", UserRole.ADMIN)
        val (a, b) = identifier("bprena") to identifier("bprenb")
        try {
            admin.postJson("/api/v1/blueprints", simpleRequest(a))
            val other = admin.postJson("/api/v1/blueprints", simpleRequest(b)).body<BlueprintResponse>()
            val clash = admin.putJson("/api/v1/blueprints/${other.id}", simpleRequest(a))
            assertEquals(HttpStatusCode.Conflict, clash.status)
            assertNotNull(clash.body<ProblemDetail>().detail)
        } finally {
            TestBlueprints.remove(a, b)
        }
    }

    @Test
    fun `a self-relation is allowed on create, survives a rename, and never blocks its own delete`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpself", UserRole.ADMIN)
        val id = identifier("bpself")
        var renamedId = id
        try {
            val create = admin.postJson(
                "/api/v1/blueprints",
                simpleRequest(id).copy(relations = mapOf("parent" to relationTo(id))),
            )
            assertEquals(HttpStatusCode.Created, create.status)
            val created = create.body<BlueprintResponse>()

            renamedId = identifier("bpself2")
            val replace = admin.putJson(
                "/api/v1/blueprints/${created.id}",
                simpleRequest(renamedId).copy(relations = mapOf("parent" to relationTo(renamedId))),
            )
            assertEquals(HttpStatusCode.NoContent, replace.status)
            val replaced = admin.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
            assertEquals(renamedId, replaced.relations.getValue("parent").target)

            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/blueprints/${created.id}").status)
        } finally {
            TestBlueprints.remove(id, renamedId)
        }
    }

    @Test
    fun `a self-relation still naming the OLD identifier is rewritten by the rename itself`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpselfstale", UserRole.ADMIN)
        val id = identifier("bpselfstale")
        var renamedId = id
        try {
            val created = admin.postJson(
                "/api/v1/blueprints",
                simpleRequest(id).copy(relations = mapOf("parent" to relationTo(id))),
            ).body<BlueprintResponse>()

            renamedId = identifier("bpselfstale2")
            // The submitted body still says the OLD identifier — the caller never updated its
            // own self-reference text to match the rename.
            val replace = admin.putJson(
                "/api/v1/blueprints/${created.id}",
                simpleRequest(renamedId).copy(relations = mapOf("parent" to relationTo(id))),
            )
            assertEquals(HttpStatusCode.NoContent, replace.status)
            val replaced = admin.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
            assertEquals(renamedId, replaced.relations.getValue("parent").target)
        } finally {
            TestBlueprints.remove(id, renamedId)
        }
    }

    @Test
    fun `renaming cascades to every dependent's relation and aggregation targets, and audits it`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpcascade", UserRole.ADMIN)
        val targetId = identifier("bpcascadetarget")
        val dependentId = identifier("bpcascadedep")
        var renamedTargetId = targetId
        try {
            val target = admin.postJson("/api/v1/blueprints", simpleRequest(targetId)).body<BlueprintResponse>()
            val dependent = admin.postJson(
                "/api/v1/blueprints",
                simpleRequest(dependentId).copy(
                    relations = mapOf("rel" to relationTo(targetId)),
                    aggregationProperties = mapOf(
                        "agg" to AggregationPropertyDefinition(
                            title = "Agg",
                            target = targetId,
                            calculationSpec = AggregationCalculationSpec(calculationBy = "entities", func = "count"),
                        ),
                    ),
                ),
            ).body<BlueprintResponse>()

            renamedTargetId = identifier("bpcascaderenamed")
            withAuditCapture { capture ->
                val replace = admin.putJson("/api/v1/blueprints/${target.id}", simpleRequest(renamedTargetId))
                assertEquals(HttpStatusCode.NoContent, replace.status)
                val event = capture.awaitEvent { it.message == "blueprint.updated" }
                assertNotNull(event, "update must audit")
                assertTrue(event.hasKeyValue("cascaded", 1))
            }

            val reloadedDependent = admin.get("/api/v1/blueprints/${dependent.id}").body<BlueprintResponse>()
            assertEquals(renamedTargetId, reloadedDependent.relations.getValue("rel").target)
            assertEquals(renamedTargetId, reloadedDependent.aggregationProperties.getValue("agg").target)
        } finally {
            TestBlueprints.remove(renamedTargetId, dependentId)
        }
    }

    @Test
    fun `deleting a targeted blueprint is 409 naming the referrer, then 204 once the referrer is removed`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpdelblock", UserRole.ADMIN)
        val targetId = identifier("bpdelblocktarget")
        val referrerId = identifier("bpdelblockref")
        try {
            val target = admin.postJson("/api/v1/blueprints", simpleRequest(targetId)).body<BlueprintResponse>()
            val referrer = admin.postJson(
                "/api/v1/blueprints",
                simpleRequest(referrerId).copy(relations = mapOf("rel" to relationTo(targetId))),
            ).body<BlueprintResponse>()

            val blocked = admin.delete("/api/v1/blueprints/${target.id}")
            assertEquals(HttpStatusCode.Conflict, blocked.status)
            assertTrue(blocked.body<ProblemDetail>().detail!!.contains(referrerId))

            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/blueprints/${referrer.id}").status)
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/blueprints/${target.id}").status)
        } finally {
            TestBlueprints.remove(targetId, referrerId)
        }
    }

    @Test
    fun `the registry enforces its 200-blueprint cap`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("bpcap")
        val userId = TestUsers.seed(email = email, password = "pw", role = UserRole.ADMIN)
        val admin = authedClient(email, "pw")
        val prefix = identifier("bpcap")
        val initialCount = admin.readBlueprints().items.size
        check(initialCount < 199) { "blueprint fixtures leaked into the shared registry: $initialCount active rows" }
        try {
            insertBlueprintFillers(prefix, 199 - initialCount, userId)
            val ok = admin.postJson("/api/v1/blueprints", simpleRequest("$prefix-a"))
            assertEquals(HttpStatusCode.Created, ok.status)
            val full = admin.postJson("/api/v1/blueprints", simpleRequest("$prefix-b"))
            assertEquals(HttpStatusCode.BadRequest, full.status)
            assertTrue(full.body<ProblemDetail>().detail!!.contains("registry is full"))
        } finally {
            removeBlueprintFillers(prefix)
            TestBlueprints.remove("$prefix-a", "$prefix-b")
        }
    }

    @Test
    fun `mutations audit and a failed mutation does not`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            val id = identifier("bpaudit")
            val created = admin.postJson("/api/v1/blueprints", microserviceRequest(id)).body<BlueprintResponse>()
            val event = capture.awaitEvent { it.message == "blueprint.created" }
            assertNotNull(event, "create must audit")
            assertTrue(event.hasKeyValue("identifier", id))
            assertTrue(event.hasKeyValue("properties", 2))

            admin.putJson("/api/v1/blueprints/${created.id}", simpleRequest(id))
            assertNotNull(capture.awaitEvent { it.message == "blueprint.updated" }, "update must audit")

            admin.delete("/api/v1/blueprints/${created.id}")
            val deleted = capture.awaitEvent { it.message == "blueprint.deleted" }
            assertNotNull(deleted, "delete must audit")
            assertTrue(deleted.hasKeyValue("blueprintId", created.id.toLong()))
            assertTrue(deleted.hasKeyValue("identifier", id))

            val before = capture.events.count { it.message == "blueprint.created" }
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.postJson("/api/v1/blueprints", simpleRequest("")).status,
            )
            assertEquals(before, capture.events.count { it.message == "blueprint.created" }, "failed create must not audit")

            TestBlueprints.remove(id)
        }
    }

    private suspend fun insertBlueprintFillers(prefix: String, count: Int, userId: UInt) =
        withContext(NonCancellable + Dispatchers.IO) {
            DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl,
                PostgresTestSupport.user,
                PostgresTestSupport.password,
            ).use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO blueprints (identifier, title, definition, created_by, created_at, updated_at)
                        VALUES (?, ?, '{}', ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        val now = System.currentTimeMillis()
                        repeat(count) { index ->
                            statement.setString(1, "$prefix-filler-$index")
                            statement.setString(2, "Filler")
                            statement.setLong(3, userId.toLong())
                            statement.setLong(4, now)
                            statement.setLong(5, now)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    connection.commit()
                } catch (failure: Exception) {
                    runCatching { connection.rollback() }
                    throw failure
                }
            }
        }

    private suspend fun removeBlueprintFillers(prefix: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl,
                PostgresTestSupport.user,
                PostgresTestSupport.password,
            ).use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("UPDATE blueprints SET marked_as_deleted = true WHERE identifier LIKE ?").use {
                        it.setString(1, "$prefix%")
                        it.executeUpdate()
                    }
                    connection.commit()
                } catch (failure: Exception) {
                    runCatching { connection.rollback() }
                    throw failure
                }
            }
        }
}
