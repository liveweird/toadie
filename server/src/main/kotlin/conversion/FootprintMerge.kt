package ch.nokillswit.conversion

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.CatalogLink
import ch.nokillswit.catalog.EntityProfile
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.catalog.RegistrySnapshot
import ch.nokillswit.catalog.SPEC_SHAPES
import ch.nokillswit.catalog.SpecShape
import ch.nokillswit.catalog.specLists
import ch.nokillswit.catalog.specOf
import ch.nokillswit.catalog.specStrings

/**
 * What a pair OWNS on a Backstage document — the leaves its export-writing rules produce, derived
 * from the rules and nothing else. A footprint merge replaces exactly these leaves of a stored
 * catalog file with the freshly exported ones and keeps every other byte, so converting Port ->
 * Backstage over a file someone also edited in Backstage (extra labels, hand-added tags, links,
 * a `template:` dependency) does not wipe their work. "Owned" is per leaf: a label by key, an
 * annotation by key, tags by tag CATEGORY, links by title (every link when a `links: rest` rule
 * exists), a reference list by the BLUEPRINT its entries resolve to.
 */
internal class Footprint(
    val metadata: Set<MetadataField>,
    val profile: Set<ProfileField>,
    /** Text and single-reference spec fields. */
    val specFields: Set<String>,
    /** Reference-list spec fields -> the blueprints whose entities the owning rules reference. */
    val listFields: Map<String, Set<String>>,
    val labelKeys: Set<String>,
    val annotationKeys: Set<String>,
    val tagCategories: Set<String>,
    val linkTitles: Set<String>,
    val ownsAllLinks: Boolean,
)

internal fun footprintOf(pair: MappingPair, definition: BlueprintDefinition?): Footprint {
    val rules = pair.rules.filter { it.writesOnExport }
    val addresses = rules.map { it.backstage }
    val listFields = mutableMapOf<String, MutableSet<String>>()
    val specFields = mutableSetOf<String>()
    rules.forEach { rule ->
        val field = (rule.backstage as? BackstageAddress.Spec)?.field ?: return@forEach
        if (SPEC_SHAPES[field] == SpecShape.REF_LIST) {
            val targets = listFields.getOrPut(field) { mutableSetOf() }
            referencedBlueprint(rule, definition)?.let { targets += it }
        } else {
            specFields += field
        }
    }
    return Footprint(
        metadata = addresses.filterIsInstance<BackstageAddress.Metadata>().map { it.field }.toSet(),
        profile = addresses.filterIsInstance<BackstageAddress.Profile>().map { it.field }.toSet(),
        specFields = specFields,
        listFields = listFields,
        labelKeys = addresses.filterIsInstance<BackstageAddress.Label>().map { it.key }.toSet(),
        annotationKeys = addresses.filterIsInstance<BackstageAddress.Annotation>().map { it.key }.toSet(),
        tagCategories = addresses.filterIsInstance<BackstageAddress.Tags>().map { it.category }.toSet(),
        linkTitles = addresses.filterIsInstance<BackstageAddress.Link>().map { it.title }.toSet(),
        ownsAllLinks = addresses.any { it == BackstageAddress.LinksRest },
    )
}

/** The blueprint a reference-writing rule points at: the relation's target, `_team` for the team field, the reverse blueprint. */
private fun referencedBlueprint(rule: MappingRule, definition: BlueprintDefinition?): String? = when (val port = rule.port) {
    is PortAddress.Relation -> definition?.relations?.get(port.id)?.target
    is PortAddress.Meta -> if (port.field == PortMeta.TEAM) SYSTEM_TEAM_BLUEPRINT else null
    is PortAddress.Reverse -> port.blueprint
    else -> null
}

/**
 * Overlays [generated] (the pair's fresh export) onto [existing] (the stored file of the same
 * identity): every leaf in [footprint] comes from [generated], everything else from [existing].
 * [blueprintOfReference] classifies one of [existing]'s reference-list entries by the blueprint of
 * the Port entity it resolves to (null = resolves to no converted entity, e.g. a `template:` ref or
 * a dangling one), which is how a foreign entry survives while an owned one is re-derived.
 */
internal fun mergeFootprint(
    footprint: Footprint,
    generated: CatalogFile,
    existing: CatalogFile,
    registry: RegistrySnapshot,
    blueprintOfReference: (field: String, reference: String) -> String?,
): CatalogFile {
    val gm = generated.metadata
    val em = existing.metadata
    val ownedLink = { link: CatalogLink -> footprint.ownsAllLinks || link.title in footprint.linkTitles }
    val metadata = CatalogFileMetadata(
        name = gm.name,
        namespace = gm.namespace,
        title = if (MetadataField.TITLE in footprint.metadata) gm.title else em.title,
        description = if (MetadataField.DESCRIPTION in footprint.metadata) gm.description else em.description,
        labels = em.labels.filterKeys { it !in footprint.labelKeys } + gm.labels,
        annotations = em.annotations.filterKeys { it !in footprint.annotationKeys } + gm.annotations,
        tags = (em.tags.filter { registry.tags[it]?.first !in footprint.tagCategories } + gm.tags).distinct(),
        links = em.links.filterNot(ownedLink) + gm.links,
    )
    return CatalogFile(
        kind = generated.kind,
        metadata = metadata,
        spec = mergeSpec(footprint, generated, existing, blueprintOfReference),
    )
}

private fun mergeSpec(
    footprint: Footprint,
    generated: CatalogFile,
    existing: CatalogFile,
    blueprintOfReference: (field: String, reference: String) -> String?,
): EntitySpec {
    val genStrings = specStrings(generated.spec)
    val genLists = specLists(generated.spec)
    val strings = specStrings(existing.spec).filterKeys { it !in footprint.specFields } +
        genStrings.filterKeys { it in footprint.specFields }
    val lists = specLists(existing.spec).toMutableMap()
    footprint.listFields.forEach { (field, ownedBlueprints) ->
        val kept = lists[field].orEmpty().filter { blueprintOfReference(field, it) !in ownedBlueprints }
        lists[field] = (kept + genLists[field].orEmpty()).distinct()
    }
    val gp = generated.spec.profile
    val ep = existing.spec.profile
    val profile = EntityProfile(
        displayName = if (ProfileField.DISPLAY_NAME in footprint.profile) gp?.displayName else ep?.displayName,
        email = if (ProfileField.EMAIL in footprint.profile) gp?.email else ep?.email,
        picture = if (ProfileField.PICTURE in footprint.profile) gp?.picture else ep?.picture,
    ).takeIf { it.displayName != null || it.email != null || it.picture != null }
    return specOf(strings, lists, profile)
}
