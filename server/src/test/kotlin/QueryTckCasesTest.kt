package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.GraphBlueprint
import ch.nokillswit.entities.IndexedRow
import ch.nokillswit.entityquery.InMemoryQueryGraph
import ch.nokillswit.entityquery.QueryBudget
import ch.nokillswit.entityquery.runEntityQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ~25 hand-transcribed openCypher TCK scenarios (`clauses/match`, `clauses/match-where`,
 * `expressions/comparison`, `expressions/string`, `expressions/null`, `expressions/boolean`),
 * rewritten to Toadie's accepted subset over ONE fixture graph — labels = fixture blueprints,
 * `RETURN` yields matched nodes only (no projections/aggregates, no TCK `.feature`/tabular
 * reader: this table format is the equivalent transcription).
 *
 * Fixture: `person` (`name`/`age`/`active` properties; `knows` — many, person -> person; `worksAt`
 * — single, person -> company) and `company` (`name`). Alice --knows--> {Bob, Carol}; Bob
 * --knows--> {Carol, Dave}; Carol --knows--> {Alice} (a triangle Alice/Bob/Carol). Alice and Bob
 * --worksAt--> Acme; Carol --worksAt--> Initech; Dave has `age`/`active` but no `worksAt`. Carol
 * carries neither `age` nor `active` at all — the TCK's "missing property" fixture.
 */
class QueryTckCasesTest {

    private data class TckCase(val source: String, val cypher: String, val expected: Set<String>)

    private fun relation(target: String, many: Boolean = false) =
        RelationDefinition(title = "R", target = target, required = false, many = many)

    private val personDefinition = BlueprintDefinition(
        schema = BlueprintSchema(
            properties = mapOf(
                "name" to PropertyDefinition(type = "string"),
                "age" to PropertyDefinition(type = "number"),
                "active" to PropertyDefinition(type = "boolean"),
            ),
        ),
        relations = mapOf("knows" to relation("person", many = true), "worksAt" to relation("company")),
    )
    private val companyDefinition = BlueprintDefinition(
        schema = BlueprintSchema(properties = mapOf("name" to PropertyDefinition(type = "string"))),
    )

    private val blueprints = mapOf(
        "person" to GraphBlueprint("person", "Person", personDefinition, emptyMap()),
        "company" to GraphBlueprint("company", "Company", companyDefinition, emptyMap()),
    )

    private fun props(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Int -> put(key, value)
                is Boolean -> put(key, value)
                else -> error("unsupported fixture value $value")
            }
        }
    }

    private fun rels(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun knowsList(vararg identifiers: String): JsonElement = JsonArray(identifiers.map { JsonPrimitive(it) })

    private fun row(
        blueprint: String,
        identifier: String,
        properties: JsonObject = JsonObject(emptyMap()),
        relations: JsonObject = JsonObject(emptyMap()),
    ) = IndexedRow(blueprint, identifier, identifier, null, 1L, 2L, EntityDocument(properties, relations), null)

    private val alice = row(
        "person",
        "alice",
        properties = props("name" to "Alice", "age" to 30, "active" to true),
        relations = rels("knows" to knowsList("bob", "carol"), "worksAt" to JsonPrimitive("acme")),
    )
    private val bob = row(
        "person",
        "bob",
        properties = props("name" to "Bob", "age" to 42, "active" to false),
        relations = rels("knows" to knowsList("carol", "dave"), "worksAt" to JsonPrimitive("acme")),
    )
    private val carol = row(
        "person",
        "carol",
        properties = props("name" to "Carol"),
        relations = rels("knows" to knowsList("alice"), "worksAt" to JsonPrimitive("initech")),
    )
    private val dave = row("person", "dave", properties = props("name" to "Dave", "age" to 25, "active" to true))
    private val acme = row("company", "acme", properties = props("name" to "Acme"))
    private val initech = row("company", "initech", properties = props("name" to "Initech"))

    private val allRows = listOf(alice, bob, carol, dave, acme, initech)
    private val graph = InMemoryQueryGraph(allRows, blueprints, hierarchies = emptySet())

    private fun p(identifier: String) = "person|$identifier"

    private fun c(identifier: String) = "company|$identifier"

    private val cases = listOf(
        TckCase(
            "clauses/match/Match1 — Match non-existent nodes returns empty",
            "MATCH (n:company {\$identifier: 'doesnotexist'}) RETURN n",
            emptySet(),
        ),
        TckCase(
            "clauses/match/Match2 — Matching all nodes of one label",
            "MATCH (n:person) RETURN n",
            setOf(p("alice"), p("bob"), p("carol"), p("dave")),
        ),
        TckCase(
            "clauses/match/Match3 — Matching all nodes, unlabelled",
            "MATCH (n) RETURN n",
            setOf(p("alice"), p("bob"), p("carol"), p("dave"), c("acme"), c("initech")),
        ),
        TckCase(
            "clauses/match/Match4 — Simple node inline predicate",
            "MATCH (n:person {name: 'Bob'}) RETURN n",
            setOf(p("bob")),
        ),
        TckCase(
            "clauses/match/Match5 — Use multiple MATCH clauses to solve a triangle",
            "MATCH (a:person)-[:knows]->(b:person) MATCH (b)-[:knows]->(c:person) MATCH (c)-[:knows]->(a) RETURN a, b, c",
            setOf(p("alice"), p("bob"), p("carol")),
        ),
        TckCase(
            "clauses/match/Match7 — A relationship pattern with a label predicate on both nodes",
            "MATCH (a:person)-[:worksAt]->(b:company {name: 'Acme'}) RETURN a, b",
            setOf(p("alice"), p("bob"), c("acme")),
        ),
        TckCase(
            "clauses/match/DirectionOut — a directed relationship pattern",
            "MATCH (c:company {\$identifier: 'acme'})<-[:worksAt]-(p:person) RETURN p",
            setOf(p("alice"), p("bob")),
        ),
        TckCase(
            "clauses/match/DirectionIn — the mirror of the same pattern",
            "MATCH (p:person)-[:worksAt]->(c:company {\$identifier: 'acme'}) RETURN p",
            setOf(p("alice"), p("bob")),
        ),
        TckCase(
            "clauses/match/Undirected — an undirected relationship pattern",
            "MATCH (b:person {\$identifier: 'bob'})-[:knows]-(x) RETURN x",
            setOf(p("alice"), p("carol"), p("dave")),
        ),
        TckCase(
            "clauses/match/TypeAlternation — relation type alternation",
            "MATCH (a:person {\$identifier: 'alice'})-[:knows|worksAt]->(x) RETURN x",
            setOf(p("bob"), p("carol"), c("acme")),
        ),
        TckCase(
            "clauses/match-where/MatchWhere1 — Filter node with property predicate",
            "MATCH (n:person) WHERE n.age > 30 RETURN n",
            setOf(p("bob")),
        ),
        TckCase(
            "clauses/match-where/MatchWhere2 — Filter node with disjunctive property predicate",
            "MATCH (n:person) WHERE n.name = 'Alice' OR n.name = 'Dave' RETURN n",
            setOf(p("alice"), p("dave")),
        ),
        TckCase(
            "clauses/match-where/MatchWhere3 — Filter out based on a related node's property",
            "MATCH (n:person)-[:worksAt]->(c:company) WHERE c.name <> 'Initech' RETURN n",
            setOf(p("alice"), p("bob")),
        ),
        TckCase(
            "expressions/comparison/Comparison1 — less than",
            "MATCH (n:person) WHERE n.age < 30 RETURN n",
            setOf(p("dave")),
        ),
        TckCase(
            "expressions/comparison/Comparison2 — greater than or equal",
            "MATCH (n:person) WHERE n.age >= 30 RETURN n",
            setOf(p("alice"), p("bob")),
        ),
        TckCase(
            "expressions/comparison/Comparison3 — not equal excludes an absent property (UNKNOWN, not TRUE)",
            "MATCH (n:person) WHERE n.age <> 30 RETURN n",
            setOf(p("bob"), p("dave")),
        ),
        TckCase(
            "expressions/comparison/MixedTypes — ordering across incomparable types is UNKNOWN",
            "MATCH (n:person) WHERE n.age > 'abc' RETURN n",
            emptySet(),
        ),
        TckCase(
            "expressions/string/StartsWith1",
            "MATCH (n:person) WHERE n.name STARTS WITH 'A' RETURN n",
            setOf(p("alice")),
        ),
        TckCase(
            "expressions/string/EndsWith1",
            "MATCH (n:person) WHERE n.name ENDS WITH 'b' RETURN n",
            setOf(p("bob")),
        ),
        TckCase(
            "expressions/string/Contains1 — byte-exact, case-sensitive",
            "MATCH (n:person) WHERE n.name CONTAINS 'a' RETURN n",
            setOf(p("carol"), p("dave")),
        ),
        TckCase(
            "expressions/null/IsNull1",
            "MATCH (n:person) WHERE n.age IS NULL RETURN n",
            setOf(p("carol")),
        ),
        TckCase(
            "expressions/null/IsNotNull1",
            "MATCH (n:person) WHERE n.age IS NOT NULL RETURN n",
            setOf(p("alice"), p("bob"), p("dave")),
        ),
        TckCase(
            "expressions/boolean/And1",
            "MATCH (n:person) WHERE n.active = true AND n.age IS NOT NULL RETURN n",
            setOf(p("alice"), p("dave")),
        ),
        TckCase(
            "expressions/boolean/Or1",
            "MATCH (n:person) WHERE n.active = true OR n.age IS NULL RETURN n",
            setOf(p("alice"), p("carol"), p("dave")),
        ),
        TckCase(
            "expressions/boolean/Not1 — NOT of UNKNOWN stays UNKNOWN, not TRUE",
            "MATCH (n:person) WHERE NOT n.active RETURN n",
            setOf(p("bob")),
        ),
        TckCase(
            "expressions/list/In1 — list membership",
            "MATCH (n:person) WHERE n.name IN ['Alice', 'Bob'] RETURN n",
            setOf(p("alice"), p("bob")),
        ),
    )

    @Test
    fun `openCypher TCK subset scenarios`() = runBlocking {
        cases.forEach { case ->
            val result = runEntityQuery(case.cypher, graph, QueryBudget(deadlineMillis = 20_000))
            val actual = result.rows.map { "${it.blueprint.identifier}|${it.row.identifier}" }.toSet()
            assertEquals(case.expected, actual, "TCK case failed: ${case.source}\nquery: ${case.cypher}")
        }
    }
}
