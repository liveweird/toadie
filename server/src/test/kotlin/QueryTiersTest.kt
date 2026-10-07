package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintTiers
import ch.nokillswit.entityquery.computeFillTier
import ch.nokillswit.entityquery.isFilledProperty
import ch.nokillswit.entityquery.isFilledRelation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure coverage of `entityquery/QueryTiers.kt` (2.18.0): the filled tables and the `$fillTier` matrix. No database. */
class QueryTiersTest {

    private val noProperties: () -> JsonObject = { error("properties must not be decoded") }

    // --- isFilledProperty ----------------------------------------------------------------------

    @Test
    fun `a property is filled unless absent, null, blank, empty array or empty object`() {
        assertFalse(isFilledProperty(null))
        assertFalse(isFilledProperty(JsonNull))
        assertFalse(isFilledProperty(JsonPrimitive("")))
        assertFalse(isFilledProperty(JsonPrimitive("  \t")))
        assertFalse(isFilledProperty(JsonArray(emptyList())))
        assertFalse(isFilledProperty(JsonObject(emptyMap())))

        assertTrue(isFilledProperty(JsonPrimitive("x")))
        assertTrue(isFilledProperty(JsonPrimitive(false)))
        assertTrue(isFilledProperty(JsonPrimitive(0)))
        assertTrue(isFilledProperty(JsonArray(listOf(JsonPrimitive("")))))
        assertTrue(isFilledProperty(buildJsonObject { put("k", 1) }))
    }

    // --- isFilledRelation ----------------------------------------------------------------------

    @Test
    fun `a relation is filled by a non-blank string or an array holding one non-blank element`() {
        assertFalse(isFilledRelation(null))
        assertFalse(isFilledRelation(JsonNull))
        assertFalse(isFilledRelation(JsonPrimitive("")))
        assertFalse(isFilledRelation(JsonPrimitive("   ")))
        assertFalse(isFilledRelation(JsonPrimitive(3)))
        assertFalse(isFilledRelation(JsonArray(emptyList())))
        assertFalse(isFilledRelation(JsonArray(listOf(JsonPrimitive(""), JsonPrimitive("  "), JsonNull))))
        assertFalse(isFilledRelation(JsonArray(listOf(JsonArray(listOf(JsonPrimitive("x")))))))
        assertFalse(isFilledRelation(JsonObject(emptyMap())))

        assertTrue(isFilledRelation(JsonPrimitive("target")))
        assertTrue(isFilledRelation(JsonArray(listOf(JsonPrimitive(""), JsonPrimitive("target")))))
    }

    // --- computeFillTier -----------------------------------------------------------------------

    @Test
    fun `no tiered property or relation is null even with a blueprint tier, and never decodes properties`() {
        assertNull(computeFillTier(BlueprintTiers(), JsonObject(emptyMap()), noProperties))
        assertNull(computeFillTier(BlueprintTiers(blueprint = 2), JsonObject(emptyMap()), noProperties))
    }

    @Test
    fun `an empty tier-1 field is 0, an empty tier-2 field is 1, and everything filled is 4`() {
        val tiers = BlueprintTiers(properties = mapOf("name" to 1, "owner" to 2), relations = mapOf("system" to 3))
        val properties = buildJsonObject { put("name", "n"); put("owner", "o") }
        val relations = buildJsonObject { put("system", "s") }

        assertEquals(4, computeFillTier(tiers, relations, { properties }))
        assertEquals(0, computeFillTier(tiers, relations, { buildJsonObject { put("owner", "o") } }))
        assertEquals(1, computeFillTier(tiers, relations, { buildJsonObject { put("name", "n") } }))
        assertEquals(2, computeFillTier(tiers, JsonObject(emptyMap()), { properties }))
    }

    @Test
    fun `tiers without fields count as satisfied and the lowest unfilled tier decides`() {
        // Only tier 3 and 4 fields exist: tiers 1-2 are vacuously satisfied.
        val tiers = BlueprintTiers(relations = mapOf("a" to 3, "b" to 4))
        assertEquals(2, computeFillTier(tiers, JsonObject(emptyMap()), noProperties))
        assertEquals(3, computeFillTier(tiers, buildJsonObject { put("a", "x") }, noProperties))
        val allFilled = buildJsonObject { put("a", "x"); put("b", JsonArray(listOf(JsonPrimitive("y")))) }
        assertEquals(4, computeFillTier(tiers, allFilled, noProperties))
    }

    @Test
    fun `properties are decoded only when a property is tiered, and exactly once`() {
        val relationsOnly = BlueprintTiers(relations = mapOf("a" to 1))
        assertEquals(0, computeFillTier(relationsOnly, JsonObject(emptyMap()), noProperties))

        var calls = 0
        val tiered = BlueprintTiers(properties = mapOf("p" to 1, "q" to 2))
        computeFillTier(tiered, JsonObject(emptyMap())) { calls++; JsonObject(emptyMap()) }
        assertEquals(1, calls)

        assertFailsWith<IllegalStateException> { computeFillTier(tiered, JsonObject(emptyMap()), noProperties) }
    }
}
