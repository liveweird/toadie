package ch.nokillswit

import ch.nokillswit.blueprints.ArrayItems
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.entities.CascadeCause
import ch.nokillswit.entities.EntityChangeSet
import ch.nokillswit.entities.EntityDocument
import ch.nokillswit.entities.EntityEventState
import ch.nokillswit.entities.EntityEventType
import ch.nokillswit.entities.EntityWriteKind
import ch.nokillswit.entities.MAX_EVENT_CHANGED_PATHS
import ch.nokillswit.entities.MAX_EVENT_VALUE_CHARS
import ch.nokillswit.entities.cascadeEvent
import ch.nokillswit.entities.entityChanges
import ch.nokillswit.entities.entityCreationEvent
import ch.nokillswit.entities.entityDeletionEvent
import ch.nokillswit.entities.replaceEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of the entity history (`entities/EntityEvents.kt`): which stored fields a diff
 * walks, how each kind of value is reported or withheld, the bounds, and the write-kind rule that
 * decides which event (if any) a replace mints. No database — the minting through the real
 * service lives in `EntityHistoryTest`.
 */
class EntityEventsTest {

    private fun strings(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    private fun state(
        identifier: String = "e",
        title: String = "T",
        icon: String? = null,
        team: JsonElement? = null,
        properties: Map<String, JsonElement> = emptyMap(),
        relations: Map<String, JsonElement> = emptyMap(),
        sourceUrl: String? = null,
    ) = EntityEventState(identifier, title, icon, team, EntityDocument(JsonObject(properties), JsonObject(relations)), sourceUrl)

    private fun definition(vararg properties: Pair<String, PropertyDefinition>) =
        BlueprintDefinition(schema = BlueprintSchema(properties = mapOf(*properties)))

    /** Unless a test names its own definition, every property either state holds is declared as a plain string. */
    private fun diff(before: EntityEventState, after: EntityEventState, definition: BlueprintDefinition? = null) =
        entityChanges(
            before,
            after,
            definition ?: definition(
                *(before.document.properties.keys + after.document.properties.keys)
                    .map { it to PropertyDefinition(type = "string") }.toTypedArray(),
            ),
        )

    private fun fields(changes: EntityChangeSet) = changes.changes.map { it.field }

    @Test
    fun `identical states change nothing`() {
        val s = state(
            icon = "i", team = JsonPrimitive("a"), properties = mapOf("p" to JsonPrimitive("v")),
            relations = mapOf("r" to strings("x", "y")), sourceUrl = "https://example.com/e.json",
        )
        assertTrue(diff(s, s.copy()).isEmpty)
        assertEquals(emptyMap(), diff(s, s.copy()).params())
    }

    @Test
    fun `identity fields report from and to, an icon can be set and cleared`() {
        val changes = diff(state(), state(identifier = "e2", title = "T2", icon = "star"))
        assertEquals(listOf("identifier", "title", "icon"), fields(changes))
        val params = changes.params()
        assertEquals("identifier,title,icon", params["changed"])
        assertEquals("e" to "e2", params["identifier.from"] to params["identifier.to"])
        assertEquals("T" to "T2", params["title.from"] to params["title.to"])
        assertNull(params["icon.from"])
        assertEquals("star", params["icon.to"])

        val cleared = diff(state(icon = "star"), state()).params()
        assertEquals("star", cleared["icon.from"])
        assertNull(cleared["icon.to"])
    }

    @Test
    fun `team reports a scalar change as from and to, a list change as added and removed, and a reorder as nothing`() {
        val scalar = diff(state(team = JsonPrimitive("a")), state(team = JsonPrimitive("b"))).params()
        assertEquals("a" to "b", scalar["team.from"] to scalar["team.to"])

        val set = diff(state(), state(team = JsonPrimitive("a"))).params()
        assertNull(set["team.from"])
        assertEquals("a", set["team.to"])

        val cleared = diff(state(team = JsonPrimitive("a")), state()).params()
        assertEquals("a", cleared["team.from"])
        assertNull(cleared["team.to"])

        val list = diff(state(team = strings("a", "b")), state(team = strings("b", "c"))).params()
        assertEquals("c", list["team.added"])
        assertEquals("a", list["team.removed"])

        assertTrue(diff(state(team = strings("a", "b")), state(team = strings("b", "a"))).isEmpty)
    }

    @Test
    fun `a scalar becoming a list and back is compared as the two lists it is`() {
        val grow = diff(state(team = JsonPrimitive("a")), state(team = strings("a", "b"))).params()
        assertEquals("b", grow["team.added"])
        assertNull(grow["team.removed"])
        assertNull(grow["team.from"])

        val shrink = diff(
            state(relations = mapOf("r" to strings("a", "b"))),
            state(relations = mapOf("r" to JsonPrimitive("b"))),
        ).params()
        assertEquals("a", shrink["relations.r.removed"])
        assertNull(shrink["relations.r.added"])

        // The same value in a different SHAPE changed the stored document without moving an entry: named only.
        val reshape = diff(state(team = JsonPrimitive("a")), state(team = strings("a")))
        assertEquals(mapOf("changed" to "team"), reshape.params())
    }

    @Test
    fun `relations diff per key — one-to-one as from and to, to-many as added and removed`() {
        val changes = diff(
            state(relations = mapOf("owner" to JsonPrimitive("a"), "deps" to strings("x", "y"), "kept" to JsonPrimitive("k"))),
            state(
                relations = mapOf(
                    "owner" to JsonPrimitive("b"), "deps" to strings("y", "z"), "kept" to JsonPrimitive("k"), "new" to strings("n"),
                ),
            ),
        )
        assertEquals(listOf("relations.owner", "relations.deps", "relations.new"), fields(changes))
        val params = changes.params()
        assertEquals("a" to "b", params["relations.owner.from"] to params["relations.owner.to"])
        assertEquals("z", params["relations.deps.added"])
        assertEquals("x", params["relations.deps.removed"])
        assertEquals("n", params["relations.new.added"])
    }

    @Test
    fun `properties diff per key in document order — the old keys first, then the ones the new side added`() {
        val before = state(properties = mapOf("a" to JsonPrimitive("1"), "b" to JsonPrimitive("2"), "c" to JsonPrimitive("3")))
        val after = state(
            properties = mapOf("a" to JsonPrimitive("1"), "b" to JsonPrimitive("22"), "c" to JsonPrimitive("3"), "d" to JsonPrimitive("4")),
        )
        val changes = diff(before, after)
        assertEquals(listOf("properties.b", "properties.d"), fields(changes))
        assertEquals("2" to "22", changes.params()["properties.b.from"] to changes.params()["properties.b.to"])
        assertEquals("4", changes.params()["properties.d.to"])
        assertNull(changes.params()["properties.d.from"])
    }

    @Test
    fun `numbers booleans and explicit nulls are scalars, an unset and a nulled property are the same absence`() {
        val changes = diff(
            state(properties = mapOf("n" to JsonPrimitive(1), "b" to JsonPrimitive(true), "gone" to JsonNull)),
            state(properties = mapOf("n" to JsonPrimitive(2.5), "b" to JsonPrimitive(false))),
        )
        assertEquals(listOf("properties.n", "properties.b"), fields(changes))
        val params = changes.params()
        assertEquals("1" to "2.5", params["properties.n.from"] to params["properties.n.to"])
        assertEquals("true" to "false", params["properties.b.from"] to params["properties.b.to"])
    }

    @Test
    fun `an array property diffs entry-wise and a pure reorder is nothing`() {
        val before = state(properties = mapOf("tags" to strings("a", "b")))
        val changes = diff(before, state(properties = mapOf("tags" to strings("b", "c"))))
        assertEquals("c", changes.params()["properties.tags.added"])
        assertEquals("a", changes.params()["properties.tags.removed"])
        assertTrue(diff(before, state(properties = mapOf("tags" to strings("b", "a")))).isEmpty)
        // An empty list and an absent key are the same nothing.
        assertTrue(diff(state(), state(properties = mapOf("tags" to JsonArray(emptyList())))).isEmpty)
    }

    @Test
    fun `yaml markdown and proto properties are named only — their text never rides`() {
        for (format in listOf("yaml", "markdown", "proto")) {
            val definition = definition("doc" to PropertyDefinition(type = "string", format = format))
            val changes = diff(
                state(properties = mapOf("doc" to JsonPrimitive("old text"))),
                state(properties = mapOf("doc" to JsonPrimitive("new text"))),
                definition,
            )
            assertEquals(listOf("properties.doc"), fields(changes), format)
            assertEquals(mapOf("changed" to "properties.doc"), changes.params(), format)
            // Set and cleared are facts too, and an unchanged text is still nothing.
            val text = state(properties = mapOf("doc" to JsonPrimitive("t")))
            assertEquals(mapOf("changed" to "properties.doc"), diff(state(), text, definition).params())
            assertTrue(diff(text, text.copy(), definition).isEmpty)
        }
    }

    @Test
    fun `a list whose items carry a free-text format is named only too`() {
        val definition = definition("notes" to PropertyDefinition(type = "array", items = ArrayItems(type = "string", format = "markdown")))
        val changes = diff(
            state(properties = mapOf("notes" to strings("one"))),
            state(properties = mapOf("notes" to strings("one", "two"))),
            definition,
        )
        assertEquals(mapOf("changed" to "properties.notes"), changes.params())
    }

    @Test
    fun `a property the current definition no longer declares is named only — it may once have been document text`() {
        val removed = diff(
            state(properties = mapOf("runbook" to JsonPrimitive("SECRET TEXT"))),
            state(),
            definition(), // the blueprint dropped `runbook`
        )
        assertEquals(mapOf("changed" to "properties.runbook"), removed.params())
        // The same for a value that is still stored but undeclared, on either side.
        val undeclared = diff(
            state(properties = mapOf("old" to JsonPrimitive("a"))),
            state(properties = mapOf("old" to JsonPrimitive("b"))),
            definition(),
        )
        assertEquals(mapOf("changed" to "properties.old"), undeclared.params())
        // ...while a declared plain property keeps its values.
        assertEquals(
            "b",
            diff(
                state(properties = mapOf("p" to JsonPrimitive("a"))),
                state(properties = mapOf("p" to JsonPrimitive("b"))),
                definition("p" to PropertyDefinition(type = "string")),
            ).params()["properties.p.to"],
        )
    }

    @Test
    fun `a property carrying an inline spec is named only like the catalog's spec definition`() {
        val definition = definition("api" to PropertyDefinition(type = "string", spec = "open-api"))
        val changes = diff(
            state(properties = mapOf("api" to JsonPrimitive("openapi: 3.0.3"))),
            state(properties = mapOf("api" to JsonPrimitive("openapi: 3.1.0"))),
            definition,
        )
        assertEquals(mapOf("changed" to "properties.api"), changes.params())
    }

    @Test
    fun `a duplicate dropped from a list, or a shape change with the same members, is named — a pure reorder is still nothing`() {
        val dropped = diff(
            state(properties = mapOf("l" to strings("a", "a"))),
            state(properties = mapOf("l" to strings("a"))),
        )
        assertEquals(mapOf("changed" to "properties.l"), dropped.params())
        val gained = diff(state(relations = mapOf("r" to strings("a"))), state(relations = mapOf("r" to strings("a", "a"))))
        assertEquals(mapOf("changed" to "relations.r"), gained.params())
        val reshape = diff(state(relations = mapOf("r" to JsonPrimitive("a"))), state(relations = mapOf("r" to strings("a"))))
        assertEquals(mapOf("changed" to "relations.r"), reshape.params())
        val reordered = diff(
            state(properties = mapOf("l" to strings("a", "a", "b"))),
            state(properties = mapOf("l" to strings("b", "a", "a"))),
        )
        assertTrue(reordered.isEmpty)
        // An empty list stays the same absence as no key.
        assertTrue(diff(state(relations = mapOf("r" to JsonArray(emptyList()))), state()).isEmpty)
    }

    @Test
    fun `an ordinary string property keeps its values, only the free-text formats are withheld`() {
        val definition = definition("url" to PropertyDefinition(type = "string", format = "url"))
        val params = diff(
            state(properties = mapOf("url" to JsonPrimitive("https://a.example"))),
            state(properties = mapOf("url" to JsonPrimitive("https://b.example"))),
            definition,
        ).params()
        assertEquals("https://a.example", params["properties.url.from"])
        assertEquals("https://b.example", params["properties.url.to"])
    }

    @Test
    fun `objects and arrays of objects are named only`() {
        val obj = { v: String -> buildJsonObject { put("k", v) } }
        val objectChange = diff(state(properties = mapOf("o" to obj("1"))), state(properties = mapOf("o" to obj("2"))))
        assertEquals(mapOf("changed" to "properties.o"), objectChange.params())

        val listChange = diff(
            state(properties = mapOf("l" to JsonArray(listOf(obj("1"))))),
            state(properties = mapOf("l" to JsonArray(listOf(obj("1"), obj("2"))))),
        )
        assertEquals(mapOf("changed" to "properties.l"), listChange.params())

        // A growing object where there was nothing is a fact as well (Items side vs Opaque side).
        val fromEmpty = diff(
            state(properties = mapOf("l" to JsonArray(emptyList()))),
            state(properties = mapOf("l" to JsonArray(listOf(obj("1"))))),
        )
        assertEquals(mapOf("changed" to "properties.l"), fromEmpty.params())
        assertTrue(diff(state(properties = mapOf("o" to obj("1"))), state(properties = mapOf("o" to obj("1")))).isEmpty)
    }

    @Test
    fun `a list item with a comma is named only because the added and removed lists are comma-joined — and its reorder is nothing`() {
        val before = state(properties = mapOf("l" to strings("a,b", "c")))
        val changed = diff(before, state(properties = mapOf("l" to strings("a,b", "d"))))
        assertEquals(mapOf("changed" to "properties.l"), changed.params())
        assertTrue(diff(before, state(properties = mapOf("l" to strings("c", "a,b")))).isEmpty)
        // A comma-free list becoming a comma-bearing one is still a named change.
        val becoming = diff(state(properties = mapOf("l" to strings("a", "b"))), state(properties = mapOf("l" to strings("a,b"))))
        assertEquals(mapOf("changed" to "properties.l"), becoming.params())
    }

    @Test
    fun `a value over the ceiling on either side is named only`() {
        val long = "x".repeat(201)
        val atLimit = "y".repeat(200)
        val over = diff(state(properties = mapOf("p" to JsonPrimitive("short"))), state(properties = mapOf("p" to JsonPrimitive(long))))
        assertEquals(mapOf("changed" to "properties.p"), over.params())
        val within = diff(
            state(properties = mapOf("p" to JsonPrimitive("short"))),
            state(properties = mapOf("p" to JsonPrimitive(atLimit))),
        ).params()
        assertEquals(atLimit, within["properties.p.to"])
        val longTitle = diff(state(), state(title = long))
        assertEquals(mapOf("changed" to "title"), longTitle.params())
    }

    @Test
    fun `the source reference diffs as a pseudo-field, last, and alone it is a change`() {
        val set = diff(state(), state(sourceUrl = "https://example.com/e.json"))
        assertEquals(listOf("sourceUrl"), fields(set))
        assertEquals("https://example.com/e.json", set.params()["sourceUrl.to"])
        val moved = diff(state(sourceUrl = "https://a.example/e"), state(title = "T2", sourceUrl = "https://b.example/e"))
        assertEquals(listOf("title", "sourceUrl"), fields(moved))
        assertEquals("https://a.example/e", moved.params()["sourceUrl.from"])
        assertEquals(listOf("sourceUrl"), fields(diff(state(sourceUrl = "https://a.example/e"), state())))
    }

    @Test
    fun `more than forty changed fields keep the first forty and count the rest`() {
        val properties = (1..41).associate { "p${it.toString().padStart(2, '0')}" to JsonPrimitive("v") }
        val changes = diff(state(), state(properties = properties))
        assertEquals(MAX_EVENT_CHANGED_PATHS, changes.changes.size)
        assertEquals(1, changes.more)
        val params = changes.params()
        assertEquals(MAX_EVENT_CHANGED_PATHS, params.getValue("changed").split(",").size)
        assertEquals("1", params["more"])
        assertEquals("properties.p01", params.getValue("changed").split(",").first())
        assertFalse(params.getValue("changed").contains("properties.p41"))
        assertNull(params["properties.p41.to"])

        val exactlyForty = diff(state(), state(properties = properties.entries.take(40).associate { it.key to it.value }))
        assertEquals(0, exactlyForty.more)
        assertFalse(exactlyForty.params().containsKey("more"))
    }

    @Test
    fun `value companions share one budget in document order, a field that does not fit falls back to name-only`() {
        val big = "z".repeat(190)
        // 30 fields of ~195 characters each: the first 20 fit in 4,000, the rest are named only.
        val properties = (1..30).associate { "p${it.toString().padStart(2, '0')}" to JsonPrimitive(big + it) }
        val before = state(properties = properties.mapValues { JsonPrimitive("b") })
        val withBudget = diff(before, state(properties = properties + ("q" to JsonPrimitive("tiny"))))
        val params = withBudget.params()
        val spent = withBudget.changes.sumOf { (it.from?.length ?: 0) + (it.to?.length ?: 0) }
        assertTrue(spent <= MAX_EVENT_VALUE_CHARS, "spent $spent")
        val withValues = withBudget.changes.filter { it.to != null }.map { it.field }
        val nameOnly = withBudget.changes.filter { it.to == null && it.from == null }.map { it.field }
        assertTrue(nameOnly.isNotEmpty() && "properties.p30" in nameOnly, "the field that no longer fits is named only: $nameOnly")
        assertTrue("properties.p01" in withValues, "document order: the first fields keep their values")
        // The budget is spent per field, so a later field that is small enough still fits.
        assertEquals("tiny", params["properties.q.to"])
        assertEquals(31, params.getValue("changed").split(",").size)
    }

    @Test
    fun `param names cannot collide — dotted and reserved-looking ids stay distinct and never hit a reserved key`() {
        val ids = listOf("a", "a.from", "more", "origin", "changed", "cascaded", "causeFrom")
        val properties = ids.associateWith { JsonPrimitive("v") }
        val changes = diff(state(), state(properties = properties, relations = mapOf("a" to JsonPrimitive("r"))))
        val params = changes.params()
        // One `changed` + one `.to` companion per field (no `.from`: every field was absent before).
        assertEquals(1 + changes.changes.size, params.size, params.keys.toString())
        listOf("more", "origin", "cascaded", "causeFrom", "causeTo", "causeEntityId", "causeBlueprint").forEach {
            assertFalse(params.containsKey(it), "reserved key $it must not be produced by a field path")
        }
        assertEquals("v", params["properties.a.from.to"])
        assertEquals("v", params["properties.a.to"])
        assertTrue(params.keys.all { it == "changed" || it.startsWith("properties.") || it.startsWith("relations.") })
    }

    @Test
    fun `the creation event names the blueprint and marks the import pipeline's origin`() {
        val ordinary = entityCreationEvent("service")
        assertEquals(EntityEventType.CREATED, ordinary.type)
        assertEquals(mapOf("blueprint" to "service"), ordinary.params)
        assertEquals(mapOf("blueprint" to "service", "origin" to "import"), entityCreationEvent("service", EntityWriteKind.IMPORT).params)
        assertEquals(EntityEventType.DELETED, entityDeletionEvent().type)
        assertEquals(emptyMap(), entityDeletionEvent().params)
    }

    @Test
    fun `the replace rule — sync always records, import records a stamped sync, an edit records only a change`() {
        val none = EntityChangeSet(emptyList())
        val some = diff(state(), state(title = "T2"))

        // EDIT: nothing changed -> nothing minted; a change -> UPDATED without an origin.
        assertNull(replaceEvent(EntityWriteKind.EDIT, synced = false, none, cascaded = 0))
        val edit = replaceEvent(EntityWriteKind.EDIT, synced = false, some, cascaded = 0)!!
        assertEquals(EntityEventType.UPDATED, edit.type)
        assertNull(edit.params["origin"])
        assertEquals("title", edit.params["changed"])

        // SYNC: SYNCED even when the remote copy matched.
        val syncedNoChange = replaceEvent(EntityWriteKind.SYNC, synced = true, none, cascaded = 0)!!
        assertEquals(EntityEventType.SYNCED, syncedNoChange.type)
        assertEquals(emptyMap(), syncedNoChange.params)
        assertEquals(EntityEventType.SYNCED, replaceEvent(EntityWriteKind.SYNC, synced = true, some, cascaded = 0)!!.type)

        // IMPORT under a batch sourceUrl: UPDATED when changed, SYNCED (origin=import) when not.
        assertEquals(EntityEventType.UPDATED, replaceEvent(EntityWriteKind.IMPORT, synced = true, some, cascaded = 0)!!.type)
        val importSynced = replaceEvent(EntityWriteKind.IMPORT, synced = true, none, cascaded = 0)!!
        assertEquals(EntityEventType.SYNCED, importSynced.type)
        assertEquals("import", importSynced.params["origin"])
        // IMPORT without a sourceUrl: the edit rule, with the origin.
        assertNull(replaceEvent(EntityWriteKind.IMPORT, synced = false, none, cascaded = 0))
        assertEquals("import", replaceEvent(EntityWriteKind.IMPORT, synced = false, some, cascaded = 0)!!.params["origin"])
        // The pass-two restoration has its own origin.
        val restoration = replaceEvent(EntityWriteKind.IMPORT_DEFERRED, synced = false, some, cascaded = 0)!!
        assertEquals("import-deferred", restoration.params["origin"])
    }

    @Test
    fun `a bulk write is an ordinary edit that marks origin=bulk and names its run`() {
        val none = EntityChangeSet(emptyList())
        val some = diff(state(), state(title = "T2"))
        val bulk = replaceEvent(EntityWriteKind.BULK, synced = false, some, cascaded = 0, bulkId = "run-1")!!
        assertEquals(EntityEventType.UPDATED, bulk.type)
        assertEquals("bulk", bulk.params["origin"])
        assertEquals("run-1", bulk.params["bulkId"])
        assertEquals("title", bulk.params["changed"])
        // the edit rule still applies: a bulk write that changed nothing mints nothing
        assertNull(replaceEvent(EntityWriteKind.BULK, synced = false, none, cascaded = 0, bulkId = "run-1"))
        // and an ordinary write never carries a bulkId
        assertNull(replaceEvent(EntityWriteKind.EDIT, synced = false, some, cascaded = 0)!!.params["bulkId"])
    }

    @Test
    fun `a rename event carries the cascade count, a cascade event names its cause and diffs the rewritten reference`() {
        val some = diff(state(), state(title = "T2"))
        val renamed = replaceEvent(EntityWriteKind.EDIT, synced = false, diff(state(), state(identifier = "e2")), cascaded = 3)!!
        assertEquals("3", renamed.params["cascaded"])
        assertEquals("e2", renamed.params["identifier.to"])
        assertNull(replaceEvent(EntityWriteKind.EDIT, synced = false, some, cascaded = 0)!!.params["cascaded"])

        val rewrite = diff(
            state(relations = mapOf("owner" to JsonPrimitive("e"))),
            state(relations = mapOf("owner" to JsonPrimitive("e2"))),
        )
        val cascade = cascadeEvent(rewrite, CascadeCause(entityId = 7u, blueprint = "team", from = "e", to = "e2"))
        assertEquals(EntityEventType.UPDATED, cascade.type)
        assertEquals("cascade", cascade.params["origin"])
        assertEquals("7", cascade.params["causeEntityId"])
        assertEquals("team", cascade.params["causeBlueprint"])
        assertEquals("e" to "e2", cascade.params["causeFrom"] to cascade.params["causeTo"])
        assertEquals("relations.owner", cascade.params["changed"])
        assertEquals("e2", cascade.params["relations.owner.to"])
    }
}
