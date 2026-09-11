package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintPlanVerdict
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.MAX_BLUEPRINTS
import ch.nokillswit.blueprints.MirrorPropertyDefinition
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.RegistryBlueprint
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.blueprints.planBlueprintImport
import ch.nokillswit.infra.importing.IMPORT_SCHEMA_MESSAGE
import ch.nokillswit.infra.importing.OntologyImportStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure blueprint-import planner (`blueprints/BlueprintImport.kt`) — one case per rule from
 * `.claude/docs/port-data-model.md` "Import and export": decode/validate, the reserved-
 * identifier rule, in-batch duplicates, EXISTS/UPDATED, system-blueprint extension, unknown
 * targets, the registry cap (including a cap slot freed by an unrelated rejection), Kahn
 * ordering with ties, 2-cycle deferral (relation and aggregation), the mirror/ownership/
 * hierarchyRelation strip, and an acyclic batch deferring nothing. No database.
 */
class BlueprintImportPlanTest {

    private fun doc(request: BlueprintRequest): JsonObject = blueprintJson.encodeToJsonElement(request).jsonObject

    private fun simple(id: String, relations: Map<String, RelationDefinition> = emptyMap()) =
        BlueprintRequest(identifier = id, title = "T", schema = BlueprintSchema(), relations = relations)

    private fun relationTo(target: String, required: Boolean = false, many: Boolean = false) =
        RelationDefinition(title = "R", target = target, required = required, many = many)

    private fun plan(documents: List<JsonObject>, registry: List<RegistryBlueprint> = emptyList(), replaceExisting: Boolean = false) =
        planBlueprintImport(documents, registry, replaceExisting)

    @Test
    fun `decode failure keeps the raw identifier and reports the fixed schema message`() {
        val bad = buildJsonObject { put("identifier", "keep-me"); put("title", 123) }
        val result = plan(listOf(bad))
        val row = (result.verdicts[0] as BlueprintPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, row.status)
        assertEquals("keep-me", row.identifier)
        assertEquals(IMPORT_SCHEMA_MESSAGE, row.message)
    }

    @Test
    fun `a validator rejection passes its own message through`() {
        val result = plan(listOf(doc(simple("bad id with spaces"))))
        val row = (result.verdicts[0] as BlueprintPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, row.status)
        assertTrue(row.message!!.contains("identifier"), row.message!!)
    }

    @Test
    fun `an underscore identifier is reserved unless it names an active system blueprint`() {
        val result = plan(listOf(doc(simple("_notasystemblueprint"))))
        val row = (result.verdicts[0] as BlueprintPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, row.status)
        assertTrue(row.message!!.contains("reserved"), row.message!!)
    }

    @Test
    fun `an in-batch duplicate identifier is CONFLICT naming the first document`() {
        val result = plan(listOf(doc(simple("dup")), doc(simple("DUP"))))
        assertTrue(result.verdicts[0] is BlueprintPlanVerdict.Store)
        val row = (result.verdicts[1] as BlueprintPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.CONFLICT, row.status)
        assertEquals("Duplicate of document 0", row.message)
    }

    @Test
    fun `an existing identifier is EXISTS with the flag off and UPDATED with it on`() {
        val registry = listOf(RegistryBlueprint(id = 42u, identifier = "existing", isSystem = false))
        val offResult = plan(listOf(doc(simple("existing"))), registry, replaceExisting = false)
        val offRow = (offResult.verdicts[0] as BlueprintPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.EXISTS, offRow.status)
        assertEquals(42u, offRow.id)

        val onResult = plan(listOf(doc(simple("existing"))), registry, replaceExisting = true)
        val onVerdict = onResult.verdicts[0] as BlueprintPlanVerdict.Store
        assertEquals(42u, onVerdict.existingId)
    }

    @Test
    fun `a system blueprint extension is rejected when it drops the base shape and accepted otherwise`() {
        val registry = listOf(RegistryBlueprint(id = 1u, identifier = SYSTEM_TEAM_BLUEPRINT, isSystem = true))
        val stripped = simple(SYSTEM_TEAM_BLUEPRINT) // no `parent` relation — drops the base shape
        val rejectResult = plan(listOf(doc(stripped)), registry, replaceExisting = true)
        val rejectRow = (rejectResult.verdicts[0] as BlueprintPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, rejectRow.status)

        val extended = simple(SYSTEM_TEAM_BLUEPRINT, relations = mapOf("parent" to relationTo(SYSTEM_TEAM_BLUEPRINT)))
        val acceptResult = plan(listOf(doc(extended)), registry, replaceExisting = true)
        assertTrue(acceptResult.verdicts[0] is BlueprintPlanVerdict.Store)
    }

    @Test
    fun `an unresolvable relation target is INVALID naming the target`() {
        val result = plan(listOf(doc(simple("a", relations = mapOf("r" to relationTo("nowhere"))))))
        val row = (result.verdicts[0] as BlueprintPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, row.status)
        assertTrue(row.message!!.contains("nowhere"), row.message!!)
    }

    @Test
    fun `a target that itself drops out of the batch cascades to its referrer`() {
        // b targets c; c targets an unresolvable identifier and is rejected — b must then be
        // rejected too, since c will never exist.
        val b = doc(simple("b", relations = mapOf("r" to relationTo("c"))))
        val c = doc(simple("c", relations = mapOf("r" to relationTo("nowhere"))))
        val result = plan(listOf(b, c))
        assertEquals(OntologyImportStatus.INVALID, (result.verdicts[0] as BlueprintPlanVerdict.Rejected).row.status)
        assertEquals(OntologyImportStatus.INVALID, (result.verdicts[1] as BlueprintPlanVerdict.Rejected).row.status)
    }

    @Test
    fun `the registry cap rejects creates past the limit, in batch order`() {
        val registry = (1..(MAX_BLUEPRINTS - 1)).map { RegistryBlueprint(id = it.toUInt(), identifier = "seed-$it", isSystem = false) }
        val result = plan(listOf(doc(simple("fits")), doc(simple("does-not-fit"))), registry)
        assertTrue(result.verdicts[0] is BlueprintPlanVerdict.Store)
        val row = (result.verdicts[1] as BlueprintPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, row.status)
        assertTrue(row.message!!.contains("full"), row.message!!)
    }

    @Test
    fun `a rejection elsewhere in the batch frees a cap slot for a later document`() {
        val registry = (1..(MAX_BLUEPRINTS - 1)).map { RegistryBlueprint(id = it.toUInt(), identifier = "seed-$it", isSystem = false) }
        // doc 0 is rejected for an unrelated reason (unknown target) — one fewer create competes
        // for the single remaining slot, so doc 1 must now fit.
        val rejectedElsewhere = doc(simple("bad", relations = mapOf("r" to relationTo("nowhere"))))
        val result = plan(listOf(rejectedElsewhere, doc(simple("fits-after-all"))), registry)
        assertEquals(OntologyImportStatus.INVALID, (result.verdicts[0] as BlueprintPlanVerdict.Rejected).row.status)
        assertTrue(result.verdicts[1] is BlueprintPlanVerdict.Store, "the freed slot must let the second document land")
    }

    @Test
    fun `independent documents keep submission order`() {
        val result = plan(listOf(doc(simple("a")), doc(simple("b")), doc(simple("c"))))
        assertEquals(listOf(0, 1, 2), result.order)
    }

    @Test
    fun `a two-node relation cycle emits the lowest index first and defers its unmet target`() {
        val a = doc(simple("a", relations = mapOf("toB" to relationTo("b"))))
        val b = doc(simple("b", relations = mapOf("toA" to relationTo("a"))))
        val result = plan(listOf(a, b))
        assertEquals(listOf(0, 1), result.order)
        val storeA = result.verdicts[0] as BlueprintPlanVerdict.Store
        assertEquals(setOf("b"), storeA.deferred)
        assertTrue(storeA.pass1.relations.isEmpty(), "pass1 must drop the relation targeting the not-yet-stored sibling")
        assertEquals(1, storeA.request.relations.size, "the full request keeps the relation for pass 2")
        val storeB = result.verdicts[1] as BlueprintPlanVerdict.Store
        assertTrue(storeB.deferred.isEmpty(), "b's target (a) is already stored by the time b is written")
    }

    @Test
    fun `a forward aggregation through a relation cycle is deferred and restored by pass 2`() {
        val a = BlueprintRequest(
            identifier = "a",
            title = "A",
            schema = BlueprintSchema(),
            aggregationProperties = mapOf(
                "count" to ch.nokillswit.blueprints.AggregationPropertyDefinition(
                    title = "Count",
                    target = "b",
                    calculationSpec = ch.nokillswit.blueprints.AggregationCalculationSpec(calculationBy = "entities", func = "count"),
                ),
            ),
        )
        val b = simple("b", relations = mapOf("peer" to relationTo("a")))
        val result = plan(listOf(doc(a), doc(b)))
        assertEquals(listOf(0, 1), result.order)
        val storeA = result.verdicts[0] as BlueprintPlanVerdict.Store
        assertEquals(setOf("b"), storeA.deferred)
        assertTrue(storeA.pass1.aggregationProperties.isEmpty(), "pass1 must drop the aggregation targeting the not-yet-stored sibling")
        assertEquals(1, storeA.request.aggregationProperties.size, "the full request keeps the aggregation for pass 2")
    }

    @Test
    fun `deferring a relation also strips its dependent mirror, ownership, and hierarchyRelation`() {
        val a = BlueprintRequest(
            identifier = "a",
            title = "A",
            schema = BlueprintSchema(),
            relations = mapOf("toB" to relationTo("b")),
            mirrorProperties = mapOf("mirrored" to MirrorPropertyDefinition(title = "M", path = "toB.\$title")),
            ownership = OwnershipDefinition(type = "Inherited", path = "toB"),
            hierarchyRelation = "toB",
        )
        val b = simple("b", relations = mapOf("peer" to relationTo("a")))
        val result = plan(listOf(doc(a), doc(b)))
        val storeA = result.verdicts[0] as BlueprintPlanVerdict.Store
        assertEquals(setOf("b"), storeA.deferred)
        assertTrue(storeA.pass1.relations.isEmpty())
        assertTrue(storeA.pass1.mirrorProperties.isEmpty(), "a mirror path starting with the dropped relation must be stripped too")
        assertNull(storeA.pass1.ownership, "an Inherited ownership path starting with the dropped relation must be stripped too")
        assertNull(storeA.pass1.hierarchyRelation, "a hierarchyRelation naming the dropped relation must be stripped too")
    }

    @Test
    fun `an acyclic batch defers nothing, even when the target is itself a batch create`() {
        val a = doc(simple("a"))
        val b = doc(simple("b", relations = mapOf("toA" to relationTo("a"))))
        val result = plan(listOf(b, a)) // b listed BEFORE its target a — a pure forward reference
        assertEquals(listOf(1, 0), result.order, "Kahn must place the dependency (a) before its dependent (b)")
        result.verdicts.forEach { verdict ->
            check(verdict is BlueprintPlanVerdict.Store)
            assertTrue(verdict.deferred.isEmpty())
        }
    }
}
