package ch.nokillswit

import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.EntityFinding
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the Port-native wire shape [blueprintJson] produces for the entity DTOs — the absent-
 * not-null rule for `icon`/`team` — using the `BlueprintWireNamesTest` "only optional param
 * named" shape, which exercises the default-args bridge constructor's per-field mask branch a
 * whole-object round trip never reaches.
 */
class EntityWireNamesTest {

    private inline fun <reified T> assertOnlyOptional(instance: T, required: Set<String>, key: String, check: (JsonElement) -> Boolean) {
        val obj = Json.parseToJsonElement(blueprintJson.encodeToString(instance)).jsonObject
        assertEquals(required + key, obj.keys, "unexpected key set for '$key': $obj")
        assertTrue(check(obj.getValue(key)), "unexpected value for '$key': $obj")
    }

    private fun eq(value: JsonElement): (JsonElement) -> Boolean = { it == value }

    private val emptyProperties = buildJsonObject { }
    private val emptyRelations = buildJsonObject { }

    @Test
    fun `EntityRequest wire names - icon and team are the only genuinely optional fields`() {
        // properties/relations default to an empty (never absent) JsonObject, so they show up
        // in every case below alongside the three required identity fields.
        val alwaysPresent = setOf("blueprint", "identifier", "title", "properties", "relations")

        assertOnlyOptional(
            EntityRequest(blueprint = "bp", identifier = "e1", title = "T", icon = "I"),
            alwaysPresent,
            "icon",
            eq(JsonPrimitive("I")),
        )
        assertOnlyOptional(
            EntityRequest(blueprint = "bp", identifier = "e1", title = "T", team = JsonPrimitive("payments")),
            alwaysPresent,
            "team",
            eq(JsonPrimitive("payments")),
        )

        val withProperties = EntityRequest(blueprint = "bp", identifier = "e1", title = "T", properties = buildJsonObject { put("p", "v") })
        val obj = Json.parseToJsonElement(blueprintJson.encodeToString(withProperties)).jsonObject
        assertEquals(alwaysPresent, obj.keys)
        assertEquals(buildJsonObject { put("p", "v") }, obj.getValue("properties"))
        assertEquals(emptyRelations, obj.getValue("relations"))
    }

    @Test
    fun `EntityDocument wire names - both fields always present`() {
        val document = EntityDocument(properties = buildJsonObject { put("p", "v") }, relations = buildJsonObject { put("r", "x") })
        val obj = Json.parseToJsonElement(blueprintJson.encodeToString(document)).jsonObject
        assertEquals(setOf("properties", "relations"), obj.keys)
    }

    @Test
    fun `EntityFinding wire names - all three fields required`() {
        val finding = EntityFinding(code = "TYPE_MISMATCH", field = "properties.p", message = "m")
        val obj = Json.parseToJsonElement(blueprintJson.encodeToString(finding)).jsonObject
        assertEquals(setOf("code", "field", "message"), obj.keys)
    }

    @Test
    fun `EntityResponse wire names - icon and team are the only genuinely optional fields`() {
        val requiredKeys = setOf(
            "id", "blueprint", "blueprintId", "identifier", "title", "properties", "relations",
            "findings", "createdBy", "creatorName", "creatorDeleted", "createdAt", "updatedAt",
        )

        fun base(icon: String? = null, team: JsonElement? = null) = EntityResponse(
            id = 1u,
            blueprint = "bp",
            blueprintId = 2u,
            identifier = "e1",
            title = "T",
            icon = icon,
            team = team,
            properties = JsonObject(emptyMap()),
            relations = JsonObject(emptyMap()),
            findings = emptyList(),
            createdBy = 3u,
            creatorName = "Creator",
            creatorDeleted = false,
            createdAt = 1L,
            updatedAt = 2L,
        )

        assertOnlyOptional(base(icon = "I"), requiredKeys, "icon", eq(JsonPrimitive("I")))
        assertOnlyOptional(base(team = JsonPrimitive("payments")), requiredKeys, "team", eq(JsonPrimitive("payments")))

        val neither = Json.parseToJsonElement(blueprintJson.encodeToString(base())).jsonObject
        assertEquals(requiredKeys, neither.keys, "unset icon/team must be ABSENT, never explicit null")
        assertEquals(emptyProperties, neither.getValue("properties"))
        assertEquals(emptyRelations, neither.getValue("relations"))
    }
}
