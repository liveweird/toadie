package ch.nokillswit

import ch.nokillswit.blueprints.AggregationCalculationSpec
import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.ArrayItems
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.MirrorPropertyDefinition
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.ComputedSubject
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.EntityIndex
import ch.nokillswit.entities.Inbound
import ch.nokillswit.entities.IndexedRow
import ch.nokillswit.entities.JqEvaluator
import ch.nokillswit.entities.MAX_MIRROR_FANOUT
import ch.nokillswit.entities.RowLookup
import ch.nokillswit.entities.asOwned
import ch.nokillswit.entities.calculationValue
import ch.nokillswit.entities.computedPathBlueprints
import ch.nokillswit.entities.computedProperties
import ch.nokillswit.entities.mirrorValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 5 (`.claude/docs/port-data-model.md` "Computed properties"): pure coverage of
 * `entities/EntityComputed.kt` — the mirror walk, calculation coercion, the orchestrator, and
 * the static snapshot-widening twin of `EntityOwnership.kt`'s `ownershipPathBlueprints`. No
 * database — every lookup goes through a tiny in-memory [FakeIndex].
 */
class EntityComputedTest {

    private class FakeIndex(
        private val rows: Map<Pair<String, String>, IndexedRow> = emptyMap(),
        private val inboundMap: Map<Pair<String, String>, List<Inbound>> = emptyMap(),
    ) : EntityIndex {
        override fun row(blueprint: String, identifier: String): IndexedRow? = rows[blueprint to identifier]
        override fun inbound(blueprint: String, identifier: String): List<Inbound> = inboundMap[blueprint to identifier].orEmpty()
        override val rowLookup: RowLookup = { bp, id -> row(bp, id)?.asOwned() }
    }

    private fun relation(target: String, many: Boolean = false, required: Boolean = false) =
        RelationDefinition(title = "R", target = target, required = required, many = many)

    private fun doc(properties: JsonObject = JsonObject(emptyMap()), relations: JsonObject = JsonObject(emptyMap())) =
        EntityDocument(properties = properties, relations = relations)

    private fun row(
        blueprint: String,
        identifier: String,
        title: String = identifier,
        icon: String? = null,
        team: JsonElement? = null,
        document: EntityDocument = doc(),
        createdAt: Long = 111,
        updatedAt: Long = 222,
    ) = IndexedRow(blueprint, identifier, title, icon, createdAt, updatedAt, document, team)

    private fun subject(
        blueprint: String,
        identifier: String = "subj",
        title: String = "Subj",
        document: EntityDocument = doc(),
        team: JsonElement? = null,
        createdAt: Long = 1,
        updatedAt: Long = 2,
    ) = ComputedSubject(blueprint, identifier, title, null, team, document, createdAt, updatedAt)

    // ---------------------------------------------------------------------------------------
    // mirrorValue
    // ---------------------------------------------------------------------------------------

    @Test
    fun `mirror - a single hop reads a meta-property off the related entity`() {
        val sBp = "system"
        val bBp = "component"
        val index = FakeIndex(rows = mapOf(sBp to "checkout" to row(sBp, "checkout", title = "Checkout System")))
        val definitions = mapOf(bBp to BlueprintDefinition(relations = mapOf("sys" to relation(sBp))), sBp to BlueprintDefinition())
        val subj = subject(bBp, document = doc(relations = buildJsonObject { put("sys", "checkout") }))
        assertEquals(JsonPrimitive("Checkout System"), mirrorValue("sys.\$title", subj, definitions, index))
    }

    @Test
    fun `mirror - two hops walk through an intermediate blueprint`() {
        val sBp = "system"
        val dBp = "domain"
        val bBp = "component"
        val sRow = row(sBp, "checkout", document = doc(relations = buildJsonObject { put("domain", "commerce") }))
        val index = FakeIndex(rows = mapOf(sBp to "checkout" to sRow, dBp to "commerce" to row(dBp, "commerce", title = "Commerce")))
        val definitions = mapOf(
            bBp to BlueprintDefinition(relations = mapOf("sys" to relation(sBp))),
            sBp to BlueprintDefinition(relations = mapOf("domain" to relation(dBp))),
            dBp to BlueprintDefinition(),
        )
        val subj = subject(bBp, document = doc(relations = buildJsonObject { put("sys", "checkout") }))
        assertEquals(JsonPrimitive("Commerce"), mirrorValue("sys.domain.\$title", subj, definitions, index))
    }

    @Test
    fun `mirror - a many hop fans out into a deduped, one-level-flattened array`() {
        val cBp = "child"
        val bBp = "component"
        val tagsProperty = PropertyDefinition(type = "array", items = ArrayItems(type = "string"))
        val c1Tags = buildJsonObject { put("tags", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))) }
        val c1 = row(cBp, "c1", title = "same", document = doc(properties = c1Tags))
        val c2Tags = buildJsonObject { put("tags", JsonArray(listOf(JsonPrimitive("b"), JsonPrimitive("c")))) }
        val c2 = row(cBp, "c2", title = "same", document = doc(properties = c2Tags))
        val index = FakeIndex(rows = mapOf(cBp to "c1" to c1, cBp to "c2" to c2))
        val definitions = mapOf(
            bBp to BlueprintDefinition(relations = mapOf("children" to relation(cBp, many = true))),
            cBp to BlueprintDefinition(schema = BlueprintSchema(properties = mapOf("tags" to tagsProperty))),
        )
        val childrenValue = JsonArray(listOf(JsonPrimitive("c1"), JsonPrimitive("c2")))
        val subj = subject(bBp, document = doc(relations = buildJsonObject { put("children", childrenValue) }))

        assertEquals(JsonArray(listOf(JsonPrimitive("same"))), mirrorValue("children.\$title", subj, definitions, index))
        assertEquals(
            JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("c"))),
            mirrorValue("children.tags", subj, definitions, index),
        )
    }

    @Test
    fun `mirror - a many hop that resolves nothing is an empty array, not absent`() {
        val cBp = "child"
        val bBp = "component"
        val definitions =
            mapOf(bBp to BlueprintDefinition(relations = mapOf("children" to relation(cBp, many = true))), cBp to BlueprintDefinition())
        val subj = subject(bBp, document = doc(relations = buildJsonObject { put("children", JsonArray(emptyList())) }))
        assertEquals(JsonArray(emptyList()), mirrorValue("children.\$title", subj, definitions, FakeIndex()))
    }

    @Test
    fun `mirror - an unknown relation segment is absent`() {
        val bBp = "component"
        assertNull(mirrorValue("nope.\$title", subject(bBp), mapOf(bBp to BlueprintDefinition()), FakeIndex()))
    }

    @Test
    fun `mirror - a relation whose target blueprint is unknown is absent`() {
        val bBp = "component"
        val definitions = mapOf(bBp to BlueprintDefinition(relations = mapOf("sys" to relation("ghost"))))
        val subj = subject(bBp, document = doc(relations = buildJsonObject { put("sys", "x") }))
        assertNull(mirrorValue("sys.\$title", subj, definitions, FakeIndex()))
    }

    @Test
    fun `mirror - an unresolved hop value is skipped, not a give-up, for a single chain`() {
        val sBp = "system"
        val bBp = "component"
        val definitions = mapOf(bBp to BlueprintDefinition(relations = mapOf("sys" to relation(sBp))), sBp to BlueprintDefinition())
        val subj = subject(bBp, document = doc(relations = buildJsonObject { put("sys", "unknown-target") }))
        assertNull(mirrorValue("sys.\$title", subj, definitions, FakeIndex()))
    }

    @Test
    fun `mirror - a chain exceeding the hop budget is absent`() {
        val path = (0..10).joinToString(".") { "h$it" } + ".\$title" // 11 hop segments > MAX_COMPUTED_HOPS
        assertNull(mirrorValue(path, subject("bp"), mapOf("bp" to BlueprintDefinition()), FakeIndex()))
    }

    @Test
    fun `mirror - a computed id of the landed blueprint is absent, never recursed into`() {
        val sBp = "system"
        val bBp = "component"
        val index = FakeIndex(rows = mapOf(sBp to "checkout" to row(sBp, "checkout")))
        val definitions = mapOf(
            bBp to BlueprintDefinition(relations = mapOf("sys" to relation(sBp))),
            sBp to BlueprintDefinition(
                calculationProperties = mapOf("special" to CalculationPropertyDefinition(title = "S", type = "string", calculation = ".")),
            ),
        )
        val subj = subject(bBp, document = doc(relations = buildJsonObject { put("sys", "checkout") }))
        assertNull(mirrorValue("sys.special", subj, definitions, index))
    }

    @Test
    fun `mirror - every meta-property terminal resolves off the landed row`() {
        val sBp = "system"
        val bBp = "component"
        val teamBp = "_team"
        val checkoutRow = row(
            sBp, "checkout", title = "Checkout", icon = "Sys", createdAt = 111, updatedAt = 222, team = JsonPrimitive("payments"),
        )
        val index = FakeIndex(
            rows = mapOf(
                sBp to "checkout" to checkoutRow,
                teamBp to "payments" to row(teamBp, "payments"),
            ),
        )
        val definitions = mapOf(
            bBp to BlueprintDefinition(relations = mapOf("sys" to relation(sBp))),
            sBp to BlueprintDefinition(ownership = OwnershipDefinition(type = "Direct")),
            teamBp to BlueprintDefinition(),
        )
        val subj = subject(bBp, document = doc(relations = buildJsonObject { put("sys", "checkout") }))
        assertEquals(JsonPrimitive("checkout"), mirrorValue("sys.\$identifier", subj, definitions, index))
        assertEquals(JsonPrimitive("Checkout"), mirrorValue("sys.\$title", subj, definitions, index))
        assertEquals(JsonPrimitive("Sys"), mirrorValue("sys.\$icon", subj, definitions, index))
        assertEquals(JsonPrimitive(sBp), mirrorValue("sys.\$blueprint", subj, definitions, index))
        assertEquals(JsonPrimitive("payments"), mirrorValue("sys.\$team", subj, definitions, index))
        assertEquals(JsonPrimitive(111L), mirrorValue("sys.\$createdAt", subj, definitions, index))
        assertEquals(JsonPrimitive(222L), mirrorValue("sys.\$updatedAt", subj, definitions, index))
        assertNull(mirrorValue("sys.\$createdBy", subj, definitions, index))
        assertNull(mirrorValue("sys.\$updatedBy", subj, definitions, index))
        assertNull(mirrorValue("sys.\$unknown", subj, definitions, index))
    }

    @Test
    fun `mirror - icon absent on the landed row stays absent, not a JSON null`() {
        val sBp = "system"
        val bBp = "component"
        val index = FakeIndex(rows = mapOf(sBp to "checkout" to row(sBp, "checkout", icon = null)))
        val definitions = mapOf(bBp to BlueprintDefinition(relations = mapOf("sys" to relation(sBp))), sBp to BlueprintDefinition())
        val subj = subject(bBp, document = doc(relations = buildJsonObject { put("sys", "checkout") }))
        assertNull(mirrorValue("sys.\$icon", subj, definitions, index))
    }

    @Test
    fun `mirror - a many hop stops landing once it reaches the fan-out cap`() {
        val cBp = "child"
        val bBp = "component"
        val count = MAX_MIRROR_FANOUT + 50
        val rows = (0 until count).associate { i -> (cBp to "c$i") to row(cBp, "c$i") }
        val definitions =
            mapOf(bBp to BlueprintDefinition(relations = mapOf("children" to relation(cBp, many = true))), cBp to BlueprintDefinition())
        val values = JsonArray((0 until count).map { JsonPrimitive("c$it") })
        val subj = subject(bBp, document = doc(relations = buildJsonObject { put("children", values) }))
        val result = mirrorValue("children.\$identifier", subj, definitions, FakeIndex(rows = rows))
        assertEquals(MAX_MIRROR_FANOUT, (result as JsonArray).size)
    }

    // ---------------------------------------------------------------------------------------
    // calculationValue
    // ---------------------------------------------------------------------------------------

    @Test
    fun `calculation - output is coerced strictly against the declared type`() = runBlocking {
        val jq = JqEvaluator()
        val subj = subject("bp", document = doc(properties = buildJsonObject { put("x", 5) }))
        fun calc(type: String, expr: String) = CalculationPropertyDefinition(title = "C", type = type, calculation = expr)

        assertEquals(JsonPrimitive("hi"), calculationValue("s", calc("string", "\"hi\""), subj, jq))
        assertNull(calculationValue("s2", calc("string", "1"), subj, jq))

        assertEquals(JsonPrimitive(7), calculationValue("n", calc("number", ".properties.x + 2"), subj, jq))
        assertNull(calculationValue("n2", calc("number", "\"x\""), subj, jq))

        assertEquals(JsonPrimitive(true), calculationValue("b", calc("boolean", "true"), subj, jq))
        assertNull(calculationValue("b2", calc("boolean", "1"), subj, jq))

        assertEquals(JsonArray(listOf(JsonPrimitive(1))), calculationValue("a", calc("array", "[1]"), subj, jq))
        assertNull(calculationValue("a2", calc("array", "1"), subj, jq))

        assertEquals(JsonObject(mapOf("k" to JsonPrimitive(1))), calculationValue("o", calc("object", "{k:1}"), subj, jq))
        assertNull(calculationValue("o2", calc("object", "1"), subj, jq))
    }

    @Test
    fun `calculation - a failing jq expression is absent`() = runBlocking {
        val jq = JqEvaluator()
        val subj = subject("bp")
        val def = CalculationPropertyDefinition(title = "C", type = "string", calculation = ".a.b")
        assertNull(calculationValue("c", def, subj, jq))
    }

    // ---------------------------------------------------------------------------------------
    // computedProperties: ordering + collision
    // ---------------------------------------------------------------------------------------

    @Test
    fun `computedProperties evaluates mirror then calculation then aggregation, in that order`() = runBlocking {
        val bBp = "component"
        val cBp = "child"
        val index = FakeIndex(rows = mapOf(cBp to "c1" to row(cBp, "c1", title = "Child Title")))
        val definition = BlueprintDefinition(
            relations = mapOf("kid" to relation(cBp)),
            mirrorProperties = mapOf("mirroredTitle" to MirrorPropertyDefinition(title = "M", path = "kid.\$title")),
            calculationProperties = mapOf(
                "calc" to CalculationPropertyDefinition(title = "C", type = "string", calculation = "\"calculated\""),
            ),
            aggregationProperties = mapOf(
                "agg" to AggregationPropertyDefinition(
                    title = "A",
                    target = cBp,
                    calculationSpec = AggregationCalculationSpec(calculationBy = "entities", func = "count"),
                ),
            ),
        )
        val document = doc(
            properties = buildJsonObject { put("mirroredTitle", "STALE") },
            relations = buildJsonObject { put("kid", "c1") },
        )
        val subj = subject(bBp, document = document)
        val definitionsByIdentifier = mapOf(bBp to definition, cBp to BlueprintDefinition())
        val computed = computedProperties(subj, definition, definitionsByIdentifier, index, JqEvaluator(), now = 0)

        assertEquals(listOf("mirroredTitle", "calc", "agg"), computed.keys.toList())
        assertEquals(JsonPrimitive("Child Title"), computed.getValue("mirroredTitle"))
        assertEquals(JsonPrimitive("calculated"), computed.getValue("calc"))
        assertEquals(JsonPrimitive(1L), computed.getValue("agg"))

        // The response-assembly rule EntityService.toResponse uses: computed wins over a stale
        // stored key sharing its id.
        val merged = JsonObject(document.properties + computed)
        assertEquals(JsonPrimitive("Child Title"), merged.getValue("mirroredTitle"))
    }

    @Test
    fun `computedProperties never throws and omits every absent value`() = runBlocking {
        val bBp = "component"
        val definition = BlueprintDefinition(
            mirrorProperties = mapOf("m" to MirrorPropertyDefinition(title = "M", path = "nope.\$title")),
        )
        val computed = computedProperties(subject(bBp), definition, mapOf(bBp to definition), FakeIndex(), JqEvaluator(), now = 0)
        assertTrue(computed.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // computedPathBlueprints
    // ---------------------------------------------------------------------------------------

    @Test
    fun `computedPathBlueprints widens for mirror hops, aggregation targets, pathFilter chains, and Inherited ownership`() {
        val bBp = "component"
        val sBp = "system"
        val dBp = "domain"
        val wBp = "workload"
        val tBp = "team"
        val bDefinition = BlueprintDefinition(
            relations = mapOf("sys" to relation(sBp)),
            mirrorProperties = mapOf("domainTitle" to MirrorPropertyDefinition(title = "D", path = "sys.domain.\$title")),
            aggregationProperties = mapOf(
                "count" to AggregationPropertyDefinition(
                    title = "C",
                    target = wBp,
                    calculationSpec = AggregationCalculationSpec(calculationBy = "entities", func = "count"),
                    pathFilter = listOf(
                        buildJsonObject {
                            put("fromBlueprint", wBp)
                            put("path", JsonArray(listOf(JsonPrimitive("sys"))))
                        },
                    ),
                ),
            ),
        )
        val definitions = mapOf(
            bBp to bDefinition,
            sBp to BlueprintDefinition(relations = mapOf("domain" to relation(dBp))),
            dBp to BlueprintDefinition(
                relations = mapOf("owner" to relation(tBp)),
                ownership = OwnershipDefinition(type = "Inherited", path = "owner"),
            ),
            wBp to BlueprintDefinition(relations = mapOf("sys" to relation(bBp))),
            tBp to BlueprintDefinition(),
        )

        val result = computedPathBlueprints(bDefinition, definitions)

        assertTrue(sBp in result, "mirror hop 1")
        assertTrue(dBp in result, "mirror hop 2 (landed blueprint)")
        assertTrue(wBp in result, "aggregation target")
        assertTrue(bBp in result, "aggregation pathFilter chain target")
        assertTrue(tBp in result, "the landed domain blueprint's own Inherited ownership target")
    }

    @Test
    fun `computedPathBlueprints is empty for a definition with no computed properties`() {
        assertTrue(computedPathBlueprints(BlueprintDefinition(), emptyMap()).isEmpty())
    }
}
