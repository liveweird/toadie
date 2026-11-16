package ch.nokillswit.entities

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.infra.db.FieldChange
import ch.nokillswit.infra.db.Leaf
import ch.nokillswit.infra.db.changeFor
import ch.nokillswit.infra.db.encodeChanges
import ch.nokillswit.infra.db.sourceUrlChange
import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * The entity's history vocabulary — the `catalog/CatalogFileEvents.kt` twin one level up the
 * ontology. An entity has no transitions, so the interesting event is the EDIT, and its detail
 * lives in the params' field-level diff rather than in the type.
 */
@Serializable
enum class EntityEventType { CREATED, UPDATED, SYNCED, DELETED }

/**
 * One history entry as the (later) `GET /entities/{id}/events` serves it. Structural on purpose:
 * no rendered string is stored or sent, the SPA localizes [type] and [params] in the viewer's
 * language. [userDeleted] / [userServiceAccount] qualify the actor so the SPA can tell a
 * removed account and an integration client (an MCP write) from an ordinary person.
 */
@Serializable
data class EntityEventResponse(
    val id: UInt,
    val entityId: UInt,
    val userId: UInt,
    /** Display name of the acting user; server-resolved, read-only. */
    val userName: String,
    /** The acting account has been soft-deleted. */
    val userDeleted: Boolean,
    /** The acting account is an integration client's service account (an MCP write). */
    val userServiceAccount: Boolean,
    /** Epoch milliseconds when the event was recorded. Server-managed. */
    val timestamp: Long,
    val type: EntityEventType,
    val params: Map<String, String> = emptyMap(),
)

typealias EntityEventPageResponse = PageResponse<EntityEventResponse>

/** A structured audit event: its [type] plus string params for the SPA's interpolation. */
data class EntityEventDescriptor(
    val type: EntityEventType,
    val params: Map<String, String> = emptyMap(),
)

/**
 * Why an entity write happened, which decides both the event's `origin` param and the replace
 * rule ([replaceEvent]): [EDIT] is the ordinary create/PUT, [SYNC] the entity's own HTTP re-sync,
 * [IMPORT] pass one of the batch import (REST import, the conversion import and every MCP write
 * all ride it) and [IMPORT_DEFERRED] its pass-two restoration of the references pass one withheld.
 */
enum class EntityWriteKind(internal val origin: String?) {
    EDIT(null),
    SYNC(null),
    IMPORT("import"),
    IMPORT_DEFERRED("import-deferred"),
}

/** The `origin` of an event minted by the rename cascade's rewrite of a referrer. */
internal const val ORIGIN_CASCADE = "cascade"

/** At most this many field paths ride one event's `changed`; the rest are only counted (`more`). */
internal const val MAX_EVENT_CHANGED_PATHS = 40

/** The characters of value companions (`.from`/`.to`/`.added`/`.removed`) one event may carry in total. */
internal const val MAX_EVENT_VALUE_CHARS = 4_000

/** The property formats that hold document text: recorded as the bare FACT of a change, never the value. */
private val FREE_TEXT_FORMATS = setOf("yaml", "markdown", "proto")

/**
 * The stored fields of an entity a history diff reads, on one side of a write: identity and
 * envelope fields plus the stored document. Never computed properties, the blueprint (an entity
 * never moves), `updated_at`, or the sync stamp/baseline — only [sourceUrl] of the sync
 * envelope, as a pseudo-field.
 */
internal data class EntityEventState(
    val identifier: String,
    val title: String,
    val icon: String?,
    val team: JsonElement?,
    val document: EntityDocument,
    val sourceUrl: String?,
)

/** The state a stored row holds right now. */
internal fun ResultRow.toEventState(): EntityEventState = EntityEventState(
    identifier = this[EntityService.Entities.identifier],
    title = this[EntityService.Entities.title],
    icon = this[EntityService.Entities.icon],
    team = this[EntityService.Entities.team]?.let { blueprintJson.decodeFromString<JsonElement>(it) },
    document = blueprintJson.decodeFromString(this[EntityService.Entities.document]),
    sourceUrl = this[EntityService.Entities.sourceUrl],
)

/** The state a replace is about to store (the FINAL request, after any rename rewriting) and the reference it resolved. */
internal fun EntityRequest.toEventState(sourceUrl: String?): EntityEventState =
    EntityEventState(identifier, title, icon, team, EntityDocument(properties, relations), sourceUrl)

/**
 * A bounded diff: [changes] (at most [MAX_EVENT_CHANGED_PATHS] fields, value companions within
 * [MAX_EVENT_VALUE_CHARS]) plus [more] fields beyond the path cap that are only counted.
 */
internal data class EntityChangeSet(val changes: List<FieldChange>, val more: Int = 0) {
    val isEmpty: Boolean get() = changes.isEmpty()

    /** `changed` + the per-field companions + `more` when paths were dropped. */
    fun params(): Map<String, String> = encodeChanges(changes) + if (more > 0) mapOf("more" to more.toString()) else emptyMap()
}

/**
 * The field-level diff of two stored entity states, in document order: `identifier`, `title`,
 * `icon`, `team`, `properties.<id>` and `relations.<id>` (per-key granularity), then the
 * `sourceUrl` pseudo-field. Values ride the event unless withheld by rule: a property whose
 * blueprint format is `yaml`/`markdown`/`proto` (document text), any object, an array of
 * objects, a list item containing a comma (the `added`/`removed` lists are comma-joined), or a
 * value over the per-side ceiling is reported by NAME only. A pure reorder of a scalar list
 * changes nothing worth telling. The result is then bounded ([boundChanges]).
 */
internal fun entityChanges(before: EntityEventState, after: EntityEventState, definition: BlueprintDefinition): EntityChangeSet {
    val all = buildList {
        changeFor("identifier", Leaf.Scalar(before.identifier), Leaf.Scalar(after.identifier))?.let(::add)
        changeFor("title", Leaf.Scalar(before.title), Leaf.Scalar(after.title))?.let(::add)
        changeFor("icon", before.icon?.let(Leaf::Scalar), after.icon?.let(Leaf::Scalar))?.let(::add)
        elementChange("team", before.team, after.team)?.let(::add)
        addAll(keyedChanges("properties", before.document.properties, after.document.properties) { id -> withheldProperty(definition, id) })
        addAll(keyedChanges("relations", before.document.relations, after.document.relations) { false })
        sourceUrlChange(before.sourceUrl, after.sourceUrl)?.let(::add)
    }
    return boundChanges(all)
}

/**
 * Per-key diff of one JSON object (`properties` / `relations`): keys in document order — the
 * old side's, then any the new side added. [freeText] marks a key whose value must never ride.
 */
private fun keyedChanges(
    section: String,
    before: JsonObject,
    after: JsonObject,
    freeText: (String) -> Boolean,
): List<FieldChange> {
    val keys = LinkedHashSet<String>().apply { addAll(before.keys); addAll(after.keys) }
    return keys.mapNotNull { key -> elementChange("$section.$key", before[key], after[key], freeText(key)) }
}

/**
 * Whether a property's VALUE may never ride an event. FAIL CLOSED: the only definition at hand is the CURRENT one, so a property it
 * no longer declares (removed from the blueprint, then dropped by the next save) may once have been document text and is named only;
 * so is one that carries an inline `spec` (open-api / async-api definition, the catalog's `spec.definition` twin) and one whose
 * format — or whose list items' format — is yaml / markdown / proto. The residual gap is a format changed AWAY from those: the old
 * value, document text under the earlier definition, can be recorded once on the next edit (`persistence.md` V51).
 */
private fun withheldProperty(definition: BlueprintDefinition, id: String): Boolean {
    val property: PropertyDefinition = definition.schema.properties[id] ?: return true
    return property.format in FREE_TEXT_FORMATS || property.items?.format in FREE_TEXT_FORMATS || property.spec != null
}

/** One JSON value's change: explicit null is the same absence as a missing key. */
private fun elementChange(path: String, before: JsonElement?, after: JsonElement?, freeText: Boolean = false): FieldChange? {
    val b = before.takeUnless { it is JsonNull }
    val a = after.takeUnless { it is JsonNull }
    if (b == null && a == null) return null
    val (beforeLeaf, afterLeaf) = leavesOf(b, a, freeText)
    // The shared walk ignores multiplicity and shape (`["a","a"]` -> `["a"]`, a stale one-to-one `"a"` -> `["a"]`), yet the STORED value
    // changed: when it yields nothing and the values are not a pure reorder, the field is reported by name.
    return changeFor(path, beforeLeaf, afterLeaf) ?: if (storedValueChanged(b, a)) FieldChange(path) else null
}

/** Whether two stored values differ by more than ordering: lists compare as sorted lists WITH duplicates, an empty list is absence. */
private fun storedValueChanged(before: JsonElement?, after: JsonElement?): Boolean {
    if (before == after) return false
    fun members(element: JsonElement?): List<String>? = when (element) {
        null -> emptyList()
        is JsonArray -> element.map { it.toString() }.sorted()
        else -> null
    }
    val old = members(before)
    val new = members(after)
    return old == null || new == null || old != new
}

/**
 * Classifies both sides TOGETHER so a reshape (a one-element value becoming a list or back) is
 * compared as the two lists it is — `"a"` -> `["a","b"]` reads as "b added", not as a vanished
 * scalar. Anything withheld by rule becomes an [Leaf.Opaque] fingerprint (compared, never shown).
 */
private fun leavesOf(before: JsonElement?, after: JsonElement?, freeText: Boolean): Pair<Leaf?, Leaf?> {
    val sides = listOf(before, after)
    if (freeText || sides.any { it != null && !isScalarOrScalarList(it) }) {
        return opaque(before) to opaque(after)
    }
    if (sides.none { it is JsonArray }) {
        return before?.let { Leaf.Scalar((it as JsonPrimitive).content) } to after?.let { Leaf.Scalar((it as JsonPrimitive).content) }
    }
    val beforeItems = before?.let(::itemsOf)
    val afterItems = after?.let(::itemsOf)
    // Reordering a comma-bearing list is still "nothing", hence the SORTED fingerprint.
    val withheld = listOfNotNull(beforeItems, afterItems).any { items -> items.any { ',' in it } }
    return if (withheld) {
        beforeItems?.let { Leaf.Opaque(it.sorted().toString()) } to afterItems?.let { Leaf.Opaque(it.sorted().toString()) }
    } else {
        beforeItems?.let(Leaf::Items) to afterItems?.let(Leaf::Items)
    }
}

private fun opaque(element: JsonElement?): Leaf? = element?.let { Leaf.Opaque(it.toString()) }

private fun isScalarOrScalarList(element: JsonElement): Boolean =
    element is JsonPrimitive || (element is JsonArray && element.all { it is JsonPrimitive })

private fun itemsOf(element: JsonElement): List<String> = when (element) {
    is JsonArray -> element.map { (it as JsonPrimitive).content }
    else -> listOf((element as JsonPrimitive).content)
}

/**
 * Caps the diff: the first [MAX_EVENT_CHANGED_PATHS] fields stay (the rest are counted in
 * `more`), and their value companions share [MAX_EVENT_VALUE_CHARS] characters in document
 * order — a field whose values do not fit in what is left degrades to name-only, while a later
 * smaller one may still fit.
 */
internal fun boundChanges(all: List<FieldChange>): EntityChangeSet {
    val kept = all.take(MAX_EVENT_CHANGED_PATHS)
    var spent = 0
    val bounded = kept.map { change ->
        val size = valueChars(change)
        when {
            size == 0 -> change
            spent + size > MAX_EVENT_VALUE_CHARS -> FieldChange(change.field)
            else -> change.also { spent += size }
        }
    }
    return EntityChangeSet(bounded, more = all.size - kept.size)
}

private fun valueChars(change: FieldChange): Int =
    (change.from?.length ?: 0) + (change.to?.length ?: 0) +
        change.added.sumOf { it.length + 1 } + change.removed.sumOf { it.length + 1 }

/** The event recorded when an entity is stored; [kind]'s origin marks the import pipeline's rows. */
internal fun entityCreationEvent(blueprint: String, kind: EntityWriteKind = EntityWriteKind.EDIT): EntityEventDescriptor =
    EntityEventDescriptor(
        EntityEventType.CREATED,
        buildMap {
            put("blueprint", blueprint)
            kind.origin?.let { put("origin", it) }
        },
    )

/**
 * The replace's event, per `.claude/docs/persistence.md` "Entity mutations and product history":
 * the entity's own re-sync ([EntityWriteKind.SYNC]) is ALWAYS recorded as SYNCED, changes or not
 * (pulling the remote copy is itself the act worth recording); an import row that stamps a sync
 * ([synced], a batch `sourceUrl`) is UPDATED when it changed something and SYNCED otherwise; any
 * other write is UPDATED only when something changed — a no-op PUT mints nothing (null).
 * [cascaded] is the count of referrers a rename rewrote.
 */
internal fun replaceEvent(
    kind: EntityWriteKind,
    synced: Boolean,
    changes: EntityChangeSet,
    cascaded: Int,
): EntityEventDescriptor? {
    val type = when {
        kind == EntityWriteKind.SYNC -> EntityEventType.SYNCED
        synced -> if (changes.isEmpty) EntityEventType.SYNCED else EntityEventType.UPDATED
        changes.isEmpty -> return null
        else -> EntityEventType.UPDATED
    }
    val params = buildMap {
        kind.origin?.let { put("origin", it) }
        if (cascaded > 0) put("cascaded", cascaded.toString())
        putAll(changes.params())
    }
    return EntityEventDescriptor(type, params)
}

/** What caused a cascade rewrite: the renamed entity and the identifier it moved from/to. */
internal data class CascadeCause(val entityId: UInt, val blueprint: String, val from: String, val to: String)

/** The UPDATED event a referrer gets when a rename rewrote a reference it held. */
internal fun cascadeEvent(changes: EntityChangeSet, cause: CascadeCause): EntityEventDescriptor =
    EntityEventDescriptor(
        EntityEventType.UPDATED,
        buildMap {
            put("origin", ORIGIN_CASCADE)
            put("causeEntityId", cause.entityId.toString())
            put("causeBlueprint", cause.blueprint)
            put("causeFrom", cause.from)
            put("causeTo", cause.to)
            putAll(changes.params())
        },
    )

internal fun entityDeletionEvent(): EntityEventDescriptor = EntityEventDescriptor(EntityEventType.DELETED)

/** What a cascade rewrite changed on one referrer [candidate]: its stored fields before and after the renamed reference was swapped. */
internal fun cascadeRewrite(
    candidate: ResultRow,
    definition: BlueprintDefinition,
    beforeDocument: EntityDocument,
    beforeTeam: JsonElement?,
    afterDocument: EntityDocument,
    afterTeam: JsonElement?,
): EntityChangeSet {
    val before = EntityEventState(
        identifier = candidate[EntityService.Entities.identifier],
        title = candidate[EntityService.Entities.title],
        icon = candidate[EntityService.Entities.icon],
        team = beforeTeam,
        document = beforeDocument,
        sourceUrl = candidate[EntityService.Entities.sourceUrl],
    )
    return entityChanges(before, before.copy(team = afterTeam, document = afterDocument), definition)
}
