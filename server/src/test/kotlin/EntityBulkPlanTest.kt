package ch.nokillswit

import ch.nokillswit.blueprints.ArrayItems
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.entities.BulkRowPlan
import ch.nokillswit.entities.EntityBulkChangeRequest
import ch.nokillswit.entities.EntityBulkOperation
import ch.nokillswit.entities.EntityBulkStatus
import ch.nokillswit.entities.EntityBulkTarget
import ch.nokillswit.entities.EntityImportBlueprint
import ch.nokillswit.entities.EntityImportSnapshot
import ch.nokillswit.entities.MAX_BULK_VALUE_CHARS
import ch.nokillswit.entities.ResolvedBulkChange
import ch.nokillswit.entities.StoredEntity
import ch.nokillswit.entities.applyBulkOperation
import ch.nokillswit.entities.planEntityBulkChange
import ch.nokillswit.entities.requireBulkShape
import ch.nokillswit.entities.resolveBulkChange
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure bulk-change planner (`entities/EntityBulk.kt` + `EntityBulkPlan.kt`): the request-level rules, every operation's
 * semantics, and one case per classification step in its documented order. No database.
 */
class EntityBulkPlanTest {

    private val bp = "svc"

    private val definition = BlueprintDefinition(
        schema = BlueprintSchema(
            properties = mapOf(
                "tier" to PropertyDefinition(type = "string", enum = listOf(JsonPrimitive("gold"), JsonPrimitive("silver"))),
                "tags" to PropertyDefinition(type = "array", items = ArrayItems(type = "string")),
                "n" to PropertyDefinition(type = "number"),
                "note" to PropertyDefinition(type = "string", maxLength = 20),
                "doc" to PropertyDefinition(type = "string", format = "markdown"),
            ),
            required = listOf("tier"),
        ),
        relations = mapOf(
            "owner" to RelationDefinition(title = "Owner", target = bp, required = false, many = false),
            "peers" to RelationDefinition(title = "Peers", target = bp, required = false, many = true),
        ),
    )

    private val inheritedDefinition = definition.copy(ownership = OwnershipDefinition(type = "Inherited", path = "owner"))

    private fun json(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

    private fun list(vararg items: String): JsonArray = buildJsonArray { items.forEach { add(it) } }

    private fun snapshot(documentBytes: Long = 0L, bytes: Map<UInt, Long> = emptyMap()) = EntityImportSnapshot(
        blueprints = listOf(
            EntityImportBlueprint(1u, bp, definition),
            EntityImportBlueprint(2u, SYSTEM_TEAM_BLUEPRINT, BlueprintDefinition()),
        ),
        identifiersByBlueprint = mapOf(
            1u to listOf("a" to 10u, "b" to 11u, "c" to 12u),
            2u to listOf("alpha" to 20u, "beta" to 21u),
        ),
        total = 5L,
        countsByBlueprint = mapOf(1u to 3L, 2u to 2L),
        documentBytes = documentBytes,
        bytesById = bytes,
    )

    private fun entity(
        id: UInt,
        properties: JsonObject = json { put("tier", "gold") },
        relations: JsonObject = JsonObject(emptyMap()),
        team: JsonElement? = null,
        blueprint: String = bp,
        updatedAt: Long = 100L + id.toLong(),
    ) = StoredEntity(id, blueprint, "ent-$id", "Entity $id", null, team, properties, relations, updatedAt)

    private fun request(
        field: String,
        operation: EntityBulkOperation,
        value: JsonElement? = null,
        entities: List<EntityBulkTarget> = listOf(EntityBulkTarget(1u)),
    ) = EntityBulkChangeRequest(bp, field, operation, value, entities)

    private fun change(
        field: String,
        operation: EntityBulkOperation,
        value: JsonElement? = null,
        def: BlueprintDefinition = definition,
    ): ResolvedBulkChange = resolveBulkChange(request(field, operation, value), def)

    private fun plan(
        change: ResolvedBulkChange,
        stored: List<StoredEntity>,
        targets: List<EntityBulkTarget> = stored.map { EntityBulkTarget(it.id) },
        snapshot: EntityImportSnapshot = snapshot(),
        workspaceBytes: Long = 1_000_000L,
    ): List<BulkRowPlan> = planEntityBulkChange(targets, stored.associateBy { it.id }, change, snapshot, workspaceBytes)

    private fun single(change: ResolvedBulkChange, stored: StoredEntity, target: EntityBulkTarget = EntityBulkTarget(stored.id)) =
        plan(change, listOf(stored), listOf(target)).single()

    // ---- request-level rules -------------------------------------------------------------------------------------------------

    @Test
    fun `the request shape rules each refuse with a 400`() {
        fun refused(request: EntityBulkChangeRequest) = assertFailsWith<BadRequestException> { requireBulkShape(request) }
        refused(request("team", EntityBulkOperation.CLEAR, entities = emptyList()))
        refused(request("team", EntityBulkOperation.CLEAR, entities = List(201) { EntityBulkTarget(it.toUInt()) }))
        refused(request("team", EntityBulkOperation.CLEAR, entities = listOf(EntityBulkTarget(1u), EntityBulkTarget(1u, 5L))))
        refused(request("team", EntityBulkOperation.CLEAR, entities = listOf(EntityBulkTarget(1u, -1L))))
        refused(request("properties.n", EntityBulkOperation.SET, value = null))
        refused(request("properties.n", EntityBulkOperation.CLEAR, value = JsonPrimitive(1)))
        refused(request("properties.tags", EntityBulkOperation.ADD, value = null))
        refused(request("properties.tags", EntityBulkOperation.ADD, value = JsonArray(emptyList())))
        refused(request("properties.tags", EntityBulkOperation.REMOVE, value = JsonPrimitive("x")))
        refused(request("properties.note", EntityBulkOperation.SET, value = JsonPrimitive("x".repeat(300 * 1024))))
        val atTheCap = List(200) { EntityBulkTarget(it.toUInt()) }
        requireBulkShape(request("properties.tags", EntityBulkOperation.ADD, value = list("x"), entities = atTheCap))
    }

    @Test
    fun `the field must be declared, writable and fit the operation`() {
        fun refused(field: String, operation: EntityBulkOperation, value: JsonElement? = null, def: BlueprintDefinition = definition) =
            assertFailsWith<BadRequestException> { resolveBulkChange(request(field, operation, value), def) }
        refused("title", EntityBulkOperation.SET, JsonPrimitive("x"))
        refused("properties.unknown", EntityBulkOperation.SET, JsonPrimitive("x"))
        refused("relations.unknown", EntityBulkOperation.SET, JsonPrimitive("x"))
        val calculated = ch.nokillswit.blueprints.CalculationPropertyDefinition(title = "D", calculation = ".x", type = "string")
        val withCalculation = definition.copy(calculationProperties = mapOf("derived" to calculated))
        refused("properties.derived", EntityBulkOperation.CLEAR, def = withCalculation)
        refused("team", EntityBulkOperation.CLEAR, def = inheritedDefinition)
        refused("properties.tier", EntityBulkOperation.ADD, list("gold"))
        refused("properties.n", EntityBulkOperation.REMOVE, list("1"))
        refused("relations.owner", EntityBulkOperation.ADD, list("a"))
        // the list-capable fields accept ADD/REMOVE
        change("properties.tags", EntityBulkOperation.ADD, list("x"))
        change("relations.peers", EntityBulkOperation.REMOVE, list("a"))
        change("team", EntityBulkOperation.ADD, list("alpha"))
    }

    // ---- the operations --------------------------------------------------------------------------------------------------------

    @Test
    fun `SET replaces and CLEAR drops`() {
        assertEquals(JsonPrimitive("silver"), applyBulkOperation(JsonPrimitive("gold"), EntityBulkOperation.SET, JsonPrimitive("silver")))
        assertEquals(JsonPrimitive("silver"), applyBulkOperation(null, EntityBulkOperation.SET, JsonPrimitive("silver")))
        assertNull(applyBulkOperation(JsonPrimitive("gold"), EntityBulkOperation.CLEAR, null))
    }

    @Test
    fun `ADD is set-like and order-preserving, and leaves a field with nothing to add untouched`() {
        assertEquals(list("a", "b", "c"), applyBulkOperation(list("a", "b"), EntityBulkOperation.ADD, list("c", "a", "c")))
        assertEquals(list("x"), applyBulkOperation(null, EntityBulkOperation.ADD, list("x", "x")))
        val untouched = list("a", "b")
        assertEquals(untouched, applyBulkOperation(untouched, EntityBulkOperation.ADD, list("b", "a")))
        // a bare string (a team stored as a scalar) counts as the one-item list it is
        assertEquals(list("alpha", "beta"), applyBulkOperation(JsonPrimitive("alpha"), EntityBulkOperation.ADD, list("beta")))
        assertEquals(JsonPrimitive("alpha"), applyBulkOperation(JsonPrimitive("alpha"), EntityBulkOperation.ADD, list("alpha")))
    }

    @Test
    fun `REMOVE drops the listed items and the key once the list is empty`() {
        assertEquals(list("a", "c"), applyBulkOperation(list("a", "b", "c"), EntityBulkOperation.REMOVE, list("b", "z")))
        assertNull(applyBulkOperation(list("a", "b"), EntityBulkOperation.REMOVE, list("b", "a")))
        val untouched = list("a")
        assertEquals(untouched, applyBulkOperation(untouched, EntityBulkOperation.REMOVE, list("z")))
        assertNull(applyBulkOperation(null, EntityBulkOperation.REMOVE, list("z")))
    }

    // ---- the classification steps, in order ------------------------------------------------------------------------------------

    @Test
    fun `step 1 - a target that is no longer active is STALE`() {
        val change = change("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver"))
        val row = plan(change, emptyList(), listOf(EntityBulkTarget(99u))).single().row
        assertEquals(EntityBulkStatus.STALE, row.status)
        assertEquals(99u, row.id)
        assertNull(row.identifier)
        assertNull(row.updatedAt)
    }

    @Test
    fun `step 2 - an entity of another blueprint is INVALID`() {
        val row = single(change("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver")), entity(1u, blueprint = "other")).row
        assertEquals(EntityBulkStatus.INVALID, row.status)
        assertTrue(row.message!!.contains("other"), row.message!!)
    }

    @Test
    fun `step 3 - a client stamp that differs from the stored one is STALE and a matching one proceeds`() {
        val change = change("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver"))
        val stored = entity(1u)
        val stale = single(change, stored, EntityBulkTarget(1u, stored.updatedAt - 1)).row
        assertEquals(EntityBulkStatus.STALE, stale.status)
        assertEquals(stored.updatedAt, stale.updatedAt)
        assertEquals(EntityBulkStatus.UPDATED, single(change, stored, EntityBulkTarget(1u, stored.updatedAt)).row.status)
        // the stamp check outranks UNCHANGED: a stale view of an unchanged row is still STALE
        val noop = change("properties.tier", EntityBulkOperation.SET, JsonPrimitive("gold"))
        assertEquals(EntityBulkStatus.STALE, single(noop, stored, EntityBulkTarget(1u, 1L)).row.status)
    }

    @Test
    fun `step 4 - no difference is UNCHANGED, even when the entity would otherwise fail validation`() {
        val stale = entity(1u, properties = json { put("tier", "bronze") }) // already violates the enum
        val row = single(change("properties.tags", EntityBulkOperation.REMOVE, list("z")), stale).row
        assertEquals(EntityBulkStatus.UNCHANGED, row.status)
        assertEquals(stale.updatedAt, row.updatedAt)
        fun unchanged(change: ResolvedBulkChange, stored: StoredEntity) =
            assertEquals(EntityBulkStatus.UNCHANGED, single(change, stored).row.status)
        unchanged(change("properties.tier", EntityBulkOperation.SET, JsonPrimitive("gold")), entity(1u))
        unchanged(change("properties.n", EntityBulkOperation.CLEAR), entity(1u))
        unchanged(change("team", EntityBulkOperation.ADD, list("alpha")), entity(1u, team = list("alpha")))
        // a pure reorder is nothing worth a write (the history would record none)
        val tagged = entity(1u, properties = json { put("tier", "gold"); put("tags", list("a", "b")) })
        unchanged(change("properties.tags", EntityBulkOperation.SET, list("b", "a")), tagged)
    }

    @Test
    fun `step 5 - the PUT shape rules refuse the after-document`() {
        val long = entity(1u, team = JsonPrimitive("alpha"))
        val row = single(change("team", EntityBulkOperation.SET, JsonPrimitive("x".repeat(101))), long).row
        assertEquals(EntityBulkStatus.INVALID, row.status)
        assertTrue(row.message!!.contains("team"), row.message!!)
        assertNull(row.findings)
    }

    @Test
    fun `step 6 - findings over the whole after-document are INVALID and carry the findings`() {
        val enumBreak = single(change("properties.tier", EntityBulkOperation.SET, JsonPrimitive("bronze")), entity(1u)).row
        assertEquals(listOf("ENUM_MISMATCH"), enumBreak.findings!!.map { it.code })
        assertEquals(EntityBulkStatus.INVALID, enumBreak.status)

        val requiredClear = single(change("properties.tier", EntityBulkOperation.CLEAR), entity(1u)).row
        assertEquals(listOf("REQUIRED_MISSING"), requiredClear.findings!!.map { it.code })

        val danglingRelation = single(change("relations.owner", EntityBulkOperation.SET, JsonPrimitive("ghost")), entity(1u)).row
        assertEquals(listOf("RELATION_TARGET_MISSING"), danglingRelation.findings!!.map { it.code })

        val missingTeam = single(change("team", EntityBulkOperation.ADD, list("nobody")), entity(1u)).row
        assertEquals(listOf("TEAM_TARGET_MISSING"), missingTeam.findings!!.map { it.code })

        // an unrelated pre-existing violation also refuses the row: a bulk write is an ordinary strict write
        val alreadyBroken = entity(1u, properties = json { put("tier", "gold"); put("note", "x".repeat(40)) })
        val row = single(change("properties.n", EntityBulkOperation.SET, JsonPrimitive(3)), alreadyBroken).row
        assertEquals(EntityBulkStatus.INVALID, row.status)
        assertEquals("properties.note", row.findings!!.single().field)
    }

    @Test
    fun `step 7 - the running workspace byte budget refuses the rows that do not fit, in request order`() {
        val change = change("properties.note", EntityBulkOperation.SET, JsonPrimitive("0123456789"))
        val entities = listOf(entity(1u), entity(2u), entity(3u))
        val ledger = snapshot(documentBytes = 100L, bytes = mapOf(1u to 30L, 2u to 30L, 3u to 30L))
        // each write grows its row by well under 100 bytes; a budget of 130 admits one growth only
        val rows = plan(change, entities, snapshot = ledger, workspaceBytes = 140L).map { it.row.status }
        assertEquals(listOf(EntityBulkStatus.UPDATED, EntityBulkStatus.INVALID, EntityBulkStatus.INVALID), rows)
        assertTrue(plan(change, entities, snapshot = ledger, workspaceBytes = 140L)[1].row.message!!.contains("workspace is full"))
        assertTrue(plan(change, entities, snapshot = ledger).all { it.row.status == EntityBulkStatus.UPDATED })
    }

    @Test
    fun `step 8 - UPDATED carries the full after-document, the stamp to expect and before and after`() {
        val stored = entity(
            1u,
            properties = json { put("tier", "gold"); put("tags", list("a")) },
            relations = json { put("peers", list("a")) },
        )
        val plan = single(change("properties.tags", EntityBulkOperation.ADD, list("b")), stored) as BulkRowPlan.Write
        assertEquals(EntityBulkStatus.UPDATED, plan.row.status)
        assertEquals(stored.updatedAt, plan.expectedUpdatedAt)
        assertEquals(list("a"), plan.row.before)
        assertEquals(list("a", "b"), plan.row.after)
        assertEquals(list("a", "b"), plan.request.properties["tags"])
        assertEquals("gold", (plan.request.properties["tier"] as JsonPrimitive).content, "untouched fields ride along")
        assertEquals(list("a"), plan.request.relations["peers"])
        assertEquals("ent-1", plan.request.identifier)
        assertNull(plan.request.sourceUrl)
    }

    @Test
    fun `CLEAR on a relation and on team drops the key`() {
        val owned = entity(1u, relations = json { put("owner", "a") }, team = JsonPrimitive("alpha"))
        val relation = single(change("relations.owner", EntityBulkOperation.CLEAR), owned) as BulkRowPlan.Write
        assertTrue("owner" !in relation.request.relations)
        assertNull(relation.row.after)
        assertEquals(JsonPrimitive("a"), relation.row.before)
        val team = single(change("team", EntityBulkOperation.CLEAR), owned) as BulkRowPlan.Write
        assertNull(team.request.team)
    }

    @Test
    fun `an explicit JSON null stored value reads as absent`() {
        val withNull = entity(1u, properties = json { put("tier", "gold"); put("n", JsonNull) })
        assertEquals(EntityBulkStatus.UNCHANGED, single(change("properties.n", EntityBulkOperation.CLEAR), withNull).row.status)
    }

    @Test
    fun `before and after beyond the cap are withheld with valuesOmitted`() {
        val big = "y".repeat(MAX_BULK_VALUE_CHARS + 10)
        val row = single(change("properties.doc", EntityBulkOperation.SET, JsonPrimitive(big)), entity(1u)).row
        assertEquals(EntityBulkStatus.UPDATED, row.status)
        assertNull(row.before)
        assertNull(row.after)
        assertEquals(true, row.valuesOmitted)
        val small = single(change("properties.doc", EntityBulkOperation.SET, JsonPrimitive("short")), entity(1u)).row
        assertNull(small.valuesOmitted)
    }

    @Test
    fun `results keep request order across mixed outcomes`() {
        val change = change("properties.tier", EntityBulkOperation.SET, JsonPrimitive("silver"))
        val stored = listOf(entity(1u), entity(2u, properties = json { put("tier", "silver") }), entity(3u, blueprint = "other"))
        val order = listOf(3u, 2u, 1u, 9u).map { EntityBulkTarget(it) }
        val rows = plan(change, stored, order).map { it.row }
        assertEquals(listOf(3u, 2u, 1u, 9u), rows.map { it.id })
        assertEquals(
            listOf(EntityBulkStatus.INVALID, EntityBulkStatus.UNCHANGED, EntityBulkStatus.UPDATED, EntityBulkStatus.STALE),
            rows.map { it.status },
        )
    }
}
