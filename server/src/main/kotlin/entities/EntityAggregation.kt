package ch.nokillswit.entities

import ch.nokillswit.blueprints.AggregationCalculationSpec
import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.BlueprintDefinition
import java.time.OffsetDateTime
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Phase 5 aggregation candidates (`.claude/docs/port-data-model.md` "Aggregation properties"):
 * finding the TARGET-blueprint rows an `aggregationProperties` entry counts/measures over, and
 * reducing them per its `calculationSpec`. Pure, DB-free — every lookup goes through
 * [EntityIndex]. Decision: "direct relations + `pathFilter`, then Port's `query` rules, then
 * `calculationSpec`" ([EntityComputed.kt]'s `aggregationValue` wires the three together).
 */

private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_WEEK = 7 * SECONDS_PER_DAY
private const val SECONDS_PER_MONTH = 30 * SECONDS_PER_DAY

/** [this] as an [AggregationQuery] filter subject of [blueprint] — its EFFECTIVE team resolved via [effectiveTeamOf]. */
fun IndexedRow.toQueryCandidate(
    blueprint: String,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
): QueryCandidate =
    QueryCandidate(
        identifier = identifier,
        title = title,
        blueprint = blueprint,
        icon = icon,
        team = teamValues(effectiveTeamOf(this, blueprint, blueprintsByIdentifier, index)),
        createdAt = createdAt,
        updatedAt = updatedAt,
        properties = document.properties,
    )

/**
 * Every ACTIVE row of [def]'s `target` blueprint related to [subject] — deduped by identifier.
 * No `pathFilter` → DIRECT relations in EITHER direction: entities of `target` naming [subject]
 * (via [EntityIndex.inbound]) UNION every entity [subject]'s OWN relations targeting it names (via
 * [EntityIndex.row]); a self-targeting aggregation counts both sides (documented). Each
 * `pathFilter` entry `{fromBlueprint, path}` (malformed → nothing) narrows to ONE direction; any
 * `fromBlueprint` other than [subject]'s own blueprint or `target` contributes nothing
 * (**assumption**).
 */
fun relatedEntities(
    subject: ComputedSubject,
    def: AggregationPropertyDefinition,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
): List<IndexedRow> {
    val target = def.target
    if (target !in blueprintsByIdentifier) return emptyList()
    val filters = def.pathFilter.orEmpty()
    val rows = if (filters.isEmpty()) {
        directCandidates(subject, target, blueprintsByIdentifier, index)
    } else {
        filters.flatMap { entry -> pathFilterCandidates(entry, subject, target, blueprintsByIdentifier, index) }
    }
    return rows.distinctBy { it.identifier }
}

private fun directCandidates(
    subject: ComputedSubject,
    target: String,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
): List<IndexedRow> {
    val inboundHits = index.inbound(subject.blueprint, subject.identifier).filter { it.source.blueprint == target }.map { it.source }
    val outboundHits = blueprintsByIdentifier[subject.blueprint]?.relations.orEmpty()
        .filterValues { it.target == target }
        .flatMap { (relationId, relation) -> hopTargetIdentifiers(subject.document.relations[relationId], relation.many) }
        .mapNotNull { index.row(target, it) }
    return inboundHits + outboundHits
}

private fun pathFilterCandidates(
    entry: JsonObject,
    subject: ComputedSubject,
    target: String,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
): List<IndexedRow> {
    val from = (entry["fromBlueprint"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
    val path = (entry["path"] as? JsonArray)?.filterIsInstance<JsonPrimitive>()?.filter { it.isString }?.map { it.content }.orEmpty()
    if (path.isEmpty()) return emptyList()
    return when (from) {
        subject.blueprint -> forwardPathCandidates(subject, path, target, blueprintsByIdentifier, index)
        target -> reversePathCandidates(subject, path, target, blueprintsByIdentifier, index)
        else -> emptyList()
    }
}

/**
 * `fromBlueprint == subject's own blueprint`: forward walk from [subject], fanning out on
 * `many`; the LAST relation must target [target].
 */
private fun forwardPathCandidates(
    subject: ComputedSubject,
    path: List<String>,
    target: String,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
): List<IndexedRow> {
    if (path.size > MAX_COMPUTED_HOPS) return emptyList()
    var currentBlueprint = subject.blueprint
    var currentRows = listOf(subject.toIndexedRow())
    path.forEachIndexed { i, relationId ->
        val relation = blueprintsByIdentifier[currentBlueprint]?.relations?.get(relationId) ?: return emptyList()
        if (relation.target !in blueprintsByIdentifier) return emptyList()
        if (i == path.lastIndex && relation.target != target) return emptyList()
        currentRows = landHop(currentRows, relationId, relation.target, relation.many, index)
        currentBlueprint = relation.target
    }
    return currentRows
}

/**
 * `fromBlueprint == target`: statically resolve `target -r1-> X1 ... -rn-> Xn` (must end at
 * [subject]'s own blueprint), then hop BACKWARDS from [subject] through [EntityIndex.inbound].
 */
private fun reversePathCandidates(
    subject: ComputedSubject,
    path: List<String>,
    target: String,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
): List<IndexedRow> {
    if (path.size > MAX_COMPUTED_HOPS) return emptyList()
    val expectedBlueprints = mutableListOf(target)
    var current = target
    for (relationId in path) {
        val relation = blueprintsByIdentifier[current]?.relations?.get(relationId) ?: return emptyList()
        if (relation.target !in blueprintsByIdentifier) return emptyList()
        current = relation.target
        expectedBlueprints += current
    }
    if (current != subject.blueprint) return emptyList()

    var frontier: List<IndexedRow> = listOf(subject.toIndexedRow())
    for (step in path.indices.reversed()) {
        val relationId = path[step]
        val expectedSource = expectedBlueprints[step]
        val nextFrontier = mutableListOf<IndexedRow>()
        for (node in frontier) {
            index.inbound(node.blueprint, node.identifier)
                .filter { it.relationId == relationId && it.source.blueprint == expectedSource }
                .forEach { nextFrontier += it.source }
        }
        frontier = nextFrontier.take(MAX_MIRROR_FANOUT)
        if (frontier.isEmpty()) return emptyList()
    }
    return frontier
}

/**
 * Reduces [rows] per [spec]: `entities/count` is the row count (0 is a value); `entities/average`
 * divides that count by the elapsed period count since the EARLIEST parseable `measureTimeBy`
 * (choice 3, **assumption**: hour/day/week/month = 3600s/86400s/7d/30d, `total` = plain count,
 * unparseable timestamps skipped, nothing measurable → absent); `property/<func>` reduces the
 * numeric values of `spec.property` (non-numeric skipped, none → absent). Integral results emit
 * as a JSON integer (`16`, never `16.0`).
 */
fun applyCalculationSpec(spec: AggregationCalculationSpec, rows: List<QueryCandidate>, now: Long): JsonElement? =
    when (spec.calculationBy) {
        "entities" -> entitiesCalculation(spec, rows, now)
        "property" -> propertyCalculation(spec, rows)
        else -> null
    }

private fun entitiesCalculation(spec: AggregationCalculationSpec, rows: List<QueryCandidate>, now: Long): JsonElement? = when (spec.func) {
    "count" -> JsonPrimitive(rows.size.toLong())
    "average" -> averageValue(spec, rows, now)
    else -> null
}

private fun averageValue(spec: AggregationCalculationSpec, rows: List<QueryCandidate>, now: Long): JsonElement? {
    if (spec.averageOf == "total") return JsonPrimitive(rows.size.toLong())
    val periodSeconds = periodSecondsFor(spec.averageOf) ?: return null
    val times = rows.mapNotNull { measuredTimeMillis(spec.measureTimeBy, it) }
    if (times.isEmpty()) return null
    val elapsedSeconds = ((now - times.min()) / 1000.0).coerceAtLeast(0.0)
    val periods = max(1L, ceil(elapsedSeconds / periodSeconds).toLong())
    return integralOrDouble(rows.size.toDouble() / periods)
}

private fun periodSecondsFor(averageOf: String?): Long? = when (averageOf) {
    "hour" -> SECONDS_PER_HOUR
    "day" -> SECONDS_PER_DAY
    "week" -> SECONDS_PER_WEEK
    "month" -> SECONDS_PER_MONTH
    else -> null
}

private fun measuredTimeMillis(measureTimeBy: String?, candidate: QueryCandidate): Long? = when (measureTimeBy) {
    "\$createdAt" -> candidate.createdAt
    "\$updatedAt" -> candidate.updatedAt
    null -> null
    else -> {
        val text = (candidate.properties[measureTimeBy] as? JsonPrimitive)?.takeIf { it.isString }?.content
        text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    }
}

private fun propertyCalculation(spec: AggregationCalculationSpec, rows: List<QueryCandidate>): JsonElement? {
    val propertyId = spec.property ?: return null
    val numbers = rows.mapNotNull { (it.properties[propertyId] as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull }
    if (numbers.isEmpty()) return null
    val result = when (spec.func) {
        "sum" -> numbers.sum()
        "min" -> numbers.min()
        "max" -> numbers.max()
        "average" -> numbers.average()
        "median" -> median(numbers)
        else -> return null
    }
    return integralOrDouble(result)
}

private fun median(numbers: List<Double>): Double {
    val sorted = numbers.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
}

private fun integralOrDouble(value: Double): JsonElement =
    if (value.isFinite() && value == Math.floor(value)) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
