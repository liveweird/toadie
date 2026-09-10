package ch.nokillswit

import ch.nokillswit.blueprints.ArrayItems
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.MirrorPropertyDefinition
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.entityFindings
import ch.nokillswit.entities.sanitizedEntityRequest
import ch.nokillswit.entities.toDocument
import ch.nokillswit.entities.validateEntityRequest
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pure coverage of `entities/EntityValidation.kt`: request-shape rules + the [entityFindings] rule table. */
class EntityValidationTest {

    private fun request(
        identifier: String = "e1",
        blueprint: String = "bp",
        title: String = "Title",
        properties: JsonObject = JsonObject(emptyMap()),
        relations: JsonObject = JsonObject(emptyMap()),
    ) = EntityRequest(blueprint = blueprint, identifier = identifier, title = title, properties = properties, relations = relations)

    // -------------------------------------------------------------------------------------
    // Shape rules
    // -------------------------------------------------------------------------------------

    @Test
    fun `a valid minimal request passes`() {
        validateEntityRequest(request())
    }

    @Test
    fun `identifier accepts unicode letters and the wide Port charset`() {
        validateEntityRequest(request(identifier = "Żółw+a'b\\c:d=e-f_g.h@i/j"))
    }

    @Test
    fun `identifier rejects the bare dot and double dot`() {
        assertFailsWith<BadRequestException> { validateEntityRequest(request(identifier = ".")) }
        assertFailsWith<BadRequestException> { validateEntityRequest(request(identifier = "..")) }
    }

    @Test
    fun `identifier rejects blank and over-length values`() {
        assertFailsWith<BadRequestException> { validateEntityRequest(request(identifier = "")) }
        assertFailsWith<BadRequestException> { validateEntityRequest(request(identifier = "a".repeat(201))) }
    }

    @Test
    fun `identifier rejects characters outside the charset`() {
        assertFailsWith<BadRequestException> { validateEntityRequest(request(identifier = "a b")) }
    }

    @Test
    fun `title must be 1-200 characters`() {
        assertFailsWith<BadRequestException> { validateEntityRequest(request(title = "")) }
        assertFailsWith<BadRequestException> { validateEntityRequest(request(title = "a".repeat(201))) }
    }

    @Test
    fun `icon must be at most 100 characters`() {
        validateEntityRequest(request().copy(icon = "a".repeat(100)))
        assertFailsWith<BadRequestException> { validateEntityRequest(request().copy(icon = "a".repeat(101))) }
    }

    @Test
    fun `blueprint must be 1-100 characters of the blueprint identifier grammar`() {
        validateEntityRequest(request(blueprint = "a".repeat(100)))
        assertFailsWith<BadRequestException> { validateEntityRequest(request(blueprint = "")) }
        assertFailsWith<BadRequestException> { validateEntityRequest(request(blueprint = "a".repeat(101))) }
        assertFailsWith<BadRequestException> { validateEntityRequest(request(blueprint = "not a blueprint id")) }
    }

    @Test
    fun `team accepts a single string`() {
        validateEntityRequest(request().copy(team = JsonPrimitive("payments")))
    }

    @Test
    fun `team accepts a string array within the entry cap`() {
        val team = JsonArray(List(50) { JsonPrimitive("team-$it") })
        validateEntityRequest(request().copy(team = team))
    }

    @Test
    fun `team rejects more than 50 entries`() {
        val team = JsonArray(List(51) { JsonPrimitive("team-$it") })
        assertFailsWith<BadRequestException> { validateEntityRequest(request().copy(team = team)) }
    }

    @Test
    fun `team rejects a non-string entry`() {
        assertFailsWith<BadRequestException> { validateEntityRequest(request().copy(team = JsonArray(listOf(JsonPrimitive(1))))) }
    }

    @Test
    fun `team rejects an over-length entry`() {
        assertFailsWith<BadRequestException> { validateEntityRequest(request().copy(team = JsonPrimitive("a".repeat(101)))) }
    }

    @Test
    fun `team rejects a shape that is neither string nor array`() {
        assertFailsWith<BadRequestException> { validateEntityRequest(request().copy(team = buildJsonObject { put("x", "y") })) }
    }

    @Test
    fun `properties and relations keys must satisfy the blueprint identifier grammar`() {
        val badKeyProperties = buildJsonObject { put("bad key!", "v") }
        assertFailsWith<BadRequestException> { validateEntityRequest(request(properties = badKeyProperties)) }
        val badKeyRelations = buildJsonObject { put("bad key!", "v") }
        assertFailsWith<BadRequestException> { validateEntityRequest(request(relations = badKeyRelations)) }
    }

    @Test
    fun `an oversized document is rejected`() {
        val big = buildJsonObject { put("p", "x".repeat(300_000)) }
        assertFailsWith<BadRequestException> { validateEntityRequest(request(properties = big)) }
    }

    @Test
    fun `sanitizedEntityRequest trims identifier, title and icon only`() {
        val sanitized = sanitizedEntityRequest(request(identifier = " e1 ", title = " T ").copy(icon = " i "))
        assertEquals("e1", sanitized.identifier)
        assertEquals("T", sanitized.title)
        assertEquals("i", sanitized.icon)
    }

    @Test
    fun `toDocument drops explicit JSON null values - unset, never stored`() {
        val properties = buildJsonObject { put("a", "x"); put("b", JsonNull) }
        val relations = buildJsonObject { put("r", JsonNull) }
        val document = request(properties = properties, relations = relations).toDocument()
        assertEquals(setOf("a"), document.properties.keys)
        assertTrue(document.relations.isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // entityFindings - the pure rule table
    // -------------------------------------------------------------------------------------

    private fun definition(
        properties: Map<String, PropertyDefinition> = emptyMap(),
        required: List<String> = emptyList(),
        relations: Map<String, RelationDefinition> = emptyMap(),
        mirror: Map<String, MirrorPropertyDefinition> = emptyMap(),
        calculation: Map<String, CalculationPropertyDefinition> = emptyMap(),
        ownership: OwnershipDefinition? = null,
    ) = BlueprintDefinition(
        schema = BlueprintSchema(properties = properties, required = required),
        relations = relations,
        mirrorProperties = mirror,
        calculationProperties = calculation,
        ownership = ownership,
    )

    private fun doc(properties: JsonObject = JsonObject(emptyMap()), relations: JsonObject = JsonObject(emptyMap())) =
        EntityDocument(properties, relations)

    private val neverExists: (String, String) -> Boolean = { _, _ -> false }

    @Test
    fun `unknown property key is UNKNOWN_PROPERTY`() {
        val findings = entityFindings(doc(buildJsonObject { put("x", "v") }), definition(), neverExists)
        assertEquals(listOf("UNKNOWN_PROPERTY"), findings.map { it.code })
        assertEquals("properties.x", findings.single().field)
    }

    @Test
    fun `a mirror-calculation-aggregation id is COMPUTED_PROPERTY, not UNKNOWN_PROPERTY`() {
        val def = definition(mirror = mapOf("m" to MirrorPropertyDefinition(title = "M", path = "r.\$title")))
        val findings = entityFindings(doc(buildJsonObject { put("m", "v") }), def, neverExists)
        assertEquals(listOf("COMPUTED_PROPERTY"), findings.map { it.code })
    }

    @Test
    fun `a required property absent is REQUIRED_MISSING`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string")), required = listOf("p"))
        val findings = entityFindings(doc(), def, neverExists)
        assertEquals(listOf("REQUIRED_MISSING"), findings.map { it.code })
    }

    @Test
    fun `a required property present has no finding`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string")), required = listOf("p"))
        val findings = entityFindings(doc(buildJsonObject { put("p", "v") }), def, neverExists)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `string type mismatch`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string")))
        val findings = entityFindings(doc(buildJsonObject { put("p", 1) }), def, neverExists)
        assertEquals(listOf("TYPE_MISMATCH"), findings.map { it.code })
    }

    @Test
    fun `string minLength and maxLength`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string", minLength = 3, maxLength = 5)))
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "abc") }), def, neverExists).isEmpty())
        assertEquals(
            listOf("LENGTH_OUT_OF_RANGE"),
            entityFindings(doc(buildJsonObject { put("p", "ab") }), def, neverExists).map { it.code },
        )
        assertEquals(
            listOf("LENGTH_OUT_OF_RANGE"),
            entityFindings(doc(buildJsonObject { put("p", "abcdef") }), def, neverExists).map { it.code },
        )
    }

    @Test
    fun `string pattern is unanchored containsMatchIn`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string", pattern = "ab")))
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "xxabyy") }), def, neverExists).isEmpty())
        assertEquals(listOf("PATTERN_MISMATCH"), entityFindings(doc(buildJsonObject { put("p", "xx") }), def, neverExists).map { it.code })
    }

    @Test
    fun `string enum`() {
        val def = definition(
            properties = mapOf("p" to PropertyDefinition(type = "string", enum = listOf(JsonPrimitive("a"), JsonPrimitive("b")))),
        )
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "a") }), def, neverExists).isEmpty())
        assertEquals(listOf("ENUM_MISMATCH"), entityFindings(doc(buildJsonObject { put("p", "c") }), def, neverExists).map { it.code })
    }

    @Test
    fun `format url`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string", format = "url")))
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "https://a.test") }), def, neverExists).isEmpty())
        assertEquals(
            listOf("FORMAT_INVALID"),
            entityFindings(doc(buildJsonObject { put("p", "not a url") }), def, neverExists).map { it.code },
        )
    }

    @Test
    fun `format email - non-empty local at domain on the last at`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string", format = "email")))
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "a@b.test") }), def, neverExists).isEmpty())
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "a@b@c.test") }), def, neverExists).isEmpty())
        assertEquals(
            listOf("FORMAT_INVALID"),
            entityFindings(doc(buildJsonObject { put("p", "@b.test") }), def, neverExists).map { it.code },
        )
        assertEquals(listOf("FORMAT_INVALID"), entityFindings(doc(buildJsonObject { put("p", "a@") }), def, neverExists).map { it.code })
    }

    @Test
    fun `format date-time`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string", format = "date-time")))
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "2024-01-01T00:00:00Z") }), def, neverExists).isEmpty())
        assertEquals(
            listOf("FORMAT_INVALID"),
            entityFindings(doc(buildJsonObject { put("p", "not-a-date") }), def, neverExists).map { it.code },
        )
    }

    @Test
    fun `format timer parses an Instant`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string", format = "timer")))
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "2024-01-01T00:00:00Z") }), def, neverExists).isEmpty())
        assertEquals(
            listOf("FORMAT_INVALID"),
            entityFindings(doc(buildJsonObject { put("p", "not-a-timer") }), def, neverExists).map { it.code },
        )
    }

    @Test
    fun `format ipv4 - four octets`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string", format = "ipv4")))
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "10.0.0.1") }), def, neverExists).isEmpty())
        assertEquals(
            listOf("FORMAT_INVALID"),
            entityFindings(doc(buildJsonObject { put("p", "10.0.0") }), def, neverExists).map { it.code },
        )
        assertEquals(
            listOf("FORMAT_INVALID"),
            entityFindings(doc(buildJsonObject { put("p", "999.0.0.1") }), def, neverExists).map { it.code },
        )
    }

    @Test
    fun `format ipv6 - hex-colon literal, never DNS`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string", format = "ipv6")))
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "::1") }), def, neverExists).isEmpty())
        assertEquals(
            listOf("FORMAT_INVALID"),
            entityFindings(doc(buildJsonObject { put("p", "not-an-ip") }), def, neverExists).map { it.code },
        )
        // A bare hostname must never be treated as valid (and must never trigger a DNS lookup).
        assertEquals(
            listOf("FORMAT_INVALID"),
            entityFindings(doc(buildJsonObject { put("p", "example.invalid") }), def, neverExists).map { it.code },
        )
    }

    @Test
    fun `formats yaml, markdown, proto are free text`() {
        listOf("yaml", "markdown", "proto").forEach { format ->
            val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string", format = format)))
            assertTrue(entityFindings(doc(buildJsonObject { put("p", "anything at all") }), def, neverExists).isEmpty())
        }
    }

    @Test
    fun `format team resolves against an active _team entity - TEAM_TARGET_MISSING otherwise`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string", format = "team")))
        val exists: (String, String) -> Boolean = { bp, id -> bp == "_team" && id == "platform" }
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "platform") }), def, exists).isEmpty())
        val findings = entityFindings(doc(buildJsonObject { put("p", "ghost") }), def, exists)
        assertEquals(listOf("TEAM_TARGET_MISSING"), findings.map { it.code })
        assertEquals("properties.p", findings.single().field)
    }

    @Test
    fun `format user resolves against an active _user entity - USER_TARGET_MISSING otherwise`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "string", format = "user")))
        val exists: (String, String) -> Boolean = { bp, id -> bp == "_user" && id == "alice@example.test" }
        assertTrue(entityFindings(doc(buildJsonObject { put("p", "alice@example.test") }), def, exists).isEmpty())
        val findings = entityFindings(doc(buildJsonObject { put("p", "ghost@example.test") }), def, exists)
        assertEquals(listOf("USER_TARGET_MISSING"), findings.map { it.code })
    }

    @Test
    fun `array items of format user are target-checked one by one`() {
        val def = definition(
            properties = mapOf("p" to PropertyDefinition(type = "array", items = ArrayItems(type = "string", format = "user"))),
        )
        val exists: (String, String) -> Boolean = { bp, id -> bp == "_user" && id == "known" }
        assertTrue(entityFindings(doc(buildJsonObject { putJsonArray("p") { add("known") } }), def, exists).isEmpty())
        val findings = entityFindings(doc(buildJsonObject { putJsonArray("p") { add("known"); add("ghost") } }), def, exists)
        assertEquals(listOf("USER_TARGET_MISSING"), findings.map { it.code })
    }

    @Test
    fun `number type, range and enum`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "number", minimum = 1.0, maximum = 5.0)))
        assertTrue(entityFindings(doc(buildJsonObject { put("p", 3) }), def, neverExists).isEmpty())
        assertEquals(listOf("RANGE_OUT_OF_BOUNDS"), entityFindings(doc(buildJsonObject { put("p", 0) }), def, neverExists).map { it.code })
        assertEquals(listOf("RANGE_OUT_OF_BOUNDS"), entityFindings(doc(buildJsonObject { put("p", 6) }), def, neverExists).map { it.code })
        assertEquals(listOf("TYPE_MISMATCH"), entityFindings(doc(buildJsonObject { put("p", "3") }), def, neverExists).map { it.code })

        val enumDef = definition(
            properties = mapOf("p" to PropertyDefinition(type = "number", enum = listOf(JsonPrimitive(1), JsonPrimitive(2)))),
        )
        assertEquals(listOf("ENUM_MISMATCH"), entityFindings(doc(buildJsonObject { put("p", 3) }), enumDef, neverExists).map { it.code })
    }

    @Test
    fun `number exclusive bounds`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "number", exclusiveMinimum = 1.0, exclusiveMaximum = 5.0)))
        assertTrue(entityFindings(doc(buildJsonObject { put("p", 3) }), def, neverExists).isEmpty())
        assertEquals(listOf("RANGE_OUT_OF_BOUNDS"), entityFindings(doc(buildJsonObject { put("p", 1) }), def, neverExists).map { it.code })
        assertEquals(listOf("RANGE_OUT_OF_BOUNDS"), entityFindings(doc(buildJsonObject { put("p", 5) }), def, neverExists).map { it.code })
    }

    @Test
    fun `boolean type`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "boolean")))
        assertTrue(entityFindings(doc(buildJsonObject { put("p", true) }), def, neverExists).isEmpty())
        // jsonMatchesType (shared with a property's own `default` check, PropertyValidation.kt)
        // resolves booleans via JsonPrimitive.booleanOrNull, which parses CONTENT regardless of
        // quoting - "true"/"false" strings already matched type boolean before this file
        // existed. A value with no boolean reading at all is the one that must TYPE_MISMATCH.
        assertEquals(
            listOf("TYPE_MISMATCH"),
            entityFindings(doc(buildJsonObject { put("p", "not-a-boolean") }), def, neverExists).map { it.code },
        )
    }

    @Test
    fun `array items type, enum, format, size and uniqueness`() {
        val def = definition(
            properties = mapOf(
                "p" to PropertyDefinition(
                    type = "array",
                    items = ArrayItems(type = "string", enum = listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                    minItems = 1,
                    maxItems = 2,
                    uniqueItems = true,
                ),
            ),
        )
        val ok = doc(buildJsonObject { putJsonArray("p") { add("a") } })
        assertTrue(entityFindings(ok, def, neverExists).isEmpty())

        val badItem = doc(buildJsonObject { putJsonArray("p") { add(1) } })
        assertEquals(listOf("TYPE_MISMATCH"), entityFindings(badItem, def, neverExists).map { it.code })

        val badEnum = doc(buildJsonObject { putJsonArray("p") { add("c") } })
        assertEquals(listOf("ENUM_MISMATCH"), entityFindings(badEnum, def, neverExists).map { it.code })

        val tooFew = doc(buildJsonObject { putJsonArray("p") { } })
        assertEquals(listOf("ARRAY_SIZE"), entityFindings(tooFew, def, neverExists).map { it.code })

        val tooMany = doc(buildJsonObject { putJsonArray("p") { add("a"); add("b"); add("a") } })
        assertTrue(entityFindings(tooMany, def, neverExists).map { it.code }.containsAll(listOf("ARRAY_SIZE", "ARRAY_NOT_UNIQUE")))

        val notUnique = doc(buildJsonObject { putJsonArray("p") { add("a"); add("a") } })
        assertEquals(listOf("ARRAY_NOT_UNIQUE"), entityFindings(notUnique, def, neverExists).map { it.code })
    }

    @Test
    fun `array of objects accepts objects and rejects primitives`() {
        // Port's `links`-style arrays: items.type object. The element check is the shared
        // jsonMatchesType, so an object element passes and a primitive one is TYPE_MISMATCH —
        // the enum/format rules only ever apply to primitive items.
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "array", items = ArrayItems(type = "object"))))
        val ok = doc(buildJsonObject { putJsonArray("p") { add(buildJsonObject { put("title", "Docs"); put("url", "https://a.test") }) } })
        assertTrue(entityFindings(ok, def, neverExists).isEmpty())
        val badItem = doc(buildJsonObject { putJsonArray("p") { add("https://a.test") } })
        assertEquals(listOf("TYPE_MISMATCH"), entityFindings(badItem, def, neverExists).map { it.code })
    }

    @Test
    fun `object labeled-url shape`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "object", format = "labeled-url")))
        val ok = doc(buildJsonObject { put("p", buildJsonObject { put("url", "https://a.test"); put("displayText", "A") }) })
        assertTrue(entityFindings(ok, def, neverExists).isEmpty())

        val missingUrl = doc(buildJsonObject { put("p", buildJsonObject { put("displayText", "A") }) })
        assertEquals(listOf("OBJECT_SHAPE"), entityFindings(missingUrl, def, neverExists).map { it.code })

        val extraKey = doc(buildJsonObject { put("p", buildJsonObject { put("url", "https://a.test"); put("extra", "x") }) })
        assertEquals(listOf("OBJECT_SHAPE"), entityFindings(extraKey, def, neverExists).map { it.code })
    }

    @Test
    fun `object without labeled-url format is stored verbatim - no shape check`() {
        val def = definition(properties = mapOf("p" to PropertyDefinition(type = "object")))
        val value = doc(
            buildJsonObject { put("p", buildJsonObject { put("anything", "goes"); put("nested", buildJsonObject { put("x", 1) }) }) },
        )
        assertTrue(entityFindings(value, def, neverExists).isEmpty())
    }

    @Test
    fun `unknown relation key is UNKNOWN_RELATION`() {
        val findings = entityFindings(doc(relations = buildJsonObject { put("x", "v") }), definition(), neverExists)
        assertEquals(listOf("UNKNOWN_RELATION"), findings.map { it.code })
    }

    @Test
    fun `single relation must be a string, many must be an array of distinct strings`() {
        val single = definition(relations = mapOf("r" to RelationDefinition(title = "R", target = "bp2", required = false, many = false)))
        assertTrue(entityFindings(doc(relations = buildJsonObject { put("r", "x") }), single, { _, _ -> true }).isEmpty())
        assertEquals(
            listOf("RELATION_SHAPE"),
            entityFindings(doc(relations = buildJsonObject { putJsonArray("r") { add("x") } }), single, neverExists).map { it.code },
        )

        val many = definition(relations = mapOf("r" to RelationDefinition(title = "R", target = "bp2", required = false, many = true)))
        assertTrue(entityFindings(doc(relations = buildJsonObject { putJsonArray("r") { add("x") } }), many, { _, _ -> true }).isEmpty())
        assertEquals(
            listOf("RELATION_SHAPE"),
            entityFindings(doc(relations = buildJsonObject { put("r", "x") }), many, neverExists).map { it.code },
        )
        assertEquals(
            listOf("RELATION_SHAPE"),
            entityFindings(doc(relations = buildJsonObject { putJsonArray("r") { add("x"); add("x") } }), many, neverExists)
                .map { it.code },
        )
    }

    @Test
    fun `a required relation missing is RELATION_REQUIRED`() {
        val def = definition(relations = mapOf("r" to RelationDefinition(title = "R", target = "bp2", required = true, many = false)))
        assertEquals(listOf("RELATION_REQUIRED"), entityFindings(doc(), def, neverExists).map { it.code })
        assertTrue(entityFindings(doc(relations = buildJsonObject { put("r", "x") }), def, { _, _ -> true }).isEmpty())
    }

    @Test
    fun `a required many relation missing or empty is RELATION_REQUIRED`() {
        val def = definition(relations = mapOf("r" to RelationDefinition(title = "R", target = "bp2", required = true, many = true)))
        assertEquals(listOf("RELATION_REQUIRED"), entityFindings(doc(), def, neverExists).map { it.code })
        assertEquals(
            listOf("RELATION_REQUIRED"),
            entityFindings(doc(relations = buildJsonObject { putJsonArray("r") { } }), def, neverExists).map { it.code },
        )
    }

    @Test
    fun `an unresolved relation target is RELATION_TARGET_MISSING`() {
        val def = definition(relations = mapOf("r" to RelationDefinition(title = "R", target = "bp2", required = false, many = false)))
        val findings = entityFindings(doc(relations = buildJsonObject { put("r", "missing") }), def, neverExists)
        assertEquals(listOf("RELATION_TARGET_MISSING"), findings.map { it.code })
    }

    @Test
    fun `self-relations are allowed - target existence is checked, not the blueprint identity`() {
        val def = definition(relations = mapOf("r" to RelationDefinition(title = "R", target = "self", required = false, many = false)))
        val exists: (String, String) -> Boolean = { bp, id -> bp == "self" && id == "e1" }
        val findings = entityFindings(doc(relations = buildJsonObject { put("r", "e1") }), def, exists)
        assertTrue(findings.isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // entityFindings - team (Phase 4 ownership)
    // -------------------------------------------------------------------------------------

    private val teamExists: (String, String) -> Boolean = { bp, id -> bp == "_team" && id == "platform" }

    @Test
    fun `Direct-absent ownership - a string team must resolve to an active _team entity`() {
        assertTrue(entityFindings(doc(), definition(), teamExists, JsonPrimitive("platform")).isEmpty())
        val findings = entityFindings(doc(), definition(), teamExists, JsonPrimitive("ghost"))
        assertEquals(listOf("TEAM_TARGET_MISSING"), findings.map { it.code })
        assertEquals("team", findings.single().field)
    }

    @Test
    fun `Direct-absent ownership - every array team value must resolve, unknown values are reported individually`() {
        val team = JsonArray(listOf(JsonPrimitive("platform"), JsonPrimitive("ghost")))
        val findings = entityFindings(doc(), definition(), teamExists, team)
        assertEquals(listOf("TEAM_TARGET_MISSING"), findings.map { it.code })
    }

    @Test
    fun `Direct-absent ownership - no team is not a finding`() {
        assertTrue(entityFindings(doc(), definition(), teamExists, null).isEmpty())
    }

    @Test
    fun `Inherited ownership - a supplied team is TEAM_NOT_ALLOWED`() {
        val def = definition(ownership = OwnershipDefinition(type = "Inherited", path = "service"))
        val findings = entityFindings(doc(), def, teamExists, JsonPrimitive("platform"))
        assertEquals(listOf("TEAM_NOT_ALLOWED"), findings.map { it.code })
        assertEquals("team", findings.single().field)
    }

    @Test
    fun `Inherited ownership - no team is not a finding`() {
        val def = definition(ownership = OwnershipDefinition(type = "Inherited", path = "service"))
        assertTrue(entityFindings(doc(), def, teamExists, null).isEmpty())
    }
}
