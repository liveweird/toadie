package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintTiers
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.GraphBlueprint
import ch.nokillswit.entities.IndexedRow
import ch.nokillswit.entityquery.InMemoryQueryGraph
import ch.nokillswit.entityquery.MAX_QUERY_BINDINGS
import ch.nokillswit.entityquery.QueryBudget
import ch.nokillswit.entityquery.QueryBudgetExceeded
import ch.nokillswit.entityquery.QueryDiagnosticCodes
import ch.nokillswit.entityquery.QueryException
import ch.nokillswit.entityquery.QueryGraph
import ch.nokillswit.entityquery.QueryResult
import ch.nokillswit.entityquery.runEntityQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pure coverage of `entityquery/QueryEvaluator.kt` (`InMemoryQueryExecutor`, `runEntityQuery`)
 * and `entityquery/QueryGraph.kt` (`InMemoryQueryGraph`) over one hand-built fixture graph — the
 * `EntityComputedTest` builder idiom. No database.
 *
 * Fixture: `service` / `_team` / `cluster` / `environment` / `workload`. `cluster`'s own
 * `environment` relation serves BOTH the `composition` and `deployment` hierarchies; `workload`'s
 * `cluster` relation serves `deployment` too — two DIFFERENT relation keys across blueprints
 * sharing one hierarchy id, exactly the case `.claude/docs/entity-query-language.md` calls out.
 * `workload` is Inherited-owned via `ownership.path = "cluster"` onto `cluster`'s Direct team.
 */
class QueryEvaluatorTest {

    // ---------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------

    private fun relation(target: String, many: Boolean = false) =
        RelationDefinition(title = "R", target = target, required = false, many = many)

    private fun doc(properties: JsonObject = JsonObject(emptyMap()), relations: JsonObject = JsonObject(emptyMap())) =
        EntityDocument(properties = properties, relations = relations)

    private fun row(
        blueprint: String,
        identifier: String,
        title: String = identifier,
        team: JsonElement? = null,
        document: EntityDocument = doc(),
    ) = IndexedRow(
        blueprint = blueprint,
        identifier = identifier,
        title = title,
        icon = null,
        createdAt = 1L,
        updatedAt = 2L,
        document = document,
        team = team,
    )

    private val teamBp = GraphBlueprint(SYSTEM_TEAM_BLUEPRINT, "Team", BlueprintDefinition(), emptyMap(), BlueprintTiers())
    private val environmentBp = GraphBlueprint("environment", "Environment", BlueprintDefinition(), emptyMap(), BlueprintTiers())
    private val clusterDefinition = BlueprintDefinition(relations = mapOf("environment" to relation("environment")))
    private val clusterBp = GraphBlueprint(
        "cluster",
        "Cluster",
        clusterDefinition,
        mapOf("composition" to "environment", "deployment" to "environment"),
        BlueprintTiers(),
    )
    private val workloadDefinition = BlueprintDefinition(
        relations = mapOf("cluster" to relation("cluster")),
        ownership = OwnershipDefinition(type = "Inherited", path = "cluster"),
    )
    private val workloadBp = GraphBlueprint("workload", "Workload", workloadDefinition, mapOf("deployment" to "cluster"), BlueprintTiers())
    private val serviceDefinition = BlueprintDefinition(
        schema = BlueprintSchema(
            properties = mapOf(
                "tier" to PropertyDefinition(type = "string"),
                "priority" to PropertyDefinition(type = "number"),
                "active" to PropertyDefinition(type = "boolean"),
            ),
        ),
        relations = mapOf(
            "cluster" to relation("cluster"),
            "dependsOn" to relation("service"),
            "shortcut" to relation("service"),
        ),
    )
    private val serviceBp = GraphBlueprint("service", "Service", serviceDefinition, emptyMap(), BlueprintTiers())

    private val blueprints = listOf(teamBp, environmentBp, clusterBp, workloadBp, serviceBp).associateBy { it.identifier }

    private val platform = row(SYSTEM_TEAM_BLUEPRINT, "platform")
    private val data = row(SYSTEM_TEAM_BLUEPRINT, "data")
    private val unassigned = row(SYSTEM_TEAM_BLUEPRINT, "unassigned")
    private val prod = row("environment", "prod")
    private val clusterProd = row(
        "cluster",
        "cluster-prod",
        team = JsonPrimitive("platform"),
        document = doc(relations = buildJsonObject { put("environment", "prod") }),
    )
    private val clusterStaging = row(
        "cluster",
        "cluster-staging",
        team = JsonPrimitive("data"),
        document = doc(relations = buildJsonObject { put("environment", "prod") }),
    )
    private val checkoutWorkload = row(
        "workload",
        "checkout-workload",
        document = doc(relations = buildJsonObject { put("cluster", "cluster-prod") }),
    )
    private val checkout = row(
        "service",
        "checkout",
        title = "Checkout Service",
        team = JsonPrimitive("platform"),
        document = doc(
            properties = buildJsonObject {
                put("tier", "1")
                put("priority", 1)
                put("active", true)
            },
            relations = buildJsonObject { put("cluster", "cluster-prod") },
        ),
    )
    private val billing = row(
        "service",
        "billing",
        title = "Billing Service",
        team = JsonPrimitive("data"),
        document = doc(
            properties = buildJsonObject {
                put("tier", "2")
                put("priority", 2)
                put("active", false)
            },
            relations = buildJsonObject { put("cluster", "cluster-staging") },
        ),
    )
    private val cart = row(
        "service",
        "cart",
        document = doc(relations = buildJsonObject { put("cluster", "cluster-prod"); put("dependsOn", "checkout") }),
    )
    private val gateway = row(
        "service",
        "gateway",
        document = doc(
            relations = buildJsonObject {
                put("cluster", "cluster-prod")
                put("dependsOn", "cart")
                put("shortcut", "checkout")
            },
        ),
    )

    private val allRows = listOf(
        platform, data, unassigned, prod, clusterProd, clusterStaging,
        checkoutWorkload, checkout, billing, cart, gateway,
    )

    private fun graph(hierarchies: Set<String> = setOf("composition", "deployment")): QueryGraph =
        InMemoryQueryGraph(allRows, blueprints, hierarchies)

    private suspend fun run(
        text: String,
        g: QueryGraph = graph(),
        deadlineMillis: Long = 60_000,
        maxBindings: Int = MAX_QUERY_BINDINGS,
    ): QueryResult = runEntityQuery(text, g, QueryBudget(deadlineMillis, maxBindings))

    private fun QueryResult.keys(): Set<Pair<String, String>> = rows.map { it.key }.toSet()

    private fun k(blueprint: String, identifier: String) = blueprint to identifier

    // ---------------------------------------------------------------------------------------
    // Direction / bare arrow / type alternation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `single hop OUT follows a declared relation`() = runBlocking {
        val result = run("MATCH (s:service)-[:cluster]->(c:cluster) RETURN s, c")
        assertEquals(
            setOf(
                k("service", "checkout"), k("service", "billing"), k("service", "cart"), k("service", "gateway"),
                k("cluster", "cluster-prod"), k("cluster", "cluster-staging"),
            ),
            result.keys(),
        )
    }

    @Test
    fun `single hop IN is the mirror of OUT`() = runBlocking {
        val result = run("MATCH (c:cluster)<-[:cluster]-(s:service) RETURN s")
        assertEquals(
            setOf(k("service", "checkout"), k("service", "billing"), k("service", "cart"), k("service", "gateway")),
            result.keys(),
        )
    }

    @Test
    fun `undirected edge finds the same pairs as OUT`() = runBlocking {
        val result = run("MATCH (a:service)-[:cluster]-(b:cluster) RETURN a, b")
        assertEquals(
            setOf(
                k("service", "checkout"), k("service", "billing"), k("service", "cart"), k("service", "gateway"),
                k("cluster", "cluster-prod"), k("cluster", "cluster-staging"),
            ),
            result.keys(),
        )
    }

    @Test
    fun `bare arrow expands every relation plus dollar-team`() = runBlocking {
        val result = run("MATCH (s:service {\$identifier: 'checkout'})-->(x) RETURN x")
        assertEquals(setOf(k("cluster", "cluster-prod"), k(SYSTEM_TEAM_BLUEPRINT, "platform")), result.keys())
    }

    @Test
    fun `type alternation follows either relation key`() = runBlocking {
        val result = run("MATCH (s:service {\$identifier: 'cart'})-[:cluster|dependsOn]->(x) RETURN x")
        assertEquals(setOf(k("cluster", "cluster-prod"), k("service", "checkout")), result.keys())
    }

    // ---------------------------------------------------------------------------------------
    // Variable-length hops
    // ---------------------------------------------------------------------------------------

    @Test
    fun `star alone bounds 1 to 10 and crosses a hierarchy virtual edge with a different relation key per blueprint`() = runBlocking {
        val result = run("MATCH (w:workload)-[:deployment*]->(x) RETURN x")
        assertEquals(setOf(k("cluster", "cluster-prod"), k("environment", "prod")), result.keys())
    }

    @Test
    fun `star n is exact`() = runBlocking {
        val result = run("MATCH (w:workload)-[:deployment*2]->(x) RETURN x")
        assertEquals(setOf(k("environment", "prod")), result.keys())
    }

    @Test
    fun `star range narrows the levels`() = runBlocking {
        val result = run("MATCH (w:workload)-[:deployment*1..2]->(x) RETURN x")
        assertEquals(setOf(k("cluster", "cluster-prod"), k("environment", "prod")), result.keys())
    }

    @Test
    fun `star 0 dot dot n includes the start node itself`() = runBlocking {
        val result = run("MATCH (g:service {\$identifier: 'gateway'})-[:dependsOn*0..1]->(x) RETURN x")
        assertEquals(setOf(k("service", "gateway"), k("service", "cart")), result.keys())
    }

    @Test
    fun `star min dot dot min is exact even when a shorter path to the same node also exists`() = runBlocking {
        // gateway --shortcut--> checkout (1 hop) AND gateway --dependsOn--> cart --dependsOn--> checkout (2 hops):
        // *2..2 must still report checkout, since it appears at level 2, regardless of the shorter path.
        val result = run("MATCH (g:service {\$identifier: 'gateway'})-[:dependsOn|shortcut*2..2]->(x) RETURN x")
        assertEquals(setOf(k("service", "checkout")), result.keys())
    }

    // ---------------------------------------------------------------------------------------
    // Hierarchy ids, single hop
    // ---------------------------------------------------------------------------------------

    @Test
    fun `hierarchy id resolves to workload's own relation key`() = runBlocking {
        val result = run("MATCH (w:workload)-[:deployment]->(c:cluster) RETURN c")
        assertEquals(setOf(k("cluster", "cluster-prod")), result.keys())
    }

    @Test
    fun `hierarchy id resolves to a DIFFERENT relation key on cluster`() = runBlocking {
        val result = run("MATCH (c:cluster {\$identifier: 'cluster-prod'})-[:deployment]->(e:environment) RETURN e")
        assertEquals(setOf(k("environment", "prod")), result.keys())
    }

    @Test
    fun `a relation named like a hierarchy id is unioned with the hierarchy's aliased relation, not shadowed`() = runBlocking {
        // "node" has an ORDINARY relation literally called "composition" AND names "composition"
        // as the hierarchy id whose relation is the DIFFERENT "parent" relation — both target
        // "target". n1 populates BOTH relations, pointing at two DIFFERENT target rows, so
        // `-[:composition]->` must reach both: the literal relation's own edge must never be
        // shadowed by the hierarchy alias (`.claude/docs/entity-query-language.md`).
        val nodeDefinition = BlueprintDefinition(
            relations = mapOf("composition" to relation("target"), "parent" to relation("target")),
        )
        val nodeBp = GraphBlueprint("node", "Node", nodeDefinition, mapOf("composition" to "parent"), BlueprintTiers())
        val targetBp = GraphBlueprint("target", "Target", BlueprintDefinition(), emptyMap(), BlueprintTiers())
        val t1 = row("target", "shadow-t1")
        val t2 = row("target", "shadow-t2")
        val n1 = row(
            "node",
            "n1",
            document = doc(relations = buildJsonObject { put("composition", "shadow-t1"); put("parent", "shadow-t2") }),
        )
        val shadowGraph = InMemoryQueryGraph(
            listOf(t1, t2, n1),
            mapOf("node" to nodeBp, "target" to targetBp),
            setOf("composition"),
        )

        val outgoing = run("MATCH (n:node {\$identifier: 'n1'})-[:composition]->(x) RETURN x", shadowGraph)
        assertEquals(setOf(k("target", "shadow-t1"), k("target", "shadow-t2")), outgoing.keys())

        val incomingViaLiteral = run("MATCH (t:target {\$identifier: 'shadow-t1'})<-[:composition]-(n) RETURN n", shadowGraph)
        assertEquals(setOf(k("node", "n1")), incomingViaLiteral.keys())
        val incomingViaAlias = run("MATCH (t:target {\$identifier: 'shadow-t2'})<-[:composition]-(n) RETURN n", shadowGraph)
        assertEquals(setOf(k("node", "n1")), incomingViaAlias.keys())

        val undirected = run("MATCH (n:node {\$identifier: 'n1'})-[:composition]-(x) RETURN x", shadowGraph)
        assertEquals(setOf(k("target", "shadow-t1"), k("target", "shadow-t2")), undirected.keys())
    }

    // ---------------------------------------------------------------------------------------
    // Ownership ($team), Direct and Inherited
    // ---------------------------------------------------------------------------------------

    @Test
    fun `dollar-team follows a Direct stored team`() = runBlocking {
        val result = run("MATCH (s:service {\$identifier: 'checkout'})-[:\$team]->(t:_team) RETURN t")
        assertEquals(setOf(k(SYSTEM_TEAM_BLUEPRINT, "platform")), result.keys())
    }

    @Test
    fun `dollar-team follows an Inherited effective team`() = runBlocking {
        val result = run("MATCH (w:workload)-[:\$team]->(t:_team) RETURN t")
        assertEquals(setOf(k(SYSTEM_TEAM_BLUEPRINT, "platform")), result.keys())
    }

    // ---------------------------------------------------------------------------------------
    // Inline properties / $identifier anchor
    // ---------------------------------------------------------------------------------------

    @Test
    fun `dollar-identifier property is a direct anchor lookup`() = runBlocking {
        val result = run("MATCH (s:service {\$identifier: 'checkout'}) RETURN s")
        assertEquals(setOf(k("service", "checkout")), result.keys())
    }

    @Test
    fun `an ordinary inline property filters candidates`() = runBlocking {
        val result = run("MATCH (s:service {tier: '1'}) RETURN s")
        assertEquals(setOf(k("service", "checkout")), result.keys())
    }

    // ---------------------------------------------------------------------------------------
    // WHERE — every operator family, three-valued logic
    // ---------------------------------------------------------------------------------------

    @Test
    fun `WHERE equal`() = runBlocking {
        assertEquals(setOf(k("service", "checkout")), run("MATCH (s:service) WHERE s.tier = '1' RETURN s").keys())
    }

    @Test
    fun `WHERE ordering drops UNKNOWN rows rather than treating absence as false`() = runBlocking {
        assertEquals(
            setOf(k("service", "checkout"), k("service", "billing")),
            run("MATCH (s:service) WHERE s.priority > 0 RETURN s").keys(),
        )
        assertEquals(setOf(k("service", "billing")), run("MATCH (s:service) WHERE s.priority > 1 RETURN s").keys())
    }

    @Test
    fun `WHERE is null and is not null`() = runBlocking {
        assertEquals(
            setOf(k("service", "cart"), k("service", "gateway")),
            run("MATCH (s:service) WHERE s.priority IS NULL RETURN s").keys(),
        )
        assertEquals(
            setOf(k("service", "checkout"), k("service", "billing")),
            run("MATCH (s:service) WHERE s.priority IS NOT NULL RETURN s").keys(),
        )
    }

    @Test
    fun `WHERE in`() = runBlocking {
        assertEquals(setOf(k("service", "checkout")), run("MATCH (s:service) WHERE s.tier IN ['1', '3'] RETURN s").keys())
    }

    @Test
    fun `WHERE contains starts with ends with`() = runBlocking {
        val bothServices = setOf(k("service", "checkout"), k("service", "billing"))
        assertEquals(bothServices, run("MATCH (s:service) WHERE s.\$title CONTAINS 'Service' RETURN s").keys())
        assertEquals(setOf(k("service", "checkout")), run("MATCH (s:service) WHERE s.\$title STARTS WITH 'Checkout' RETURN s").keys())
        assertEquals(bothServices, run("MATCH (s:service) WHERE s.\$title ENDS WITH 'Service' RETURN s").keys())
    }

    @Test
    fun `WHERE a bare operand is truthy only for the JSON literal true`() = runBlocking {
        assertEquals(setOf(k("service", "checkout")), run("MATCH (s:service) WHERE s.active RETURN s").keys())
    }

    @Test
    fun `WHERE and or not combine with Kleene logic`() = runBlocking {
        assertEquals(
            setOf(k("service", "checkout"), k("service", "billing")),
            run("MATCH (s:service) WHERE s.active = true OR s.tier = '2' RETURN s").keys(),
        )
        // NOT of an UNKNOWN (absent `active`, cart/gateway) stays UNKNOWN — excluded, not included.
        assertEquals(setOf(k("service", "billing")), run("MATCH (s:service) WHERE NOT s.active RETURN s").keys())
    }

    // ---------------------------------------------------------------------------------------
    // Joins: multi-MATCH, comma patterns
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a second MATCH clause joins on a variable already bound`() = runBlocking {
        val result = run("MATCH (s:service {\$identifier: 'checkout'}) MATCH (s)-[:cluster]->(c:cluster) RETURN s, c")
        assertEquals(setOf(k("service", "checkout"), k("cluster", "cluster-prod")), result.keys())
    }

    @Test
    fun `comma patterns within one clause join on a shared variable`() = runBlocking {
        val result = run(
            "MATCH (s:service {\$identifier: 'checkout'})-[:cluster]->(c:cluster), (c)-[:environment]->(e:environment) RETURN s, c, e",
        )
        assertEquals(setOf(k("service", "checkout"), k("cluster", "cluster-prod"), k("environment", "prod")), result.keys())
    }

    // ---------------------------------------------------------------------------------------
    // OPTIONAL MATCH
    // ---------------------------------------------------------------------------------------

    @Test
    fun `OPTIONAL MATCH keeps the anchor when no extension is found`() = runBlocking {
        // Every team is returned, including one no service owns — a plain (inner-join) MATCH would drop it.
        val result = run("MATCH (t:_team) OPTIONAL MATCH (t)<-[:\$team]-(s:service) RETURN t")
        val expected = setOf(k(SYSTEM_TEAM_BLUEPRINT, "platform"), k(SYSTEM_TEAM_BLUEPRINT, "data"), k(SYSTEM_TEAM_BLUEPRINT, "unassigned"))
        assertEquals(expected, result.keys())
    }

    @Test
    fun `OPTIONAL MATCH's own WHERE filters only its extensions, never the anchor`() = runBlocking {
        val result = run(
            "MATCH (t:_team) OPTIONAL MATCH (t)<-[:\$team]-(s:service) WHERE s.\$identifier = 'checkout' RETURN t, s",
        )
        // All three teams stay bound (the anchor is never dropped), but only checkout passed the WHERE.
        assertTrue(k(SYSTEM_TEAM_BLUEPRINT, "platform") in result.keys())
        assertTrue(k(SYSTEM_TEAM_BLUEPRINT, "data") in result.keys())
        assertTrue(k(SYSTEM_TEAM_BLUEPRINT, "unassigned") in result.keys())
        assertTrue(k("service", "checkout") in result.keys())
        assertTrue(k("service", "billing") !in result.keys())
    }

    // ---------------------------------------------------------------------------------------
    // RETURN */subset/dedupe, LIMIT
    // ---------------------------------------------------------------------------------------

    @Test
    fun `RETURN star returns every node slot and dedupes when two variables bind the same row`() = runBlocking {
        val result = run(
            "MATCH (a:service)-[:cluster]->(c:cluster)<-[:cluster]-(b:service) WHERE a.\$identifier = b.\$identifier RETURN *",
        )
        assertEquals(
            setOf(
                k("service", "checkout"), k("service", "cart"), k("service", "gateway"), k("service", "billing"),
                k("cluster", "cluster-prod"), k("cluster", "cluster-staging"),
            ),
            result.keys(),
        )
    }

    @Test
    fun `LIMIT cuts the returned row set`() = runBlocking {
        val result = run("MATCH (s:service) RETURN s LIMIT 2")
        assertEquals(2, result.rows.size)
        val services = setOf(k("service", "checkout"), k("service", "billing"), k("service", "cart"), k("service", "gateway"))
        assertTrue(result.keys().all { it in services })
    }

    // ---------------------------------------------------------------------------------------
    // Budget: binding limit, deadline, cancellation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `binding limit refuses a clause whose live binding count exceeds maxBindings`() = runBlocking {
        val ex = assertFailsWith<QueryBudgetExceeded> {
            run("MATCH (s:service) RETURN s", maxBindings = 2)
        }
        assertEquals(QueryDiagnosticCodes.BINDING_LIMIT, ex.code)
    }

    @Test
    fun `deadline exceeded once an injected clock jumps past it`() = runBlocking {
        var counter = 0L
        val budget = QueryBudget(deadlineMillis = 1, nanoTime = { val v = counter; counter += 10_000_000_000L; v })
        val ex = assertFailsWith<QueryBudgetExceeded> {
            repeat(3000) { budget.checkpoint() }
        }
        assertEquals(QueryDiagnosticCodes.DEADLINE_EXCEEDED, ex.code)
    }

    @Test
    fun `caller cancellation propagates from checkpoint without being swallowed`() = runBlocking {
        val budget = QueryBudget(deadlineMillis = 60_000)
        assertFailsWith<CancellationException> {
            withTimeout(50) {
                withContext(Dispatchers.Default) {
                    while (true) {
                        budget.checkpoint()
                    }
                }
            }
        }
        Unit
    }

    // ---------------------------------------------------------------------------------------
    // Validation failure surfaced by runEntityQuery
    // ---------------------------------------------------------------------------------------

    @Test
    fun `hierarchy id resolves in the IN direction against the far end's own relation key`() = runBlocking {
        // Both clusters name `environment` as their deployment parent; the workload names `cluster`.
        val clusters = run("MATCH (e:environment)<-[:deployment]-(c) RETURN c")
        assertEquals(setOf(k("cluster", "cluster-prod"), k("cluster", "cluster-staging")), clusters.keys())
        val workloads = run("MATCH (c:cluster {\$identifier: 'cluster-prod'})<-[:deployment]-(w) RETURN w")
        assertEquals(setOf(k("workload", "checkout-workload")), workloads.keys())
    }

    @Test
    fun `undirected dollar-team from a team reaches its Direct and Inherited owners`() = runBlocking {
        val owned = run("MATCH (t:_team {\$identifier: 'platform'})-[:\$team]-(x) RETURN x")
        assertEquals(
            setOf(k("cluster", "cluster-prod"), k("service", "checkout"), k("workload", "checkout-workload")),
            owned.keys(),
        )
    }

    @Test
    fun `LIMIT applies after dedupe when two variables bind the same row`() = runBlocking {
        // cart -> checkout and gateway -> cart: `a` and `b` together bind three DISTINCT rows.
        val all = run("MATCH (a:service)-[:dependsOn]->(b:service) RETURN a, b")
        assertEquals(setOf(k("service", "cart"), k("service", "checkout"), k("service", "gateway")), all.keys())
        val limited = run("MATCH (a:service)-[:dependsOn]->(b:service) RETURN a, b LIMIT 2")
        assertEquals(2, limited.rows.size)
        assertTrue(limited.keys().all { it in all.keys() })
    }

    @Test
    fun `chained OPTIONAL MATCH clauses extend through a null slot without dropping the anchor`() = runBlocking {
        val lonely = run(
            "MATCH (t:_team {\$identifier: 'unassigned'}) OPTIONAL MATCH (t)<-[:\$team]-(s:service) " +
                "OPTIONAL MATCH (s)-[:cluster]->(c) RETURN t, s, c",
        )
        assertEquals(setOf(k(SYSTEM_TEAM_BLUEPRINT, "unassigned")), lonely.keys())
        val owning = run(
            "MATCH (t:_team {\$identifier: 'platform'}) OPTIONAL MATCH (t)<-[:\$team]-(s:service) " +
                "OPTIONAL MATCH (s)-[:cluster]->(c) RETURN t, s, c",
        )
        assertEquals(
            setOf(k(SYSTEM_TEAM_BLUEPRINT, "platform"), k("service", "checkout"), k("cluster", "cluster-prod")),
            owning.keys(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // EXISTS { pattern } — the correlated existential predicate (2.36.0)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `EXISTS keeps a binding with a match and NOT EXISTS is the exact complement`() = runBlocking {
        assertEquals(
            setOf(k("service", "cart"), k("service", "gateway")),
            run("MATCH (s:service) WHERE EXISTS { (s)-[:dependsOn]->(d) } RETURN s").keys(),
        )
        assertEquals(
            setOf(k("service", "checkout"), k("service", "billing")),
            run("MATCH (s:service) WHERE NOT EXISTS { (s)-[:dependsOn]->(d) } RETURN s").keys(),
        )
    }

    @Test
    fun `EXISTS after AND and inside parentheses`() = runBlocking {
        assertEquals(
            setOf(k("service", "gateway")),
            run("MATCH (s:service) WHERE s.\$identifier = 'gateway' AND EXISTS { (s)-[:dependsOn]->(d) } RETURN s").keys(),
        )
        assertEquals(
            setOf(k("service", "billing")),
            run("MATCH (s:service) WHERE (NOT EXISTS { (s)-[:dependsOn]->(d) }) AND s.tier = '2' RETURN s").keys(),
        )
    }

    @Test
    fun `a chain block with two correlated ends and a block-local middle`() = runBlocking {
        // gateway -dependsOn-> cart; gateway -shortcut-> checkout <-dependsOn- cart closes the chain.
        val result = run(
            "MATCH (g:service)-[:dependsOn]->(c:service) " +
                "WHERE EXISTS { (g)-[:shortcut]->(t:service)<-[:dependsOn]-(c) } RETURN g, c",
        )
        assertEquals(setOf(k("service", "gateway"), k("service", "cart")), result.keys())
    }

    @Test
    fun `the block's own WHERE filters its candidates`() = runBlocking {
        val result = run(
            "MATCH (s:service) WHERE EXISTS { (s)-[:cluster]->(c:cluster) WHERE c.\$identifier = 'cluster-staging' } RETURN s",
        )
        assertEquals(setOf(k("service", "billing")), result.keys())
    }

    @Test
    fun `EXISTS is TRUE through a later candidate not the first`() = runBlocking {
        // cluster-prod's incoming services are checkout, cart, gateway in that order.
        val viaSecond = run(
            "MATCH (c:cluster {\$identifier: 'cluster-prod'}) " +
                "WHERE EXISTS { (c)<-[:cluster]-(s:service) WHERE s.\$identifier = 'cart' } RETURN c",
        )
        assertEquals(setOf(k("cluster", "cluster-prod")), viaSecond.keys())
        val none = run(
            "MATCH (c:cluster {\$identifier: 'cluster-prod'}) " +
                "WHERE EXISTS { (c)<-[:cluster]-(s:service) WHERE s.\$identifier = 'nobody' } RETURN c",
        )
        assertEquals(emptySet(), none.keys())
    }

    @Test
    fun `an UNKNOWN inner WHERE drops the candidate so the block is FALSE and NOT EXISTS is TRUE`() = runBlocking {
        // checkout has priority 1; cart/gateway have none (UNKNOWN) — no candidate is TRUE.
        val result = run(
            "MATCH (c:cluster {\$identifier: 'cluster-prod'}) " +
                "WHERE NOT EXISTS { (c)<-[:cluster]-(s:service) WHERE s.priority > 5 } RETURN c",
        )
        assertEquals(setOf(k("cluster", "cluster-prod")), result.keys())
    }

    @Test
    fun `a null anchor from an unmatched OPTIONAL MATCH makes the block FALSE and NOT EXISTS TRUE`() = runBlocking {
        val negative = "MATCH (t:_team {\$identifier: 'platform'}) " +
            "OPTIONAL MATCH (t)<-[:\$team]-(s:service) WHERE s.\$identifier = 'nothing' " +
            "OPTIONAL MATCH (t)<-[:\$team]-(u) WHERE NOT EXISTS { (s)-[:cluster]->(:cluster) } RETURN u"
        assertEquals(
            setOf(k("cluster", "cluster-prod"), k("service", "checkout"), k("workload", "checkout-workload")),
            run(negative).keys(),
        )
        val positive = negative.replace("WHERE NOT EXISTS", "WHERE EXISTS")
        assertEquals(emptySet(), run(positive).keys())
    }

    @Test
    fun `a block in an OPTIONAL MATCH WHERE correlates on the optional's own variable`() = runBlocking {
        val result = run(
            "MATCH (t:_team {\$identifier: 'platform'}) OPTIONAL MATCH (t)<-[:\$team]-(s:service) " +
                "WHERE EXISTS { (s)-[:cluster]->(c:cluster) WHERE c.\$identifier = 'cluster-prod' } RETURN s",
        )
        assertEquals(setOf(k("service", "checkout")), result.keys())
    }

    @Test
    fun `dollar-team inside a block uses the effective team, Inherited included`() = runBlocking {
        assertEquals(
            setOf(k("service", "cart"), k("service", "gateway")),
            run("MATCH (s:service) WHERE NOT EXISTS { (s)-[:\$team]->(:_team) } RETURN s").keys(),
        )
        assertEquals(
            setOf(k("workload", "checkout-workload")),
            run("MATCH (w:workload) WHERE EXISTS { (w)-[:\$team]->(t:_team) WHERE t.\$identifier = 'platform' } RETURN w").keys(),
        )
    }

    @Test
    fun `a hierarchy virtual edge inside a block resolves per source blueprint`() = runBlocking {
        assertEquals(
            setOf(k("workload", "checkout-workload")),
            run("MATCH (w:workload) WHERE EXISTS { (w)-[:deployment]->(:cluster) } RETURN w").keys(),
        )
        assertEquals(
            setOf(k("cluster", "cluster-prod"), k("cluster", "cluster-staging")),
            run("MATCH (c:cluster) WHERE EXISTS { (c)-[:deployment]->(:environment) } RETURN c").keys(),
        )
    }

    @Test
    fun `a variable-length edge inside a block honours its hop range`() = runBlocking {
        val deep = "MATCH (g:service {\$identifier: 'gateway'}) " +
            "WHERE EXISTS { (g)-[:dependsOn*1..3]->(x:service {\$identifier: 'checkout'}) } RETURN g"
        assertEquals(setOf(k("service", "gateway")), run(deep).keys())
        assertEquals(emptySet(), run(deep.replace("*1..3", "*1..1")).keys())
    }

    @Test
    fun `RETURN star never yields a block-local entity and the outer binding is untouched`() = runBlocking {
        val result = run("MATCH (s:service)-[:cluster]->(c:cluster) WHERE EXISTS { (s)-[:dependsOn]->(d:service) } RETURN *")
        assertEquals(
            setOf(k("service", "cart"), k("service", "gateway"), k("cluster", "cluster-prod")),
            result.keys(),
        )
    }

    @Test
    fun `a block-local name is free for a later clause to bind to a different row`() = runBlocking {
        // The block's local `x` is cart (gateway dependsOn cart); the later clause binds `x` to checkout
        // (gateway shortcut checkout). A leaked local would force the second clause to agree on cart.
        val result = run(
            "MATCH (g:service {\$identifier: 'gateway'}) WHERE EXISTS { (g)-[:dependsOn]->(x:service) } " +
                "MATCH (g)-[:shortcut]->(x:service) RETURN x",
        )
        assertEquals(setOf(k("service", "checkout")), result.keys())
    }

    @Test
    fun `a block stops at the first satisfying candidate wherever it sits in the order`() = runBlocking {
        // cluster-prod's incoming services are checkout, cart, gateway — gateway is the LAST candidate.
        val last = run(
            "MATCH (c:cluster {\$identifier: 'cluster-prod'}) " +
                "WHERE EXISTS { (c)<-[:cluster]-(s:service) WHERE s.\$identifier = 'gateway' } RETURN c",
        )
        assertEquals(setOf(k("cluster", "cluster-prod")), last.keys())
    }

    @Test
    fun `a multi-pattern block needs one extension satisfying every pattern and the inner WHERE`() = runBlocking {
        // billing's only cluster has no other service, so the second pattern's inner WHERE never holds.
        val result = run(
            "MATCH (s:service) WHERE EXISTS { (s)-[:cluster]->(c:cluster), (c)<-[:cluster]-(o:service) " +
                "WHERE o.\$identifier = 'gateway' } RETURN s",
        )
        assertEquals(setOf(k("service", "checkout"), k("service", "cart"), k("service", "gateway")), result.keys())
        val negated = run(
            "MATCH (s:service) WHERE NOT EXISTS { (s)-[:cluster]->(c:cluster), (c)<-[:cluster]-(o:service) " +
                "WHERE o.\$identifier = 'gateway' } RETURN s",
        )
        assertEquals(setOf(k("service", "billing")), negated.keys())
    }

    // A hub linking 400 spokes, every spoke linking the same 400 spokes (a `many` relation): the
    // two-hop block below would produce 160 000 rows, past MAX_QUERY_BINDINGS.
    private fun hubGraph(): QueryGraph {
        val spokeCount = 400
        val nodeBp = GraphBlueprint(
            "node",
            "Node",
            BlueprintDefinition(relations = mapOf("links" to relation("node", many = true))),
            emptyMap(),
            BlueprintTiers(),
        )
        val spokeIds = (0 until spokeCount).map { "spoke-$it" }
        val links = kotlinx.serialization.json.JsonArray(spokeIds.map { JsonPrimitive(it) })
        val rows = (listOf("hub") + spokeIds).map { id ->
            row("node", id, document = doc(relations = buildJsonObject { put("links", links) }))
        }
        return InMemoryQueryGraph(rows, mapOf("node" to nodeBp), emptySet())
    }

    private val hubQuery =
        "MATCH (h:node {\$identifier: 'hub'}) WHERE EXISTS { (h)-[:links]->(a)-[:links]->(b) WHERE b.\$identifier = 'none' } RETURN h"

    @Test
    fun `a block anchored on its right end expands to the left`() = runBlocking {
        // Correlated only on `c` (the LAST node), so the walk anchors there and expands leftwards:
        // b dependsOn a, and b is in cluster c — cart (dependsOn checkout) lives in cluster-prod only.
        val block = "{ (a:service)<-[:dependsOn]-(b:service)-[:cluster]->(c) }"
        assertEquals(
            setOf(k("cluster", "cluster-prod")),
            run("MATCH (c:cluster) WHERE EXISTS $block RETURN c").keys(),
        )
        assertEquals(
            setOf(k("cluster", "cluster-staging")),
            run("MATCH (c:cluster) WHERE NOT EXISTS $block RETURN c").keys(),
        )
    }

    @Test
    fun `a dead-end walk is refused with BINDING_LIMIT on its intermediate states, not by the deadline`() = runBlocking {
        // 400 x 400 intermediate states, none of which survives the last node's inline map.
        val text = "MATCH (h:node {\$identifier: 'hub'}) " +
            "WHERE EXISTS { (h)-[:links]->(a)-[:links]->(b)-[:links]->(c {\$identifier: 'nope'}) } RETURN h"
        val ex = assertFailsWith<QueryBudgetExceeded> { run(text, hubGraph()) }
        assertEquals(QueryDiagnosticCodes.BINDING_LIMIT, ex.code)
    }

    @Test
    fun `a block with a match returns TRUE without materialising its fan-out`() = runBlocking {
        // 400 x 400 complete extensions exist, but the first one already answers TRUE — no BINDING_LIMIT.
        val exists = "MATCH (h:node {\$identifier: 'hub'}) WHERE EXISTS { (h)-[:links]->(a)-[:links]->(b) } RETURN h"
        assertEquals(setOf(k("node", "hub")), run(exists, hubGraph()).keys())
        assertEquals(emptySet(), run(exists.replace("WHERE EXISTS", "WHERE NOT EXISTS"), hubGraph()).keys())
        // A satisfying candidate deep in the order is still found before the cap.
        val deep = "MATCH (h:node {\$identifier: 'hub'}) " +
            "WHERE EXISTS { (h)-[:links]->(a)-[:links]->(b) WHERE b.\$identifier = 'spoke-399' } RETURN h"
        assertEquals(setOf(k("node", "hub")), run(deep, hubGraph()).keys())
    }

    @Test
    fun `a binding explosion inside a block is refused with BINDING_LIMIT`() = runBlocking {
        val ex = assertFailsWith<QueryBudgetExceeded> { run(hubQuery, hubGraph()) }
        assertEquals(QueryDiagnosticCodes.BINDING_LIMIT, ex.code)
    }

    @Test
    fun `the deadline is honoured inside a block`() = runBlocking {
        val text = "MATCH (c:cluster {\$identifier: 'cluster-prod'}) " +
            "WHERE EXISTS { (c)<-[:cluster]-(s:service) WHERE s.\$identifier = 'none' } RETURN c"
        // One millisecond per clock read, 5 ms budget: the anchor row and the WHERE take two
        // checkpoints, the block's anchor and neighbours the next ones — the fifth read trips.
        fun ticking(): QueryBudget {
            var now = 0L
            return QueryBudget(deadlineMillis = 5, nanoTime = { val v = now; now += 1_000_000L; v })
        }
        val ex = assertFailsWith<QueryBudgetExceeded> { runEntityQuery(text, graph(), ticking()) }
        assertEquals(QueryDiagnosticCodes.DEADLINE_EXCEEDED, ex.code)
        // The same clock does not trip a query with no block (one checkpoint in total).
        assertEquals(1, runEntityQuery("MATCH (c:cluster {\$identifier: 'cluster-prod'}) RETURN c", graph(), ticking()).rows.size)
    }

    @Test
    fun `caller cancellation propagates from inside a block`() = runBlocking {
        val text = "MATCH (c:cluster {\$identifier: 'cluster-prod'}) " +
            "WHERE EXISTS { (c)<-[:cluster]-(s:service) WHERE s.\$identifier = 'none' } RETURN c"
        var reads = 0
        lateinit var job: Deferred<QueryResult>
        // Cancel the caller on the sixth clock read — inside the block's neighbour loop.
        val budget = QueryBudget(
            deadlineMillis = 60_000,
            nanoTime = {
                reads++
                if (reads == 6) job.cancel()
                0L
            },
        )
        job = async(start = CoroutineStart.LAZY) { runEntityQuery(text, graph(), budget) }
        assertFailsWith<CancellationException> { job.await() }
        Unit
    }

    @Test
    fun `runEntityQuery surfaces a validation failure as QueryException`() = runBlocking {
        assertFailsWith<QueryException> { run("MATCH (s:bogus) RETURN s") }
        Unit
    }
}
