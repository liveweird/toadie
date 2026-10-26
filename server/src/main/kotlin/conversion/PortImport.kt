package ch.nokillswit.conversion

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.specStrings
import ch.nokillswit.catalog.specLists
import ch.nokillswit.catalog.EntityIdentity
import ch.nokillswit.catalog.REF_FIELD_DEFAULT_KINDS
import ch.nokillswit.catalog.RegistrySnapshot
import ch.nokillswit.catalog.SPEC_SHAPES
import ch.nokillswit.catalog.SPEC_PROFILE
import ch.nokillswit.catalog.SpecShape
import ch.nokillswit.catalog.StoredIdentity
import ch.nokillswit.catalog.identityOf
import ch.nokillswit.catalog.resolveTarget
import ch.nokillswit.catalog.sanitizedCatalogFile
import ch.nokillswit.catalog.validateCatalogFile
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.StoredEntity
import ch.nokillswit.entities.TargetExists
import ch.nokillswit.entities.entityFindings
import ch.nokillswit.entities.toDocument
import ch.nokillswit.entities.sanitizedEntityRequest
import ch.nokillswit.entities.validateEntityRequest
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The Backstage -> Port import, PURE: a function of the mapping, the blueprints, the registries, a
 * batch of Backstage documents, the identities of the stored catalog and of the Port workspace, and
 * — to merge — the stored Port entities the batch will overwrite. No reads, no writes, no locks, no
 * audit; the caller (`ConversionImportService`) does the admitted reads.
 *
 * Two passes over the batch:
 *  1. **staging** — each document is sanitized and structurally validated by the catalog's own
 *     rules, routed to a pair (selector pairs in document order, the selector-less pair as the
 *     fallback; none = SKIPPED), checked against the pair's namespace rule (a namespace the mapping
 *     cannot place is SKIPPED, never collapsed onto another identity) and claims its Port identity
 *     ((blueprint, `metadata.name`)); a second document claiming one is INVALID. What survives is the
 *     set of documents that WILL become entities, which is what a reference may point at;
 *  2. **conversion** — each staged document runs its pair's import-reading rules ([importRule],
 *     [importRefFields]) into an [EntityDraft], the draft is settled against the stored entity
 *     ([mergeEntity]) and the result goes through the entity import's own shape rules.
 *
 * A reference resolves in this order: the batch, then the stored catalog (identity and selector
 * fields only, no content), then the Port workspace by kind — and in every case the target must be a
 * Port entity that exists or will after this batch, so the strict entity import that follows never
 * meets a relation target that is missing. What cannot resolve is dropped with a row finding.
 */
internal fun interface PortDirectory {
    /** The identifier as stored for the active entity of [blueprint] that [identifier] names case-insensitively; null = none. */
    fun canonicalIdentifier(blueprint: String, identifier: String): String?
}

internal class ImportInput(
    val mapping: MappingDocument,
    val blueprints: Map<String, BlueprintDefinition>,
    val registry: RegistrySnapshot,
    /** The namespace a document with a blank namespace lands in, and the only one a pair without a namespace rule accepts. */
    val defaultNamespace: String,
    val files: List<CatalogFile>,
    /** Every stored catalog file's identity (no content) — only to tell which pair a REFERENCED file belongs to. */
    val stored: List<StoredIdentity>,
    val directory: PortDirectory,
    /** The id of the active entity of (blueprint, identifier), case-insensitively; null = none. */
    val existingId: (blueprint: String, identifier: String) -> UInt?,
    /** The `updated_at` of that same entity (the write-version token the row reports); null = none. */
    val existingUpdatedAt: (blueprint: String, identifier: String) -> Long? = { _, _ -> null },
    val merge: Boolean,
)

internal fun importEntityDocuments(input: ImportInput, existing: Map<UInt, StoredEntity>?): EntityDocumentsResponse =
    ImportRun(input).run(existing)

private sealed interface Stage {
    val index: Int
}

private class Refused(override val index: Int, val row: EntityDocumentRow, val identity: EntityIdentity) : Stage

private class Ready(override val index: Int, val file: CatalogFile, val pair: MappingPair, val identifier: String) : Stage {
    val identity: EntityIdentity get() = identityOf(file)
    val portKey: Pair<String, String> get() = pair.blueprint to identifier.lowercase()
}

internal class ImportRun(private val input: ImportInput) {
    private val pairsByKind: Map<String, List<MappingPair>> = input.mapping.pairs.groupBy { it.kind.lowercase() }
    private val defaultNamespace = input.defaultNamespace.lowercase()

    private val stages: List<Stage>

    /** The documents that WILL become entities, by Backstage identity (what a reference may point at). */
    private val batch = HashMap<EntityIdentity, Ready>()

    /** Backstage identities of documents that will NOT become entities (skipped, invalid, conflicting). */
    private val refusedIdentities = HashSet<EntityIdentity>()
    private val storedByIdentity: Map<EntityIdentity, StoredIdentity> =
        input.stored.associateBy { EntityIdentity(it.kind.lowercase(), it.namespace.lowercase(), it.name.lowercase()) }

    init {
        val staged = input.files.mapIndexed { index, file -> stage(index, file) }
        // Identity collisions are judged over the WHOLE batch, never first-come: every document of a
        // colliding group is refused, whatever its position.
        val claims = staged.filterIsInstance<Ready>().groupBy { it.portKey }
        val identities = staged.filterIsInstance<Ready>().groupBy { it.identity }
        stages = staged.map { stage ->
            when {
                stage !is Ready -> stage
                (identities[stage.identity]?.size ?: 0) > 1 && (claims[stage.portKey]?.size ?: 0) <= 1 -> conflict(
                    stage,
                    "another document of this batch has the same Backstage identity ${stage.identity.kind}:" +
                        "${stage.identity.namespace}/${stage.identity.name} but selects a different pair",
                )
                (claims[stage.portKey]?.size ?: 0) > 1 -> conflict(
                    stage,
                    "another document of this batch also converts to ${stage.pair.blueprint} '${stage.identifier}' " +
                        "(identifiers are case-insensitive)",
                )
                else -> {
                    val stored = input.directory.canonicalIdentifier(stage.pair.blueprint, stage.identifier)
                    if (stored != null && stored != stage.identifier) {
                        conflict(
                            stage,
                            "the Port entity '$stored' of blueprint '${stage.pair.blueprint}' already holds this identifier " +
                                "under another spelling",
                        )
                    } else {
                        stage
                    }
                }
            }
        }
        stages.forEach { stage ->
            when (stage) {
                is Refused -> refusedIdentities += stage.identity
                is Ready -> batch.putIfAbsent(stage.identity, stage)
            }
        }
    }

    private fun conflict(stage: Ready, message: String): Stage = Refused(
        stage.index,
        rowOf(
            stage.index, stage.file, ConversionRowStatus.INVALID, null,
            listOf(ConversionFinding(ConversionFindingCode.IDENTITY_CONFLICT, "metadata.name", message)), emptyList(), null,
        ),
        stage.identity,
    )

    /** The ids of the stored entities the batch's documents will overwrite — what the merge needs read. Empty without `merge`. */
    fun mergeIds(): Set<UInt> =
        if (!input.merge) {
            emptySet()
        } else {
            stages.filterIsInstance<Ready>().mapNotNull { input.existingId(it.pair.blueprint, it.identifier) }.toSet()
        }

    // ── pass 1: staging ─────────────────────────────────────────────────────────────────────

    private fun stage(index: Int, submitted: CatalogFile): Stage {
        val sanitized = sanitizedCatalogFile(submitted)
        val namespace = sanitized.metadata.namespace.ifEmpty { defaultNamespace }
        val file = sanitized.copy(metadata = sanitized.metadata.copy(namespace = namespace))
        fun refuse(status: ConversionRowStatus, finding: ConversionFinding): Stage =
            Refused(index, rowOf(index, file, status, null, listOf(finding), emptyList(), null), identityOf(file))
        try {
            validateCatalogFile(file)
        } catch (e: BadRequestException) {
            val invalid = ConversionFinding(ConversionFindingCode.DOCUMENT_INVALID, "", e.message ?: "the document is invalid")
            return refuse(ConversionRowStatus.INVALID, invalid)
        }
        val pair = selectPair(file.kind, specStrings(file.spec)::get)
            ?: return refuse(
                ConversionRowStatus.SKIPPED,
                ConversionFinding(
                    ConversionFindingCode.NO_PAIR,
                    "kind",
                    "no pair of the mapping takes a ${file.kind} like this one, so the document has no Port counterpart",
                ),
            )
        if (!namespaceAccepted(pair, namespace)) {
            return refuse(
                ConversionRowStatus.SKIPPED,
                ConversionFinding(
                    ConversionFindingCode.NAMESPACE_UNMAPPED,
                    "metadata.namespace",
                    "namespace '$namespace' is not one the mapping carries for blueprint '${pair.blueprint}' — skipped rather than " +
                        "collapsed onto another identity",
                ),
            )
        }
        return Ready(index, file, pair, file.metadata.name)
    }

    /** Selector pairs of the kind are tried in document order; the selector-less pair is the fallback. */
    private fun selectPair(kind: String, spec: (String) -> String?): MappingPair? {
        val candidates = pairsByKind[kind.lowercase()].orEmpty()
        return candidates.firstOrNull { it.select != null && spec(it.select.spec) in it.select.values }
            ?: candidates.firstOrNull { it.select == null }
    }

    /**
     * Whether [pair] can place a document of [namespace]: every import-reading rule on
     * `metadata.namespace` must accept it; with no such rule only the workspace's default namespace is
     * the pair's — anything else would collapse onto the same Port identity as the default one.
     */
    private fun namespaceAccepted(pair: MappingPair, namespace: String): Boolean {
        val rules = pair.rules.filter { it.readsOnImport && it.backstage == BackstageAddress.Metadata(MetadataField.NAMESPACE) }
        if (rules.isEmpty()) return namespace == defaultNamespace
        return rules.all { rule ->
            when (val codec = rule.codec) {
                is Codec.ValueMap -> namespace in codec.map.values
                // The placeholders (`importIgnore`, the export's default) are let through: their leaf is then Kept.
                is Codec.Copy -> namespace in codec.importIgnore || namespace == codec.exportDefault ||
                    (codec.prefix?.let(namespace::startsWith) ?: true)
                else -> true
            }
        }
    }

    // ── pass 2: conversion ──────────────────────────────────────────────────────────────────

    /**
     * Converts every staged document. A document whose converted entity the entity import's shape
     * rules refuse (an identifier that is no Port identifier, a title or document too large, ...) is
     * INVALID — and because other documents may have pointed at it, it leaves the batch and the whole
     * conversion runs again, so its referrers drop the reference with a finding instead of emitting
     * a relation the strict entity import would then reject. The loop ends when no new document
     * proves invalid (at most one round per document).
     */
    fun run(existing: Map<UInt, StoredEntity>?): EntityDocumentsResponse {
        val demoted = HashMap<Int, EntityDocumentRow>()
        var results: List<Converted>
        while (true) {
            val live = stages.filterIsInstance<Ready>().filter { it.index !in demoted }
            val inBatch = live.map { it.pair.blueprint to it.identifier }.toSet()
            val targetExists: TargetExists = { blueprint, identifier ->
                (blueprint to identifier) in inBatch || input.directory.canonicalIdentifier(blueprint, identifier) == identifier
            }
            results = live.map { convert(it, existing, targetExists) }
            val invalid = results.filter { it.document == null }
            if (invalid.isEmpty()) break
            invalid.forEach {
                demoted[it.stage.index] = it.row
                batch.remove(it.stage.identity)
                refusedIdentities += it.stage.identity
            }
        }
        val byIndex = results.associateBy { it.stage.index }
        val rows = stages.map { stage ->
            when (stage) {
                is Refused -> stage.row
                is Ready -> demoted[stage.index] ?: byIndex.getValue(stage.index).row
            }
        }
        return EntityDocumentsResponse(results.mapNotNull { it.document }, rows)
    }

    private class Converted(val stage: Ready, val row: EntityDocumentRow, val document: JsonObject?)

    private fun convert(stage: Ready, stored: Map<UInt, StoredEntity>?, targetExists: TargetExists): Converted {
        val pair = stage.pair
        val findings = mutableListOf<ConversionFinding>()
        val ctx = ImportContext(stage.file, pair, input.blueprints[pair.blueprint], input.registry, refs, findings)
        val draft = EntityDraft()
        pair.rules.filter { it.readsOnImport }.forEach { importRule(it, ctx, draft) }
        importRefFields(ctx, draft)
        val existingId = input.existingId(pair.blueprint, stage.identifier)
        val existingUpdatedAt = existingId?.let { input.existingUpdatedAt(pair.blueprint, stage.identifier) }
        val counterpart = if (input.merge) existingId?.let { stored?.get(it) } else null
        val relations = input.blueprints[pair.blueprint]?.relations.orEmpty()
        val converted = mergeEntity(draft, counterpart, importFootprintOf(pair), stage.identifier) { relation, identifier ->
            relations[relation]?.target?.let { targetExists(it, identifier) } ?: true
        }
        val request = EntityRequest(
            blueprint = pair.blueprint,
            identifier = stage.identifier,
            title = converted.title,
            icon = converted.icon,
            team = converted.team,
            properties = converted.properties,
            relations = converted.relations,
        )
        val refusal = shapeRefusal(request) ?: entityRefusal(request, converted, targetExists)
        if (refusal != null) findings += refusal
        val status = when {
            refusal != null -> ConversionRowStatus.INVALID
            findings.isEmpty() -> ConversionRowStatus.CONVERTED
            else -> ConversionRowStatus.CONVERTED_WITH_FINDINGS
        }
        val target = pair.blueprint to stage.identifier
        val row = rowOf(stage.index, stage.file, status, target, findings, unmappedFields(stage.file, pair), existingId, existingUpdatedAt)
        val document = if (refusal == null) {
            blueprintJson.encodeToJsonElement(EntityRequest.serializer(), sanitizedEntityRequest(request)).jsonObject
        } else {
            null
        }
        return Converted(stage, row, document)
    }

    /**
     * The entity import's own value rules (`entityFindings`: required, enum, format, type, relation and team
     * targets) over the converted document, with the entities of this batch and of the Port workspace as the
     * targets that exist — so a document the strict import would refuse is INVALID here, and (through the
     * fixpoint of [run]) its referrers drop the reference instead of meeting a missing target.
     */
    private fun entityRefusal(request: EntityRequest, converted: ConvertedEntity, targetExists: TargetExists): ConversionFinding? {
        val definition = input.blueprints[request.blueprint] ?: return null
        val findings = entityFindings(request.toDocument(), definition, targetExists, converted.team)
        if (findings.isEmpty()) return null
        return ConversionFinding(
            ConversionFindingCode.DOCUMENT_INVALID,
            findings.first().field,
            findings.joinToString("; ") { "${it.field}: ${it.message}" },
        )
    }

    /** The entity import's own shape rules (`validateEntityRequest`); null = the document will be accepted for validation. */
    private fun shapeRefusal(request: EntityRequest): ConversionFinding? = try {
        validateEntityRequest(sanitizedEntityRequest(request))
        null
    } catch (e: BadRequestException) {
        ConversionFinding(ConversionFindingCode.DOCUMENT_INVALID, "", e.message ?: "the entity document is invalid")
    }

    private fun rowOf(
        index: Int,
        file: CatalogFile,
        status: ConversionRowStatus,
        target: Pair<String, String>?,
        findings: List<ConversionFinding>,
        unmapped: List<String>,
        existingId: UInt?,
        existingUpdatedAt: Long? = null,
    ) = EntityDocumentRow(
        index = index,
        status = status,
        kind = file.kind,
        namespace = file.metadata.namespace,
        name = file.metadata.name,
        blueprint = target?.first,
        identifier = target?.second,
        existingId = existingId,
        existingUpdatedAt = existingUpdatedAt,
        findings = findings,
        unmapped = unmapped,
    )

    // ── references ──────────────────────────────────────────────────────────────────────────

    private val refs = ImportRefs { file, field, raw -> resolveReference(file, field, raw) }

    private fun resolveReference(file: CatalogFile, field: String, raw: String): RefTarget {
        val identity = resolveTarget(raw, REF_FIELD_DEFAULT_KINDS["spec.$field"], file.metadata.namespace)
            ?: return RefTarget.Dropped("cannot be read as an entity reference, or names no kind")
        if (pairsByKind[identity.kind].isNullOrEmpty()) {
            return RefTarget.Dropped("kind '${identity.kind}' has no pair in the mapping, so it has no Port counterpart")
        }
        batch[identity]?.let { return RefTarget.Entity(it.pair.blueprint, it.identifier) }
        if (identity in refusedIdentities) return RefTarget.Dropped("names a document of this batch that was skipped or is invalid")
        storedByIdentity[identity]?.let { return resolveStored(it, identity) }
        return resolveByKind(identity)
    }

    /** A referenced file of the stored catalog: its pair comes from its kind and selector field, its existence from the Port workspace. */
    private fun resolveStored(stored: StoredIdentity, identity: EntityIdentity): RefTarget {
        val pair = selectPair(stored.kind, stored.spec::get)
            ?: return RefTarget.Dropped("the stored ${stored.kind} '${stored.name}' is of a type no pair of the mapping takes")
        if (!namespaceAccepted(pair, identity.namespace)) {
            return RefTarget.Dropped("namespace '${identity.namespace}' is not one the mapping carries for blueprint '${pair.blueprint}'")
        }
        val identifier = input.directory.canonicalIdentifier(pair.blueprint, stored.name)
            ?: return RefTarget.Dropped("names a stored file with no Port entity yet — convert it in the same batch")
        return RefTarget.Entity(pair.blueprint, identifier)
    }

    /** A reference to no document of the batch or catalog: the Port entity of that name under whichever pair of the kind has one. */
    private fun resolveByKind(identity: EntityIdentity): RefTarget {
        val found = pairsByKind[identity.kind].orEmpty()
            .filter { namespaceAccepted(it, identity.namespace) }
            .mapNotNull { pair -> input.directory.canonicalIdentifier(pair.blueprint, identity.name)?.let { pair.blueprint to it } }
            .distinct()
        return when (found.size) {
            1 -> RefTarget.Entity(found.single().first, found.single().second)
            0 -> RefTarget.Dropped("names no document of this batch, no stored file and no Port entity")
            else -> RefTarget.Dropped(
                "matches Port entities of several blueprints (${found.joinToString { it.first }}) and no stored file says which",
            )
        }
    }

    // ── unmapped ────────────────────────────────────────────────────────────────────────────

    /**
     * Backstage fields the document carries that NO rule of its pair addresses, in any direction (an
     * export-only or derive rule's field is regenerated by the export, so it is covered): for a valid
     * mapping the universe fields among them are exactly the pair's `lossy.backstage`; labels,
     * annotations, tags and links no rule claims are listed individually.
     */
    private fun unmappedFields(file: CatalogFile, pair: MappingPair): List<String> {
        val addresses = pair.rules.map { it.backstage }
        val keys = addresses.mapNotNull { fieldKey(it) }.toSet()
        val unmapped = mutableListOf<String>()
        fun note(key: String, present: Boolean) {
            if (present && key !in keys) unmapped += key
        }
        note("metadata.title", file.metadata.title != null)
        note("metadata.description", file.metadata.description != null)
        file.metadata.labels.keys.filter { BackstageAddress.Label(it) !in addresses }.forEach { unmapped += "metadata.labels.$it" }
        file.metadata.annotations.keys.filter { BackstageAddress.Annotation(it) !in addresses }
            .forEach { unmapped += "metadata.annotations.$it" }
        val categories = addresses.filterIsInstance<BackstageAddress.Tags>().map { it.category }.toSet()
        file.metadata.tags.filter { input.registry.tags[it]?.first !in categories }.forEach { unmapped += "metadata.tags.$it" }
        if (BackstageAddress.LinksRest !in addresses) {
            val claimed = addresses.filterIsInstance<BackstageAddress.Link>().map { it.title }.toSet()
            file.metadata.links.filter { it.title !in claimed }.forEach { unmapped += "metadata.links.${it.title ?: it.url}" }
        }
        specFieldsCarried(file).forEach { note("spec.$it", true) }
        file.spec.profile?.let { profile ->
            note("profile.displayName", profile.displayName != null)
            note("profile.email", profile.email != null)
            note("profile.picture", profile.picture != null)
        }
        return unmapped
    }

    /** The plain and reference spec fields (never the profile) the document actually holds a value in. */
    private fun specFieldsCarried(file: CatalogFile): List<String> {
        val strings = specStrings(file.spec).keys
        val lists = specLists(file.spec).filterValues { it.isNotEmpty() }.keys
        return SPEC_SHAPES.filter { (name, shape) ->
            name != SPEC_PROFILE && shape != SpecShape.PROFILE && (name in strings || name in lists)
        }.keys.toList()
    }
}
