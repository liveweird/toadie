package ch.nokillswit.catalog

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The catalog file's history vocabulary. Kept deliberately small: a document has no
 * transitions, so the interesting event is the EDIT, and its detail lives in the params'
 * field-level diff rather than in the type.
 */
@Serializable
enum class CatalogFileEventType { CREATED, UPDATED, SYNCED, DELETED }

/**
 * One history entry as served by `GET /files/{id}/events`. Structural on purpose — no rendered
 * string is stored or sent, the SPA localizes [type] and [params] in the viewer's language.
 */
@Serializable
data class CatalogFileEventResponse(
    val id: UInt,
    val catalogFileId: UInt,
    val userId: UInt,
    /** Display name of the user who made the change; server-resolved, read-only. */
    val userName: String,
    /** Epoch milliseconds when the event was recorded. Server-managed. */
    val timestamp: Long,
    val type: CatalogFileEventType,
    val params: Map<String, String> = emptyMap(),
)

typealias CatalogFileEventPageResponse = PageResponse<CatalogFileEventResponse>

/** A structured audit event: its [type] plus string params for the SPA's interpolation. */
data class CatalogFileEventDescriptor(
    val type: CatalogFileEventType,
    val params: Map<String, String> = emptyMap(),
)

/**
 * One changed field of a document. A scalar carries [from]/[to]; a list of scalars carries
 * [added]/[removed] (bounded by the CHANGE, not by the list's length); a field whose value is
 * withheld — free text, a structured list, or an over-long value — carries none of them and is
 * reported by NAME only ("Description changed.").
 */
data class FieldChange(
    val field: String,
    val from: String? = null,
    val to: String? = null,
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
)

/**
 * The value ceiling per side. Beyond it a field degrades to name-only rather than being
 * truncated into a misleading diff — and event rows stay small.
 */
internal const val MAX_CHANGE_VALUE_LENGTH = 200

/**
 * The document paths whose VALUE never rides an event, however short it happens to be: the two
 * free-text fields. Lettuce's rule ("no free text in params") kept as a value rule — the fact
 * of the change is recorded, its content is not. `metadata.links` needs no entry: an array of
 * objects is withheld structurally (see [leaf]).
 */
private val FACT_ONLY_FIELDS = setOf("metadata.description", "spec.definition")

/** The source reference is envelope state, not part of the document — it diffs as a pseudo-field. */
internal const val SOURCE_URL_FIELD = "sourceUrl"

/**
 * The diff's own encoder. `encodeDefaults` is ON — unlike the service's storage instance —
 * so a field at its default value still appears on both sides: without it a Component→API
 * change would read as "kind SET to API" (the "Component" default is omitted from the stored
 * JSON). Absence stays modelled by an explicit null, which the walk skips.
 */
private val json = Json { encodeDefaults = true }

/** The event recorded when a file is stored, `origin=import` marking the import loop's rows. */
internal fun catalogFileCreationEvent(kind: String, viaImport: Boolean = false): CatalogFileEventDescriptor =
    CatalogFileEventDescriptor(
        CatalogFileEventType.CREATED,
        buildMap {
            put("kind", kind)
            if (viaImport) put("origin", "import")
        },
    )

/** The edit's event — null when the save changed nothing (Lettuce's no-op rule: no empty events). */
internal fun catalogFileUpdateEvent(changes: List<FieldChange>): CatalogFileEventDescriptor? =
    if (changes.isEmpty()) null else CatalogFileEventDescriptor(CatalogFileEventType.UPDATED, encodeChanges(changes))

/**
 * The repo→DB sync's event — recorded ALWAYS, empty change list included: pulling the repo copy
 * is itself the act worth recording (it stamps the sync state), unlike a no-op PUT.
 */
internal fun catalogFileSyncEvent(changes: List<FieldChange>): CatalogFileEventDescriptor =
    CatalogFileEventDescriptor(CatalogFileEventType.SYNCED, encodeChanges(changes))

internal fun catalogFileDeletionEvent(): CatalogFileEventDescriptor =
    CatalogFileEventDescriptor(CatalogFileEventType.DELETED)

/**
 * The params encoding (Lettuce's impact-log idiom, widened to a whole document): `changed`
 * comma-joins the changed field paths in document order, and each field's values follow under
 * `<path>.from`/`.to`/`.added`/`.removed`. A name-only field contributes to `changed` alone.
 * Field paths, tags and entity references cannot contain a comma, so the joins stay unambiguous.
 */
internal fun encodeChanges(changes: List<FieldChange>): Map<String, String> {
    if (changes.isEmpty()) return emptyMap()
    return buildMap {
        put("changed", changes.joinToString(",") { it.field })
        changes.forEach { change ->
            change.from?.let { put("${change.field}.from", it) }
            change.to?.let { put("${change.field}.to", it) }
            if (change.added.isNotEmpty()) put("${change.field}.added", change.added.joinToString(","))
            if (change.removed.isNotEmpty()) put("${change.field}.removed", change.removed.joinToString(","))
        }
    }
}

/**
 * The field-level diff of two documents, in document order. Both sides are encoded to a JSON
 * tree and walked generically, so a document field added tomorrow is diffed the day it is added
 * and the two can never drift apart. Nested objects recurse (`spec.profile.email`), and so do
 * the label/annotation MAPS — a changed label surfaces as its own `metadata.labels.tier` field
 * for free.
 */
internal fun documentChanges(before: CatalogFile, after: CatalogFile): List<FieldChange> =
    buildList {
        diffInto(json.encodeToJsonElement(before), json.encodeToJsonElement(after), path = "", into = this)
    }

/** The envelope's source-reference change, as a pseudo-field beside the document's own. */
internal fun sourceUrlChange(before: String?, after: String?): FieldChange? =
    changeFor(SOURCE_URL_FIELD, before?.let(Leaf::Scalar), after?.let(Leaf::Scalar))

/** A document leaf: compared by value, reported per its own kind. */
private sealed interface Leaf {
    /** A single value, reported as from/to. */
    data class Scalar(val value: String) : Leaf

    /** A list of scalars, reported as added/removed. */
    data class Items(val values: List<String>) : Leaf

    /** Compared by [fingerprint], reported by NAME only — free text and structured lists. */
    data class Opaque(val fingerprint: String) : Leaf
}

/**
 * Walks both trees in parallel so the changes come out in DOCUMENT order — an object's keys
 * are its old ones followed by any the new side added, which keeps a freshly set field (and a
 * freshly added label) in its own section instead of at the end of the list.
 */
private fun diffInto(before: JsonElement?, after: JsonElement?, path: String, into: MutableList<FieldChange>) {
    if (before is JsonObject || after is JsonObject) {
        val keys = LinkedHashSet<String>()
        (before as? JsonObject)?.let { keys.addAll(it.keys) }
        (after as? JsonObject)?.let { keys.addAll(it.keys) }
        keys.forEach { key ->
            val child = if (path.isEmpty()) key else "$path.$key"
            diffInto((before as? JsonObject)?.get(key), (after as? JsonObject)?.get(key), child, into)
        }
        return
    }
    changeFor(path, leafOrNull(before, path), leafOrNull(after, path))?.let(into::add)
}

/** An explicit null is the same absence as a missing key — neither side has a value to report. */
private fun leafOrNull(element: JsonElement?, path: String): Leaf? =
    if (element == null || element is JsonNull) null else leaf(element, path)

private fun leaf(element: JsonElement, path: String): Leaf = when {
    path in FACT_ONLY_FIELDS -> Leaf.Opaque(element.toString())
    element is JsonPrimitive -> Leaf.Scalar(element.content)
    // A list of scalars diffs entry-wise; a list of objects (metadata.links) is withheld —
    // positional churn would read as noise, not as history.
    element is JsonArray && element.all { it is JsonPrimitive } ->
        Leaf.Items(element.map { (it as JsonPrimitive).content })
    else -> Leaf.Opaque(element.toString())
}

private fun changeFor(path: String, before: Leaf?, after: Leaf?): FieldChange? {
    if (before == after) return null
    return when {
        // Withheld by rule (free text, a structured list): name only. Checked FIRST because an
        // EMPTY array has no elements to classify and lands in [Leaf.Items] — so a links list
        // growing from none to one object pairs an Items side with an Opaque one.
        before is Leaf.Opaque || after is Leaf.Opaque -> FieldChange(path)
        before is Leaf.Items || after is Leaf.Items -> itemsChange(path, before, after)
        else -> scalarChange(path, (before as? Leaf.Scalar)?.value, (after as? Leaf.Scalar)?.value)
    }
}

private fun scalarChange(path: String, from: String?, to: String?): FieldChange {
    val tooLong = (from?.length ?: 0) > MAX_CHANGE_VALUE_LENGTH || (to?.length ?: 0) > MAX_CHANGE_VALUE_LENGTH
    return if (tooLong) FieldChange(path) else FieldChange(path, from = from, to = to)
}

private fun itemsChange(path: String, before: Leaf?, after: Leaf?): FieldChange? {
    val old = (before as? Leaf.Items)?.values.orEmpty()
    val new = (after as? Leaf.Items)?.values.orEmpty()
    val added = new.filterNot { it in old }
    val removed = old.filterNot { it in new }
    // A pure REORDER changes the document but moves no entry — nothing to tell (Lettuce's rule),
    // and a valueless "Tags." entry would only puzzle the reader.
    if (added.isEmpty() && removed.isEmpty()) return null
    val tooLong = added.sumOf { it.length + 1 } > MAX_CHANGE_VALUE_LENGTH ||
        removed.sumOf { it.length + 1 } > MAX_CHANGE_VALUE_LENGTH
    return if (tooLong) FieldChange(path) else FieldChange(path, added = added, removed = removed)
}
