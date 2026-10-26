package ch.nokillswit.conversion

import ch.nokillswit.entities.StoredEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a pair OWNS on a Port entity when it is IMPORTED — the Port leaves its import-reading rules
 * ([MappingRule.readsOnImport]) write, derived from the rules and nothing else. The mirror of the
 * export's `Footprint`, and the guard against the dangerous bug of this phase: wiping Port-only
 * data. Everything OUTSIDE the footprint of a stored entity survives a merge untouched — properties
 * and relations no rule reads (the pair's `lossy` declarations, and any field added to the
 * blueprint after the mapping was written), the icon when no rule reads it, the title when none
 * does, the whole `team` when no team rule reads it — and everything INSIDE it is settled leaf by
 * leaf by the [Leaf] verdicts ([mergeEntity]).
 */
internal class ImportFootprint(
    val icon: Boolean,
    val team: Boolean,
    val properties: Set<String>,
    val relations: Set<String>,
)

internal fun importFootprintOf(pair: MappingPair): ImportFootprint {
    val ports = pair.rules.filter { it.readsOnImport }.mapNotNull { it.port }
    val meta = ports.filterIsInstance<PortAddress.Meta>().map { it.field }.toSet()
    return ImportFootprint(
        icon = PortMeta.ICON in meta,
        team = PortMeta.TEAM in meta,
        properties = ports.filterIsInstance<PortAddress.Property>().map { it.id }.toSet(),
        relations = ports.filterIsInstance<PortAddress.Relation>().map { it.id }.toSet(),
    )
}

/** The converted entity before it becomes the wire document. */
internal class ConvertedEntity(
    val title: String,
    val icon: String?,
    val team: JsonElement?,
    val properties: JsonObject,
    val relations: JsonObject,
)

/**
 * Settles the [draft] of a document against the [existing] stored entity of the same
 * (blueprint, identifier) — null for a bare conversion (no merge, or no such entity).
 *
 * Per owned leaf: [Leaf.Value] replaces, [Leaf.Absent] removes, [Leaf.Kept] leaves the stored leaf
 * (or nothing, when there is none), [Leaf.Union] adds to a stored many-valued relation and
 * [Leaf.LinkItems] merges link items by `url`. Per unowned leaf: the stored one survives. `team`
 * is the one leaf with positional semantics: the converted owner stands for the FIRST stored team
 * (a multi-team entity exports its first team as the owner) — equal to it, the stored team is kept
 * as it is; different, it replaces ONLY that first entry and the extra teams stay.
 *
 * Read-then-write caveat: the stored entity was read when the conversion ran, and nothing guards the
 * later import against a concurrent Port-only edit made in between (the entity import has no
 * expected-revision guard) — a caller must convert immediately before importing.
 */
internal fun mergeEntity(
    draft: EntityDraft,
    existing: StoredEntity?,
    footprint: ImportFootprint,
    identifier: String,
    /** Whether the Port entity a stored relation value names still exists — a [Leaf.Union] drops stored targets that do not. */
    relationTargetExists: (relation: String, identifier: String) -> Boolean = { _, _ -> true },
): ConvertedEntity {
    val title = (draft.title as? Leaf.Value)?.json?.let { (it as JsonPrimitive).content } ?: existing?.title ?: identifier
    val storedIcon = existing?.icon
    val icon = if (footprint.icon) leafText(draft.icon, storedIcon) else storedIcon
    val team = if (footprint.team) mergedTeam(draft.team, existing?.team) else existing?.team
    return ConvertedEntity(
        title = title,
        icon = icon,
        team = team,
        properties = mergeLeaves(existing?.properties, draft.properties, footprint.properties) { _, _ -> true },
        relations = mergeLeaves(existing?.relations, draft.relations, footprint.relations, relationTargetExists),
    )
}

private fun leafText(leaf: Leaf?, stored: String?): String? = when (leaf) {
    is Leaf.Value -> (leaf.json as JsonPrimitive).content
    Leaf.Absent -> null
    else -> stored
}

private fun mergedTeam(leaf: Leaf?, stored: JsonElement?): JsonElement? = when (leaf) {
    is Leaf.Value -> replacedOwner(leaf.json, stored)
    Leaf.Absent -> null
    else -> stored
}

private fun replacedOwner(owner: JsonElement, stored: JsonElement?): JsonElement = when {
    stored is JsonArray && stored.isNotEmpty() ->
        if (stored.first() == owner) stored else JsonArray(listOf(owner) + stored.drop(1).filter { it != owner })
    else -> owner
}

private fun mergeLeaves(
    stored: JsonObject?,
    drafted: Map<String, Leaf>,
    owned: Set<String>,
    exists: (id: String, identifier: String) -> Boolean,
): JsonObject {
    val merged = LinkedHashMap<String, JsonElement>(stored.orEmpty())
    owned.forEach { id ->
        when (val leaf = drafted[id]) {
            is Leaf.Value -> merged[id] = leaf.json
            is Leaf.Union -> merged[id] = unionOf(stored?.get(id), leaf.json) { exists(id, it) }
            is Leaf.LinkItems -> mergeLinks(stored?.get(id), leaf)?.let { merged[id] = it } ?: merged.remove(id)
            is Leaf.TagValues -> mergeTags(stored?.get(id), leaf)?.let { merged[id] = it } ?: merged.remove(id)
            Leaf.Absent -> merged.remove(id)
            Leaf.Kept, null -> Unit
        }
    }
    return JsonObject(merged)
}

/**
 * The stored targets that still exist, in their order, then the new ones: nothing existing is removed, nothing already
 * stored is reordered. A stored target whose entity no longer exists is dropped — the strict import would refuse it anyway
 * (`RELATION_TARGET_MISSING`), and keeping it would make an otherwise good row INVALID.
 */
private fun unionOf(stored: JsonElement?, added: JsonArray, exists: (String) -> Boolean): JsonArray {
    val kept = when (stored) {
        is JsonArray -> stored.toList()
        null -> emptyList()
        else -> listOf(stored)
    }.filter { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(exists) ?: true }
    return JsonArray(kept + added.filter { it !in kept })
}

/**
 * The tag-backed property: the found values, plus every stored value the property's categories do not cover (a value no
 * Backstage tag can express) — stored order first, new values appended. A covered stored value survives only when its tag
 * is in the document. Null = nothing left, so the property goes.
 */
private fun mergeTags(stored: JsonElement?, leaf: Leaf.TagValues): JsonElement? {
    fun text(element: JsonElement) = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (leaf.scalar) {
        leaf.found.firstOrNull()?.let { return JsonPrimitive(it) }
        return stored?.takeIf { text(it) == null || text(it) !in leaf.covered }
    }
    val storedValues = when (stored) {
        is JsonArray -> stored.toList()
        null -> emptyList()
        else -> listOf(stored)
    }
    val kept = storedValues.filter { text(it) == null || text(it) !in leaf.covered || text(it) in leaf.found }
    val keptTexts = kept.mapNotNull { text(it) }.toSet()
    val result = kept + leaf.found.filter { it !in keptTexts }.map { JsonPrimitive(it) }
    return result.takeIf { it.isNotEmpty() }?.let { JsonArray(it) }
}

/**
 * The `links: rest` property: each Backstage link keeps the extra keys of the stored item with the same `url` (the
 * Backstage link carries only url, title and icon; a Port item may hold more) — items sharing a `url` pair up in order,
 * so none is lost to a map — and a stored item the export could not have emitted survives: one without a usable `url`,
 * or one whose title a `link` rule owns (the export skips those). Null = nothing left, so the property goes.
 */
private fun mergeLinks(stored: JsonElement?, leaf: Leaf.LinkItems): JsonArray? {
    val items = (stored as? JsonArray).orEmpty()
    fun urlOf(item: JsonElement) =
        ((item as? JsonObject)?.get("url") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    fun titleOf(item: JsonElement) = ((item as? JsonObject)?.get("title") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val unemittable = items.filter { urlOf(it) == null || titleOf(it)?.let { title -> title in leaf.claimed } == true }
    val byUrl = HashMap<String, ArrayDeque<JsonObject>>()
    items.filter { it !in unemittable }.forEach { byUrl.getOrPut(urlOf(it)!!) { ArrayDeque() }.addLast(it as JsonObject) }
    val merged = leaf.items.map { link -> byUrl[urlOf(link)]?.removeFirstOrNull()?.let { JsonObject(it + link) } ?: link }
    return (merged + unemittable).takeIf { it.isNotEmpty() }?.let { JsonArray(it) }
}
