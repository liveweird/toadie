package ch.nokillswit.conversion

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.specStrings
import ch.nokillswit.catalog.specLists
import ch.nokillswit.catalog.RegistrySnapshot
import ch.nokillswit.catalog.SPEC_SHAPES
import ch.nokillswit.catalog.SpecShape
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The IMPORT halves of the mapping codecs (Backstage -> Port): each function reads the Backstage
 * leaf its rule names off the document and records what the Port leaf its rule names should become
 * in an [EntityDraft]. The mirror of `Codecs.kt` (export), under the same rule — pure, and knowing
 * nothing about any ontology: which property, relation, label, tag category or spec field is in
 * play comes from the rule, never from a name in this file (`.claude/docs/conversion-mapping.md`).
 *
 * **Three verdicts per Port leaf** ([Leaf]) are what keeps a merge from wiping Port-only data:
 * the Backstage side HAS a value ([Leaf.Value] — the owned Port leaf is replaced), holds NOTHING
 * ([Leaf.Absent] — Backstage is the source of truth for the leaves the mapping covers, so the owned
 * Port leaf is removed), or holds something this engine could NOT convert ([Leaf.Kept] — an ignored
 * placeholder, a value the map does not list, a reference that did not resolve: the existing Port
 * leaf is left exactly as it is, because our own failure to convert is never evidence that the Port
 * value is wrong). Only a CLEANLY absent leaf removes anything.
 */
internal sealed interface Leaf {
    data class Value(val json: JsonElement) : Leaf

    /** A many-valued relation of which only SOME entries could be placed: add them to the stored targets, never remove any. */
    data class Union(val json: JsonArray) : Leaf

    /**
     * The `links: rest` items: merged with the stored items by `url`. Stored items the export could not have emitted
     * survive: those without a `url`, and those whose title a `link` rule ([claimed]) owns.
     */
    data class LinkItems(val items: List<JsonObject>, val claimed: Set<String> = emptySet()) : Leaf

    /**
     * The tags of the categories one tagCategory rule reads ([found], in document order) with the [covered] tag values of
     * those categories. At merge the stored value keeps everything NOT covered — an enum value no Backstage tag can
     * express — and loses a covered value only when the document lacks its tag. [scalar]: the property holds one value.
     */
    data class TagValues(val found: List<String>, val covered: Set<String>, val scalar: Boolean) : Leaf

    data object Absent : Leaf

    data object Kept : Leaf
}

/**
 * Combines two contributions to ONE Port leaf. A validated mapping has one contributor per leaf (several tagCategory
 * rules into one property excepted), so this is the defensive path: array values UNION, a value (or union, links, tags)
 * beats a keep beats an absence, and any keep next to an array makes it a [Leaf.Union] with the stored values — one
 * contributor's failure to convert never lets another's value remove what is stored.
 */
internal fun Leaf?.then(next: Leaf): Leaf {
    val first = this ?: return next
    val a = first.arrayPart()
    val b = next.arrayPart()
    return when {
        first is Leaf.TagValues && next is Leaf.TagValues ->
            Leaf.TagValues((first.found + next.found).distinct(), first.covered + next.covered, first.scalar)
        first is Leaf.TagValues && next == Leaf.Kept -> first.copy(covered = emptySet())
        first == Leaf.Kept && next is Leaf.TagValues -> next.copy(covered = emptySet())
        a != null && b != null -> {
            val merged = JsonArray((a + b).distinct())
            if (first is Leaf.Union || next is Leaf.Union) Leaf.Union(merged) else Leaf.Value(merged)
        }
        first.carriesValue() && next == Leaf.Kept -> if (a != null) Leaf.Union(JsonArray(a)) else first
        first == Leaf.Kept && next.carriesValue() -> if (b != null) Leaf.Union(JsonArray(b)) else next
        first.carriesValue() -> first
        next.carriesValue() -> next
        first == Leaf.Kept || next == Leaf.Kept -> Leaf.Kept
        else -> Leaf.Absent
    }
}

private fun Leaf.arrayPart(): List<JsonElement>? = when (this) {
    is Leaf.Value -> (json as? JsonArray)?.toList()
    is Leaf.Union -> json.toList()
    else -> null
}

private fun Leaf?.carriesValue() = this is Leaf.Value || this is Leaf.Union || this is Leaf.LinkItems || this is Leaf.TagValues

/** What a document's import rules said about the Port entity's leaves; an id appears once any rule touched it. */
internal class EntityDraft {
    var title: Leaf? = null
    var icon: Leaf? = null
    var team: Leaf? = null
    val properties = LinkedHashMap<String, Leaf>()
    val relations = LinkedHashMap<String, Leaf>()

    fun property(id: String, leaf: Leaf) {
        properties[id] = properties[id].then(leaf)
    }

    fun relation(id: String, leaf: Leaf) {
        relations[id] = relations[id].then(leaf)
    }

    fun meta(field: PortMeta, leaf: Leaf) {
        when (field) {
            PortMeta.TITLE -> title = title.then(leaf)
            PortMeta.ICON -> icon = icon.then(leaf)
            PortMeta.TEAM -> team = team.then(leaf)
        }
    }
}

/** What an entry of a Backstage reference field turned out to name on the Port side. */
internal sealed interface RefTarget {
    /** The Port entity ([identifier] as stored, or as the batch will store it) of [blueprint]. */
    data class Entity(val blueprint: String, val identifier: String) : RefTarget

    /** Not usable as a relation value; [reason] is the row finding's message. */
    data class Dropped(val reason: String) : RefTarget
}

/** The workspace-wide view the reference-shaped import codecs need. */
internal fun interface ImportRefs {
    /** Resolves [raw], an entry of `spec.<field>` of [file], to the Port entity it names. */
    fun resolve(file: CatalogFile, field: String, raw: String): RefTarget
}

/** Everything a document's import rules may read; [findings] is where they report what they dropped. */
internal class ImportContext(
    val file: CatalogFile,
    val pair: MappingPair,
    val definition: BlueprintDefinition?,
    val registry: RegistrySnapshot,
    val refs: ImportRefs,
    val findings: MutableList<ConversionFinding>,
) {
    /** Titles of the links some `link` rule of the pair claims (any direction), which `links: rest` therefore leaves alone. */
    val claimedLinkTitles: Set<String> =
        pair.rules.mapNotNull { (it.backstage as? BackstageAddress.Link)?.title }.toSet()
}

// ── source readers ──────────────────────────────────────────────────────────────────────────

/** The plain text a (non-list, non-tag, non-link) Backstage address holds on [file]; blank = no value. */
internal fun backstageText(file: CatalogFile, address: BackstageAddress): String? {
    val text = when (address) {
        is BackstageAddress.Metadata -> when (address.field) {
            MetadataField.TITLE -> file.metadata.title
            MetadataField.DESCRIPTION -> file.metadata.description
            MetadataField.NAMESPACE -> file.metadata.namespace
        }
        is BackstageAddress.Label -> file.metadata.labels[address.key]
        is BackstageAddress.Annotation -> file.metadata.annotations[address.key]
        is BackstageAddress.Profile -> file.spec.profile?.let {
            when (address.field) {
                ProfileField.DISPLAY_NAME -> it.displayName
                ProfileField.EMAIL -> it.email
                ProfileField.PICTURE -> it.picture
            }
        }
        is BackstageAddress.Spec -> specStrings(file.spec)[address.field]
        is BackstageAddress.Tags, BackstageAddress.LinksRest, is BackstageAddress.Link -> null
    }
    return text?.takeIf { it.isNotEmpty() }
}

/** The Backstage-side name of [address] for finding fields: `metadata.labels.<key>`, `spec.owner`, ... */
internal fun backstageField(address: BackstageAddress): String = when (address) {
    is BackstageAddress.Metadata -> "metadata.${address.field.wire}"
    is BackstageAddress.Label -> "metadata.labels.${address.key}"
    is BackstageAddress.Annotation -> "metadata.annotations.${address.key}"
    is BackstageAddress.Tags -> "metadata.tags"
    BackstageAddress.LinksRest -> "metadata.links"
    is BackstageAddress.Link -> "metadata.links.${address.title}"
    is BackstageAddress.Profile -> "profile.${address.field.wire}"
    is BackstageAddress.Spec -> "spec.${address.field}"
}

private fun propertyOf(rule: MappingRule): String? = (rule.port as? PortAddress.Property)?.id

private fun ImportContext.propertyType(id: String): String? = definition?.schema?.properties?.get(id)?.type

/** Records [leaf] on whatever Port leaf the rule's address names (`meta` or property); relations have their own path. */
private fun ImportContext.record(rule: MappingRule, out: EntityDraft, leaf: Leaf) {
    when (val port = rule.port) {
        is PortAddress.Meta -> out.meta(port.field, leaf)
        is PortAddress.Property -> out.property(port.id, leaf)
        else -> Unit
    }
}

private fun ImportContext.unconverted(rule: MappingRule, message: String, out: EntityDraft) {
    findings += ConversionFinding(ConversionFindingCode.VALUE_UNMAPPED, backstageField(rule.backstage), message)
    record(rule, out, Leaf.Kept)
}

// ── dispatch ────────────────────────────────────────────────────────────────────────────────

/**
 * Runs one rule's import half. `ref` rules are NOT run here: several of them may read one spec field
 * and split its entries by target blueprint, so [importRefFields] runs them together. A rule that
 * does not read on import ([MappingRule.readsOnImport]) is the caller's to skip.
 */
internal fun importRule(rule: MappingRule, ctx: ImportContext, out: EntityDraft) {
    when (val codec = rule.codec) {
        is Codec.Copy -> importCopy(rule, codec, ctx, out)
        is Codec.ValueMap -> importValueMap(rule, codec, ctx, out)
        Codec.TagCategory -> importTagCategory(rule, ctx, out)
        Codec.Link -> importLink(rule, ctx, out)
        Codec.Ref, Codec.Derive, is Codec.Constant -> Unit
    }
}

// ── copy ────────────────────────────────────────────────────────────────────────────────────

private fun importCopy(rule: MappingRule, codec: Codec.Copy, ctx: ImportContext, out: EntityDraft) {
    if (rule.backstage == BackstageAddress.LinksRest) {
        importLinksRest(rule, ctx, out)
        return
    }
    val raw = backstageText(ctx.file, rule.backstage)
    if (raw == null) {
        ctx.record(rule, out, Leaf.Absent)
        return
    }
    // An ignored value (a placeholder the export wrote) says nothing about the Port side; for a
    // namespace rule so does the value the export falls back to (`namespaceAccepted` lets both through).
    val namespaceDefault = rule.backstage == BackstageAddress.Metadata(MetadataField.NAMESPACE) && raw == codec.exportDefault
    if (raw in codec.importIgnore || namespaceDefault) {
        ctx.record(rule, out, Leaf.Kept)
        return
    }
    val prefix = codec.prefix
    if (prefix != null && !raw.startsWith(prefix)) {
        ctx.unconverted(rule, "'$raw' does not start with the rule's prefix '$prefix', so it was not converted", out)
        return
    }
    val text = if (prefix != null) raw.removePrefix(prefix) else raw
    if (text.isEmpty()) {
        ctx.record(rule, out, Leaf.Absent)
        return
    }
    val value = typedScalar(rule, text, ctx)
    if (value == null) {
        val type = ctx.propertyType(propertyOf(rule).orEmpty())
        ctx.unconverted(rule, "'$text' is not a valid $type for the property, so it was not converted", out)
        return
    }
    ctx.record(rule, out, Leaf.Value(value))
}

/** [text] as the JSON scalar the rule's Port end holds (a number for a number property, else a string); null = not parseable. */
private fun typedScalar(rule: MappingRule, text: String, ctx: ImportContext): JsonPrimitive? =
    scalarFor(propertyOf(rule)?.let { ctx.propertyType(it) }, text)

/**
 * [text] as a JSON number for a `number` property, else as a string. An integer literal stays an exact `Long`; a decimal keeps
 * its digits here, though JSON encoding (kotlinx) writes a non-integer literal through a `Double`.
 */
private fun scalarFor(type: String?, text: String): JsonPrimitive? = when (type) {
    "number" -> text.toBigDecimalOrNull()?.let { exact -> text.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(exact) }
    else -> JsonPrimitive(text)
}

/** Every `metadata.links` entry no `link` rule claims, as `{url, title?, icon?}` objects onto an array-of-objects property. */
private fun importLinksRest(rule: MappingRule, ctx: ImportContext, out: EntityDraft) {
    val id = propertyOf(rule) ?: return
    val items = ctx.file.metadata.links.filter { it.title !in ctx.claimedLinkTitles }.map { link ->
        JsonObject(
            buildMap {
                put("url", JsonPrimitive(link.url))
                link.title?.let { put("title", JsonPrimitive(it)) }
                link.icon?.let { put("icon", JsonPrimitive(it)) }
            },
        )
    }
    out.property(id, Leaf.LinkItems(items, ctx.claimedLinkTitles))
}

// ── valueMap ────────────────────────────────────────────────────────────────────────────────

private fun importValueMap(rule: MappingRule, codec: Codec.ValueMap, ctx: ImportContext, out: EntityDraft) {
    val id = propertyOf(rule) ?: return
    val raw = backstageText(ctx.file, rule.backstage)
    if (raw == null) {
        out.property(id, Leaf.Absent)
        return
    }
    val key = codec.map.entries.firstOrNull { it.value == raw }?.key
    if (key == null) {
        ctx.unconverted(rule, "'$raw' is not in the rule's value map, so it was not converted", out)
        return
    }
    val type = ctx.propertyType(id)
    val value = if (type == "boolean") JsonPrimitive(key == "true") else scalarFor(type, key)
    if (value == null) {
        ctx.unconverted(rule, "'$key' is not a valid $type for the property, so it was not converted", out)
        return
    }
    out.property(id, Leaf.Value(value))
}

// ── tagCategory ─────────────────────────────────────────────────────────────────────────────

/**
 * The tags the tag registry places in the rule's category become the property's value: all of them
 * (in document order) for an array property, the first for a scalar one. Several rules from one
 * property name several categories, so each tag lands under exactly the rule whose category holds it. The leaf is a
 * [Leaf.TagValues]: at merge the stored value keeps whatever the property's categories do not cover.
 */
private fun importTagCategory(rule: MappingRule, ctx: ImportContext, out: EntityDraft) {
    val id = propertyOf(rule) ?: return
    val category = (rule.backstage as? BackstageAddress.Tags)?.category ?: return
    val tags = ctx.file.metadata.tags.filter { ctx.registry.tags[it]?.first == category }.distinct()
    val covered = ctx.registry.tags.filterValues { it.first == category }.keys
    val scalar = ctx.propertyType(id) != "array"
    if (scalar && tags.size > 1) {
        ctx.findings += ConversionFinding(
            ConversionFindingCode.VALUE_UNMAPPED,
            backstageField(rule.backstage),
            "the Port property holds one value but the document has ${tags.size} tags of category '$category' — kept '${tags.first()}'",
        )
    }
    // What Backstage could not express (an enum value no registered tag of the property's categories names, or a tag the
    // registry does not know) is never wiped: the merge keeps every stored value outside `covered`.
    out.property(id, Leaf.TagValues(if (scalar) tags.take(1) else tags, covered, scalar))
}

// ── link ────────────────────────────────────────────────────────────────────────────────────

private fun importLink(rule: MappingRule, ctx: ImportContext, out: EntityDraft) {
    val id = propertyOf(rule) ?: return
    val title = (rule.backstage as? BackstageAddress.Link)?.title ?: return
    val link = ctx.file.metadata.links.firstOrNull { it.title == title }
    out.property(id, link?.url?.takeIf { it.isNotEmpty() }?.let { Leaf.Value(JsonPrimitive(it)) } ?: Leaf.Absent)
}

// ── ref ─────────────────────────────────────────────────────────────────────────────────────

/** The entries a document holds on reference spec field [field]: one for a single reference, any number for a list. */
private fun referenceEntries(file: CatalogFile, field: String): List<String> =
    if (SPEC_SHAPES[field] == SpecShape.REF) listOfNotNull(specStrings(file.spec)[field]) else specLists(file.spec)[field].orEmpty()

/**
 * Runs every import-reading `ref` rule of the pair. The rules reading ONE spec field PARTITION its
 * entries by the blueprint of the entity each names: an entry goes to the first rule (document
 * order) whose relation targets that blueprint (`{"meta":"team"}` targets `_team`), so
 * `dependsOn` can feed `depends_on`, `uses` and `uses_libraries` according to what each entry IS.
 * An entry that does not resolve, or that no rule's target takes, is dropped with a finding
 * ([ConversionFindingCode.OWNER_DROPPED] when a team rule reads the field, otherwise
 * [ConversionFindingCode.REFERENCE_DROPPED]), so the strict entity import that follows never meets
 * a relation to an entity that does not exist. A rule left with nothing is [Leaf.Absent] when every
 * entry of its field was usable, but [Leaf.Kept] when some entry was dropped — a reference we
 * could not place is never a reason to remove Port data.
 */
internal fun importRefFields(ctx: ImportContext, out: EntityDraft) {
    val rules = ctx.pair.rules.filter { it.readsOnImport && it.codec == Codec.Ref }
    rules.mapNotNull { (it.backstage as? BackstageAddress.Spec)?.field }.distinct().forEach { field ->
        importRefField(field, rules.filter { (it.backstage as? BackstageAddress.Spec)?.field == field }, ctx, out)
    }
}

private fun importRefField(field: String, rules: List<MappingRule>, ctx: ImportContext, out: EntityDraft) {
    val targets = rules.map { refTargetOf(it, ctx.definition) }
    // Two rules with one target would send every entry to the first (the validator blocks it, AMBIGUOUS_REF_PARTITION);
    // defensively the later ones then leave their Port leaf alone rather than empty it.
    val shadowed = rules.indices.map { targets[it] == null || targets.indexOf(targets[it]) != it }
    val teamField = rules.any { (it.port as? PortAddress.Meta)?.field == PortMeta.TEAM }
    val dropCode = if (teamField) ConversionFindingCode.OWNER_DROPPED else ConversionFindingCode.REFERENCE_DROPPED
    val picks = rules.map { mutableListOf<String>() }
    var dropped = false
    fun drop(raw: String, reason: String) {
        dropped = true
        ctx.findings += ConversionFinding(dropCode, "spec.$field", "'$raw': $reason")
    }
    referenceEntries(ctx.file, field).forEach { raw ->
        when (val target = ctx.refs.resolve(ctx.file, field, raw)) {
            is RefTarget.Dropped -> drop(raw, target.reason)
            is RefTarget.Entity -> {
                val index = targets.indexOf(target.blueprint)
                when {
                    index < 0 -> drop(raw, "no rule of the mapping reads an entity of blueprint '${target.blueprint}' from spec.$field")
                    target.identifier in picks[index] -> Unit
                    else -> picks[index] += target.identifier
                }
            }
        }
    }
    rules.forEachIndexed { index, rule ->
        val chosen = picks[index]
        val leaf = when {
            shadowed[index] -> Leaf.Kept
            chosen.isEmpty() -> if (dropped) Leaf.Kept else Leaf.Absent
            else -> refLeaf(rule, chosen, ctx, field, dropped)
        }
        when (val port = rule.port) {
            is PortAddress.Relation -> out.relation(port.id, leaf)
            is PortAddress.Meta -> out.meta(port.field, leaf)
            else -> Unit
        }
    }
}

/**
 * The leaf for [chosen] identifiers: an array for a many-valued relation — a [Leaf.Union] with the stored
 * targets when some entry of the field was [dropped] (a target we failed to place is never removed) —
 * otherwise the first (extras are reported).
 */
private fun refLeaf(rule: MappingRule, chosen: List<String>, ctx: ImportContext, field: String, dropped: Boolean): Leaf {
    val relation = (rule.port as? PortAddress.Relation)?.let { ctx.definition?.relations?.get(it.id) }
    if (relation?.many == true) {
        val array = JsonArray(chosen.map { JsonPrimitive(it) })
        return if (dropped) Leaf.Union(array) else Leaf.Value(array)
    }
    if (chosen.size > 1) {
        val team = (rule.port as? PortAddress.Meta)?.field == PortMeta.TEAM
        ctx.findings += ConversionFinding(
            if (team) ConversionFindingCode.OWNER_DROPPED else ConversionFindingCode.REFERENCE_DROPPED,
            "spec.$field",
            "the Port side holds one entity here but spec.$field names ${chosen.size} — kept '${chosen.first()}', dropped " +
                chosen.drop(1).joinToString { "'$it'" },
        )
    }
    return Leaf.Value(JsonPrimitive(chosen.first()))
}
