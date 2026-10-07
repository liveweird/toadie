package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.BlueprintTiers
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.EntityRowView
import ch.nokillswit.entities.GraphBlueprint
import ch.nokillswit.entities.IndexedRow
import ch.nokillswit.entityquery.InMemoryQueryGraph
import ch.nokillswit.entityquery.MAX_QUERY_BINDINGS
import ch.nokillswit.entityquery.QueryBudget
import ch.nokillswit.entityquery.QueryBudgetExceeded
import ch.nokillswit.entityquery.QueryDiagnosticCodes
import ch.nokillswit.entityquery.QueryException
import ch.nokillswit.entityquery.QuerySchema
import ch.nokillswit.entityquery.QueryGraph
import ch.nokillswit.entityquery.QueryResult
import ch.nokillswit.entityquery.parseEntityQuery
import ch.nokillswit.entityquery.runEntityQuery
import ch.nokillswit.entityquery.validateAndBind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The 2.18.0 tier metas through the evaluator (`entityquery/QueryEvaluator.kt`, `QueryTiers.kt`):
 * `n.$tier`, `n.$fillTier` and `r.$tier`, over one hand-built graph. Query texts spell the dollar
 * as `#` (see [q]) so they stay readable. No database.
 *
 * Fixture: `service` (tier 1; properties note:1, active:2, labels:3; relations cluster:1, peer:2,
 * alias:3; `deployment` hierarchy -> `cluster`), `cluster` (tier 2; `deployment` -> `environment`),
 * `workload` (untiered; `deployment` -> `cluster`), `environment` (untiered, no fields), `_team`.
 */
class QueryTierEvaluatorTest {

    private fun q(text: String) = text.replace('#', '$')

    private fun relation(target: String, many: Boolean = false) =
        RelationDefinition(title = "R", target = target, required = false, many = many)

    private fun row(
        blueprint: String,
        identifier: String,
        team: JsonElement? = null,
        properties: JsonObject = JsonObject(emptyMap()),
        relations: JsonObject = JsonObject(emptyMap()),
    ) = IndexedRow(
        blueprint, identifier, identifier, null, 1L, 2L, EntityDocument(properties = properties, relations = relations), team,
    )

    private val teamBp = GraphBlueprint(SYSTEM_TEAM_BLUEPRINT, "Team", BlueprintDefinition(), emptyMap(), BlueprintTiers())
    private val environmentBp = GraphBlueprint("environment", "Environment", BlueprintDefinition(), emptyMap(), BlueprintTiers())
    private val clusterBp = GraphBlueprint(
        "cluster",
        "Cluster",
        BlueprintDefinition(relations = mapOf("environment" to relation("environment"))),
        mapOf("deployment" to "environment"),
        BlueprintTiers(blueprint = 2),
    )
    private val workloadBp = GraphBlueprint(
        "workload",
        "Workload",
        BlueprintDefinition(relations = mapOf("cluster" to relation("cluster"))),
        mapOf("deployment" to "cluster"),
        BlueprintTiers(),
    )
    private val serviceBp = GraphBlueprint(
        "service",
        "Service",
        BlueprintDefinition(
            schema = BlueprintSchema(
                properties = mapOf(
                    "note" to PropertyDefinition(type = "string"),
                    "active" to PropertyDefinition(type = "boolean"),
                    "labels" to PropertyDefinition(type = "array"),
                ),
            ),
            relations = mapOf("cluster" to relation("cluster"), "peer" to relation("service"), "alias" to relation("service")),
        ),
        mapOf("deployment" to "cluster"),
        BlueprintTiers(
            blueprint = 1,
            properties = mapOf("note" to 1, "active" to 2, "labels" to 3),
            relations = mapOf("cluster" to 1, "peer" to 2, "alias" to 3),
        ),
    )

    private val blueprints = listOf(teamBp, environmentBp, clusterBp, workloadBp, serviceBp).associateBy { it.identifier }

    private fun labels(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    private val platform = row(SYSTEM_TEAM_BLUEPRINT, "platform")
    private val prod = row("environment", "prod")
    private val c1 = row("cluster", "c1", relations = buildJsonObject { put("environment", "prod") })
    private val workload = row("workload", "w1", team = JsonPrimitive("platform"), relations = buildJsonObject { put("cluster", "c1") })

    // svcA: labels empty (tier 3) -> fillTier 2; peer AND alias both name svcB.
    private val svcA = row(
        "service",
        "svcA",
        team = JsonPrimitive("platform"),
        properties = buildJsonObject { put("note", "x"); put("active", false); put("labels", JsonArray(emptyList())) },
        relations = buildJsonObject { put("cluster", "c1"); put("peer", "svcB"); put("alias", "svcB") },
    )

    // svcB: blank note (tier 1) -> fillTier 0.
    private val svcB = row("service", "svcB", properties = buildJsonObject { put("note", "  ") })

    // svcC: blank peer (tier 2) and no alias -> fillTier 1.
    private val svcC = row(
        "service",
        "svcC",
        properties = buildJsonObject { put("note", "x"); put("active", true); put("labels", labels("a")) },
        relations = buildJsonObject { put("cluster", "c1"); put("peer", "") },
    )

    // svcD: everything filled -> fillTier 4.
    private val svcD = row(
        "service",
        "svcD",
        properties = buildJsonObject { put("note", "x"); put("active", false); put("labels", labels("a")) },
        relations = buildJsonObject { put("cluster", "c1"); put("peer", "svcA"); put("alias", "svcA") },
    )

    // svcE: alias only (no peer).
    private val svcE = row("service", "svcE", relations = buildJsonObject { put("alias", "svcA") })

    private val allRows = listOf(platform, prod, c1, workload, svcA, svcB, svcC, svcD, svcE)

    private fun graph(rows: List<EntityRowView> = allRows, bps: Map<String, GraphBlueprint> = blueprints): QueryGraph =
        InMemoryQueryGraph(rows, bps, setOf("deployment"))

    private fun run(text: String, g: QueryGraph = graph()): QueryResult =
        runBlocking { runEntityQuery(q(text), g, QueryBudget(60_000, MAX_QUERY_BINDINGS)) }

    private fun runWith(text: String, maxBindings: Int): QueryResult =
        runBlocking { runEntityQuery(q(text), graph(), QueryBudget(60_000, maxBindings)) }

    private fun QueryResult.ids(): Set<String> = rows.map { it.row.identifier }.toSet()

    // --- n.$tier ---------------------------------------------------------------------------------

    @Test
    fun `n tier is the blueprint tier and null when unset`() {
        assertEquals(setOf("svcA", "svcB", "svcC", "svcD", "svcE"), run("MATCH (n) WHERE n.#tier = 1 RETURN n").ids())
        assertEquals(setOf("c1"), run("MATCH (n) WHERE n.#tier >= 2 RETURN n").ids())
        assertEquals(setOf("platform", "prod", "w1"), run("MATCH (n) WHERE n.#tier IS NULL RETURN n").ids())
    }

    // --- n.$fillTier -----------------------------------------------------------------------------

    @Test
    fun `n fillTier is the highest fully filled tier and null for an untiered blueprint`() {
        assertEquals(setOf("svcB", "svcE"), run("MATCH (s:service) WHERE s.#fillTier = 0 RETURN s").ids())
        assertEquals(setOf("svcC"), run("MATCH (s:service) WHERE s.#fillTier = 1 RETURN s").ids())
        assertEquals(setOf("svcA"), run("MATCH (s:service) WHERE s.#fillTier = 2 RETURN s").ids())
        assertEquals(setOf("svcD"), run("MATCH (s:service) WHERE s.#fillTier = 4 RETURN s").ids())
        // svcB (blank note) and svcE (no properties at all) both miss tier 1
        assertEquals(setOf("svcB", "svcE"), run("MATCH (s:service) WHERE s.#fillTier < 1 RETURN s").ids())
        assertEquals(setOf("prod", "platform", "w1", "c1"), run("MATCH (n) WHERE n.#fillTier IS NULL RETURN n").ids())
    }

    @Test
    fun `the tier metas work in inline property maps`() {
        assertEquals(setOf("svcD"), run("MATCH (s:service {#fillTier: 4}) RETURN s").ids())
        assertEquals(setOf("c1"), run("MATCH (n {#tier: 2}) RETURN n").ids())
    }

    // --- laziness --------------------------------------------------------------------------------

    private class ThrowingPropertiesRow(override val blueprint: String, override val identifier: String) : EntityRowView {
        override val title = identifier
        override val icon: String? = null
        override val createdAt = 1L
        override val updatedAt = 2L
        override val relations: JsonObject = buildJsonObject { put("rel", "someone") }
        override val team: JsonElement? = null
        override val properties: JsonObject get() = error("properties must not be decoded")
    }

    private fun lazyBlueprint(identifier: String, tiers: BlueprintTiers) = GraphBlueprint(
        identifier,
        identifier,
        BlueprintDefinition(relations = mapOf("rel" to relation(identifier))),
        emptyMap(),
        tiers,
    )

    @Test
    fun `tier metas never decode properties unless a property is tiered`() {
        val relationTiered = lazyBlueprint("relonly", BlueprintTiers(blueprint = 1, relations = mapOf("rel" to 1)))
        val untiered = lazyBlueprint("plain", BlueprintTiers())
        val propertyTiered = lazyBlueprint("proptiered", BlueprintTiers(properties = mapOf("p" to 1)))
        val bps = listOf(relationTiered, untiered, propertyTiered).associateBy { it.identifier }
        val rows = listOf(
            ThrowingPropertiesRow("relonly", "r1"),
            ThrowingPropertiesRow("plain", "p1"),
            ThrowingPropertiesRow("proptiered", "t1"),
        )
        val g = graph(rows, bps)

        assertEquals(setOf("r1"), run("MATCH (n:relonly) WHERE n.#tier = 1 RETURN n", g).ids())
        assertEquals(setOf("r1"), run("MATCH (n:relonly) WHERE n.#fillTier = 4 RETURN n", g).ids())
        assertEquals(emptySet(), run("MATCH (n:plain) WHERE n.#fillTier >= 0 RETURN n", g).ids())
        // The provider IS reached for a tiered property: the throwing document proves it.
        assertFailsWith<IllegalStateException> { run("MATCH (n:proptiered) WHERE n.#fillTier = 0 RETURN n", g) }
    }

    // --- r.$tier ---------------------------------------------------------------------------------

    @Test
    fun `r tier is the concrete relation key's tier on the source blueprint`() {
        assertEquals(
            setOf("svcA", "svcB", "svcD", "svcE"),
            run("MATCH (a:service)-[r:peer|alias]->(b:service) WHERE r.#tier >= 2 RETURN a, b").ids(),
        )
        assertEquals(
            setOf("svcD", "svcA"),
            run("MATCH (a:service)-[r:peer]->(b:service) WHERE r.#tier = 2 AND a.#fillTier = 4 RETURN a, b").ids(),
        )
        assertEquals(setOf("svcA", "svcC", "svcD"), run("MATCH (a:service)-[r:cluster]->(c:cluster) WHERE r.#tier = 1 RETURN a").ids())
    }

    @Test
    fun `two relation keys from one source to the same target bind as separate edges`() {
        // svcA -> svcB through BOTH peer (tier 2) and alias (tier 3): each of these only holds for
        // one of the two bindings, so each only returns svcB if the edges stayed separate.
        val notPeer = run("$edgeAB WHERE NOT (r.#tier = 2) RETURN b")
        val notAlias = run("$edgeAB WHERE NOT (r.#tier = 3) RETURN b")
        assertEquals(setOf("svcB"), notPeer.ids())
        assertEquals(setOf("svcB"), notAlias.ids())
        assertEquals(emptySet(), run("$edgeAB WHERE r.#tier = 1 RETURN b").ids())
    }

    @Test
    fun `a hierarchy virtual edge resolves the concrete relation per source blueprint`() {
        // service.cluster is tier 1; workload.cluster has no tier; cluster.environment has none either.
        assertEquals(setOf("svcA", "svcC", "svcD"), run("MATCH (x)-[r:deployment]->(c:cluster) WHERE r.#tier = 1 RETURN x").ids())
        assertEquals(setOf("w1"), run("MATCH (x:workload)-[r:deployment]->(c:cluster) WHERE r.#tier IS NULL RETURN x").ids())
        assertEquals(setOf("c1"), run("MATCH (x:cluster)-[r:deployment]->(e:environment) WHERE r.#tier IS NULL RETURN x").ids())
    }

    @Test
    fun `the dollar-team ownership edge has a null tier`() {
        assertEquals(setOf("svcA", "w1"), run("MATCH (e)-[r:#team]->(t:_team) WHERE r.#tier IS NULL RETURN e").ids())
        assertEquals(emptySet(), run("MATCH (e)-[r:#team]->(t:_team) WHERE r.#tier = 1 RETURN e").ids())
    }

    @Test
    fun `r tier follows every direction and anchor side`() {
        // OUT anchored on the left, OUT anchored on the right, IN both ways, undirected.
        assertEquals(setOf("svcB"), run("MATCH (a:service {#identifier: 'svcA'})-[r:peer]->(b) WHERE r.#tier = 2 RETURN b").ids())
        assertEquals(setOf("svcA"), run("MATCH (a)-[r:peer]->(b:service {#identifier: 'svcB'}) WHERE r.#tier = 2 RETURN a").ids())
        assertEquals(setOf("svcA"), run("MATCH (b:service {#identifier: 'svcB'})<-[r:peer]-(a) WHERE r.#tier = 2 RETURN a").ids())
        assertEquals(setOf("svcB"), run("MATCH (b)<-[r:peer]-(a:service {#identifier: 'svcA'}) WHERE r.#tier = 2 RETURN b").ids())
        assertEquals(setOf("svcA"), run("MATCH (b:service {#identifier: 'svcB'})-[r:peer]-(a) WHERE r.#tier = 2 RETURN a").ids())
    }

    @Test
    fun `an unmatched OPTIONAL edge leaves the edge variable unbound and its tier UNKNOWN`() {
        assertEquals(
            setOf("svcB"),
            run("MATCH (a:service {#identifier: 'svcB'}) OPTIONAL MATCH (a)-[r:peer]->(b) WHERE r.#tier = 2 RETURN a, b").ids(),
        )
        // svcE has no peer: r stays unbound, the second OPTIONAL extends by alias, and its WHERE
        // reads the null edge slot -> UNKNOWN drops the extension; IS NULL keeps it.
        val prefix = "MATCH (a:service {#identifier: 'svcE'}) OPTIONAL MATCH (a)-[r:peer]->(b) OPTIONAL MATCH (a)-[:alias]->(c)"
        val unknown = run("$prefix WHERE r.#tier = 2 RETURN a, c")
        assertEquals(setOf("svcE"), unknown.ids())
        val kept = run("$prefix WHERE r.#tier IS NULL RETURN a, c")
        assertEquals(setOf("svcE", "svcA"), kept.ids())
    }

    private val edgeAB = "MATCH (a:service {#identifier: 'svcA'})-[r]->(b:service {#identifier: 'svcB'})"

    private fun slotsOf(text: String) = validateAndBind(parseEntityQuery(q(text)), QuerySchema(blueprints, setOf("deployment"))).second

    @Test
    fun `an edge variable that is not tier-referenced holds no slot and binds one row per target`() {
        // peer and alias both reach svcB: untracked, the hop collapses them to ONE binding, which
        // a one-binding cap accepts; tracked (below) needs two.
        assertEquals(emptyMap(), slotsOf("$edgeAB RETURN b").edges)
        assertEquals(setOf("svcB"), runWith("$edgeAB RETURN b", maxBindings = 1).ids())
        assertEquals(setOf("svcB"), run("MATCH (a:service {#identifier: 'svcA'})-->(b:service {#identifier: 'svcB'}) RETURN b").ids())
    }

    @Test
    fun `a tier-referenced edge variable binds one row per relation key and hits the binding cap like any other`() {
        val tracked = "$edgeAB WHERE r.#tier >= 1 RETURN b"
        assertEquals(mapOf("r" to 0), slotsOf(tracked).edges)
        assertEquals(setOf("svcB"), runWith(tracked, maxBindings = 2).ids())
        val ex = assertFailsWith<QueryBudgetExceeded> { runWith(tracked, maxBindings = 1) }
        assertEquals(QueryDiagnosticCodes.BINDING_LIMIT, ex.code)
    }

    @Test
    fun `two tier-referenced edge variables get their own slots`() {
        val base = "MATCH (a:service {#identifier: 'svcD'})-[r1:peer]->(b:service)-[r2:cluster]->(c:cluster)"
        assertEquals(mapOf("r1" to 0, "r2" to 1), slotsOf("$base WHERE r1.#tier = 2 AND r2.#tier = 1 RETURN c").edges)
        assertEquals(setOf("c1"), run("$base WHERE r1.#tier = 2 AND r2.#tier = 1 RETURN c").ids())
        // peer is tier 2 and cluster is tier 1: swapping the numbers proves the slots do not alias.
        assertEquals(emptySet(), run("$base WHERE r1.#tier = 1 OR r2.#tier = 2 RETURN c").ids())
    }

    @Test
    fun `an edge variable bound in one MATCH is readable from a later clause's WHERE`() {
        val base = "MATCH (a:service {#identifier: 'svcA'})-[r:peer]->(b) MATCH (b)<-[:alias]-(x)"
        assertEquals(setOf("svcA"), run("$base WHERE r.#tier = 2 RETURN x").ids())
        assertEquals(emptySet(), run("$base WHERE r.#tier = 3 RETURN x").ids())
    }

    @Test
    fun `an undirected edge keeps two relation keys between the same pair as separate edges`() {
        val undirected = "MATCH (a:service {#identifier: 'svcA'})-[r]-(b:service {#identifier: 'svcB'})"
        assertEquals(setOf("svcB"), run("$undirected WHERE NOT (r.#tier = 2) RETURN b").ids())
        assertEquals(setOf("svcB"), run("$undirected WHERE NOT (r.#tier = 3) RETURN b").ids())
        assertEquals(setOf("svcB"), runWith("$undirected RETURN b", maxBindings = 1).ids())
        val ex = assertFailsWith<QueryBudgetExceeded> { runWith("$undirected WHERE r.#tier >= 1 RETURN b", maxBindings = 1) }
        assertEquals(QueryDiagnosticCodes.BINDING_LIMIT, ex.code)
    }

    @Test
    fun `an inline property map on an edge is a syntax error`() {
        val ex = assertFailsWith<QueryException> { run("MATCH (a)-[r:peer {#tier: 1}]->(b) RETURN a") }
        assertEquals(QueryDiagnosticCodes.SYNTAX, ex.diagnostics.first().code)
    }
}
