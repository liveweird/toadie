package ch.nokillswit.catalog

import ch.nokillswit.infra.db.FieldChange
import ch.nokillswit.infra.db.Leaf
import ch.nokillswit.infra.db.changeFor
import ch.nokillswit.infra.db.encodeChanges
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
 * The document paths whose VALUE never rides an event, however short it happens to be: the two
 * free-text fields. Lettuce's rule ("no free text in params") kept as a value rule — the fact
 * of the change is recorded, its content is not. `metadata.links` needs no entry: an array of
 * objects is withheld structurally (see [leaf]).
 */
private val FACT_ONLY_FIELDS = setOf("metadata.description", "spec.definition")

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
