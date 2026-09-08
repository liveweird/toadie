package ch.nokillswit

import ch.nokillswit.blueprints.AggregationCalculationSpec
import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.AggregationQuery
import ch.nokillswit.blueprints.ArrayItems
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SpecAuthentication
import ch.nokillswit.blueprints.blueprintJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the Port-native wire shape [blueprintJson] produces — including the one `@SerialName`
 * remap (`dateFormat` -> `date_format`) and the absent-not-null rule for every OTHER optional
 * field — by encoding an instance built with ONLY the required constructor args plus exactly
 * ONE optional one named. This is also, deliberately, the only call shape that exercises a
 * given optional parameter's "provided" branch in Kotlin's generated default-args bridge
 * constructor (`<init>(..., int mask, DefaultConstructorMarker)`): that branch is taken only
 * when a call site names THAT parameter while at least one other is omitted, which whole-object
 * round trips (every field present, or every field defaulted) never produce.
 */
class BlueprintWireNamesTest {

    private inline fun <reified T> assertOnlyOptional(
        instance: T,
        required: Set<String>,
        key: String,
        check: (JsonElement) -> Boolean,
    ) {
        val obj = Json.parseToJsonElement(blueprintJson.encodeToString(instance)).jsonObject
        assertEquals(required + key, obj.keys, "unexpected key set for '$key': $obj")
        assertTrue(check(obj.getValue(key)), "unexpected value for '$key': $obj")
    }

    private fun eq(value: JsonElement): (JsonElement) -> Boolean = { it == value }
    private val isObject: (JsonElement) -> Boolean = { it is JsonObject }

    @Test
    fun `PropertyDefinition wire names - one optional field at a time`() {
        val r = setOf("type")
        assertOnlyOptional(PropertyDefinition(type = "string", title = "T"), r, "title", eq(JsonPrimitive("T")))
        assertOnlyOptional(PropertyDefinition(type = "string", description = "D"), r, "description", eq(JsonPrimitive("D")))
        assertOnlyOptional(PropertyDefinition(type = "string", icon = "I"), r, "icon", eq(JsonPrimitive("I")))
        assertOnlyOptional(PropertyDefinition(type = "string", default = JsonPrimitive("v")), r, "default", eq(JsonPrimitive("v")))
        assertOnlyOptional(PropertyDefinition(type = "string", format = "url"), r, "format", eq(JsonPrimitive("url")))
        // The one @SerialName remap: the Kotlin property is `dateFormat`, the Port wire key is `date_format`.
        assertOnlyOptional(
            PropertyDefinition(type = "string", dateFormat = "12-hour"),
            r,
            "date_format",
            eq(JsonPrimitive("12-hour")),
        )
        assertOnlyOptional(PropertyDefinition(type = "string", pattern = "^a$"), r, "pattern", eq(JsonPrimitive("^a$")))
        assertOnlyOptional(PropertyDefinition(type = "string", minLength = 1), r, "minLength", eq(JsonPrimitive(1)))
        assertOnlyOptional(PropertyDefinition(type = "string", maxLength = 5), r, "maxLength", eq(JsonPrimitive(5)))
        assertOnlyOptional(
            PropertyDefinition(type = "string", enum = listOf(JsonPrimitive("a"))),
            r,
            "enum",
            { it.toString() == "[\"a\"]" },
        )
        assertOnlyOptional(
            PropertyDefinition(type = "string", enumColors = mapOf("a" to "gold")),
            r,
            "enumColors",
            isObject,
        )
        assertOnlyOptional(PropertyDefinition(type = "string", spec = "open-api"), r, "spec", eq(JsonPrimitive("open-api")))
        assertOnlyOptional(
            PropertyDefinition(type = "string", specAuthentication = SpecAuthentication("https://a.test/a", "https://a.test/t", "c")),
            r,
            "specAuthentication",
            isObject,
        )
        assertOnlyOptional(PropertyDefinition(type = "number", minimum = 1.0), r, "minimum", eq(JsonPrimitive(1.0)))
        assertOnlyOptional(PropertyDefinition(type = "number", maximum = 5.0), r, "maximum", eq(JsonPrimitive(5.0)))
        assertOnlyOptional(
            PropertyDefinition(type = "number", exclusiveMinimum = 0.5),
            r,
            "exclusiveMinimum",
            eq(JsonPrimitive(0.5)),
        )
        assertOnlyOptional(
            PropertyDefinition(type = "number", exclusiveMaximum = 5.5),
            r,
            "exclusiveMaximum",
            eq(JsonPrimitive(5.5)),
        )
        assertOnlyOptional(PropertyDefinition(type = "array", items = ArrayItems(type = "string")), r, "items", isObject)
        assertOnlyOptional(PropertyDefinition(type = "array", minItems = 0), r, "minItems", eq(JsonPrimitive(0)))
        assertOnlyOptional(PropertyDefinition(type = "array", maxItems = 3), r, "maxItems", eq(JsonPrimitive(3)))
        assertOnlyOptional(PropertyDefinition(type = "array", uniqueItems = true), r, "uniqueItems", eq(JsonPrimitive(true)))
        assertOnlyOptional(
            PropertyDefinition(type = "object", properties = buildJsonObject { }),
            r,
            "properties",
            isObject,
        )
        assertOnlyOptional(
            PropertyDefinition(type = "object", patternProperties = buildJsonObject { }),
            r,
            "patternProperties",
            isObject,
        )
        assertOnlyOptional(
            PropertyDefinition(type = "object", additionalProperties = JsonPrimitive(true)),
            r,
            "additionalProperties",
            eq(JsonPrimitive(true)),
        )
    }

    @Test
    fun `ArrayItems wire names - one optional field at a time`() {
        val r = setOf("type")
        assertOnlyOptional(ArrayItems(type = "string", format = "email"), r, "format", eq(JsonPrimitive("email")))
        assertOnlyOptional(
            ArrayItems(type = "string", enum = listOf(JsonPrimitive("a"))),
            r,
            "enum",
            { it.toString() == "[\"a\"]" },
        )
        assertOnlyOptional(ArrayItems(type = "string", enumColors = mapOf("a" to "gold")), r, "enumColors", isObject)
    }

    @Test
    fun `SpecAuthentication wire names - the one optional field`() {
        val r = setOf("authorizationUrl", "tokenUrl", "clientId")
        assertOnlyOptional(
            SpecAuthentication("https://a.test/a", "https://a.test/t", "c", authorizationScope = listOf("read")),
            r,
            "authorizationScope",
            { it.toString() == "[\"read\"]" },
        )
    }

    @Test
    fun `RelationDefinition wire names - the one optional field`() {
        val r = setOf("title", "target", "required", "many")
        assertOnlyOptional(
            RelationDefinition(title = "T", description = "D", target = "t", required = false, many = false),
            r,
            "description",
            eq(JsonPrimitive("D")),
        )
    }

    @Test
    fun `CalculationPropertyDefinition wire names - one optional field at a time`() {
        val r = setOf("title", "type", "calculation")
        assertOnlyOptional(
            CalculationPropertyDefinition(title = "T", type = "string", calculation = ".x", format = "url"),
            r,
            "format",
            eq(JsonPrimitive("url")),
        )
        assertOnlyOptional(
            CalculationPropertyDefinition(title = "T", type = "string", calculation = ".x", spec = "open-api"),
            r,
            "spec",
            eq(JsonPrimitive("open-api")),
        )
        assertOnlyOptional(
            CalculationPropertyDefinition(title = "T", type = "string", calculation = ".x", colorized = true),
            r,
            "colorized",
            eq(JsonPrimitive(true)),
        )
        assertOnlyOptional(
            CalculationPropertyDefinition(title = "T", type = "string", calculation = ".x", colors = mapOf("OK" to "green")),
            r,
            "colors",
            isObject,
        )
    }

    @Test
    fun `AggregationCalculationSpec wire names - one optional field at a time`() {
        val r = setOf("calculationBy", "func")
        assertOnlyOptional(
            AggregationCalculationSpec(calculationBy = "property", func = "sum", property = "p"),
            r,
            "property",
            eq(JsonPrimitive("p")),
        )
        assertOnlyOptional(
            AggregationCalculationSpec(calculationBy = "entities", func = "average", averageOf = "week"),
            r,
            "averageOf",
            eq(JsonPrimitive("week")),
        )
        assertOnlyOptional(
            AggregationCalculationSpec(calculationBy = "entities", func = "count", measureTimeBy = "\$createdAt"),
            r,
            "measureTimeBy",
            eq(JsonPrimitive("\$createdAt")),
        )
    }

    @Test
    fun `AggregationQuery wire names - the one optional field`() {
        val r = setOf("combinator")
        assertOnlyOptional(
            AggregationQuery(combinator = "and", rules = listOf(buildJsonObject { })),
            r,
            "rules",
            { it.toString() == "[{}]" },
        )
    }

    @Test
    fun `AggregationPropertyDefinition wire names - one optional field at a time`() {
        val r = setOf("title", "target", "calculationSpec")
        val spec = AggregationCalculationSpec(calculationBy = "entities", func = "count")
        assertOnlyOptional(
            AggregationPropertyDefinition(title = "T", target = "t", calculationSpec = spec, query = AggregationQuery("and")),
            r,
            "query",
            isObject,
        )
        assertOnlyOptional(
            AggregationPropertyDefinition(
                title = "T",
                target = "t",
                calculationSpec = spec,
                pathFilter = listOf(buildJsonObject { }),
            ),
            r,
            "pathFilter",
            { it.toString() == "[{}]" },
        )
    }

    @Test
    fun `OwnershipDefinition wire names - one optional field at a time`() {
        val r = setOf("type")
        assertOnlyOptional(OwnershipDefinition(type = "Direct", title = "Owner"), r, "title", eq(JsonPrimitive("Owner")))
        assertOnlyOptional(OwnershipDefinition(type = "Inherited", path = "rel"), r, "path", eq(JsonPrimitive("rel")))
    }

    /**
     * [BlueprintSchema.properties]/`.required`, and every `BlueprintDefinition`/`Request`/
     * `Response` container field (`schema`, `relations`, `mirrorProperties`,
     * `calculationProperties`, `aggregationProperties`) are NON-nullable with a non-null
     * default (`emptyMap()`/`emptyList()`/`BlueprintSchema()`) — unlike every other optional
     * field in this file, `encodeDefaults = true` means they are ALWAYS present (as their
     * empty shape), never absent, regardless of which parameter a call site names. So instead
     * of "exactly one extra key", these fixtures assert the FULL expected document: the
     * untouched container fields at their empty default, the one field under test populated.
     */
    private inline fun <reified T> assertWireJson(instance: T, expected: JsonObject) {
        val actual = Json.parseToJsonElement(blueprintJson.encodeToString(instance)).jsonObject
        assertEquals(expected, actual, "unexpected wire shape")
    }

    private val emptySchemaJson = buildJsonObject {
        put("properties", buildJsonObject { })
        put("required", kotlinx.serialization.json.JsonArray(emptyList()))
    }

    @Test
    fun `BlueprintSchema wire names - one optional field at a time`() {
        assertWireJson(
            BlueprintSchema(properties = mapOf("p" to PropertyDefinition(type = "string"))),
            buildJsonObject {
                put("properties", buildJsonObject { put("p", buildJsonObject { put("type", "string") }) })
                put("required", kotlinx.serialization.json.JsonArray(emptyList()))
            },
        )
        assertWireJson(
            BlueprintSchema(required = listOf("p")),
            buildJsonObject {
                put("properties", buildJsonObject { })
                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("p"))))
            },
        )
    }

    @Test
    fun `BlueprintDefinition wire names - one optional field at a time`() {
        val schema = BlueprintSchema(properties = mapOf("p" to PropertyDefinition(type = "string")))
        val relations = mapOf("r" to RelationDefinition(title = "R", target = "t", required = false, many = false))
        val mirror = ch.nokillswit.blueprints.MirrorPropertyDefinition(title = "M", path = "r.prop")
        val calc = CalculationPropertyDefinition(title = "C", type = "string", calculation = ".x")
        val agg = AggregationPropertyDefinition(
            title = "A",
            target = "t",
            calculationSpec = AggregationCalculationSpec(calculationBy = "entities", func = "count"),
        )
        val defaultContainers = mapOf(
            "schema" to emptySchemaJson,
            "relations" to buildJsonObject { },
            "mirrorProperties" to buildJsonObject { },
            "calculationProperties" to buildJsonObject { },
            "aggregationProperties" to buildJsonObject { },
        )
        fun expectedWith(key: String, value: JsonElement) = JsonObject(defaultContainers + (key to value))

        assertWireJson(
            BlueprintDefinition(schema = schema),
            expectedWith("schema", Json.parseToJsonElement(blueprintJson.encodeToString(schema))),
        )
        assertWireJson(
            BlueprintDefinition(relations = relations),
            expectedWith("relations", Json.parseToJsonElement(blueprintJson.encodeToString(relations))),
        )
        assertWireJson(
            BlueprintDefinition(mirrorProperties = mapOf("m" to mirror)),
            expectedWith(
                "mirrorProperties",
                Json.parseToJsonElement(blueprintJson.encodeToString(mapOf("m" to mirror))),
            ),
        )
        assertWireJson(
            BlueprintDefinition(calculationProperties = mapOf("c" to calc)),
            expectedWith(
                "calculationProperties",
                Json.parseToJsonElement(blueprintJson.encodeToString(mapOf("c" to calc))),
            ),
        )
        assertWireJson(
            BlueprintDefinition(aggregationProperties = mapOf("a" to agg)),
            expectedWith(
                "aggregationProperties",
                Json.parseToJsonElement(blueprintJson.encodeToString(mapOf("a" to agg))),
            ),
        )
        // ownership IS nullable/null-default — the one genuinely absent-unless-named field here.
        assertWireJson(
            BlueprintDefinition(ownership = OwnershipDefinition(type = "Direct")),
            JsonObject(defaultContainers + ("ownership" to buildJsonObject { put("type", "Direct") })),
        )
    }

    private val defaultContainers = mapOf(
        "schema" to emptySchemaJson,
        "relations" to buildJsonObject { },
        "mirrorProperties" to buildJsonObject { },
        "calculationProperties" to buildJsonObject { },
        "aggregationProperties" to buildJsonObject { },
    )

    @Test
    fun `BlueprintRequest wire names - one optional field at a time`() {
        // description/icon/ownership are genuinely nullable (absent unless named); schema/
        // relations/mirrorProperties/calculationProperties/aggregationProperties are not —
        // they default to an empty (never absent) shape, so they show up in EVERY case here.
        val alwaysPresent = setOf("identifier", "title") + defaultContainers.keys
        val relations = mapOf("r" to RelationDefinition(title = "R", target = "t", required = false, many = false))
        val mirror = mapOf("m" to ch.nokillswit.blueprints.MirrorPropertyDefinition(title = "M", path = "r.prop"))
        val calc = mapOf("c" to CalculationPropertyDefinition(title = "C", type = "string", calculation = ".x"))
        val agg = mapOf(
            "a" to AggregationPropertyDefinition(
                title = "A",
                target = "t",
                calculationSpec = AggregationCalculationSpec(calculationBy = "entities", func = "count"),
            ),
        )
        val schema = BlueprintSchema(properties = mapOf("p" to PropertyDefinition(type = "string")))

        assertOnlyOptional(
            BlueprintRequest(identifier = "bp", title = "T", description = "D"),
            alwaysPresent,
            "description",
            eq(JsonPrimitive("D")),
        )
        assertOnlyOptional(
            BlueprintRequest(identifier = "bp", title = "T", icon = "I"),
            alwaysPresent,
            "icon",
            eq(JsonPrimitive("I")),
        )
        assertOnlyOptional(
            BlueprintRequest(identifier = "bp", title = "T", ownership = OwnershipDefinition(type = "Direct")),
            alwaysPresent,
            "ownership",
            isObject,
        )

        fun expectedWith(key: String, value: JsonElement) = JsonObject(
            mapOf("identifier" to JsonPrimitive("bp"), "title" to JsonPrimitive("T")) + defaultContainers + (key to value),
        )
        assertWireJson(
            BlueprintRequest(identifier = "bp", title = "T", schema = schema),
            expectedWith("schema", Json.parseToJsonElement(blueprintJson.encodeToString(schema))),
        )
        assertWireJson(
            BlueprintRequest(identifier = "bp", title = "T", relations = relations),
            expectedWith("relations", Json.parseToJsonElement(blueprintJson.encodeToString(relations))),
        )
        assertWireJson(
            BlueprintRequest(identifier = "bp", title = "T", mirrorProperties = mirror),
            expectedWith("mirrorProperties", Json.parseToJsonElement(blueprintJson.encodeToString(mirror))),
        )
        assertWireJson(
            BlueprintRequest(identifier = "bp", title = "T", calculationProperties = calc),
            expectedWith("calculationProperties", Json.parseToJsonElement(blueprintJson.encodeToString(calc))),
        )
        assertWireJson(
            BlueprintRequest(identifier = "bp", title = "T", aggregationProperties = agg),
            expectedWith("aggregationProperties", Json.parseToJsonElement(blueprintJson.encodeToString(agg))),
        )
    }

    @Test
    fun `BlueprintResponse wire names - one optional field at a time`() {
        // Every case below names ONLY the required constructor args plus exactly one optional
        // one — never `.copy()`, which always calls the master (all-args) constructor and so
        // would never exercise the default-args bridge constructor's per-field mask branch.
        val requiredKeys = setOf("id", "identifier", "title", "createdBy", "creatorName", "creatorDeleted", "createdAt", "updatedAt")
        val alwaysPresent = requiredKeys + defaultContainers.keys
        val requiredValues = mapOf(
            "id" to JsonPrimitive(1), "identifier" to JsonPrimitive("bp"), "title" to JsonPrimitive("T"),
            "createdBy" to JsonPrimitive(2), "creatorName" to JsonPrimitive("Creator"),
            "creatorDeleted" to JsonPrimitive(false), "createdAt" to JsonPrimitive(1), "updatedAt" to JsonPrimitive(2),
        )

        assertOnlyOptional(
            BlueprintResponse(
                id = 1u, identifier = "bp", title = "T", createdBy = 2u, creatorName = "Creator",
                creatorDeleted = false, createdAt = 1L, updatedAt = 2L, description = "D",
            ),
            alwaysPresent,
            "description",
            eq(JsonPrimitive("D")),
        )
        assertOnlyOptional(
            BlueprintResponse(
                id = 1u, identifier = "bp", title = "T", createdBy = 2u, creatorName = "Creator",
                creatorDeleted = false, createdAt = 1L, updatedAt = 2L, icon = "I",
            ),
            alwaysPresent,
            "icon",
            eq(JsonPrimitive("I")),
        )
        assertOnlyOptional(
            BlueprintResponse(
                id = 1u, identifier = "bp", title = "T", createdBy = 2u, creatorName = "Creator",
                creatorDeleted = false, createdAt = 1L, updatedAt = 2L, ownership = OwnershipDefinition(type = "Direct"),
            ),
            alwaysPresent,
            "ownership",
            isObject,
        )

        val schema = BlueprintSchema(properties = mapOf("p" to PropertyDefinition(type = "string")))
        val relations = mapOf("r" to RelationDefinition(title = "R", target = "t", required = false, many = false))
        val mirror = mapOf("m" to ch.nokillswit.blueprints.MirrorPropertyDefinition(title = "M", path = "r.prop"))
        val calc = mapOf("c" to CalculationPropertyDefinition(title = "C", type = "string", calculation = ".x"))
        val agg = mapOf(
            "a" to AggregationPropertyDefinition(
                title = "A",
                target = "t",
                calculationSpec = AggregationCalculationSpec(calculationBy = "entities", func = "count"),
            ),
        )
        fun expectedWith(key: String, value: JsonElement) = JsonObject(requiredValues + defaultContainers + (key to value))
        assertWireJson(
            BlueprintResponse(
                id = 1u, identifier = "bp", title = "T", createdBy = 2u, creatorName = "Creator",
                creatorDeleted = false, createdAt = 1L, updatedAt = 2L, schema = schema,
            ),
            expectedWith("schema", Json.parseToJsonElement(blueprintJson.encodeToString(schema))),
        )
        assertWireJson(
            BlueprintResponse(
                id = 1u, identifier = "bp", title = "T", createdBy = 2u, creatorName = "Creator",
                creatorDeleted = false, createdAt = 1L, updatedAt = 2L, relations = relations,
            ),
            expectedWith("relations", Json.parseToJsonElement(blueprintJson.encodeToString(relations))),
        )
        assertWireJson(
            BlueprintResponse(
                id = 1u, identifier = "bp", title = "T", createdBy = 2u, creatorName = "Creator",
                creatorDeleted = false, createdAt = 1L, updatedAt = 2L, mirrorProperties = mirror,
            ),
            expectedWith("mirrorProperties", Json.parseToJsonElement(blueprintJson.encodeToString(mirror))),
        )
        assertWireJson(
            BlueprintResponse(
                id = 1u, identifier = "bp", title = "T", createdBy = 2u, creatorName = "Creator",
                creatorDeleted = false, createdAt = 1L, updatedAt = 2L, calculationProperties = calc,
            ),
            expectedWith("calculationProperties", Json.parseToJsonElement(blueprintJson.encodeToString(calc))),
        )
        assertWireJson(
            BlueprintResponse(
                id = 1u, identifier = "bp", title = "T", createdBy = 2u, creatorName = "Creator",
                creatorDeleted = false, createdAt = 1L, updatedAt = 2L, aggregationProperties = agg,
            ),
            expectedWith("aggregationProperties", Json.parseToJsonElement(blueprintJson.encodeToString(agg))),
        )
    }
}
