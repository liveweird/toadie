package ch.nokillswit.conversion

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.catalog.REF_FIELD_ALLOWED_KINDS
import ch.nokillswit.catalog.SPEC_LIFECYCLE
import ch.nokillswit.catalog.SPEC_PROFILE
import ch.nokillswit.catalog.SPEC_SHAPES
import ch.nokillswit.catalog.SUPPORTED_KINDS
import ch.nokillswit.catalog.SpecShape
import ch.nokillswit.entities.isInherited

/** What a rule's Port address resolves to, reduced to what the codec compatibility matrix needs. */
internal sealed interface PortSlot {
    /** A string-typed value; [enum] is its closed value list when it has one, [url] true for `format: url`. */
    data class Text(val enum: Set<String>?, val url: Boolean) : PortSlot
    data object Flag : PortSlot
    data object Num : PortSlot
    data class TextArray(val enum: Set<String>?, val unique: Boolean) : PortSlot
    data object ObjectArray : PortSlot
    data object OtherProperty : PortSlot
    data class Rel(val many: Boolean) : PortSlot
    data object Team : PortSlot
    data object Reverse : PortSlot
}

internal fun portSlotOf(definition: PropertyDefinition): PortSlot = when (definition.type) {
    "string" -> PortSlot.Text(definition.enum?.map { it.content }?.toSet(), definition.format == "url")
    "boolean" -> PortSlot.Flag
    "number" -> PortSlot.Num
    "array" -> when (definition.items?.type) {
        "string" -> PortSlot.TextArray(definition.items.enum?.map { it.content }?.toSet(), definition.uniqueItems == true)
        "object" -> PortSlot.ObjectArray
        else -> PortSlot.OtherProperty
    }
    else -> PortSlot.OtherProperty
}

internal fun enumOfSlot(slot: PortSlot): Set<String>? = when (slot) {
    is PortSlot.Text -> slot.enum
    is PortSlot.TextArray -> slot.enum
    else -> null
}

/** The shape of the Backstage field a rule writes — the other axis of the compatibility matrix. */
internal enum class Target { TEXT, TAGS, LINKS, LINK, REF, REF_LIST }

/**
 * The string values a rule can put on its Backstage address, when that set is statically known
 * (a constant, a value map, a copy of an enum property without a prefix); null otherwise.
 */
internal fun producedValues(rule: MappingRule, blueprint: BlueprintDefinition): Set<String>? = when (val codec = rule.codec) {
    is Codec.Constant -> setOf(codec.value)
    is Codec.ValueMap -> codec.map.values.toSet()
    is Codec.Copy -> {
        val property = (rule.port as? PortAddress.Property)?.let { blueprint.schema.properties[it.id] }
        if (codec.prefix != null || property == null) null else enumOfSlot(portSlotOf(property))?.plus(listOfNotNull(codec.exportDefault))
    }
    else -> null
}

/**
 * Validates ONE rule: its Port address resolves, its Backstage address is a real field of the
 * pair's kind, the codec fits both ends (the compatibility matrix in [mismatch]), the direction
 * suits the codec, and — as report-only drift — the registry keys and closed vocabularies it
 * names still match. [path] is the rule's JSON pointer.
 */
internal class RuleValidator(
    private val rule: MappingRule,
    private val path: String,
    private val pair: MappingPair,
    private val blueprint: BlueprintDefinition?,
    private val allowed: Set<String>?,
    private val context: MappingContext,
    private val sink: VerdictSink,
) {
    fun validate() {
        val target = target()
        val slot = slot()
        val portResolved = rule.port == null || slot != null
        if (target != null && portResolved) {
            val problem = mismatch(slot, target)
            if (problem != null) reportMismatch(problem) else checkReferenceTarget()
        }
        checkDirection()
        if (allowed != null) registryChecks(slot)
    }

    private fun checkDirection() {
        val directionPath = pointer(path, "direction")
        if ((rule.codec is Codec.Derive || rule.codec is Codec.Constant) && rule.direction != Direction.EXPORT) {
            sink.block(
                MappingFindingCode.CODEC_TYPE_MISMATCH,
                directionPath,
                "the ${rule.codec.wire} codec is export-only — direction must be export",
            )
        }
        // An Inherited blueprint's team is computed from its ownership path, never stored: importing one is TEAM_NOT_ALLOWED.
        val team = (rule.port as? PortAddress.Meta)?.field == PortMeta.TEAM
        if (team && blueprint != null && isInherited(blueprint) && rule.direction != Direction.EXPORT) {
            sink.block(
                MappingFindingCode.CODEC_TYPE_MISMATCH,
                directionPath,
                "blueprint '${pair.blueprint}' has Inherited ownership, so its team is computed — the rule must be export-only",
            )
        }
    }

    /**
     * A `ref`/`derive` rule's far end must be mapped too, to a kind the spec field may reference
     * (`REF_FIELD_ALLOWED_KINDS`, the table the catalog's own reference check uses): the relation's
     * target blueprint, `_team` for the team meta field, the reverse blueprint for a derive.
     */
    private fun checkReferenceTarget() {
        val field = (rule.backstage as? BackstageAddress.Spec)?.field ?: return
        val allowedKinds = REF_FIELD_ALLOWED_KINDS["spec.$field"] ?: return
        val port = rule.port
        val targetBlueprint = when {
            port is PortAddress.Relation -> blueprint?.relations?.get(port.id)?.target
            port is PortAddress.Meta && port.field == PortMeta.TEAM -> SYSTEM_TEAM_BLUEPRINT
            port is PortAddress.Reverse -> port.blueprint
            else -> null
        } ?: return
        val kind = context.kindByBlueprint[targetBlueprint]
        when {
            kind == null -> sink.block(
                MappingFindingCode.TARGET_UNMAPPED,
                pointer(path, "port"),
                "'$targetBlueprint' is the other end of this reference but has no pair — spec.$field could not be exported or resolved",
            )
            kind in SUPPORTED_KINDS && kind.lowercase() !in allowedKinds -> sink.block(
                MappingFindingCode.REF_KIND_MISMATCH,
                pointer(path, "backstage"),
                "spec.$field may reference ${allowedKinds.sorted().joinToString(" or ")}, but '$targetBlueprint' is mapped to kind $kind",
            )
        }
    }

    private fun reportMismatch(message: String) =
        sink.block(MappingFindingCode.CODEC_TYPE_MISMATCH, pointer(path, "codec"), message)

    // ── Backstage side ──────────────────────────────────────────────────────────────────────

    /** The shape of the Backstage field the rule writes; null (after a finding) when it is not a field of the kind. */
    private fun target(): Target? {
        val at = pointer(path, "backstage")
        return when (val address = rule.backstage) {
            is BackstageAddress.Metadata, is BackstageAddress.Label, is BackstageAddress.Annotation -> Target.TEXT
            is BackstageAddress.Tags -> Target.TAGS
            BackstageAddress.LinksRest -> Target.LINKS
            is BackstageAddress.Link -> Target.LINK
            is BackstageAddress.Profile -> {
                if (allowed != null && SPEC_PROFILE !in allowed) {
                    sink.block(MappingFindingCode.FIELD_NOT_ALLOWED, pointer(at, "profile"), "profile does not apply to kind ${pair.kind}")
                    null
                } else {
                    Target.TEXT
                }
            }
            is BackstageAddress.Spec -> specTarget(address.field, at)
        }
    }

    private fun specTarget(field: String, at: String): Target? {
        val specPath = pointer(at, "spec")
        val shape = SPEC_SHAPES[field]
        return when {
            field == SPEC_PROFILE -> {
                sink.block(
                    MappingFindingCode.FIELD_NOT_ALLOWED,
                    specPath,
                    "use the profile address (displayName, email, picture) for spec.profile",
                )
                null
            }
            shape == null -> {
                sink.block(MappingFindingCode.FIELD_NOT_ALLOWED, specPath, "'$field' is not a spec field of the descriptor format")
                null
            }
            allowed != null && field !in allowed -> {
                sink.block(MappingFindingCode.FIELD_NOT_ALLOWED, specPath, "spec.$field does not apply to kind ${pair.kind}")
                null
            }
            shape == SpecShape.TEXT -> Target.TEXT
            shape == SpecShape.REF -> Target.REF
            else -> Target.REF_LIST
        }
    }

    // ── Port side ───────────────────────────────────────────────────────────────────────────

    private fun slot(): PortSlot? {
        val at = pointer(path, "port")
        return when (val port = rule.port) {
            null -> null
            is PortAddress.Meta -> metaSlot(port.field)
            is PortAddress.Property -> propertySlot(port.id, pointer(at, "property"))
            is PortAddress.Relation -> relationSlot(port.id, pointer(at, "relation"))
            is PortAddress.Reverse -> reverseSlot(port, pointer(at, "reverse"))
        }
    }

    private fun metaSlot(field: PortMeta): PortSlot = if (field == PortMeta.TEAM) PortSlot.Team else PortSlot.Text(null, false)

    private fun propertySlot(id: String, at: String): PortSlot? {
        if (blueprint == null) return null
        val definition = blueprint.schema.properties[id]
        if (definition != null) return portSlotOf(definition)
        if (id in computedIds(blueprint)) {
            sink.block(MappingFindingCode.COMPUTED_PROPERTY, at, "'$id' is a computed property — computed properties are never mapped")
        } else {
            sink.block(MappingFindingCode.UNKNOWN_PROPERTY, at, "blueprint '${pair.blueprint}' has no property '$id'")
        }
        return null
    }

    private fun relationSlot(id: String, at: String): PortSlot? {
        if (blueprint == null) return null
        val relation = blueprint.relations[id]
        if (relation == null) {
            sink.block(MappingFindingCode.UNKNOWN_RELATION, at, "blueprint '${pair.blueprint}' has no relation '$id'")
            return null
        }
        return PortSlot.Rel(relation.many)
    }

    private fun reverseSlot(port: PortAddress.Reverse, at: String): PortSlot? {
        val other = context.blueprints[port.blueprint]
        if (other == null) {
            sink.block(MappingFindingCode.UNKNOWN_BLUEPRINT, pointer(at, "blueprint"), "no active blueprint '${port.blueprint}'")
            return null
        }
        val relation = other.relations[port.relation]
        if (relation == null) {
            sink.block(
                MappingFindingCode.UNKNOWN_RELATION,
                pointer(at, "relation"),
                "blueprint '${port.blueprint}' has no relation '${port.relation}'",
            )
            return null
        }
        if (relation.target != pair.blueprint) {
            sink.block(
                MappingFindingCode.CODEC_TYPE_MISMATCH,
                at,
                "${port.blueprint}.${port.relation} targets '${relation.target}', not this pair's blueprint '${pair.blueprint}'",
            )
            return null
        }
        return PortSlot.Reverse
    }

    // ── The codec compatibility matrix ──────────────────────────────────────────────────────

    /** Null when the codec fits the Port slot and the Backstage target; otherwise the reason. */
    private fun mismatch(slot: PortSlot?, target: Target): String? = when (val codec = rule.codec) {
        is Codec.Copy -> copyMismatch(slot, target)
        is Codec.ValueMap -> valueMapMismatch(codec, slot, target)
        Codec.TagCategory -> tagCategoryMismatch(slot, target)
        Codec.Ref -> refMismatch(slot, target)
        Codec.Derive -> deriveMismatch(slot, target)
        Codec.Link -> linkMismatch(slot, target)
        is Codec.Constant ->
            if (rule.port == null && target == Target.TEXT) null else "constant writes a fixed value onto a text field and has no Port side"
    }

    private fun copyMismatch(slot: PortSlot?, target: Target): String? {
        val text = (slot is PortSlot.Text || slot == PortSlot.Num) && target == Target.TEXT
        val links = slot == PortSlot.ObjectArray && target == Target.LINKS
        return if (text || links) {
            null
        } else {
            "copy needs a string or number property (or the title/icon meta field) onto a text field, " +
                "or an array-of-object property onto links:rest"
        }
    }

    private fun tagCategoryMismatch(slot: PortSlot?, target: Target): String? = when {
        target != Target.TAGS || enumOfSlotOrNull(slot) == null ->
            "tagCategory needs an enum-backed string or string-array property onto a tags address"
        slot is PortSlot.TextArray && !slot.unique ->
            "a string-array property mapped to tags must set uniqueItems: true (a tag appears once)"
        else -> null
    }

    private fun refMismatch(slot: PortSlot?, target: Target): String? {
        val fits = (slot == PortSlot.Team || slot is PortSlot.Rel) && (target == Target.REF || target == Target.REF_LIST)
        return when {
            !fits -> "ref needs a relation (or the team meta field) onto an entity-reference spec field"
            slot is PortSlot.Rel && slot.many && target == Target.REF ->
                "a many-valued relation cannot fill the single-reference field; target a list field"
            else -> null
        }
    }

    private fun deriveMismatch(slot: PortSlot?, target: Target): String? =
        if (slot == PortSlot.Reverse && target == Target.REF_LIST) {
            null
        } else {
            "derive needs a reverse address onto an entity-reference LIST spec field"
        }

    private fun linkMismatch(slot: PortSlot?, target: Target): String? =
        if (slot is PortSlot.Text && slot.url && target == Target.LINK) {
            null
        } else {
            "link needs a url-format string property onto a link address"
        }

    private fun enumOfSlotOrNull(slot: PortSlot?): Set<String>? = slot?.let { enumOfSlot(it) }

    private fun valueMapMismatch(codec: Codec.ValueMap, slot: PortSlot?, target: Target): String? {
        val keys = codec.map.keys
        val expected = when {
            target != Target.TEXT -> return "valueMap writes onto a text field"
            slot == PortSlot.Flag -> setOf("true", "false")
            slot is PortSlot.Text && slot.enum != null -> slot.enum
            else -> return "valueMap needs an enum-backed string property or a boolean property"
        }
        if (codec.map.values.toSet().size != codec.map.size) return "valueMap must be a bijection — two keys map to the same value"
        if (keys != expected) {
            return "valueMap keys must be exactly the property's values: " + differenceMessage(keys, expected, "map", "property")
        }
        return null
    }

    // ── Registries (report-only drift) ──────────────────────────────────────────────────────

    private fun registryChecks(slot: PortSlot?) {
        val at = pointer(path, "backstage")
        when (val address = rule.backstage) {
            is BackstageAddress.Label -> labelCheck(address.key, pointer(at, "label"), slot)
            is BackstageAddress.Annotation -> annotationCheck(address.key, pointer(at, "annotation"))
            is BackstageAddress.Tags -> tagCategoryCheck(address.category, pointer(at, "tags"))
            is BackstageAddress.Metadata ->
                if (address.field == MetadataField.NAMESPACE) valuesWithin(context.registry.namespaces, "namespaces dictionary", at)
            is BackstageAddress.Spec ->
                if (address.field == SPEC_LIFECYCLE) valuesEqual(context.registry.lifecycles, "lifecycles dictionary", at)
            else -> Unit
        }
    }

    private fun labelCheck(key: String, at: String, slot: PortSlot?) {
        val label = context.registry.labels[key]
        if (label == null) {
            sink.drift(MappingDriftCode.REGISTRY_KEY_MISSING, at, "no label '$key' is registered")
            return
        }
        if (pair.kind !in label.first) {
            sink.drift(MappingDriftCode.REGISTRY_KIND_MISMATCH, at, "label '$key' does not apply to kind ${pair.kind}")
        }
        valuesEqual(label.second.toSet(), "label '$key' values", at)
        if (copiesFreeValue(slot)) {
            sink.drift(
                MappingDriftCode.VOCABULARY_DRIFT,
                at,
                "this rule copies a free value onto label '$key', whose values are a closed list — nothing guarantees the value is on it",
            )
        }
    }

    /** True for a prefix-less `copy` of a property with no closed value list (a free string or a number). */
    private fun copiesFreeValue(slot: PortSlot?): Boolean {
        val copy = rule.codec as? Codec.Copy ?: return false
        val free = slot == PortSlot.Num || (slot is PortSlot.Text && slot.enum == null)
        return copy.prefix == null && free
    }

    private fun annotationCheck(key: String, at: String) {
        val kinds = context.registry.annotationKeys[key]
        if (kinds == null) {
            sink.drift(MappingDriftCode.REGISTRY_KEY_MISSING, at, "no annotation key '$key' is registered")
        } else if (pair.kind !in kinds) {
            sink.drift(MappingDriftCode.REGISTRY_KIND_MISMATCH, at, "annotation key '$key' does not apply to kind ${pair.kind}")
        }
    }

    private fun tagCategoryCheck(category: String, at: String) {
        val registered = context.categories[category]
        if (registered == null) {
            sink.drift(MappingDriftCode.REGISTRY_KEY_MISSING, at, "tag category '$category' is not registered, or has no tags")
        } else if (pair.kind !in registered.second) {
            sink.drift(MappingDriftCode.REGISTRY_KIND_MISMATCH, at, "tag category '$category' does not apply to kind ${pair.kind}")
        }
    }

    private fun produced(): Set<String>? = blueprint?.let { producedValues(rule, it) }

    private fun valuesEqual(registered: Set<String>, name: String, at: String) {
        val values = produced() ?: return
        if (values != registered) {
            sink.drift(
                MappingDriftCode.VOCABULARY_DRIFT,
                at,
                "the values this rule produces differ from the $name: " + differenceMessage(values, registered, "rule", "registry"),
            )
        }
    }

    private fun valuesWithin(registered: Set<String>, name: String, at: String) {
        val stray = (produced() ?: return) - registered
        if (stray.isNotEmpty()) {
            sink.drift(MappingDriftCode.VOCABULARY_DRIFT, at, "${stray.sorted().joinToString()} not in the $name")
        }
    }
}
