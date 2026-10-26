package ch.nokillswit.conversion

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogSource
import ch.nokillswit.catalog.EntityIdentity
import ch.nokillswit.catalog.RegistrySnapshot
import ch.nokillswit.catalog.REF_FIELD_DEFAULT_KINDS
import ch.nokillswit.catalog.identityOf
import ch.nokillswit.catalog.resolveTarget
import ch.nokillswit.catalog.sanitizedCatalogFile
import ch.nokillswit.catalog.validateNamePart
import ch.nokillswit.catalog.validateCatalogFile
import ch.nokillswit.entities.ExportEntity
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull

/**
 * The Port -> Backstage export, PURE: a function of the mapping, the blueprints, the registries and
 * one snapshot of entities (and, to merge, of the stored catalog files) — no reads, no writes, no
 * locks, no audit. The caller (`ConversionExportService`) does the admitted reads.
 *
 * One pass over the workspace snapshot:
 *  1. **identities** — every entity of a mapped blueprint, selected or not, gets `(kind, namespace, name)`:
 *     the pair's kind, the entity identifier, and the namespace its `metadata.namespace` rule yields (else
 *     the workspace default). An identity the catalog would refuse — an invalid `metadata.name`, a namespace
 *     outside the namespaces dictionary, or one shared by several entities — is checked HERE, over the whole
 *     snapshot and whatever the filter: its own row is INVALID and every reference to it is dropped with a
 *     `TARGET_INVALID` finding, so one bad entity never poisons its referrers;
 *  2. **documents** — every SELECTED entity runs its pair's export-writing rules ([exportRule]) into a
 *     [DocBuilder]; references render from the TARGET's converted identity, derive rules read the whole
 *     snapshot (a filter never drops a reverse edge);
 *  3. **footprint merge** — over the stored file of the same identity, when one was supplied;
 *  4. **validation** — the catalog's own sanitizer and validator; a refused document is an INVALID row
 *     and is left out of `files`, so `files` posts to `/files/import` verbatim.
 */
internal data class ExportInput(
    val mapping: MappingDocument,
    val blueprints: Map<String, BlueprintDefinition>,
    val registry: RegistrySnapshot,
    /** The namespace a pair with no `metadata.namespace` rule (or an entity the rule yields nothing for) lands in. */
    val defaultNamespace: String,
    /** Every entity the conversion may look at: the selected ones (`shown`) and the context rows. Id order. */
    val entities: List<ExportEntity>,
    /** The stored catalog files to merge over; null = do not merge. */
    val existing: List<CatalogSource>?,
)

internal fun exportCatalogDocuments(input: ExportInput): CatalogDocumentsResponse = ExportRun(input).run()

/** The Backstage identities the selected entities would be written to — what a merge needs to read from the stored catalog. */
internal fun exportedIdentities(input: ExportInput): Set<EntityIdentity> = ExportRun(input).shownIdentities()

/** A converted entity's Backstage identity; [reference] is how another document names it. */
private data class ConvertedIdentity(val kind: String, val namespace: String, val name: String) {
    val reference: String get() = "${kind.lowercase()}:$namespace/$name"
    val key: EntityIdentity get() = EntityIdentity(kind.lowercase(), namespace.lowercase(), name.lowercase())
}

/**
 * One entity's identity with what was learned computing it: [findings] from its namespace rules, and
 * [problem] when the identity cannot be written (then the row is INVALID and references to it are dropped).
 */
private class IdentityEntry(val converted: ConvertedIdentity, val findings: List<ConversionFinding>, val problem: ConversionFinding?)

private data class EntityKey(val blueprint: String, val identifier: String)

/** Index key of the reverse-relation lookup: entities of [sourceBlueprint] whose [relation] names [targetIdentifier]. */
private data class Referrers(val sourceBlueprint: String, val relation: String, val targetIdentifier: String)

private class ExportRun(private val input: ExportInput) : ExportLookup {
    private val pairs: Map<String, MappingPair> = input.mapping.pairs.associateBy { it.blueprint }

    /** Identity of every entity of a mapped blueprint, shown or not, with collisions marked across the whole snapshot. */
    private val identities: Map<EntityKey, IdentityEntry> = computeIdentities()

    /** Blueprint of the converted entity holding a (lowercased) Backstage identity — classifies stored references on merge. */
    private val blueprintByIdentity: Map<EntityIdentity, String> = identities.entries
        .filter { it.value.problem == null }
        .associate { (key, entry) -> entry.converted.key to key.blueprint }

    /** (source blueprint, relation, target identifier) -> the entities naming it, in snapshot (id) order. */
    private val referrerIndex: Map<Referrers, List<ExportEntity>> = buildMap<Referrers, MutableList<ExportEntity>> {
        input.entities.forEach { entity ->
            input.blueprints[entity.blueprint]?.relations?.keys?.forEach { relation ->
                relationTargets(entity.relations[relation]).forEach { target ->
                    getOrPut(Referrers(entity.blueprint, relation, target)) { mutableListOf() } += entity
                }
            }
        }
    }

    private val existingByIdentity: Map<EntityIdentity, CatalogSource> =
        input.existing.orEmpty().associateBy { identityOf(it.file) }

    override fun reference(blueprint: String, identifier: String): RefOutcome {
        if (blueprint !in pairs) return RefOutcome.Unmapped
        val entry = identities[EntityKey(blueprint, identifier)] ?: return RefOutcome.Missing
        return entry.problem?.let { RefOutcome.Invalid(it.message) } ?: RefOutcome.Rendered(entry.converted.reference)
    }

    override fun referrers(sourceBlueprint: String, relation: String, targetIdentifier: String): List<ExportEntity> =
        referrerIndex[Referrers(sourceBlueprint, relation, targetIdentifier)].orEmpty()

    private fun computeIdentities(): Map<EntityKey, IdentityEntry> {
        val entries = input.entities.mapNotNull { entity ->
            pairs[entity.blueprint]?.let { EntityKey(entity.blueprint, entity.identifier) to identityEntry(entity, it) }
        }.toMap()
        val colliding = entries.entries.filter { it.value.problem == null }.groupBy { it.value.converted.key }.filterValues { it.size > 1 }
        val collisions = colliding.values.flatten().associate { (key, entry) ->
            val others = colliding.getValue(entry.converted.key).map { "${it.key.blueprint}/${it.key.identifier}" }.sorted()
            key to ConversionFinding(
                ConversionFindingCode.IDENTITY_CONFLICT,
                "",
                "${others.size} entities convert to ${entry.converted.reference}: ${others.joinToString()}",
            )
        }
        return entries.mapValues { (key, entry) -> collisions[key]?.let { IdentityEntry(entry.converted, entry.findings, it) } ?: entry }
    }

    private fun identityEntry(entity: ExportEntity, pair: MappingPair): IdentityEntry {
        val scratch = DocBuilder()
        val findings = mutableListOf<ConversionFinding>()
        val ctx = contextOf(entity, findings)
        pair.rules.filter { it.writesOnExport && it.isNamespaceRule() }.forEach { exportRule(it, ctx, scratch) }
        val converted = ConvertedIdentity(pair.kind, (scratch.namespace ?: input.defaultNamespace).lowercase(), entity.identifier)
        return IdentityEntry(converted, findings, identityProblem(converted))
    }

    /** What the catalog would refuse about the identity itself: the name's grammar, or a namespace the dictionary does not hold. */
    private fun identityProblem(identity: ConvertedIdentity): ConversionFinding? {
        val message = try {
            validateNamePart(identity.name, "metadata.name")
            if (identity.namespace in input.registry.namespaces) {
                null
            } else {
                "metadata.namespace '${identity.namespace}' is not in the namespaces dictionary"
            }
        } catch (e: BadRequestException) {
            e.message
        }
        return message?.let { ConversionFinding(ConversionFindingCode.DOCUMENT_INVALID, "", it) }
    }

    private fun contextOf(entity: ExportEntity, findings: MutableList<ConversionFinding>, pair: MappingPair? = null) =
        ExportContext(
            entity, input.blueprints[entity.blueprint], input.registry, this, findings,
            pair?.rules?.mapNotNull { (it.backstage as? BackstageAddress.Link)?.title }?.toSet().orEmpty(),
        )

    private fun MappingRule.isNamespaceRule() = backstage == BackstageAddress.Metadata(MetadataField.NAMESPACE)

    fun shownIdentities(): Set<EntityIdentity> = input.entities
        .filter { it.shown }
        .mapNotNull { identities[EntityKey(it.blueprint, it.identifier)] }
        .filter { it.problem == null }
        .map { it.converted.key }
        .toSet()

    fun run(): CatalogDocumentsResponse {
        val files = mutableListOf<CatalogFile>()
        val rows = mutableListOf<ConversionRow>()
        input.entities.filter { it.shown }.forEach { entity ->
            val pair = pairs[entity.blueprint]
            val converted = if (pair == null) skipped(entity, rows.size) else convert(entity, pair, rows.size)
            converted.file?.let { files += it }
            rows += converted.row
        }
        return CatalogDocumentsResponse(files, rows)
    }

    private class Converted(val row: ConversionRow, val file: CatalogFile?)

    private fun skipped(entity: ExportEntity, index: Int) = Converted(
        ConversionRow(
            index = index,
            status = ConversionRowStatus.SKIPPED,
            blueprint = entity.blueprint,
            identifier = entity.identifier,
            entityId = entity.id,
            findings = listOf(
                ConversionFinding(
                    ConversionFindingCode.NO_PAIR,
                    "",
                    "blueprint '${entity.blueprint}' has no pair in the mapping, so the entity has no Backstage counterpart",
                ),
            ),
            unmapped = emptyList(),
        ),
        null,
    )

    private fun convert(entity: ExportEntity, pair: MappingPair, index: Int): Converted {
        val entry = identities.getValue(EntityKey(entity.blueprint, entity.identifier))
        val identity = entry.converted
        val findings = entry.findings.toMutableList()
        var document: CatalogFile? = null
        var stored: CatalogSource? = null
        if (entry.problem != null) {
            findings += entry.problem
        } else {
            val builder = DocBuilder()
            val ctx = contextOf(entity, findings, pair)
            pair.rules.filter { it.writesOnExport && !it.isNamespaceRule() }.forEach { exportRule(it, ctx, builder) }
            val generated = builder.toFile(pair.kind, entity.identifier, identity.namespace)
            stored = existingByIdentity[identity.key]
            val merged = stored?.let { merge(pair, entity, generated, it.file) } ?: generated
            document = sanitizedCatalogFile(merged)
            try {
                validateCatalogFile(document)
            } catch (e: BadRequestException) {
                findings += ConversionFinding(ConversionFindingCode.DOCUMENT_INVALID, "", e.message ?: "the document is invalid")
                document = null
            }
        }
        val row = ConversionRow(
            index = index,
            status = when {
                document == null -> ConversionRowStatus.INVALID
                findings.isEmpty() -> ConversionRowStatus.CONVERTED
                else -> ConversionRowStatus.CONVERTED_WITH_FINDINGS
            },
            blueprint = entity.blueprint,
            identifier = entity.identifier,
            entityId = entity.id,
            kind = identity.kind,
            namespace = identity.namespace,
            name = identity.name,
            existingId = stored?.id,
            findings = findings,
            unmapped = unmappedFields(entity, pair),
        )
        return Converted(row, document)
    }

    private fun merge(pair: MappingPair, entity: ExportEntity, generated: CatalogFile, stored: CatalogFile): CatalogFile =
        mergeFootprint(footprintOf(pair, input.blueprints[entity.blueprint]), generated, stored, input.registry) { field, reference ->
            blueprintOfReference(field, reference, stored)
        }

    /** The blueprint of the converted entity a stored reference-list entry resolves to; null when it resolves to none. */
    private fun blueprintOfReference(field: String, reference: String, stored: CatalogFile): String? {
        val sourceNamespace = stored.metadata.namespace.lowercase().ifEmpty { input.defaultNamespace.lowercase() }
        val target = resolveTarget(reference, REF_FIELD_DEFAULT_KINDS["spec.$field"], sourceNamespace) ?: return null
        return blueprintByIdentity[target]
    }

    /** Port-side fields the entity carries that no export-writing rule of its pair reads. */
    private fun unmappedFields(entity: ExportEntity, pair: MappingPair): List<String> {
        val rules = pair.rules.filter { it.writesOnExport }
        val properties = rules.mapNotNull { (it.port as? PortAddress.Property)?.id }.toSet()
        val relations = rules.mapNotNull { (it.port as? PortAddress.Relation)?.id }.toSet()
        val teamMapped = rules.any { (it.port as? PortAddress.Meta)?.field == PortMeta.TEAM }
        return entity.properties.filter { (key, value) -> key !in properties && value !is JsonNull }.keys.map { "properties.$it" } +
            entity.relations.filter { (key, value) -> key !in relations && value !is JsonNull && !(value is JsonArray && value.isEmpty()) }
                .keys.map { "relations.$it" } +
            listOfNotNull("team".takeIf { entity.team.isNotEmpty() && !teamMapped })
    }
}
