package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.entityTargets
import ch.nokillswit.entities.withEntityTargetRenamed
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure, DB-free coverage of the target/rename-cascade helpers [EntityService] uses under its table lock. */
class EntityReferencesTest {

    private fun relation(target: String, many: Boolean = false) =
        RelationDefinition(title = "Rel", target = target, required = false, many = many)

    @Test
    fun `entityTargets pairs each relation's target blueprint with its value`() {
        val definition = BlueprintDefinition(
            relations = mapOf("owner" to relation("team"), "domain" to relation("domain")),
        )
        val document = EntityDocument(
            properties = buildJsonObject { },
            relations = buildJsonObject { put("owner", "payments"); put("domain", "commerce") },
        )
        assertEquals(setOf("team" to "payments", "domain" to "commerce"), entityTargets(document, definition))
    }

    @Test
    fun `entityTargets flattens a many relation's array`() {
        val definition = BlueprintDefinition(relations = mapOf("dependsOn" to relation("service", many = true)))
        val document = EntityDocument(
            properties = buildJsonObject { },
            relations = buildJsonObject { putJsonArray("dependsOn") { add("catalog"); add("pricing") } },
        )
        assertEquals(setOf("service" to "catalog", "service" to "pricing"), entityTargets(document, definition))
    }

    @Test
    fun `entityTargets ignores relation keys the document doesn't declare and null-shaped values`() {
        val definition = BlueprintDefinition(relations = mapOf("owner" to relation("team")))
        val document = EntityDocument(properties = buildJsonObject { }, relations = buildJsonObject { put("unknown", "x") })
        assertEquals(emptySet(), entityTargets(document, definition))
    }

    @Test
    fun `entityTargets is empty when there are no relations`() {
        assertEquals(emptySet(), entityTargets(EntityDocument(buildJsonObject { }, buildJsonObject { }), BlueprintDefinition()))
    }

    @Test
    fun `withEntityTargetRenamed rewrites only relations whose own definition targets the given blueprint`() {
        val definition = BlueprintDefinition(
            relations = mapOf(
                "owner" to relation("team"),
                "other" to relation("service"),
            ),
        )
        val document = EntityDocument(
            properties = buildJsonObject { },
            relations = buildJsonObject { put("owner", "payments"); put("other", "payments") },
        )
        val renamed = withEntityTargetRenamed(document, definition, targetBlueprint = "team", old = "payments", new = "billing")
        assertEquals("billing", (renamed.relations.getValue("owner")).let { it.toString().trim('"') })
        // "other" targets a DIFFERENT blueprint ("service"), so an identical string value must survive untouched.
        assertEquals("payments", (renamed.relations.getValue("other")).let { it.toString().trim('"') })
    }

    @Test
    fun `withEntityTargetRenamed rewrites every matching entry inside a many relation`() {
        val definition = BlueprintDefinition(relations = mapOf("dependsOn" to relation("service", many = true)))
        val document = EntityDocument(
            properties = buildJsonObject { },
            relations = buildJsonObject { putJsonArray("dependsOn") { add("catalog"); add("pricing"); add("catalog") } },
        )
        val renamed = withEntityTargetRenamed(document, definition, targetBlueprint = "service", old = "catalog", new = "checkout")
        val values = renamed.relations.getValue("dependsOn").toString()
        assertEquals("[\"checkout\",\"pricing\",\"checkout\"]", values)
    }

    @Test
    fun `withEntityTargetRenamed is a no-op when nothing targets the old identifier`() {
        val definition = BlueprintDefinition(relations = mapOf("owner" to relation("team")))
        val document = EntityDocument(properties = buildJsonObject { }, relations = buildJsonObject { put("owner", "other") })
        assertEquals(document, withEntityTargetRenamed(document, definition, targetBlueprint = "team", old = "payments", new = "billing"))
    }

    @Test
    fun `withEntityTargetRenamed handles a self-blueprint relation`() {
        val definition = BlueprintDefinition(relations = mapOf("parent" to relation("self")))
        val document = EntityDocument(properties = buildJsonObject { }, relations = buildJsonObject { put("parent", "old") })
        val renamed = withEntityTargetRenamed(document, definition, targetBlueprint = "self", old = "old", new = "new")
        assertEquals("\"new\"", renamed.relations.getValue("parent").toString())
    }

    @Test
    fun `withEntityTargetRenamed leaves a JsonNull relation value untouched`() {
        val definition = BlueprintDefinition(relations = mapOf("owner" to relation("team")))
        val document = EntityDocument(properties = buildJsonObject { }, relations = buildJsonObject { put("owner", JsonNull) })
        val renamed = withEntityTargetRenamed(document, definition, targetBlueprint = "team", old = "payments", new = "billing")
        assertEquals(JsonNull, renamed.relations.getValue("owner"))
    }
}
