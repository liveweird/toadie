package ch.nokillswit

import ch.nokillswit.blueprints.AggregationCalculationSpec
import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.ComputedSubject
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.EntityIndex
import ch.nokillswit.entities.Inbound
import ch.nokillswit.entities.IndexedRow
import ch.nokillswit.entities.QueryCandidate
import ch.nokillswit.entities.RowLookup
import ch.nokillswit.entities.applyCalculationSpec
import ch.nokillswit.entities.asOwned
import ch.nokillswit.entities.relatedEntities
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 5 (`.claude/docs/port-data-model.md` "Aggregation properties"): pure coverage of
 * `entities/EntityAggregation.kt` — candidate discovery (direct relations in both directions,
 * `pathFilter` in both directions) and `calculationSpec` reduction. No database.
 */
class EntityAggregationTest {

    private class FakeIndex(
        private val rows: Map<Pair<String, String>, IndexedRow> = emptyMap(),
        private val inboundMap: Map<Pair<String, String>, List<Inbound>> = emptyMap(),
    ) : EntityIndex {
        override fun row(blueprint: String, identifier: String): IndexedRow? = rows[blueprint to identifier]
        override fun inbound(blueprint: String, identifier: String): List<Inbound> = inboundMap[blueprint to identifier].orEmpty()
        override val rowLookup: RowLookup = { bp, id -> row(bp, id)?.asOwned() }
    }

    private fun relation(target: String, many: Boolean = false) =
        RelationDefinition(title = "R", target = target, required = false, many = many)

    private fun countAggregation(target: String) =
        AggregationPropertyDefinition(title = "A", target = target, calculationSpec = AggregationCalculationSpec("entities", "count"))

    private fun doc(properties: JsonObject = JsonObject(emptyMap()), relations: JsonObject = JsonObject(emptyMap())) =
        EntityDocument(properties = properties, relations = relations)

    private fun row(blueprint: String, identifier: String, document: EntityDocument = doc(), createdAt: Long = 0, updatedAt: Long = 0) =
        IndexedRow(blueprint, identifier, identifier, null, createdAt, updatedAt, document, null)

    private fun subject(blueprint: String, identifier: String = "subj", document: EntityDocument = doc()) =
        ComputedSubject(blueprint, identifier, identifier, null, null, document, 0, 0)

    private fun candidate(id: String, properties: JsonObject = JsonObject(emptyMap()), createdAt: Long = 0, updatedAt: Long = 0) =
        QueryCandidate(id, id, "bp", null, emptyList(), createdAt, updatedAt, properties)

    // ---------------------------------------------------------------------------------------
    // relatedEntities: direct relations (no pathFilter)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `relatedEntities - inbound direction, target entities relate to the subject`() {
        val bBp = "component"
        val tBp = "issue"
        val inboundRow = row(tBp, "i1")
        val index = FakeIndex(inboundMap = mapOf((bBp to "subj") to listOf(Inbound(inboundRow, "owner"))))
        val def = countAggregation(tBp)
        val definitions = mapOf(bBp to BlueprintDefinition(), tBp to BlueprintDefinition())
        val result = relatedEntities(subject(bBp), def, definitions, index)
        assertEquals(listOf("i1"), result.map { it.identifier })
    }

    @Test
    fun `relatedEntities - outbound direction, the subject's own relation targets the entities`() {
        val bBp = "component"
        val tBp = "issue"
        val target = row(tBp, "i1")
        val index = FakeIndex(rows = mapOf(tBp to "i1" to target))
        val def = countAggregation(tBp)
        val definitions =
            mapOf(bBp to BlueprintDefinition(relations = mapOf("issues" to relation(tBp, many = true))), tBp to BlueprintDefinition())
        val subj = subject(bBp, document = doc(relations = buildJsonObject { put("issues", JsonArray(listOf(JsonPrimitive("i1")))) }))
        val result = relatedEntities(subj, def, definitions, index)
        assertEquals(listOf("i1"), result.map { it.identifier })
    }

    @Test
    fun `relatedEntities - a self-targeting aggregation counts both directions, deduped`() {
        val xBp = "x"
        val outboundPeer = row(xBp, "op")
        val inboundPeer = row(xBp, "ip")
        val index = FakeIndex(
            rows = mapOf(xBp to "op" to outboundPeer),
            inboundMap = mapOf((xBp to "subj") to listOf(Inbound(inboundPeer, "peer"))),
        )
        val def = countAggregation(xBp)
        val definitions = mapOf(xBp to BlueprintDefinition(relations = mapOf("peer" to relation(xBp))))
        val subj = subject(xBp, document = doc(relations = buildJsonObject { put("peer", "op") }))
        val result = relatedEntities(subj, def, definitions, index)
        assertEquals(setOf("op", "ip"), result.map { it.identifier }.toSet())
    }

    @Test
    fun `relatedEntities - an unknown target blueprint is empty`() {
        val bBp = "component"
        val def = countAggregation("ghost")
        assertTrue(relatedEntities(subject(bBp), def, mapOf(bBp to BlueprintDefinition()), FakeIndex()).isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // relatedEntities: pathFilter (the workload -> service -> system shape)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `relatedEntities - forward pathFilter walks from the subject to the target`() {
        val workloadBp = "workload"
        val serviceBp = "service"
        val systemBp = "system"
        val system1 = row(systemBp, "sys1")
        val service1 = row(serviceBp, "svc1", document = doc(relations = buildJsonObject { put("system", "sys1") }))
        val index = FakeIndex(rows = mapOf(serviceBp to "svc1" to service1, systemBp to "sys1" to system1))
        val definitions = mapOf(
            workloadBp to BlueprintDefinition(relations = mapOf("service" to relation(serviceBp))),
            serviceBp to BlueprintDefinition(relations = mapOf("system" to relation(systemBp))),
            systemBp to BlueprintDefinition(),
        )
        val def = AggregationPropertyDefinition(
            title = "A",
            target = systemBp,
            calculationSpec = AggregationCalculationSpec("entities", "count"),
            pathFilter = listOf(
                buildJsonObject {
                    put("fromBlueprint", workloadBp)
                    put("path", JsonArray(listOf(JsonPrimitive("service"), JsonPrimitive("system"))))
                },
            ),
        )
        val wl1 = subject(workloadBp, "wl1", doc(relations = buildJsonObject { put("service", "svc1") }))
        assertEquals(listOf("sys1"), relatedEntities(wl1, def, definitions, index).map { it.identifier })
    }

    @Test
    fun `relatedEntities - reverse pathFilter statically resolves target to subject, then hops backward through inbound`() {
        val workloadBp = "workload"
        val serviceBp = "service"
        val systemBp = "system"
        val svc1 = row(serviceBp, "svc1")
        val wl1 = row(workloadBp, "wl1")
        val wl2 = row(workloadBp, "wl2")
        val wl3 = row(workloadBp, "wl3") // points at a DIFFERENT service, must not appear
        val index = FakeIndex(
            inboundMap = mapOf(
                (systemBp to "sys1") to listOf(Inbound(svc1, "system")),
                (serviceBp to "svc1") to listOf(Inbound(wl1, "service"), Inbound(wl2, "service")),
            ),
        )
        val definitions = mapOf(
            workloadBp to BlueprintDefinition(relations = mapOf("service" to relation(serviceBp))),
            serviceBp to BlueprintDefinition(relations = mapOf("system" to relation(systemBp))),
            systemBp to BlueprintDefinition(),
        )
        val def = AggregationPropertyDefinition(
            title = "A",
            target = workloadBp,
            calculationSpec = AggregationCalculationSpec("entities", "sum", property = "replicas"),
            pathFilter = listOf(
                buildJsonObject {
                    put("fromBlueprint", workloadBp)
                    put("path", JsonArray(listOf(JsonPrimitive("service"), JsonPrimitive("system"))))
                },
            ),
        )
        val sys1 = subject(systemBp, "sys1")
        val result = relatedEntities(sys1, def, definitions, index)
        assertEquals(setOf("wl1", "wl2"), result.map { it.identifier }.toSet())
        assertTrue(wl3.identifier !in result.map { it.identifier })
    }

    @Test
    fun `relatedEntities - a foreign fromBlueprint contributes nothing`() {
        val bBp = "component"
        val tBp = "issue"
        val otherBp = "other"
        val definitions = mapOf(bBp to BlueprintDefinition(), tBp to BlueprintDefinition(), otherBp to BlueprintDefinition())
        val def = AggregationPropertyDefinition(
            title = "A",
            target = tBp,
            calculationSpec = AggregationCalculationSpec("entities", "count"),
            pathFilter = listOf(buildJsonObject { put("fromBlueprint", otherBp); put("path", JsonArray(listOf(JsonPrimitive("x")))) }),
        )
        assertTrue(relatedEntities(subject(bBp), def, definitions, FakeIndex()).isEmpty())
    }

    @Test
    fun `relatedEntities - a malformed pathFilter entry contributes nothing`() {
        val bBp = "component"
        val tBp = "issue"
        val definitions = mapOf(bBp to BlueprintDefinition(), tBp to BlueprintDefinition())
        val missingPath = AggregationPropertyDefinition(
            title = "A",
            target = tBp,
            calculationSpec = AggregationCalculationSpec("entities", "count"),
            pathFilter = listOf(buildJsonObject { put("fromBlueprint", bBp) }),
        )
        assertTrue(relatedEntities(subject(bBp), missingPath, definitions, FakeIndex()).isEmpty())

        val nonStringFrom = missingPath.copy(
            pathFilter = listOf(buildJsonObject { put("fromBlueprint", 1); put("path", JsonArray(emptyList())) }),
        )
        assertTrue(relatedEntities(subject(bBp), nonStringFrom, definitions, FakeIndex()).isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // applyCalculationSpec
    // ---------------------------------------------------------------------------------------

    @Test
    fun `entities count is 0 for no candidates - 0 is a value, not absent`() {
        val spec = AggregationCalculationSpec(calculationBy = "entities", func = "count")
        assertEquals(JsonPrimitive(0L), applyCalculationSpec(spec, emptyList(), now = 0))
    }

    @Test
    fun `entities average - total is a plain count`() {
        val spec = AggregationCalculationSpec(calculationBy = "entities", func = "average", averageOf = "total")
        assertEquals(JsonPrimitive(3L), applyCalculationSpec(spec, listOf(candidate("a"), candidate("b"), candidate("c")), now = 0))
    }

    @Test
    fun `entities average - divides matched count by the elapsed period count since the earliest measured row`() {
        val dayMillis = 86_400_000L
        val spec =
            AggregationCalculationSpec(calculationBy = "entities", func = "average", averageOf = "day", measureTimeBy = "\$createdAt")
        val rows = listOf(candidate("a", createdAt = 0), candidate("b", createdAt = dayMillis), candidate("c", createdAt = 2 * dayMillis))
        // now = 10 days after the earliest (0): periods = ceil(10) = 10; 3 / 10 = 0.3 (a Double).
        assertEquals(JsonPrimitive(0.3), applyCalculationSpec(spec, rows, now = 10 * dayMillis))
        // now = 10 rows over exactly 10 periods since 0 -> an exact integer average emits as a Long.
        val tenRows = (0 until 10).map { candidate("r$it", createdAt = 0) }
        assertEquals(JsonPrimitive(1L), applyCalculationSpec(spec, tenRows, now = 10 * dayMillis))
    }

    @Test
    fun `entities average - unparseable timestamps are skipped, and nothing measurable is absent`() {
        val spec = AggregationCalculationSpec(calculationBy = "entities", func = "average", averageOf = "day", measureTimeBy = "when")
        val unparseable = candidate("a", properties = buildJsonObject { put("when", "not-a-date") })
        val parseable = candidate("b", properties = buildJsonObject { put("when", "1970-01-01T00:00:00Z") })
        val result = applyCalculationSpec(spec, listOf(unparseable, parseable), now = 86_400_000L)
        assertEquals(JsonPrimitive(2L), result) // 2 matched, 1 period elapsed since the one parseable row
        assertNull(applyCalculationSpec(spec, listOf(unparseable), now = 86_400_000L))
    }

    @Test
    fun `property func reduces numeric values, skipping non-numeric, absent when none`() {
        fun spec(func: String) = AggregationCalculationSpec(calculationBy = "property", func = func, property = "n")
        val rows = listOf(
            candidate("a", properties = buildJsonObject { put("n", 1) }),
            candidate("b", properties = buildJsonObject { put("n", 2) }),
            candidate("c", properties = buildJsonObject { put("n", 3) }),
            candidate("d", properties = buildJsonObject { put("n", 4) }),
            candidate("e", properties = buildJsonObject { put("n", "not a number") }),
        )
        assertEquals(JsonPrimitive(10L), applyCalculationSpec(spec("sum"), rows, now = 0))
        assertEquals(JsonPrimitive(1L), applyCalculationSpec(spec("min"), rows, now = 0))
        assertEquals(JsonPrimitive(4L), applyCalculationSpec(spec("max"), rows, now = 0))
        assertEquals(JsonPrimitive(2.5), applyCalculationSpec(spec("average"), rows, now = 0))
        assertEquals(JsonPrimitive(2.5), applyCalculationSpec(spec("median"), rows.take(4), now = 0))
        assertEquals(JsonPrimitive(2L), applyCalculationSpec(spec("median"), rows.take(3), now = 0))
        val onlyNonNumeric = listOf(candidate("only-non-numeric", properties = buildJsonObject { put("n", "x") }))
        assertNull(applyCalculationSpec(spec("sum"), onlyNonNumeric, now = 0))
    }

    @Test
    fun `an unknown calculationBy or func is absent`() {
        assertNull(applyCalculationSpec(AggregationCalculationSpec(calculationBy = "bogus", func = "count"), emptyList(), now = 0))
        assertNull(applyCalculationSpec(AggregationCalculationSpec(calculationBy = "entities", func = "bogus"), emptyList(), now = 0))
        val bogusPropertySpec = AggregationCalculationSpec(calculationBy = "property", func = "bogus", property = "n")
        assertNull(applyCalculationSpec(bogusPropertySpec, emptyList(), now = 0))
        assertNull(applyCalculationSpec(AggregationCalculationSpec(calculationBy = "property", func = "sum"), emptyList(), now = 0))
    }
}
