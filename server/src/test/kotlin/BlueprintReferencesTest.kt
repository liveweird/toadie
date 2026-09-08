package ch.nokillswit

import ch.nokillswit.blueprints.AggregationCalculationSpec
import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.MirrorPropertyDefinition
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.blueprintTargets
import ch.nokillswit.blueprints.withTargetRenamed
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure, DB-free coverage of the target/rename-cascade helpers [BlueprintService] uses under its table lock. */
class BlueprintReferencesTest {

    private fun relation(target: String) = RelationDefinition(title = "Rel", target = target, required = false, many = false)

    private fun aggregation(target: String) = AggregationPropertyDefinition(
        title = "Agg",
        target = target,
        calculationSpec = AggregationCalculationSpec(calculationBy = "entities", func = "count"),
    )

    @Test
    fun `blueprintTargets is the union of relation and aggregation targets`() {
        val definition = BlueprintDefinition(
            relations = mapOf("r1" to relation("system"), "r2" to relation("domain")),
            aggregationProperties = mapOf("a1" to aggregation("domain"), "a2" to aggregation("resource")),
        )
        assertEquals(setOf("system", "domain", "resource"), blueprintTargets(definition))
    }

    @Test
    fun `blueprintTargets is empty when there are no relations or aggregations`() {
        assertEquals(emptySet(), blueprintTargets(BlueprintDefinition()))
    }

    @Test
    fun `blueprintTargets ignores mirror and ownership paths - they name relation ids, not blueprint identifiers`() {
        val definition = BlueprintDefinition(
            relations = mapOf("r" to relation("system")),
            mirrorProperties = mapOf("m" to MirrorPropertyDefinition(title = "M", path = "r.\$title")),
            ownership = OwnershipDefinition(type = "Inherited", path = "r"),
        )
        assertEquals(setOf("system"), blueprintTargets(definition))
    }

    @Test
    fun `withTargetRenamed rewrites byte-exact matching relation and aggregation targets only`() {
        val definition = BlueprintDefinition(
            relations = mapOf("r1" to relation("system"), "r2" to relation("System")),
            aggregationProperties = mapOf("a1" to aggregation("system")),
        )
        val renamed = withTargetRenamed(definition, old = "system", new = "microservice")
        assertEquals("microservice", renamed.relations.getValue("r1").target)
        // Case-variant target is a DIFFERENT string — byte-exact match only, untouched.
        assertEquals("System", renamed.relations.getValue("r2").target)
        assertEquals("microservice", renamed.aggregationProperties.getValue("a1").target)
    }

    @Test
    fun `withTargetRenamed is a no-op when nothing targets the old identifier`() {
        val definition = BlueprintDefinition(relations = mapOf("r" to relation("other")))
        assertEquals(definition, withTargetRenamed(definition, old = "system", new = "microservice"))
    }

    @Test
    fun `withTargetRenamed handles a self-relation`() {
        val definition = BlueprintDefinition(relations = mapOf("r" to relation("self-target")))
        val renamed = withTargetRenamed(definition, old = "self-target", new = "renamed")
        assertEquals("renamed", renamed.relations.getValue("r").target)
    }
}
