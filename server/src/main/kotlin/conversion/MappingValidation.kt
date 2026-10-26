package ch.nokillswit.conversion

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.catalog.RegistrySnapshot
import ch.nokillswit.catalog.SPEC_SHAPES
import ch.nokillswit.catalog.SPEC_TYPE
import ch.nokillswit.catalog.SUPPORTED_KINDS
import ch.nokillswit.catalog.SpecShape
import ch.nokillswit.catalog.requiredSpecFields
import kotlinx.serialization.json.JsonElement

/**
 * The mapping validator — PURE functions of (document, current blueprints, current registries):
 * the service calls it inside the write transaction with snapshots it read there (no lock — a
 * mapping write races nothing it must serialize against), the read paths recompute it per
 * stored row, and the tests call it directly. Two outputs, deliberately separate:
 *
 * - **blocking findings** ([MappingFindingCode]): the document is structurally or semantically
 *   unusable — a write is refused with all of them;
 * - **drift** ([MappingDriftCode]): the document is usable but the registries and blueprints it
 *   mirrors disagree with it (an enum that is no longer the registry's list, a label key that
 *   was deleted) — reported on every read and in the Errors report, never blocking.
 *
 * The Backstage structural tables (which spec fields a kind allows or requires, which shape each
 * has) are NOT copied here: `catalog/CatalogFileValidation.kt` owns them and this file reads
 * them through `allowedSpecFields`/[requiredSpecFields]/[SPEC_SHAPES].
 */
internal fun validateMapping(
    raw: JsonElement,
    blueprints: Map<String, BlueprintDefinition>,
    registry: RegistrySnapshot,
): MappingVerdict {
    val parsed = parseMappingDocument(raw)
    val document = parsed.document ?: return MappingVerdict(parsed.findings, emptyList())
    return validateMappingDocument(document, blueprints, registry)
}

internal fun validateMappingDocument(
    document: MappingDocument,
    blueprints: Map<String, BlueprintDefinition>,
    registry: RegistrySnapshot,
): MappingVerdict {
    val sink = VerdictSink()
    val context = MappingContext(blueprints, registry, document.pairs.reversed().associate { it.blueprint to it.kind })
    val kindsWithPair = mutableMapOf<String, MutableList<Int>>()
    document.pairs.forEachIndexed { index, pair ->
        PairValidator(pair, "/pairs/$index", context, sink).validate()
        if (pair.kind in SUPPORTED_KINDS) kindsWithPair.getOrPut(pair.kind) { mutableListOf() } += index
    }
    checkDuplicateBlueprints(document, sink)
    kindsWithPair.forEach { (kind, indices) ->
        checkSelectorAmbiguity(document, kind, indices, context, sink)
        checkTypeVocabulary(document, kind, indices, context, sink)
    }
    return MappingVerdict(sink.findings.toList(), sink.drift.toList())
}

/** Collects the two output lists; every message is English prose for humans, the code + path are the contract. */
internal class VerdictSink {
    val findings = mutableListOf<MappingFinding>()
    val drift = mutableListOf<MappingDrift>()

    fun block(code: MappingFindingCode, path: String, message: String) {
        findings += MappingFinding(code, path, message)
    }

    fun drift(code: MappingDriftCode, path: String, message: String) {
        drift += MappingDrift(code, path, message)
    }
}

/**
 * What the document is validated AGAINST: the active blueprints and one registry snapshot, plus
 * [kindByBlueprint] — the document's own blueprint -> kind assignment, which a `ref`/`derive` rule's
 * target check reads (a relation's target blueprint must itself be mapped, to a kind the spec field
 * may reference).
 */
internal class MappingContext(
    val blueprints: Map<String, BlueprintDefinition>,
    val registry: RegistrySnapshot,
    val kindByBlueprint: Map<String, String> = emptyMap(),
) {
    /** tag category -> (its tags, the kinds it applies to), regrouped from the snapshot's tag -> category map. */
    val categories: Map<String, Pair<Set<String>, List<String>>> = registry.tags.entries
        .groupBy({ it.value.first }, { it.key to it.value.second })
        .mapValues { (_, tags) -> tags.map { it.first }.toSet() to tags.first().second }
}

private fun checkDuplicateBlueprints(document: MappingDocument, sink: VerdictSink) {
    val firstIndex = mutableMapOf<String, Int>()
    document.pairs.forEachIndexed { index, pair ->
        val first = firstIndex.putIfAbsent(pair.blueprint, index)
        if (first != null) {
            sink.block(
                MappingFindingCode.SCHEMA,
                "/pairs/$index/blueprint",
                "blueprint '${pair.blueprint}' is already mapped by /pairs/$first — a blueprint maps to exactly one kind",
            )
        }
    }
}

/**
 * Import must be able to choose ONE pair per Backstage file of a kind, so for every kind:
 * (1) at most one selector-less pair; (2) no two selector pairs reading the same spec field with
 * intersecting `in` sets; (3) no selector-less pair able to PRODUCE a value that another pair's
 * `in` claims (e.g. the fallback `service.type` enum gaining `library`); (4) every selector pair's
 * own produced values for its selector field stay inside its `in` (otherwise what it exports would
 * be re-imported through a different pair). All are `AMBIGUOUS_SELECTOR`.
 */
private fun checkSelectorAmbiguity(
    document: MappingDocument,
    kind: String,
    indices: List<Int>,
    context: MappingContext,
    sink: VerdictSink,
) {
    val fallbacks = indices.filter { document.pairs[it].select == null }
    fallbacks.drop(1).forEach { index ->
        sink.block(
            MappingFindingCode.AMBIGUOUS_SELECTOR,
            "/pairs/$index",
            "kind $kind already has a selector-less pair (/pairs/${fallbacks.first()}) — give all but one a select",
        )
    }
    val selected = indices.filter { document.pairs[it].select != null }
    selected.forEachIndexed { position, index ->
        val select = document.pairs[index].select!!
        selected.drop(position + 1).forEach { other ->
            val otherSelect = document.pairs[other].select!!
            val shared = select.values.toSet().intersect(otherSelect.values.toSet())
            if (select.spec == otherSelect.spec && shared.isNotEmpty()) {
                sink.block(
                    MappingFindingCode.AMBIGUOUS_SELECTOR,
                    "/pairs/$other/select/in",
                    "kind $kind: /pairs/$index and /pairs/$other both select ${shared.sorted().joinToString()} on spec.${select.spec}",
                )
            }
        }
    }
    for (index in indices) checkProducedAgainstSelectors(document, index, selected, context, sink)
}

/** Rules (3) and (4) of [checkSelectorAmbiguity] for the pair at [index]. */
private fun checkProducedAgainstSelectors(
    document: MappingDocument,
    index: Int,
    selected: List<Int>,
    context: MappingContext,
    sink: VerdictSink,
) {
    val pair = document.pairs[index]
    val blueprint = context.blueprints[pair.blueprint] ?: return
    pair.rules.forEachIndexed { ruleIndex, rule ->
        val address = rule.backstage as? BackstageAddress.Spec ?: return@forEachIndexed
        val produced = if (rule.writesOnExport) producedValues(rule, blueprint) else null
        if (produced != null) {
            checkProduced(document, "/pairs/$index/rules/$ruleIndex", pair.select, address.field, produced, selected, sink)
        }
    }
}

private fun checkProduced(
    document: MappingDocument,
    path: String,
    own: PairSelector?,
    field: String,
    produced: Set<String>,
    selected: List<Int>,
    sink: VerdictSink,
) {
    if (own != null) {
        val outside = if (own.spec == field) produced - own.values.toSet() else emptySet()
        if (outside.isNotEmpty()) {
            sink.block(
                MappingFindingCode.AMBIGUOUS_SELECTOR,
                path,
                "this pair exports ${outside.sorted().joinToString()} on spec.$field, outside its own select" +
                    " — import would route it elsewhere",
            )
        }
        return
    }
    for (other in selected) {
        val otherSelect = document.pairs[other].select!!
        val claimed = if (otherSelect.spec == field) produced.intersect(otherSelect.values.toSet()) else emptySet()
        if (claimed.isNotEmpty()) {
            sink.block(
                MappingFindingCode.AMBIGUOUS_SELECTOR,
                path,
                "this selector-less pair can export ${claimed.sorted().joinToString()}, which /pairs/$other selects" +
                    " — import could not tell them apart",
            )
        }
    }
}

/**
 * Per kind, the UNION of `spec.type` values its pairs can produce must equal the kind's type
 * dictionary — a pair alone need not cover it (the Component dictionary is the service pair's
 * enum plus the library pair's constant). Skipped when no pair writes `spec.type`, or when any
 * contributing rule's value set is not statically known (a free-string copy).
 */
private fun checkTypeVocabulary(document: MappingDocument, kind: String, indices: List<Int>, context: MappingContext, sink: VerdictSink) {
    val dictionary = context.registry.types[kind].orEmpty().toSet()
    val produced = mutableSetOf<String>()
    var firstPath: String? = null
    for (index in indices) {
        val pair = document.pairs[index]
        val blueprint = context.blueprints[pair.blueprint] ?: continue
        pair.rules.forEachIndexed { ruleIndex, rule ->
            if (rule.backstage != BackstageAddress.Spec(SPEC_TYPE) || !rule.writesOnExport) return@forEachIndexed
            firstPath = firstPath ?: "/pairs/$index/rules/$ruleIndex"
            val values = producedValues(rule, blueprint)
            if (values == null) return
            produced += values
        }
    }
    val path = firstPath ?: return
    if (produced != dictionary) {
        sink.drift(
            MappingDriftCode.VOCABULARY_DRIFT,
            path,
            "the spec.type values the $kind pairs can produce differ from the $kind type dictionary: " +
                differenceMessage(produced, dictionary, "mapping", "dictionary"),
        )
    }
}

/** "mapping has a, b the dictionary lacks; dictionary has c the mapping lacks". */
internal fun differenceMessage(left: Set<String>, right: Set<String>, leftName: String, rightName: String): String {
    val onlyLeft = (left - right).sorted()
    val onlyRight = (right - left).sorted()
    return listOfNotNull(
        onlyLeft.takeIf { it.isNotEmpty() }?.let { "$leftName has ${it.joinToString()} the $rightName lacks" },
        onlyRight.takeIf { it.isNotEmpty() }?.let { "$rightName has ${it.joinToString()} the $leftName lacks" },
    ).joinToString("; ")
}

/** True when [field] is a plain-text (non-reference) spec field. */
internal fun isTextSpecField(field: String): Boolean = SPEC_SHAPES[field] == SpecShape.TEXT

internal fun requiredFieldsOf(kind: String): Set<String> = requiredSpecFields(kind).orEmpty()
