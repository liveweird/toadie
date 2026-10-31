package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintTiers
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.EntityRowView
import ch.nokillswit.entities.GraphBlueprint
import ch.nokillswit.entities.MAX_ENTITIES_TOTAL
import ch.nokillswit.entityquery.InMemoryQueryGraph
import ch.nokillswit.entityquery.QueryBudget
import ch.nokillswit.entityquery.QueryBudgetExceeded
import ch.nokillswit.entityquery.QueryDiagnosticCodes
import ch.nokillswit.entityquery.QueryException
import ch.nokillswit.entityquery.QueryResult
import ch.nokillswit.entityquery.runEntityQuery
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Scale proof of the `EXISTS { … }` predicate (2.36.0, `.claude/docs/entity-query-language.md`
 * "Existential predicate" and "Performance") over a workspace at the `MAX_ENTITIES_TOTAL` ceiling,
 * shaped like the commerce/payments sample: 2 000 services each consuming 4 of 400 APIs (8 000
 * pairs), a declared `api_adoption` for 90% of the pairs (7 200) plus 50 stray adoptions whose
 * consumer does not list the API, and a small dataset twin — 9 795 rows in all. The four
 * queries are the shapes of the sample saved queries 10–13 (`NOT EXISTS` in both directions, over
 * APIs and datasets).
 *
 * Every row refuses to decode its `properties` ([LazyRow]): the queries read only identifiers and
 * relations, so a regression that began materialising documents per candidate fails loudly.
 *
 * Two budgets, two purposes:
 *  - **Deterministic "inside 2 s"**: a [QueryBudget] with the production 2 000 ms deadline whose
 *    clock is a TICKING counter — 1 000 ns per read, i.e. a pessimistic 1 µs per checkpoint
 *    (a real checkpoint is a `nanoTime` read, ~25 ns, plus a cancellation check). The deadline
 *    therefore trips after 2 000 000 checkpoints regardless of the machine; each shape must finish
 *    with its exact result under 200 000 checkpoints, a 10x margin. No wall-clock measurement.
 *  - **Real clock**: the same shapes under a GENEROUS 20 s budget inside `withTimeout(30_000)` —
 *    the elapsed time is only LOGGED (`.claude/docs/testing.md`: never a tight timing assertion).
 */
class QueryExistsScaleTest {
    private companion object {
        const val SERVICES = 2000
        const val APIS = 400
        const val DATASETS = 50
        const val CONSUMED_PER_SERVICE = 4
        const val DATASET_CONSUMERS = 100
        const val STRAY_API_ADOPTIONS = 50
        const val STRAY_DATASET_ADOPTIONS = 5
        const val NANOS_PER_TICK = 1_000L
        const val CHECKPOINT_BUDGET = 200_000L
    }

    private fun relation(target: String, many: Boolean = false) =
        RelationDefinition(title = "R", target = target, required = false, many = many)

    private fun blueprint(identifier: String, vararg relations: Pair<String, RelationDefinition>) =
        identifier to GraphBlueprint(
            identifier,
            identifier,
            BlueprintDefinition(relations = mapOf(*relations)),
            emptyMap(),
            BlueprintTiers(),
        )

    private val blueprints: Map<String, GraphBlueprint> = mapOf(
        blueprint("service", "consumes_apis" to relation("api", many = true), "consumes_datasets" to relation("dataset", many = true)),
        blueprint("api"),
        blueprint("dataset"),
        blueprint("api_adoption", "consumer" to relation("service"), "api" to relation("api")),
        blueprint("dataset_adoption", "consumer" to relation("service"), "dataset" to relation("dataset")),
    )

    private class LazyRow(
        override val blueprint: String,
        override val identifier: String,
        override val relations: JsonObject,
        private val strict: Boolean = true,
    ) : EntityRowView {
        override val title = identifier
        override val icon: String? = null
        override val createdAt = 1L
        override val updatedAt = 2L
        override val team: JsonElement? = null
        override val properties: JsonObject get() = if (strict) error("properties must not be decoded") else JsonObject(emptyMap())
    }

    // --- the generator: every expectation below is derived from these definitions, not from the engine ---

    private fun serviceId(s: Int) = "svc-$s"

    private fun apiId(a: Int) = "api-$a"

    private fun datasetId(d: Int) = "ds-$d"

    private fun consumedApis(s: Int): List<Int> = (0 until CONSUMED_PER_SERVICE).map { k -> (s * 7 + k * 101) % APIS }

    /** The adoption declared for the k-th consumed API of service [s] — absent for every tenth pair. */
    private fun adopted(s: Int, k: Int) = (s * CONSUMED_PER_SERVICE + k) % 10 != 9

    private fun consumedDataset(s: Int): Int? = if (s < DATASET_CONSUMERS) s % DATASETS else null

    private fun datasetAdopted(s: Int) = s % 10 != 0

    private fun strayApi(i: Int): Pair<Int, Int> {
        val s = (i * 37) % SERVICES
        val consumed = consumedApis(s).toSet()
        return s to generateSequence((i * 11) % APIS) { (it + 1) % APIS }.first { it !in consumed }
    }

    private fun adoption(consumer: String, targetRelation: String, target: String): JsonObject =
        buildJsonObject { put("consumer", consumer); put(targetRelation, target) }

    private fun ids(vararg ids: String): JsonElement = JsonArray(ids.map { JsonPrimitive(it) })

    private val rows: List<EntityRowView> = buildList {
        for (a in 0 until APIS) add(LazyRow("api", apiId(a), JsonObject(emptyMap())))
        for (d in 0 until DATASETS) add(LazyRow("dataset", datasetId(d), JsonObject(emptyMap())))
        for (s in 0 until SERVICES) {
            val relations = buildJsonObject {
                put("consumes_apis", ids(*consumedApis(s).map(::apiId).toTypedArray()))
                consumedDataset(s)?.let { put("consumes_datasets", ids(datasetId(it))) }
            }
            add(LazyRow("service", serviceId(s), relations))
            consumedApis(s).forEachIndexed { k, a ->
                if (adopted(s, k)) {
                    add(LazyRow("api_adoption", "ad-$s-$a", adoption(serviceId(s), "api", apiId(a))))
                }
            }
            consumedDataset(s)?.let { d ->
                if (datasetAdopted(s)) {
                    add(LazyRow("dataset_adoption", "dad-$s", adoption(serviceId(s), "dataset", datasetId(d))))
                }
            }
        }
        for (i in 0 until STRAY_API_ADOPTIONS) {
            val (s, a) = strayApi(i)
            add(LazyRow("api_adoption", "stray-ad-$i", adoption(serviceId(s), "api", apiId(a))))
        }
        for (i in 0 until STRAY_DATASET_ADOPTIONS) {
            // Services 100.. consume no dataset at all, so each of these declares a dataset nobody lists.
            add(LazyRow("dataset_adoption", "stray-dad-$i", adoption(serviceId(DATASET_CONSUMERS + i), "dataset", datasetId(i))))
        }
    }

    private val graph = InMemoryQueryGraph(rows, blueprints, hierarchies = emptySet())

    // The adversarial query READS `x.$identifier`, and the meta view of a row carries its properties — so it
    // runs over the same rows with decoding allowed.
    private val readableGraph = InMemoryQueryGraph(
        rows.map { LazyRow(it.blueprint, it.identifier, it.relations, strict = false) },
        blueprints,
        hierarchies = emptySet(),
    )

    private val queryConsumptionWithoutAdoption =
        "MATCH (s:service)-[:consumes_apis]->(a:api)\nWHERE NOT EXISTS { (s)<-[:consumer]-(:api_adoption)-[:api]->(a) }\nRETURN s, a"
    private val queryAdoptionWithoutConsumption =
        "MATCH (ad:api_adoption)-[:consumer]->(s:service), (ad)-[:api]->(a:api)\n" +
            "WHERE NOT EXISTS { (s)-[:consumes_apis]->(a) }\nRETURN ad, s, a"
    private val queryDatasetConsumptionWithoutAdoption =
        "MATCH (s:service)-[:consumes_datasets]->(d:dataset)\n" +
            "WHERE NOT EXISTS { (s)<-[:consumer]-(:dataset_adoption)-[:dataset]->(d) }\nRETURN s, d"
    private val queryDatasetAdoptionWithoutConsumption =
        "MATCH (ad:dataset_adoption)-[:consumer]->(s:service), (ad)-[:dataset]->(d:dataset)\n" +
            "WHERE NOT EXISTS { (s)-[:consumes_datasets]->(d) }\nRETURN ad, s, d"

    private fun QueryResult.countOf(blueprint: String) = rows.count { it.blueprint.identifier == blueprint }

    /** A clock that advances [NANOS_PER_TICK] per read; `checkpoints` is the reads since the budget was built. */
    private class TickingClock {
        var reads = 0L
        val nanoTime: () -> Long = { reads++ * NANOS_PER_TICK }
        val checkpoints: Long get() = reads - 1
    }

    private data class Expected(val counts: Map<String, Int>)

    private fun expectedQuery10(): Expected {
        val unadopted = (0 until SERVICES).flatMap { s -> consumedApis(s).filterIndexed { k, _ -> !adopted(s, k) }.map { s to it } }
        return Expected(mapOf("service" to unadopted.map { it.first }.toSet().size, "api" to unadopted.map { it.second }.toSet().size))
    }

    private fun expectedQuery11(): Expected {
        val strays = (0 until STRAY_API_ADOPTIONS).map { strayApi(it) }
        return Expected(
            mapOf(
                "api_adoption" to STRAY_API_ADOPTIONS,
                "service" to strays.map { it.first }.toSet().size,
                "api" to strays.map { it.second }.toSet().size,
            ),
        )
    }

    private fun expectedQuery12(): Expected {
        val unadopted = (0 until DATASET_CONSUMERS).filter { !datasetAdopted(it) }
        return Expected(mapOf("service" to unadopted.size, "dataset" to unadopted.mapNotNull(::consumedDataset).toSet().size))
    }

    private fun expectedQuery13() = Expected(
        mapOf(
            "dataset_adoption" to STRAY_DATASET_ADOPTIONS,
            "service" to STRAY_DATASET_ADOPTIONS,
            "dataset" to STRAY_DATASET_ADOPTIONS,
        ),
    )

    private val shapes: List<Triple<String, String, Expected>> by lazy {
        listOf(
            Triple("10 consumption without adoption", queryConsumptionWithoutAdoption, expectedQuery10()),
            Triple("11 adoption without consumption", queryAdoptionWithoutConsumption, expectedQuery11()),
            Triple("12 dataset consumption without adoption", queryDatasetConsumptionWithoutAdoption, expectedQuery12()),
            Triple("13 dataset adoption without consumption", queryDatasetAdoptionWithoutConsumption, expectedQuery13()),
        )
    }

    private fun assertCounts(label: String, expected: Expected, result: QueryResult) {
        for ((blueprint, count) in expected.counts) {
            assertEquals(count, result.countOf(blueprint), "$label: $blueprint rows")
        }
        assertEquals(expected.counts.values.sum(), result.rows.size, "$label: total rows")
    }

    @Test
    fun `the fixture sits at the workspace ceiling and the expectations are non-trivial`() {
        assertEquals(9795, rows.size)
        assertTrue(rows.size <= MAX_ENTITIES_TOTAL)
        assertEquals(800, (0 until SERVICES).sumOf { s -> (0 until CONSUMED_PER_SERVICE).count { k -> !adopted(s, k) } })
        shapes.forEach { (label, _, expected) -> assertTrue(expected.counts.values.all { it > 0 }, label) }
    }

    @Test
    fun `the four sample shapes finish inside the two-second budget on the pessimistic ticking clock`() = runBlocking {
        for ((label, text, expected) in shapes) {
            val clock = TickingClock()
            val result = runEntityQuery(text, graph, QueryBudget(deadlineMillis = 2_000, nanoTime = clock.nanoTime))
            assertCounts(label, expected, result)
            println("QueryExistsScaleTest ticking-clock checkpoints, query $label: ${clock.checkpoints}")
            assertTrue(clock.checkpoints < CHECKPOINT_BUDGET, "$label used ${clock.checkpoints} checkpoints (budget $CHECKPOINT_BUDGET)")
        }
    }

    @Test
    fun `the four sample shapes under a real clock - elapsed time is logged, never asserted`() = runBlocking {
        withTimeout(30_000) {
            for ((label, text, expected) in shapes) {
                var result: QueryResult? = null
                val elapsed = measureTimeMillis { result = runEntityQuery(text, graph, QueryBudget(deadlineMillis = 20_000)) }
                assertCounts(label, expected, result!!)
                println("QueryExistsScaleTest real-clock elapsed, query $label: $elapsed ms")
            }
        }
    }

    @Test
    fun `an adversarial unanchored-depth block is refused by the deadline, deterministically`() = runBlocking {
        val clock = TickingClock()
        val text = "MATCH (s:service) WHERE NOT EXISTS { (s)-[*1..10]-(x) WHERE x.\$identifier = 'never' } RETURN s"
        val ex = assertFailsWith<QueryBudgetExceeded> {
            runEntityQuery(text, readableGraph, QueryBudget(deadlineMillis = 2_000, nanoTime = clock.nanoTime))
        }
        assertEquals(QueryDiagnosticCodes.DEADLINE_EXCEEDED, ex.code)
        println("QueryExistsScaleTest adversarial checkpoints at refusal: ${clock.checkpoints}")
    }

    @Test
    fun `an uncorrelated block is refused before any evaluation`() = runBlocking {
        val clock = TickingClock()
        val ex = assertFailsWith<QueryException> {
            runEntityQuery(
                "MATCH (s:service) WHERE NOT EXISTS { (x:api_adoption) } RETURN s",
                graph,
                QueryBudget(deadlineMillis = 2_000, nanoTime = clock.nanoTime),
            )
        }
        assertEquals(listOf(QueryDiagnosticCodes.DISCONNECTED_PATTERN), ex.diagnostics.map { it.code })
        // Only the budget's own construction read the clock: nothing was evaluated.
        assertEquals(0L, clock.checkpoints)
    }
}
