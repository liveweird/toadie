package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.GraphBlueprint
import ch.nokillswit.entities.IndexedRow
import ch.nokillswit.entityquery.InMemoryQueryGraph
import ch.nokillswit.entityquery.QueryBudget
import ch.nokillswit.entityquery.runEntityQuery
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Synthetic-scale coverage of `entityquery/QueryEvaluator.kt`/`QueryGraph.kt`: 10 blueprints
 * (`b0`..`b9`, a `next` chain `b0 -> b1 -> ... -> b9`) times 1000 rows each = 10,000 rows, plus a
 * `_team` ownership row every entity carries. `b0-0000` is a HUB every row's `hub` relation
 * points at (never the other way — the hub itself carries no `hub` relation), so the full
 * workspace is reachable from it within 2 undirected hops. Exact counts, not timing — the whole
 * test runs under a GENEROUS outer [withTimeout] per `.claude/docs/testing.md`'s rule against
 * tight elapsed-time assertions.
 */
class QueryEvaluatorScaleTest {
    private companion object {
        const val BLUEPRINT_COUNT = 10
        const val ROWS_PER_BLUEPRINT = 1000
        const val HUB_IDENTIFIER = "b0-0000"
    }

    private fun relation(target: String) = RelationDefinition(title = "R", target = target, required = false, many = false)

    private fun identifierOf(blueprintIndex: Int, n: Int) = "b$blueprintIndex-${n.toString().padStart(4, '0')}"

    private val teamBp = GraphBlueprint(SYSTEM_TEAM_BLUEPRINT, "Team", BlueprintDefinition(), emptyMap())

    private val blueprints: Map<String, GraphBlueprint> = buildMap {
        put(SYSTEM_TEAM_BLUEPRINT, teamBp)
        for (i in 0 until BLUEPRINT_COUNT) {
            val relations = buildMap {
                if (i < BLUEPRINT_COUNT - 1) put("next", relation("b${i + 1}"))
                put("hub", relation("b0"))
            }
            val identifier = "b$i"
            put(identifier, GraphBlueprint(identifier, identifier, BlueprintDefinition(relations = relations), emptyMap()))
        }
    }

    private fun rowRelations(blueprintIndex: Int, n: Int): JsonObject = buildJsonObject {
        if (blueprintIndex < BLUEPRINT_COUNT - 1) put("next", identifierOf(blueprintIndex + 1, n))
        // The hub row itself carries no outgoing `hub` relation — everyone else points AT it.
        if (identifierOf(blueprintIndex, n) != HUB_IDENTIFIER) put("hub", HUB_IDENTIFIER)
    }

    private val allRows: List<IndexedRow> = buildList {
        val emptyDocument = EntityDocument(JsonObject(emptyMap()), JsonObject(emptyMap()))
        add(IndexedRow(SYSTEM_TEAM_BLUEPRINT, "team-0", "Team 0", null, 1L, 2L, emptyDocument, null))
        for (i in 0 until BLUEPRINT_COUNT) {
            for (n in 0 until ROWS_PER_BLUEPRINT) {
                val identifier = identifierOf(i, n)
                val document = EntityDocument(JsonObject(emptyMap()), rowRelations(i, n))
                add(IndexedRow("b$i", identifier, identifier, null, 1L, 2L, document, JsonPrimitive("team-0")))
            }
        }
    }

    private val graph = InMemoryQueryGraph(allRows, blueprints, hierarchies = emptySet())

    private suspend fun run(text: String) = runEntityQuery(text, graph, QueryBudget(deadlineMillis = 20_000))

    @Test
    fun `scale - label scan, anchored multi-hop, undirected reachability, and a two-pattern chain join`() = kotlinx.coroutines.runBlocking {
        withTimeout(30_000) {
            // Total rows = 10 blueprints * 1000 + 1 team row.
            assertEquals(BLUEPRINT_COUNT * ROWS_PER_BLUEPRINT + 1, allRows.size)

            // 1. Plain label scan: every row of one blueprint.
            val labelScan = run("MATCH (n:b5) RETURN n")
            assertEquals(ROWS_PER_BLUEPRINT, labelScan.rows.size)

            // 2. Anchored variable-length hop from a single identified row.
            val anchored = run("MATCH (n:b0 {\$identifier: '$HUB_IDENTIFIER'})-[:next*1..3]->(x) RETURN x")
            assertEquals(3, anchored.rows.size)

            // 3. Undirected reachability from the hub across up to 10 hops: the whole workspace
            //    is reachable — everyone else at odd levels (their own `hub` edge), the hub back
            //    at even levels (bouncing off any of those rows, since `next` never reduces the
            //    undirected `hub`-typed frontier here).
            val fromHub = run("MATCH (h:b0 {\$identifier: '$HUB_IDENTIFIER'})-[:hub*1..10]-(x) RETURN x")
            assertEquals(BLUEPRINT_COUNT * ROWS_PER_BLUEPRINT, fromHub.rows.size)

            // 4. A two-pattern (comma) join walking a 1:1 chain across three blueprints. `b` is
            //    deliberately UNLABELLED on its second occurrence: every b0..b8 blueprint declares
            //    its own `next` relation (to a different target each), so the validator must
            //    resolve the re-reference through the labels `b` was bound with, not by picking
            //    an arbitrary blueprint that happens to have a `next` key (a fixed false positive).
            val chained = run("MATCH (a:b0)-[:next]->(b:b1), (b)-[:next]->(c:b2) RETURN a, b, c")
            assertEquals(ROWS_PER_BLUEPRINT * 3, chained.rows.size)
        }
    }
}
