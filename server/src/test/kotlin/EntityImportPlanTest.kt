package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.blueprints.SYSTEM_USER_BLUEPRINT
import ch.nokillswit.entities.EntityImportBlueprint
import ch.nokillswit.entities.EntityImportSnapshot
import ch.nokillswit.entities.EntityPlanVerdict
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.MAX_ENTITIES_PER_BLUEPRINT
import ch.nokillswit.entities.MAX_ENTITIES_TOTAL
import ch.nokillswit.entities.planEntityImport
import ch.nokillswit.infra.importing.IMPORT_SCHEMA_MESSAGE
import ch.nokillswit.infra.importing.OntologyImportStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure entity-import planner (`entities/EntityImport.kt`) — one case per rule from
 * `.claude/docs/port-data-model.md` "Import and export": decode/validate, the unknown-blueprint
 * rule, per-blueprint duplicates (and cross-blueprint reuse), EXISTS/UPDATED, the registry cap,
 * the findings fixpoint cascading a dropped sibling into RELATION_TARGET_MISSING, ordering with
 * an optional back-edge deferred, and a required back-edge refused outright. No database.
 */
class EntityImportPlanTest {

    private fun doc(request: EntityRequest): JsonObject = blueprintJson.encodeToJsonElement(request).jsonObject

    private fun blueprint(identifier: String, id: UInt, definition: BlueprintDefinition) = EntityImportBlueprint(id, identifier, definition)

    private fun snapshot(
        blueprints: List<EntityImportBlueprint>,
        existing: List<Triple<UInt, String, UInt>> = emptyList(),
        total: Long = existing.size.toLong(),
    ): EntityImportSnapshot {
        val byBlueprint = existing.groupBy({ it.first }, { it.second to it.third })
        val counts = existing.groupingBy { it.first }.eachCount().mapValues { it.value.toLong() }
        return EntityImportSnapshot(blueprints, byBlueprint, total, counts)
    }

    private fun request(blueprint: String, identifier: String, relations: JsonObject = JsonObject(emptyMap())) =
        EntityRequest(blueprint = blueprint, identifier = identifier, title = "T", relations = relations)

    private fun relationOnlyDefinition(target: String, required: Boolean = false): BlueprintDefinition {
        val relation = RelationDefinition(title = "Peer", target = target, required = required, many = false)
        return BlueprintDefinition(relations = mapOf("peer" to relation))
    }

    @Test
    fun `decode failure keeps the raw blueprint and identifier and reports the fixed schema message`() {
        val bad = buildJsonObject { put("blueprint", "bp"); put("identifier", "keep-me"); put("title", 123) }
        val plan = planEntityImport(listOf(bad), snapshot(emptyList()), replaceExisting = false)
        val row = (plan.verdicts[0] as EntityPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, row.status)
        assertEquals("bp", row.blueprint)
        assertEquals("keep-me", row.identifier)
        assertEquals(IMPORT_SCHEMA_MESSAGE, row.message)
    }

    @Test
    fun `a shape-validation rejection passes its own message through`() {
        val plan = planEntityImport(listOf(doc(request("bp", ""))), snapshot(emptyList()), false)
        val row = (plan.verdicts[0] as EntityPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, row.status)
        assertTrue(row.message!!.contains("identifier"), row.message!!)
    }

    @Test
    fun `an unknown blueprint is INVALID`() {
        val plan = planEntityImport(listOf(doc(request("nope", "x"))), snapshot(emptyList()), false)
        val row = (plan.verdicts[0] as EntityPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, row.status)
        assertEquals("Unknown blueprint", row.message)
    }

    @Test
    fun `identifiers are unique per blueprint but freely reused across different blueprints`() {
        val a = blueprint("a", 1u, BlueprintDefinition())
        val b = blueprint("b", 2u, BlueprintDefinition())
        val plan = planEntityImport(listOf(doc(request("a", "x")), doc(request("b", "x"))), snapshot(listOf(a, b)), false)
        assertTrue(plan.verdicts[0] is EntityPlanVerdict.Store)
        assertTrue(plan.verdicts[1] is EntityPlanVerdict.Store)
    }

    @Test
    fun `an in-batch duplicate within the same blueprint is CONFLICT`() {
        val a = blueprint("a", 1u, BlueprintDefinition())
        val plan = planEntityImport(listOf(doc(request("a", "dup")), doc(request("a", "DUP"))), snapshot(listOf(a)), false)
        assertTrue(plan.verdicts[0] is EntityPlanVerdict.Store)
        val row = (plan.verdicts[1] as EntityPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.CONFLICT, row.status)
    }

    @Test
    fun `an existing identity is EXISTS with the flag off and UPDATED with it on`() {
        val a = blueprint("a", 1u, BlueprintDefinition())
        val existing = listOf(Triple(1u, "x", 99u))
        val offPlan = planEntityImport(listOf(doc(request("a", "x"))), snapshot(listOf(a), existing), false)
        val offRow = (offPlan.verdicts[0] as EntityPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.EXISTS, offRow.status)
        assertEquals(99u, offRow.id)

        val onPlan = planEntityImport(listOf(doc(request("a", "x"))), snapshot(listOf(a), existing), true)
        val onVerdict = onPlan.verdicts[0] as EntityPlanVerdict.Store
        assertEquals(99u, onVerdict.existingId)
    }

    @Test
    fun `the per-blueprint cap rejects creates past the limit, in batch order`() {
        val a = blueprint("a", 1u, BlueprintDefinition())
        val existing = (1..(MAX_ENTITIES_PER_BLUEPRINT - 1)).map { Triple(1u, "seed-$it", it.toUInt()) }
        val plan = planEntityImport(listOf(doc(request("a", "fits")), doc(request("a", "no-room"))), snapshot(listOf(a), existing), false)
        assertTrue(plan.verdicts[0] is EntityPlanVerdict.Store)
        val row = (plan.verdicts[1] as EntityPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, row.status)
        assertTrue(row.message!!.contains("full"), row.message!!)
    }

    @Test
    fun `a dropped sibling cascades RELATION_TARGET_MISSING onto its referrer`() {
        // "a" of blueprint bp has an unknown property (its own INVALID reason); "b" optionally
        // relates to "a" — once "a" drops out of the batch, "b"'s relation can no longer resolve.
        val bp = blueprint("bp", 1u, relationOnlyDefinition(target = "bp"))
        val badA = buildJsonObject {
            put("blueprint", "bp"); put("identifier", "a"); put("title", "A")
            put("properties", buildJsonObject { put("nope", "x") })
        }
        val b = doc(request("bp", "b", relations = buildJsonObject { put("peer", "a") }))
        val plan = planEntityImport(listOf(badA, b), snapshot(listOf(bp)), false)
        assertEquals(OntologyImportStatus.INVALID, (plan.verdicts[0] as EntityPlanVerdict.Rejected).row.status)
        val bRow = (plan.verdicts[1] as EntityPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, bRow.status)
        assertTrue(bRow.findings.orEmpty().any { it.code == "RELATION_TARGET_MISSING" }, bRow.findings.toString())
    }

    @Test
    fun `an optional relation cycle is deferred and restored by pass 2`() {
        val bp = blueprint("bp", 1u, relationOnlyDefinition(target = "bp"))
        val a = doc(request("bp", "a", relations = buildJsonObject { put("peer", "b") }))
        val b = doc(request("bp", "b", relations = buildJsonObject { put("peer", "a") }))
        val plan = planEntityImport(listOf(a, b), snapshot(listOf(bp)), false)
        assertEquals(listOf(0, 1), plan.order)
        val storeA = plan.verdicts[0] as EntityPlanVerdict.Store
        assertEquals(setOf("bp/b"), storeA.deferred)
        assertTrue(storeA.pass1.relations.isEmpty(), "pass1 must drop the relation targeting the not-yet-stored sibling")
        assertEquals(1, storeA.request.relations.size, "the full request keeps the relation for pass 2")
    }

    @Test
    fun `a required relation cycle cannot be deferred and is INVALID`() {
        val bp = blueprint("bp", 1u, relationOnlyDefinition(target = "bp", required = true))
        val a = doc(request("bp", "a", relations = buildJsonObject { put("peer", "b") }))
        val b = doc(request("bp", "b", relations = buildJsonObject { put("peer", "a") }))
        val plan = planEntityImport(listOf(a, b), snapshot(listOf(bp)), false)
        val rejected = plan.verdicts.filterIsInstance<EntityPlanVerdict.Rejected>()
        // The lowest-index side is rejected directly for the mandatory cycle; the other then
        // loses its only (now-gone) relation target and is rejected too, via the ordinary
        // findings fixpoint — neither side ever stores.
        assertEquals(2, rejected.size, "both sides of a required cycle must end up rejected")
        assertTrue(
            rejected.any { it.row.message!!.contains("Circular required reference") },
            rejected.map { it.row.message }.toString(),
        )
    }

    @Test
    fun `a forward team reference to a batch _team sibling resolves via ordering, not deferral`() {
        val team = blueprint(SYSTEM_TEAM_BLUEPRINT, 1u, BlueprintDefinition())
        val user = blueprint(
            "bp",
            2u,
            BlueprintDefinition(schema = BlueprintSchema(properties = mapOf("t" to PropertyDefinition(type = "string", format = "team")))),
        )
        val entity = doc(
            EntityRequest(
                blueprint = "bp", identifier = "x", title = "X",
                properties = buildJsonObject { put("t", "acme") },
            ),
        )
        val teamEntity = doc(EntityRequest(blueprint = SYSTEM_TEAM_BLUEPRINT, identifier = "acme", title = "Acme"))
        // The entity is listed BEFORE its team sibling — a pure forward reference.
        val plan = planEntityImport(listOf(entity, teamEntity), snapshot(listOf(team, user)), false)
        assertEquals(listOf(1, 0), plan.order, "the team entity must be written before its referrer")
        plan.verdicts.forEach { verdict ->
            check(verdict is EntityPlanVerdict.Store)
            assertTrue(verdict.deferred.isEmpty())
        }
    }

    @Test
    fun `a many relation cycle defers only its unresolved element, keeping the resolved one`() {
        val manyPeer = RelationDefinition(title = "Peers", target = "bp", required = false, many = true)
        val bp = blueprint("bp", 1u, BlueprintDefinition(relations = mapOf("peers" to manyPeer)))
        val existing = Triple(1u, "already-there", 5u)
        val a = doc(
            request(
                "bp", "a",
                relations = buildJsonObject {
                    put(
                        "peers",
                        buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("already-there"))
                            add(kotlinx.serialization.json.JsonPrimitive("b"))
                        },
                    )
                },
            ),
        )
        val b = doc(
            request(
                "bp", "b",
                relations = buildJsonObject {
                    put("peers", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("a")) })
                },
            ),
        )
        val plan = planEntityImport(listOf(a, b), snapshot(listOf(bp), listOf(existing)), false)
        val storeA = plan.verdicts[0] as EntityPlanVerdict.Store
        assertEquals(setOf("bp/b"), storeA.deferred)
        val pass1Peers = storeA.pass1.relations["peers"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("already-there"), pass1Peers, "the resolved array element must survive, only the deferred one drops")
    }

    @Test
    fun `a mandatory format-user property cycle is INVALID naming the property field`() {
        val bp = blueprint(
            "bp", 1u,
            BlueprintDefinition(
                schema = BlueprintSchema(
                    properties = mapOf("owner" to PropertyDefinition(type = "string", format = "user")),
                    required = listOf("owner"),
                ),
            ),
        )
        // A fake "_user"-identified blueprint with an OPTIONAL relation back to "bp" — enough to
        // stall Kahn's ordering without needing the real system-blueprint base shape, which the
        // pure planner never inspects.
        val userPeer = RelationDefinition(title = "Peer", target = "bp", required = false, many = false)
        val userBp = blueprint(SYSTEM_USER_BLUEPRINT, 2u, BlueprintDefinition(relations = mapOf("peer" to userPeer)))
        val a = doc(EntityRequest(blueprint = "bp", identifier = "a", title = "A", properties = buildJsonObject { put("owner", "u") }))
        val u = doc(
            EntityRequest(
                blueprint = SYSTEM_USER_BLUEPRINT, identifier = "u", title = "U",
                relations = buildJsonObject { put("peer", "a") },
            ),
        )
        val plan = planEntityImport(listOf(a, u), snapshot(listOf(bp, userBp)), false)
        val rejected = plan.verdicts.filterIsInstance<EntityPlanVerdict.Rejected>()
        assertTrue(rejected.isNotEmpty())
        assertTrue(
            rejected.any { it.row.message!!.contains("Circular required reference 'properties.owner'") },
            rejected.map { it.row.message }.toString(),
        )
    }

    @Test
    fun `the total entity cap rejects creates past the limit`() {
        val bp = blueprint("bp", 1u, BlueprintDefinition())
        // Seeded under a DIFFERENT blueprint id so only the TOTAL cap trips, never the
        // per-blueprint one, for "bp" itself.
        val existing = (1..(MAX_ENTITIES_TOTAL - 1)).map { Triple(999u, "seed-$it", it.toUInt()) }
        val plan = planEntityImport(
            listOf(doc(request("bp", "fits")), doc(request("bp", "no-room"))),
            snapshot(listOf(bp), existing),
            false,
        )
        assertTrue(plan.verdicts[0] is EntityPlanVerdict.Store)
        val row = (plan.verdicts[1] as EntityPlanVerdict.Rejected).row
        assertEquals(OntologyImportStatus.INVALID, row.status)
        assertTrue(row.message!!.contains("full"), row.message!!)
    }
}
