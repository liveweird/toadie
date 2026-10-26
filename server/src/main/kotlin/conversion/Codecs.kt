package ch.nokillswit.conversion

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.catalog.CatalogLink
import ch.nokillswit.catalog.RegistrySnapshot
import ch.nokillswit.entities.ExportEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The EXPORT halves of the mapping codecs (Port -> Backstage): each function reads the leaf its rule
 * names on the Port entity and writes the leaf the rule names into a [DocBuilder]. They are pure and
 * know nothing about any ontology — which property, relation, label, tag category or spec field is in
 * play comes from the rule, never from a name in this file (`.claude/docs/conversion-mapping.md`).
 * The Backstage -> Port halves are in `CodecsImport.kt`.
 */

/** What a reference to another entity resolves to while a document is built. */
internal sealed interface RefOutcome {
    /** The rendered `kind:namespace/name`, from the TARGET's own converted identity. */
    data class Rendered(val reference: String) : RefOutcome

    /** The target's blueprint has no pair in the mapping. */
    data object Unmapped : RefOutcome

    /** The target's blueprint is mapped but no such entity exists. */
    data object Missing : RefOutcome

    /** The target exists but cannot be written as a Backstage entity ([reason]: invalid name or namespace, or an identity clash). */
    data class Invalid(val reason: String) : RefOutcome
}

/** The workspace-wide view the reference-shaped codecs need: converted identities and reverse relations. */
internal interface ExportLookup {
    fun reference(blueprint: String, identifier: String): RefOutcome

    /** The entities of [sourceBlueprint] whose [relation] names [targetIdentifier], in entity-id order. */
    fun referrers(sourceBlueprint: String, relation: String, targetIdentifier: String): List<ExportEntity>
}

/** Everything a rule's export half may read; [findings] is where it reports what it dropped. */
internal class ExportContext(
    val entity: ExportEntity,
    val definition: BlueprintDefinition?,
    val registry: RegistrySnapshot,
    val lookup: ExportLookup,
    val findings: MutableList<ConversionFinding>,
    /** Titles of the links some `link` rule owns: a `links: rest` item with one is not exported (the import reads links by title). */
    val claimedLinkTitles: Set<String> = emptySet(),
)

/** Runs one rule's export half. A rule whose direction is import-only is the caller's to skip. */
internal fun exportRule(rule: MappingRule, ctx: ExportContext, out: DocBuilder) {
    when (val codec = rule.codec) {
        is Codec.Copy -> exportCopy(rule, codec, ctx, out)
        is Codec.ValueMap -> exportValueMap(rule, codec, ctx, out)
        Codec.TagCategory -> exportTagCategory(rule, ctx, out)
        Codec.Ref -> exportRef(rule, ctx, out)
        Codec.Derive -> exportDerive(rule, ctx, out)
        Codec.Link -> exportLink(rule, ctx, out)
        is Codec.Constant -> out.putText(rule.backstage, codec.value)
    }
}

// ── value readers ───────────────────────────────────────────────────────────────────────────

/** A scalar's text; a JSON null, an array/object or an empty string is "no value". */
private fun scalarText(value: JsonElement?): String? =
    (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() }

private fun propertyValue(ctx: ExportContext, id: String): JsonElement? = ctx.entity.properties[id]?.takeIf { it !is JsonNull }

/** The identifiers a relation value names: a string, or an array of strings; null/absent names none. */
internal fun relationTargets(value: JsonElement?): List<String> = when (value) {
    is JsonPrimitive -> listOfNotNull(scalarText(value))
    is JsonArray -> value.mapNotNull { scalarText(it) }
    else -> emptyList()
}

private fun propertyOf(rule: MappingRule): String? = (rule.port as? PortAddress.Property)?.id

// ── copy ────────────────────────────────────────────────────────────────────────────────────

private fun exportCopy(rule: MappingRule, codec: Codec.Copy, ctx: ExportContext, out: DocBuilder) {
    val value: JsonElement? = when (val port = rule.port) {
        is PortAddress.Meta -> when (port.field) {
            PortMeta.TITLE -> JsonPrimitive(ctx.entity.title)
            PortMeta.ICON -> ctx.entity.icon?.let { JsonPrimitive(it) }
            PortMeta.TEAM -> null
        }
        is PortAddress.Property -> propertyValue(ctx, port.id)
        else -> null
    }
    if (rule.backstage == BackstageAddress.LinksRest) {
        (value as? JsonArray)?.forEach { item ->
            linkOf(item)?.takeIf { it.title !in ctx.claimedLinkTitles }?.let { out.links += it }
        }
        return
    }
    val present = scalarText(value)
    // exportDefault stands in for an ABSENT value verbatim (no prefix: it is already the Backstage-side text).
    val text = if (present != null) codec.prefix.orEmpty() + present else codec.exportDefault ?: return
    out.putText(rule.backstage, text)
}

/** One `links`-property item (`{url, title?, icon?}`) as a catalog link; an item without a string `url` is skipped. */
private fun linkOf(item: JsonElement): CatalogLink? {
    val obj = item as? JsonObject ?: return null
    val url = scalarText(obj["url"]) ?: return null
    return CatalogLink(url = url, title = scalarText(obj["title"]), icon = scalarText(obj["icon"]))
}

// ── valueMap ────────────────────────────────────────────────────────────────────────────────

private fun exportValueMap(rule: MappingRule, codec: Codec.ValueMap, ctx: ExportContext, out: DocBuilder) {
    val id = propertyOf(rule) ?: return
    val key = scalarText(propertyValue(ctx, id)) ?: return
    val mapped = codec.map[key]
    if (mapped == null) {
        ctx.findings += ConversionFinding(
            ConversionFindingCode.VALUE_UNMAPPED,
            "properties.$id",
            "'$key' is not in the rule's value map, so the property was not converted",
        )
        return
    }
    out.putText(rule.backstage, mapped)
}

// ── tagCategory ─────────────────────────────────────────────────────────────────────────────

/**
 * A scalar enum value (one tag) or an array of them (several): a value becomes a tag when the tag
 * registry places it in the category the rule names. Several rules from one property name several
 * categories, so each value lands under exactly the rule whose category holds it.
 */
private fun exportTagCategory(rule: MappingRule, ctx: ExportContext, out: DocBuilder) {
    val id = propertyOf(rule) ?: return
    val category = (rule.backstage as? BackstageAddress.Tags)?.category ?: return
    val values = when (val value = propertyValue(ctx, id)) {
        is JsonArray -> value.mapNotNull { scalarText(it) }
        else -> listOfNotNull(scalarText(value))
    }
    values.filter { ctx.registry.tags[it]?.first == category }.forEach { out.tags += it }
}

// ── ref / derive ────────────────────────────────────────────────────────────────────────────

private fun exportRef(rule: MappingRule, ctx: ExportContext, out: DocBuilder) {
    val field = (rule.backstage as? BackstageAddress.Spec)?.field ?: return
    val refs = when (val port = rule.port) {
        is PortAddress.Relation -> {
            val target = ctx.definition?.relations?.get(port.id)?.target ?: return
            relationTargets(ctx.entity.relations[port.id]).mapNotNull { resolve(ctx, target, it, "relations.${port.id}") }
        }
        is PortAddress.Meta -> if (port.field == PortMeta.TEAM) teamRefs(ctx, field) else return
        else -> return
    }
    out.putRefs(field, refs)
}

/** The `{"meta":"team"}` source: the entity's effective team onto an owner-shaped field; several teams keep the first and report it. */
private fun teamRefs(ctx: ExportContext, field: String): List<String> {
    val teams = ctx.entity.team
    if (teams.size > 1) {
        ctx.findings += ConversionFinding(
            ConversionFindingCode.OWNER_TRUNCATED,
            "team",
            "spec.$field holds one reference but the entity has ${teams.size} teams — kept '${teams.first()}', dropped " +
                teams.drop(1).joinToString { "'$it'" },
        )
    }
    return teams.take(1).mapNotNull { resolve(ctx, SYSTEM_TEAM_BLUEPRINT, it, "team") }
}

private fun exportDerive(rule: MappingRule, ctx: ExportContext, out: DocBuilder) {
    val field = (rule.backstage as? BackstageAddress.Spec)?.field ?: return
    val reverse = rule.port as? PortAddress.Reverse ?: return
    val refs = ctx.lookup.referrers(reverse.blueprint, reverse.relation, ctx.entity.identifier)
        .mapNotNull { resolve(ctx, it.blueprint, it.identifier, "relations.${reverse.relation}") }
    out.putRefs(field, refs)
}

/** Renders the reference to ([blueprint], [identifier]), or reports why it cannot be rendered and answers null. */
private fun resolve(ctx: ExportContext, blueprint: String, identifier: String, field: String): String? =
    when (val outcome = ctx.lookup.reference(blueprint, identifier)) {
        is RefOutcome.Rendered -> outcome.reference
        RefOutcome.Unmapped -> {
            ctx.findings += ConversionFinding(
                ConversionFindingCode.TARGET_UNMAPPED,
                field,
                "'$identifier' is an entity of blueprint '$blueprint', which has no pair in the mapping — the reference was dropped",
            )
            null
        }
        is RefOutcome.Invalid -> {
            ctx.findings += ConversionFinding(
                ConversionFindingCode.TARGET_INVALID,
                field,
                "'$identifier' ($blueprint) cannot be written as a Backstage entity — ${outcome.reason} — the reference was dropped",
            )
            null
        }
        RefOutcome.Missing -> {
            ctx.findings += ConversionFinding(
                ConversionFindingCode.TARGET_MISSING,
                field,
                "no active '$blueprint' entity '$identifier' exists — the reference was dropped",
            )
            null
        }
    }

// ── link ────────────────────────────────────────────────────────────────────────────────────

private fun exportLink(rule: MappingRule, ctx: ExportContext, out: DocBuilder) {
    val id = propertyOf(rule) ?: return
    val link = rule.backstage as? BackstageAddress.Link ?: return
    val url = scalarText(propertyValue(ctx, id)) ?: return
    out.links += CatalogLink(url = url, title = link.title, icon = link.icon)
}
