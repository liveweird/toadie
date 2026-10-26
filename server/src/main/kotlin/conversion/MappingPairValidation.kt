package ch.nokillswit.conversion

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.catalog.SPEC_PROFILE
import ch.nokillswit.catalog.SPEC_SHAPES
import ch.nokillswit.catalog.SPEC_TYPE
import ch.nokillswit.catalog.SUPPORTED_KINDS
import ch.nokillswit.catalog.allowedSpecFields

/**
 * Validates ONE pair of a mapping document — the blueprint/kind/selector it names, every rule,
 * the lossy declarations, the "every field is mapped or declared" completeness rule, and the
 * required Backstage fields. [path] is the pair's JSON pointer (`/pairs/3`). Findings go to the
 * shared [VerdictSink]; nothing here throws.
 */
internal class PairValidator(
    private val pair: MappingPair,
    private val path: String,
    private val context: MappingContext,
    private val sink: VerdictSink,
) {
    private val blueprint: BlueprintDefinition? = context.blueprints[pair.blueprint]
    private val allowed: Set<String>? = allowedSpecFields(pair.kind)

    fun validate() {
        if (blueprint == null) {
            sink.block(MappingFindingCode.UNKNOWN_BLUEPRINT, pointer(path, "blueprint"), "no active blueprint '${pair.blueprint}'")
        }
        if (allowed == null) {
            sink.block(
                MappingFindingCode.UNKNOWN_KIND,
                pointer(path, "kind"),
                "'${pair.kind}' is not a supported kind (one of ${SUPPORTED_KINDS.joinToString()})",
            )
        }
        validateSelector()
        pair.rules.forEachIndexed { index, rule ->
            RuleValidator(rule, pointer(pointer(path, "rules"), index), pair, blueprint, allowed, context, sink).validate()
        }
        if (blueprint != null) {
            validateLossyPorts(blueprint)
            checkUndeclared(blueprint)
            checkTagVocabulary(blueprint)
        }
        if (allowed != null) {
            validateLossyBackstage(allowed)
            checkRequired()
            reportUnmappedBackstage(allowed)
        }
        reportDuplicateLossy()
        reportDuplicateLinkTitles()
    }

    /** A lossy entry listed twice, or two `link` rules sharing a title (import finds a link by title) — both ambiguous. */
    private fun reportDuplicateLossy() {
        val lossyPath = pointer(path, "lossy")
        listOf(
            "properties" to pair.lossy.properties,
            "relations" to pair.lossy.relations,
            "backstage" to pair.lossy.backstage,
        ).forEach { (member, values) ->
            val seen = mutableSetOf<String>()
            values.forEachIndexed { index, value ->
                if (!seen.add(value)) {
                    sink.block(
                        MappingFindingCode.SCHEMA,
                        pointer(pointer(lossyPath, member), index),
                        "'$value' is listed twice",
                    )
                }
            }
        }
    }

    private fun reportDuplicateLinkTitles() {
        val seen = mutableSetOf<String>()
        pair.rules.forEachIndexed { index, rule ->
            val link = rule.backstage as? BackstageAddress.Link ?: return@forEachIndexed
            if (!seen.add(link.title)) {
                val titlePath = pointer(pointer(pointer(pointer(path, "rules"), index), "backstage"), "link")
                sink.block(MappingFindingCode.SCHEMA, pointer(titlePath, "title"), "link title '${link.title}' is used by two rules")
            }
        }
    }

    private fun validateSelector() {
        val select = pair.select ?: return
        val specPath = pointer(pointer(path, "select"), "spec")
        when {
            select.spec !in SPEC_SHAPES || !isTextSpecField(select.spec) ->
                sink.block(
                    MappingFindingCode.FIELD_NOT_ALLOWED,
                    specPath,
                    "'${select.spec}' is not a plain-text spec field a selector can read",
                )
            allowed != null && select.spec !in allowed ->
                sink.block(MappingFindingCode.FIELD_NOT_ALLOWED, specPath, "spec.${select.spec} does not apply to kind ${pair.kind}")
            select.spec == SPEC_TYPE && allowed != null -> {
                val dictionary = context.registry.types[pair.kind].orEmpty().toSet()
                select.values.forEachIndexed { index, value ->
                    if (value !in dictionary) {
                        sink.drift(
                            MappingDriftCode.VOCABULARY_DRIFT,
                            pointer(pointer(pointer(path, "select"), "in"), index),
                            "'$value' is not in the ${pair.kind} type dictionary",
                        )
                    }
                }
            }
        }
    }

    private fun validateLossyPorts(blueprint: BlueprintDefinition) {
        val lossyPath = pointer(path, "lossy")
        pair.lossy.properties.forEachIndexed { index, id ->
            val at = pointer(pointer(lossyPath, "properties"), index)
            when {
                id in computedIds(blueprint) ->
                    sink.block(
                        MappingFindingCode.COMPUTED_PROPERTY,
                        at,
                        "'$id' is a computed property — computed properties are never mapped or declared lossy",
                    )
                id !in blueprint.schema.properties ->
                    sink.block(MappingFindingCode.UNKNOWN_PROPERTY, at, "blueprint '${pair.blueprint}' has no property '$id'")
                id in blueprint.schema.required ->
                    sink.block(
                        MappingFindingCode.REQUIRED_UNMAPPED,
                        at,
                        "property '$id' is required by the blueprint — an import could not fill it",
                    )
                id in mappedProperties() ->
                    sink.block(MappingFindingCode.SCHEMA, at, "property '$id' is both mapped by a rule and declared lossy")
            }
        }
        pair.lossy.relations.forEachIndexed { index, id ->
            val at = pointer(pointer(lossyPath, "relations"), index)
            when {
                id !in blueprint.relations ->
                    sink.block(MappingFindingCode.UNKNOWN_RELATION, at, "blueprint '${pair.blueprint}' has no relation '$id'")
                blueprint.relations.getValue(id).required ->
                    sink.block(
                        MappingFindingCode.REQUIRED_UNMAPPED,
                        at,
                        "relation '$id' is required by the blueprint — an import could not fill it",
                    )
                id in mappedRelations() ->
                    sink.block(MappingFindingCode.SCHEMA, at, "relation '$id' is both mapped by a rule and declared lossy")
            }
        }
    }

    private fun validateLossyBackstage(allowed: Set<String>) {
        val universe = backstageUniverse(allowed).toSet()
        val covered = pair.rules.mapNotNull { fieldKey(it.backstage) }.toSet()
        pair.lossy.backstage.forEachIndexed { index, field ->
            val at = pointer(pointer(pointer(path, "lossy"), "backstage"), index)
            when {
                field !in universe ->
                    sink.block(
                        MappingFindingCode.FIELD_NOT_ALLOWED,
                        at,
                        "'$field' is not a field of kind ${pair.kind} (one of ${universe.sorted().joinToString()})",
                    )
                field in covered ->
                    sink.block(MappingFindingCode.SCHEMA, at, "'$field' is both written by a rule and declared lossy")
            }
        }
    }

    /** Every schema property and relation must be mapped by a rule or declared lossy. */
    private fun checkUndeclared(blueprint: BlueprintDefinition) {
        val mappedProps = mappedProperties()
        blueprint.schema.properties.keys.filter { it !in mappedProps && it !in pair.lossy.properties }.forEach {
            sink.block(
                MappingFindingCode.UNDECLARED_FIELD,
                pointer(pointer(path, "lossy"), "properties"),
                "property '$it' of '${pair.blueprint}' is neither mapped nor declared lossy",
            )
        }
        val mappedRels = mappedRelations()
        blueprint.relations.keys.filter { it !in mappedRels && it !in pair.lossy.relations }.forEach {
            sink.block(
                MappingFindingCode.UNDECLARED_FIELD,
                pointer(pointer(path, "lossy"), "relations"),
                "relation '$it' of '${pair.blueprint}' is neither mapped nor declared lossy",
            )
        }
    }

    private fun checkRequired() {
        val writes = pair.rules.filter { it.writesOnExport }.mapNotNull { fieldKey(it.backstage) }.toSet()
        requiredFieldsOf(pair.kind).sorted().filter { "spec.$it" !in writes }.forEach {
            sink.block(
                MappingFindingCode.REQUIRED_UNMAPPED,
                pointer(path, "rules"),
                "spec.$it is required for kind ${pair.kind} but no rule writes it on export",
            )
        }
    }

    private fun reportUnmappedBackstage(allowed: Set<String>) {
        val carried = pair.rules.mapNotNull { fieldKey(it.backstage) }.toSet() + pair.lossy.backstage
        backstageUniverse(allowed).filter { it !in carried }.forEach {
            sink.drift(
                MappingDriftCode.BACKSTAGE_FIELD_UNMAPPED,
                path,
                "$it of kind ${pair.kind} is carried by no rule — map it or list it in lossy.backstage",
            )
        }
    }

    /**
     * A property whose tagCategory rules (one per category) together name several categories must
     * have an enum equal to the UNION of those categories' tags — the resource `engine` =
     * Database + Events shape; a single rule is the one-category case.
     */
    private fun checkTagVocabulary(blueprint: BlueprintDefinition) {
        val byProperty = pair.rules.withIndex()
            .filter { (_, rule) -> rule.codec == Codec.TagCategory && rule.backstage is BackstageAddress.Tags }
            .mapNotNull { (index, rule) ->
                val property = rule.port as? PortAddress.Property
                val tags = rule.backstage as? BackstageAddress.Tags
                if (property != null && tags != null) Triple(property.id, index, tags) else null
            }
            .groupBy({ it.first }, { it.second to it.third })
        byProperty.forEach { (property, rules) ->
            val enum = blueprint.schema.properties[property]?.let { portSlotOf(it) }?.let { enumOfSlot(it) } ?: return@forEach
            val categories = rules.map { (_, tags) -> context.categories[tags.category] }
            if (categories.any { it == null }) return@forEach
            val union = categories.flatMap { it!!.first }.toSet()
            if (union != enum) {
                sink.drift(
                    MappingDriftCode.VOCABULARY_DRIFT,
                    pointer(pointer(path, "rules"), rules.first().first),
                    "property '$property' enum differs from its tag categories (${rules.joinToString { it.second.category }}): " +
                        differenceMessage(enum, union, "property", "categories"),
                )
            }
        }
    }

    private fun mappedProperties(): Set<String> = pair.rules.mapNotNull { (it.port as? PortAddress.Property)?.id }.toSet()
    private fun mappedRelations(): Set<String> = pair.rules.mapNotNull { (it.port as? PortAddress.Relation)?.id }.toSet()
}

internal fun computedIds(blueprint: BlueprintDefinition): Set<String> =
    blueprint.mirrorProperties.keys + blueprint.calculationProperties.keys + blueprint.aggregationProperties.keys

/** The Backstage fields a pair can carry, as `metadata.*` / `spec.*` / `profile.*` keys. */
internal fun backstageUniverse(allowed: Set<String>): List<String> =
    listOf("metadata.title", "metadata.description") +
        allowed.filter { it != SPEC_PROFILE }.sorted().map { "spec.$it" } +
        if (SPEC_PROFILE in allowed) ProfileField.entries.map { "profile.${it.wire}" } else emptyList()

/** The universe key a rule's Backstage address occupies; null for the open-ended ones (labels, annotations, tags, links, namespace). */
internal fun fieldKey(address: BackstageAddress): String? = when (address) {
    is BackstageAddress.Spec -> "spec.${address.field}"
    is BackstageAddress.Profile -> "profile.${address.field.wire}"
    is BackstageAddress.Metadata ->
        if (address.field == MetadataField.NAMESPACE) null else "metadata.${address.field.wire}"
    else -> null
}
