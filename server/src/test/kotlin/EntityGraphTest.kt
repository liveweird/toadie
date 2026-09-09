package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.EntityGraphEdge
import ch.nokillswit.entities.EntityGraphSource
import ch.nokillswit.entities.GraphBlueprint
import ch.nokillswit.entities.buildEntityGraph
import ch.nokillswit.entities.entityNodeId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure tests of [buildEntityGraph] (Port migration phase 3): the both-ends-shown rule (never
 * a MISSING node, assumption A1), relation-value array expansion + edge dedupe, the hierarchy
 * flag (only the source blueprint's OWN `hierarchyRelation`), the node id grammar, and pure
 * pass-through of blueprintTitle/findings. No database, no service — mirrors
 * `catalog/Graph.kt`'s own pure-builder test shape one level down.
 */
class EntityGraphTest {

    private fun relation(target: String, many: Boolean = false) =
        RelationDefinition(title = "Rel", target = target, required = false, many = many)

    private fun bp(
        identifier: String,
        title: String = identifier.uppercase(),
        relations: Map<String, RelationDefinition> = emptyMap(),
        hierarchyRelation: String? = null,
    ) = identifier to GraphBlueprint(
        identifier = identifier,
        title = title,
        definition = BlueprintDefinition(relations = relations),
        hierarchyRelation = hierarchyRelation,
    )

    private fun source(
        id: UInt,
        blueprintId: UInt,
        identifier: String,
        title: String = identifier,
        icon: String? = null,
        relations: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ) = EntityGraphSource(
        id = id,
        blueprintId = blueprintId,
        identifier = identifier,
        title = title,
        icon = icon,
        document = EntityDocument(
            properties = buildJsonObject { },
            relations = buildJsonObject { relations.forEach { (k, v) -> put(k, v) } },
        ),
    )

    private val noFindings: (EntityGraphSource) -> Int = { 0 }

    @Test
    fun `an edge needs both ends among the shown rows - a stale target is dropped, never MISSING`() {
        val (bpId, bpDef) = bp("team", relations = mapOf("owner" to relation("person")))
        val rowWithBadTarget = source(1u, 1u, "e1", relations = mapOf("owner" to JsonPrimitive("ghost")))
        val graph = buildEntityGraph(listOf(rowWithBadTarget), mapOf(1u to bpDef), noFindings)

        assertEquals(1, graph.nodes.size, "the source row itself is still a node")
        assertTrue(graph.edges.isEmpty(), "a target not among the rows must be dropped, not drawn MISSING")
    }

    @Test
    fun `array relation values expand into several edges, and repeats dedupe`() {
        val (_, teamDef) = bp("team", relations = mapOf("members" to relation("person", many = true)))
        val (_, personDef) = bp("person")
        val team = source(
            1u, 1u, "t1",
            relations = mapOf("members" to JsonArray(listOf(JsonPrimitive("p1"), JsonPrimitive("p2"), JsonPrimitive("p1")))),
        )
        val p1 = source(2u, 2u, "p1")
        val p2 = source(3u, 2u, "p2")

        val graph = buildEntityGraph(listOf(team, p1, p2), mapOf(1u to teamDef, 2u to personDef), noFindings)

        val expected = setOf(
            EntityGraphEdge(entityNodeId("team", "t1"), entityNodeId("person", "p1"), "members", hierarchy = false),
            EntityGraphEdge(entityNodeId("team", "t1"), entityNodeId("person", "p2"), "members", hierarchy = false),
        )
        assertEquals(expected, graph.edges.toSet())
        assertEquals(2, graph.edges.size, "the repeated p1 value must not double the edge")
    }

    @Test
    fun `hierarchy is true only for the source blueprint's own hierarchyRelation`() {
        val (_, childDef) = bp(
            "child",
            relations = mapOf("parent" to relation("parent"), "peer" to relation("child", many = true)),
            hierarchyRelation = "parent",
        )
        val (_, parentDef) = bp("parent")
        val parent = source(1u, 2u, "root")
        val child = source(
            2u, 1u, "kid",
            relations = mapOf("parent" to JsonPrimitive("root"), "peer" to JsonArray(listOf(JsonPrimitive("kid")))),
        )

        val graph = buildEntityGraph(listOf(parent, child), mapOf(1u to childDef, 2u to parentDef), noFindings)

        val hierarchyEdge = graph.edges.single { it.relation == "parent" }
        assertTrue(hierarchyEdge.hierarchy)
        val peerEdge = graph.edges.single { it.relation == "peer" }
        assertTrue(!peerEdge.hierarchy, "a self-referencing but non-hierarchy relation must not be flagged")
    }

    @Test
    fun `node id is blueprint-pipe-identifier, and node order follows the input rows`() {
        val (_, aDef) = bp("bp-a")
        val (_, bDef) = bp("bp-b")
        val second = source(1u, 1u, "z-entity")
        val first = source(2u, 2u, "a-entity")

        val graph = buildEntityGraph(listOf(second, first), mapOf(1u to aDef, 2u to bDef), noFindings)

        assertEquals(listOf("bp-a|z-entity", "bp-b|a-entity"), graph.nodes.map { it.id })
        assertEquals("bp-a|z-entity", entityNodeId("bp-a", "z-entity"))
    }

    @Test
    fun `blueprintTitle and the findings closure pass straight through to the node`() {
        val (_, def) = bp("svc", title = "Service")
        val row = source(7u, 1u, "e7", icon = "cube")

        val graph = buildEntityGraph(listOf(row), mapOf(1u to def), findings = { source -> if (source.id == 7u) 3 else 0 })

        val node = graph.nodes.single()
        assertEquals("Service", node.blueprintTitle)
        assertEquals("svc", node.blueprint)
        assertEquals(7u, node.entityId)
        assertEquals("cube", node.icon)
        assertEquals(3, node.findings)
    }
}
