package ch.nokillswit

import ch.nokillswit.blueprints.AggregationCalculationSpec
import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.AggregationQuery
import ch.nokillswit.blueprints.ArrayItems
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.MirrorPropertyDefinition
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SpecAuthentication
import ch.nokillswit.blueprints.validateBlueprintRequest
import ch.nokillswit.blueprints.validateProperty
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The pure Port-blueprint rule table — no DB, one case per rule in the plan's "Port rules"
 * section. [validateBlueprintRequest] and [validateProperty] are the route- and
 * service-enforced single source; this file pins every branch in isolation.
 */
class BlueprintValidationTest {

    private fun base(
        identifier: String = "bp-valid",
        title: String = "Valid Blueprint",
        schema: BlueprintSchema = BlueprintSchema(),
        relations: Map<String, RelationDefinition> = emptyMap(),
        mirrorProperties: Map<String, MirrorPropertyDefinition> = emptyMap(),
        calculationProperties: Map<String, CalculationPropertyDefinition> = emptyMap(),
        aggregationProperties: Map<String, AggregationPropertyDefinition> = emptyMap(),
        ownership: OwnershipDefinition? = null,
    ) = BlueprintRequest(
        identifier = identifier,
        title = title,
        schema = schema,
        relations = relations,
        mirrorProperties = mirrorProperties,
        calculationProperties = calculationProperties,
        aggregationProperties = aggregationProperties,
        ownership = ownership,
    )

    private fun assertValid(request: BlueprintRequest) = validateBlueprintRequest(request)

    private fun assertInvalid(request: BlueprintRequest) {
        assertFailsWith<BadRequestException> { validateBlueprintRequest(request) }
    }

    // ─── blueprint-level ─────────────────────────────────────────────────────────────────

    @Test
    fun `a well-formed request passes`() = assertValid(base())

    @Test
    fun `identifier grammar and length are enforced`() {
        assertInvalid(base(identifier = ""))
        assertInvalid(base(identifier = "has space"))
        assertInvalid(base(identifier = "has\$dollar"))
        assertInvalid(base(identifier = "x".repeat(101)))
        assertValid(base(identifier = "a-Z0.9@_:/=-valid"))
    }

    @Test
    fun `title must be 1-100 characters`() {
        assertInvalid(base(title = ""))
        assertInvalid(base(title = "x".repeat(101)))
    }

    @Test
    fun `description and icon length caps are enforced`() {
        assertInvalid(base().copy(description = "x".repeat(2001)))
        assertInvalid(base().copy(icon = "x".repeat(101)))
        assertValid(base().copy(description = "x".repeat(2000), icon = "x".repeat(100)))
    }

    @Test
    fun `family caps reject oversized maps`() {
        val tooManyProperties = (1..201).associate { "p$it" to PropertyDefinition(type = "string") }
        assertInvalid(base(schema = BlueprintSchema(properties = tooManyProperties)))

        val tooManyRelations = (1..101).associate { "r$it" to relation() }
        assertInvalid(base(relations = tooManyRelations))

        val tooManyMirrors = (1..101).associate { "m$it" to MirrorPropertyDefinition("T", "r.\$title") }
        assertInvalid(base(relations = mapOf("r" to relation()), mirrorProperties = tooManyMirrors))

        val tooManyCalcs = (1..101).associate { "c$it" to calc() }
        assertInvalid(base(calculationProperties = tooManyCalcs))

        val tooManyAggs = (1..101).associate { "a$it" to aggregation("bp-valid") }
        assertInvalid(base(aggregationProperties = tooManyAggs))
    }

    @Test
    fun `schema required must be a subset of properties with no duplicates`() {
        assertInvalid(base(schema = BlueprintSchema(properties = mapOf("a" to str()), required = listOf("missing"))))
        assertInvalid(base(schema = BlueprintSchema(properties = mapOf("a" to str()), required = listOf("a", "a"))))
        assertValid(base(schema = BlueprintSchema(properties = mapOf("a" to str()), required = listOf("a"))))
    }

    @Test
    fun `one identifier namespace spans properties, mirror, calculation, and aggregation`() {
        assertInvalid(
            base(
                schema = BlueprintSchema(properties = mapOf("dup" to str())),
                calculationProperties = mapOf("dup" to calc()),
            ),
        )
        // Relations are a SEPARATE namespace: colliding with a property id is fine.
        assertValid(
            base(
                schema = BlueprintSchema(properties = mapOf("dup" to str())),
                relations = mapOf("dup" to relation()),
            ),
        )
    }

    // ─── relations ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a relation cannot be both required and many`() {
        assertInvalid(base(relations = mapOf("r" to relation(required = true, many = true))))
        assertValid(base(relations = mapOf("r" to relation(required = true, many = false))))
    }

    @Test
    fun `a relation target must follow the identifier grammar`() {
        assertInvalid(base(relations = mapOf("r" to relation(target = "has space"))))
    }

    @Test
    fun `a relation key must follow the identifier grammar`() {
        assertInvalid(base(relations = mapOf("has space" to relation())))
    }

    // ─── hierarchyRelations (Toadie-only extension, V29, widened to a MAP in V34) ────────────

    @Test
    fun `hierarchyRelations entry must name a relation of this blueprint`() {
        val invalid = base(relations = mapOf("r" to relation()), ownership = null)
            .copy(hierarchyRelations = mapOf("composition" to "nope"))
        assertInvalid(invalid)
        assertValid(base(relations = mapOf("r" to relation())).copy(hierarchyRelations = mapOf("composition" to "r")))
    }

    @Test
    fun `hierarchyRelations entry must name a single relation, not a many one`() {
        val many = base(relations = mapOf("r" to relation(many = true))).copy(hierarchyRelations = mapOf("composition" to "r"))
        assertInvalid(many)
        val single = base(relations = mapOf("r" to relation(many = false))).copy(hierarchyRelations = mapOf("composition" to "r"))
        assertValid(single)
    }

    @Test
    fun `hierarchyRelations is optional and absent by default`() {
        assertValid(base())
        assertValid(base(relations = mapOf("r" to relation())))
    }

    @Test
    fun `two different hierarchy identifiers may legitimately share one relation`() {
        val request = base(relations = mapOf("r" to relation())).copy(
            hierarchyRelations = mapOf("composition" to "r", "deployment" to "r"),
        )
        assertValid(request)
    }

    // ─── mirror properties ───────────────────────────────────────────────────────────────

    @Test
    fun `a mirror path must have 1-10 dot segments starting with a declared relation`() {
        val manySegments = (1..11).joinToString(".")
        assertInvalid(
            base(relations = mapOf("r" to relation()), mirrorProperties = mapOf("m" to MirrorPropertyDefinition("T", manySegments))),
        )
        assertInvalid(
            base(relations = mapOf("r" to relation()), mirrorProperties = mapOf("m" to MirrorPropertyDefinition("T", "unknownRel.prop"))),
        )
        assertValid(
            base(relations = mapOf("r" to relation()), mirrorProperties = mapOf("m" to MirrorPropertyDefinition("T", "r.prop"))),
        )
    }

    @Test
    fun `only the last mirror path segment may be a meta-property`() {
        assertInvalid(
            base(
                relations = mapOf("r" to relation()),
                mirrorProperties = mapOf("m" to MirrorPropertyDefinition("T", "r.\$identifier.prop")),
            ),
        )
        assertValid(
            base(
                relations = mapOf("r" to relation()),
                mirrorProperties = mapOf("m" to MirrorPropertyDefinition("T", "r.\$identifier")),
            ),
        )
    }

    // ─── calculation properties ──────────────────────────────────────────────────────────

    @Test
    fun `calculation type must be one of the five property types`() {
        assertInvalid(base(calculationProperties = mapOf("c" to calc(type = "unknown"))))
    }

    @Test
    fun `calculation format and spec only apply to string and object types`() {
        assertInvalid(base(calculationProperties = mapOf("c" to calc(type = "boolean", format = "url"))))
        assertInvalid(base(calculationProperties = mapOf("c" to calc(type = "number", spec = "open-api"))))
        // Type IS applicable (string/object) but the value itself is unrecognized.
        assertInvalid(base(calculationProperties = mapOf("c" to calc(type = "string", format = "not-a-format"))))
        assertInvalid(base(calculationProperties = mapOf("c" to calc(type = "string", spec = "not-a-spec"))))
        assertValid(base(calculationProperties = mapOf("c" to calc(type = "string", format = "url", spec = "open-api"))))
        assertValid(base(calculationProperties = mapOf("c" to calc(type = "object", format = "labeled-url"))))
    }

    @Test
    fun `calculation length must be 1-10000 characters`() {
        assertInvalid(base(calculationProperties = mapOf("c" to calc(calculation = ""))))
        assertInvalid(base(calculationProperties = mapOf("c" to calc(calculation = "x".repeat(10_001)))))
    }

    @Test
    fun `calculation colors must be one of the 14 enum colors`() {
        assertInvalid(base(calculationProperties = mapOf("c" to calc(colors = mapOf("v" to "notacolor")))))
        assertValid(base(calculationProperties = mapOf("c" to calc(colors = mapOf("v" to "gold")))))
        assertValid(base(calculationProperties = mapOf("c" to calc(colors = emptyMap()))))
    }

    @Test
    fun `an oversized definition document is rejected`() {
        val calcs = (1..30).associate { "c$it" to calc(calculation = "x".repeat(10_000)) }
        assertInvalid(base(calculationProperties = calcs))
    }

    // ─── aggregation properties ──────────────────────────────────────────────────────────

    @Test
    fun `aggregation target must follow the identifier grammar`() {
        assertInvalid(base(aggregationProperties = mapOf("a" to aggregation("has space"))))
    }

    @Test
    fun `calculationBy and func must be recognized values`() {
        assertInvalid(base(aggregationProperties = mapOf("a" to aggregation("bp-valid", calculationBy = "unknown"))))
        assertInvalid(base(aggregationProperties = mapOf("a" to aggregation("bp-valid", func = "unknown"))))
    }

    @Test
    fun `calculationBy entities allows only count or average and no property`() {
        assertInvalid(base(aggregationProperties = mapOf("a" to aggregation("bp-valid", calculationBy = "entities", func = "sum"))))
        assertInvalid(
            base(
                aggregationProperties = mapOf(
                    "a" to aggregation("bp-valid", calculationBy = "entities", func = "count", property = "p"),
                ),
            ),
        )
        assertValid(base(aggregationProperties = mapOf("a" to aggregation("bp-valid", calculationBy = "entities", func = "average"))))
    }

    @Test
    fun `calculationBy property requires property and forbids func count`() {
        assertInvalid(base(aggregationProperties = mapOf("a" to aggregation("bp-valid", calculationBy = "property", func = "sum"))))
        assertInvalid(
            base(
                aggregationProperties = mapOf(
                    "a" to aggregation("bp-valid", calculationBy = "property", func = "count", property = "p"),
                ),
            ),
        )
        assertValid(
            base(
                aggregationProperties = mapOf(
                    "a" to aggregation("bp-valid", calculationBy = "property", func = "sum", property = "p"),
                ),
            ),
        )
    }

    @Test
    fun `averageOf must be a supported period`() {
        assertInvalid(
            base(
                aggregationProperties = mapOf(
                    "a" to aggregation("bp-valid", calculationBy = "entities", func = "average", averageOf = "century"),
                ),
            ),
        )
        assertValid(
            base(
                aggregationProperties = mapOf(
                    "a" to aggregation("bp-valid", calculationBy = "entities", func = "average", averageOf = "week"),
                ),
            ),
        )
    }

    @Test
    fun `query combinator must be and or or`() {
        assertInvalid(
            base(
                aggregationProperties = mapOf(
                    "a" to aggregation("bp-valid").copy(query = AggregationQuery(combinator = "xor")),
                ),
            ),
        )
        assertValid(
            base(
                aggregationProperties = mapOf(
                    "a" to aggregation("bp-valid").copy(query = AggregationQuery(combinator = "and")),
                ),
            ),
        )
    }

    // ─── ownership ───────────────────────────────────────────────────────────────────────

    @Test
    fun `ownership type must be Direct or Inherited`() {
        assertInvalid(base(ownership = OwnershipDefinition(type = "Other")))
    }

    @Test
    fun `Direct ownership forbids a path`() {
        assertInvalid(base(ownership = OwnershipDefinition(type = "Direct", path = "r")))
        assertValid(base(ownership = OwnershipDefinition(type = "Direct")))
    }

    @Test
    fun `Inherited ownership requires a path starting with a declared relation`() {
        assertInvalid(base(relations = mapOf("r" to relation()), ownership = OwnershipDefinition(type = "Inherited")))
        assertInvalid(
            base(relations = mapOf("r" to relation()), ownership = OwnershipDefinition(type = "Inherited", path = "unknown")),
        )
        assertValid(base(relations = mapOf("r" to relation()), ownership = OwnershipDefinition(type = "Inherited", path = "r")))
    }

    // ─── property applicability (delegated to validateProperty) ─────────────────────────

    @Test
    fun `property type must be one of the five`() {
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "unknown")) }
    }

    @Test
    fun `property id must not start with dollar`() {
        assertFailsWith<BadRequestException> { validateProperty("\$identifier", PropertyDefinition(type = "string")) }
    }

    @Test
    fun `a field not applicable to the property's type is rejected`() {
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "number", minLength = 1)) }
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "string", minimum = 1.0)) }
    }

    @Test
    fun `string format must be one of the descriptor list`() {
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "string", format = "not-a-format")) }
        validateProperty("p", PropertyDefinition(type = "string", format = "url"))
    }

    @Test
    fun `date_format only applies with format date-time`() {
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "string", dateFormat = "relative"))
        }
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "string", format = "date-time", dateFormat = "not-a-format"))
        }
        validateProperty("p", PropertyDefinition(type = "string", format = "date-time", dateFormat = "relative"))
    }

    @Test
    fun `pattern must be a valid non-empty regular expression within the length cap`() {
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "string", pattern = "")) }
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "string", pattern = "x".repeat(501)))
        }
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "string", pattern = "[")) }
        validateProperty("p", PropertyDefinition(type = "string", pattern = "^[a-z]+$"))
    }

    @Test
    fun `string minLength must be at most maxLength`() {
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "string", minLength = 5, maxLength = 1))
        }
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "string", minLength = -1)) }
    }

    @Test
    fun `spec is only valid for string and object and specAuthentication requires spec plus absolute urls`() {
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "string", spec = "unknown")) }
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "string",
                    specAuthentication = SpecAuthentication("relative", "https://example.test/token", "id"),
                ),
            )
        }
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "string",
                    spec = "open-api",
                    specAuthentication = SpecAuthentication("not a url", "https://example.test/token", "id"),
                ),
            )
        }
        validateProperty(
            "p",
            PropertyDefinition(
                type = "string",
                spec = "open-api",
                specAuthentication = SpecAuthentication("https://example.test/auth", "https://example.test/token", "id"),
            ),
        )
    }

    @Test
    fun `number bounds must be internally consistent`() {
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "number", minimum = 5.0, maximum = 1.0)) }
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "number", minimum = 1.0, exclusiveMinimum = 1.0))
        }
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "number", exclusiveMinimum = 5.0, exclusiveMaximum = 1.0))
        }
        validateProperty("p", PropertyDefinition(type = "number", minimum = 1.0, maximum = 5.0))
    }

    @Test
    fun `array items are required and their type and format are constrained`() {
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "array")) }
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "array", items = ArrayItems(type = "array")))
        }
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "array", items = ArrayItems(type = "boolean", format = "url")))
        }
        validateProperty("p", PropertyDefinition(type = "array", items = ArrayItems(type = "string", format = "url")))
    }

    @Test
    fun `array minItems must be at most maxItems`() {
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(type = "array", items = ArrayItems(type = "string"), minItems = 5, maxItems = 1),
            )
        }
    }

    @Test
    fun `object format spec and open sub-trees are shape-checked`() {
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "object", format = "url")) }
        validateProperty("p", PropertyDefinition(type = "object", format = "labeled-url"))
        assertFailsWith<BadRequestException> { validateProperty("p", PropertyDefinition(type = "object", spec = "unknown")) }
    }

    @Test
    fun `enum entries must match the type, be non-empty, unique, and colors must reference declared values`() {
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "string", enum = emptyList()))
        }
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "string",
                    enum = listOf(JsonPrimitive("a"), JsonPrimitive(1)),
                ),
            )
        }
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "string",
                    enum = listOf(JsonPrimitive("a"), JsonPrimitive("a")),
                ),
            )
        }
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "string",
                    enum = listOf(JsonPrimitive("a")),
                    enumColors = mapOf("b" to "gold"),
                ),
            )
        }
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "string",
                    enum = listOf(JsonPrimitive("a")),
                    enumColors = mapOf("a" to "notacolor"),
                ),
            )
        }
        validateProperty(
            "p",
            PropertyDefinition(
                type = "string",
                enum = listOf(JsonPrimitive("a")),
                enumColors = mapOf("a" to "gold"),
            ),
        )
    }

    @Test
    fun `default must match the type, array elements must match items type, and default in enum`() {
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "string", default = JsonPrimitive(1)))
        }
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "array",
                    items = ArrayItems(type = "number"),
                    default = JsonArray(listOf(JsonPrimitive("not-a-number"))),
                ),
            )
        }
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "string",
                    enum = listOf(JsonPrimitive("a")),
                    default = JsonPrimitive("b"),
                ),
            )
        }
        validateProperty(
            "p",
            PropertyDefinition(
                type = "array",
                items = ArrayItems(type = "number"),
                default = JsonArray(listOf(JsonPrimitive(1))),
            ),
        )
    }

    @Test
    fun `property title, description, and icon length caps are enforced`() {
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "string", description = "x".repeat(2001)))
        }
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "string", icon = "x".repeat(101)))
        }
    }

    @Test
    fun `a mirror path with a blank segment is rejected even within the length cap`() {
        assertFailsWith<BadRequestException> {
            validateBlueprintRequest(
                base(
                    relations = mapOf("r" to relation()),
                    mirrorProperties = mapOf("m" to MirrorPropertyDefinition("T", "r..prop")),
                ),
            )
        }
    }

    @Test
    fun `specAuthentication rejects an invalid tokenUrl even with a valid authorizationUrl`() {
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "string",
                    spec = "open-api",
                    specAuthentication = SpecAuthentication("https://example.test/auth", "not a url", "id"),
                ),
            )
        }
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "string",
                    spec = "open-api",
                    specAuthentication = SpecAuthentication("", "https://example.test/token", "id"),
                ),
            )
        }
    }

    @Test
    fun `array items format must be a recognized string format`() {
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "array", items = ArrayItems(type = "string", format = "not-a-format")))
        }
    }

    @Test
    fun `array minItems must not be negative`() {
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "array", items = ArrayItems(type = "string"), minItems = -1))
        }
    }

    @Test
    fun `object additionalProperties accepts a boolean or an object but rejects anything else`() {
        validateProperty("p", PropertyDefinition(type = "object", additionalProperties = JsonPrimitive(true)))
        validateProperty(
            "p",
            PropertyDefinition(type = "object", additionalProperties = kotlinx.serialization.json.buildJsonObject {}),
        )
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "object", additionalProperties = JsonPrimitive("neither")))
        }
    }

    @Test
    fun `object properties and patternProperties values must themselves be objects`() {
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "object",
                    properties = kotlinx.serialization.json.buildJsonObject { put("k", JsonPrimitive("not-an-object")) },
                ),
            )
        }
        assertFailsWith<BadRequestException> {
            validateProperty(
                "p",
                PropertyDefinition(
                    type = "object",
                    patternProperties = kotlinx.serialization.json.buildJsonObject { put("k", JsonPrimitive("not-an-object")) },
                ),
            )
        }
    }

    @Test
    fun `enumColors without enum is rejected and an oversized enum is rejected`() {
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "string", enumColors = mapOf("a" to "gold")))
        }
        val tooManyValues = (1..201).map { JsonPrimitive("v$it") }
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "string", enum = tooManyValues))
        }
    }

    @Test
    fun `exclusiveMinimum equal to exclusiveMaximum is rejected - strictly less than`() {
        assertFailsWith<BadRequestException> {
            validateProperty("p", PropertyDefinition(type = "number", exclusiveMinimum = 1.0, exclusiveMaximum = 1.0))
        }
    }

    @Test
    fun `validateDefault is a no-op without items and matches boolean and object types directly`() {
        // items.type absent: an array default's elements are unchecked (nothing to check against).
        ch.nokillswit.blueprints.validateDefault(
            "p",
            PropertyDefinition(type = "array", default = JsonArray(listOf(JsonPrimitive("anything")))),
        )
        ch.nokillswit.blueprints.validateDefault("p", PropertyDefinition(type = "boolean", default = JsonPrimitive(true)))
        assertFailsWith<BadRequestException> {
            ch.nokillswit.blueprints.validateDefault("p", PropertyDefinition(type = "boolean", default = JsonPrimitive("not-a-bool")))
        }
        ch.nokillswit.blueprints.validateDefault(
            "p",
            PropertyDefinition(type = "object", default = kotlinx.serialization.json.buildJsonObject {}),
        )
        assertFailsWith<BadRequestException> {
            ch.nokillswit.blueprints.validateDefault("p", PropertyDefinition(type = "object", default = JsonPrimitive("not-an-object")))
        }
    }

    @Test
    fun `validateDefault array elements accept boolean and object item types`() {
        ch.nokillswit.blueprints.validateDefault(
            "p",
            PropertyDefinition(
                type = "array",
                items = ArrayItems(type = "boolean"),
                default = JsonArray(listOf(JsonPrimitive(true))),
            ),
        )
        assertFailsWith<BadRequestException> {
            ch.nokillswit.blueprints.validateDefault(
                "p",
                PropertyDefinition(
                    type = "array",
                    items = ArrayItems(type = "boolean"),
                    default = JsonArray(listOf(JsonPrimitive("not-a-bool"))),
                ),
            )
        }
    }

    // ─── fixtures ────────────────────────────────────────────────────────────────────────

    private fun str() = PropertyDefinition(type = "string")

    private fun relation(target: String = "bp-valid", required: Boolean = false, many: Boolean = false) =
        RelationDefinition(title = "Rel", target = target, required = required, many = many)

    private fun calc(
        type: String = "string",
        format: String? = null,
        spec: String? = null,
        calculation: String = ".x",
        colors: Map<String, String>? = null,
    ) = CalculationPropertyDefinition(title = "Calc", type = type, format = format, spec = spec, calculation = calculation, colors = colors)

    private fun aggregation(
        target: String,
        calculationBy: String = "entities",
        func: String = "count",
        property: String? = null,
        averageOf: String? = null,
    ) = AggregationPropertyDefinition(
        title = "Agg",
        target = target,
        calculationSpec = AggregationCalculationSpec(
            calculationBy = calculationBy,
            func = func,
            property = property,
            averageOf = averageOf,
        ),
    )
}
