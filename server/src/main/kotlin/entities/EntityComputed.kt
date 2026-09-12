package ch.nokillswit.entities

import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.jsonMatchesType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 5 (`.claude/docs/port-data-model.md` "Computed properties"): the orchestrator that
 * evaluates a blueprint's `mirrorProperties`/`calculationProperties`/`aggregationProperties`
 * against ONE entity (the [ComputedSubject]) at READ time, over the [EntityIndex] snapshot
 * `EntityService` already loads under its transaction — this file, `EntityAggregation.kt` and
 * `AggregationQuery.kt` are pure and DB-free: every lookup goes through [EntityIndex], never a
 * fresh query, so evaluation can safely run AFTER the transaction closes
 * (`.claude/docs/persistence.md`). An unresolvable value is ABSENT — never `null`, never a
 * finding; only [entityFindings] (`EntityValidation.kt`) can fail a save.
 */

/** Defensive hop budget for a mirror path and a `pathFilter` chain — mirrors [MAX_OWNERSHIP_HOPS] one layer up. */
const val MAX_COMPUTED_HOPS = 10

/** Caps how many rows a single `many` hop (mirror fan-out, or an aggregation `pathFilter` step) may land, before the walk stops growing. */
const val MAX_MIRROR_FANOUT = 1000

/**
 * One entity row as computed-property evaluation needs it — [EntityOwnership.kt]'s [OwnedRow]
 * widened with the identity/timestamp fields mirror and aggregation terminals read. [team] is
 * always the STORED (raw) value; callers resolve the EFFECTIVE one via [effectiveTeamOf].
 */
data class IndexedRow(
    val blueprint: String,
    val identifier: String,
    val title: String,
    val icon: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val document: EntityDocument,
    val team: JsonElement?,
)

/** [IndexedRow] narrowed to what [EntityOwnership.kt]'s Inherited walk needs. */
fun IndexedRow.asOwned(): OwnedRow = OwnedRow(blueprint, identifier, document, team)

/** [subject] wrapped as an [IndexedRow] — the walk's own identity is hop zero of a mirror path or a `pathFilter` chain. */
fun ComputedSubject.toIndexedRow(): IndexedRow = IndexedRow(blueprint, identifier, title, icon, createdAt, updatedAt, document, team)

/** One inbound reference: [source] names the CURRENT row through its own relation [relationId]. */
data class Inbound(val source: IndexedRow, val relationId: String)

/**
 * The read-only snapshot computed-property evaluation walks: [row] resolves one entity by
 * identity, [inbound] answers "who points AT this entity", and [rowLookup] is the narrower shape
 * [EntityOwnership.kt]'s Inherited walk already expects. `EntityService`'s snapshot is the only
 * implementation; a lazily-built index backs [inbound] so list/read/create pay for it only when a
 * computed property actually asks for it — `update`/`graph` never touch [row]/[inbound] at all.
 */
interface EntityIndex {
    fun row(blueprint: String, identifier: String): IndexedRow?
    fun inbound(blueprint: String, identifier: String): List<Inbound>
    val rowLookup: RowLookup
}

/**
 * The entity [computedProperties] evaluates FOR — its EFFECTIVE team already resolved by the
 * caller (feeds `$team` and the calculation jq input alike).
 */
data class ComputedSubject(
    val blueprint: String,
    val identifier: String,
    val title: String,
    val icon: String?,
    val team: JsonElement?,
    val document: EntityDocument,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The identifiers a hop's relation VALUE carries — one string for `many: false`, every string entry for `many: true`. */
fun hopTargetIdentifiers(value: JsonElement?, many: Boolean): List<String> = when {
    value == null -> emptyList()
    many -> (value as? JsonArray)?.filterIsInstance<JsonPrimitive>()?.filter { it.isString }?.map { it.content }.orEmpty()
    value is JsonPrimitive && value.isString -> listOf(value.content)
    else -> emptyList()
}

/**
 * Follows [rows]' [relationId] value(s) (single or `many`, per [many]) into [EntityIndex.row]
 * lookups of [target], capped at [MAX_MIRROR_FANOUT].
 */
fun landHop(rows: List<IndexedRow>, relationId: String, target: String, many: Boolean, index: EntityIndex): List<IndexedRow> {
    val landed = mutableListOf<IndexedRow>()
    outer@ for (row in rows) {
        for (identifier in hopTargetIdentifiers(row.document.relations[relationId], many)) {
            index.row(target, identifier)?.let { landed += it }
            if (landed.size >= MAX_MIRROR_FANOUT) break@outer
        }
    }
    return landed
}

/**
 * The EFFECTIVE team of a snapshot [row] belonging to [blueprint] — Direct/absent returns the
 * stored value, Inherited walks [EntityOwnership.kt]'s `effectiveTeam`.
 */
fun effectiveTeamOf(
    row: IndexedRow,
    blueprint: String,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
): JsonElement? {
    val definition = blueprintsByIdentifier[blueprint] ?: return null
    return effectiveTeam(row.team, row.document, definition, blueprintsByIdentifier, index.rowLookup)
}

/**
 * Every computed value for [subject] under its OWN [definition] — mirror, then calculation, then
 * aggregation, each family in its OWN declaration order (a stale stored key never fails HERE:
 * that is [entityFindings]'s job, run over the STORED document). Never throws its OWN failures
 * (jq's are absorbed by [JqEvaluator.evaluateBounded]) — the one exception is the caller's own
 * cancellation, which propagates unchanged.
 */
suspend fun computedProperties(
    subject: ComputedSubject,
    definition: BlueprintDefinition,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
    jq: JqEvaluator,
    now: Long,
): JsonObject {
    val result = LinkedHashMap<String, JsonElement>()
    definition.mirrorProperties.forEach { (id, def) ->
        mirrorValue(def.path, subject, blueprintsByIdentifier, index)?.let { result[id] = it }
    }
    definition.calculationProperties.forEach { (id, def) ->
        calculationValue(id, def, subject, jq)?.let { result[id] = it }
    }
    definition.aggregationProperties.forEach { (id, def) ->
        aggregationValue(def, subject, blueprintsByIdentifier, index, now)?.let { result[id] = it }
    }
    return JsonObject(result)
}

// -------------------------------------------------------------------------------------------
// Mirror properties
// -------------------------------------------------------------------------------------------

/**
 * Walks every segment but the last of [path] as a relation of the CURRENT blueprint (starting at
 * [subject]'s own), landing on the entities each hop's value names; the LAST segment is the
 * terminal, read off every landed row. Absent when a hop's relation is unknown, its target
 * blueprint is unknown, or the chain exceeds [MAX_COMPUTED_HOPS] (the give-up conditions
 * `EntityOwnership.kt`'s `ownershipHop` uses one layer up) — an individual unresolved VALUE is
 * simply skipped, never a give-up. No `many` hop along the way → the single landed value (or
 * absent when nothing landed); any `many` hop → a deduped, one-level-flattened [JsonArray] of
 * every landed value (`[]` when the chain resolved but nothing landed).
 */
fun mirrorValue(
    path: String,
    subject: ComputedSubject,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
): JsonElement? {
    val segments = path.split('.').map { it.trim() }.filter { it.isNotEmpty() }
    if (segments.isEmpty()) return null
    val hops = segments.dropLast(1)
    val terminal = segments.last()
    if (hops.size > MAX_COMPUTED_HOPS) return null

    var currentBlueprint = subject.blueprint
    var currentRows = listOf(subject.toIndexedRow())
    var anyMany = false

    for (segment in hops) {
        val definition = blueprintsByIdentifier[currentBlueprint] ?: return null
        val relation = definition.relations[segment] ?: return null
        if (relation.target !in blueprintsByIdentifier) return null
        anyMany = anyMany || relation.many
        currentRows = landHop(currentRows, segment, relation.target, relation.many, index)
        currentBlueprint = relation.target
    }

    val values = currentRows.mapNotNull { terminalValue(terminal, currentBlueprint, it, blueprintsByIdentifier, index) }
    return if (!anyMany) {
        values.firstOrNull()
    } else {
        JsonArray(LinkedHashSet(values.flatMap { if (it is JsonArray) it else listOf(it) }).toList())
    }
}

private fun terminalValue(
    terminal: String,
    landedBlueprint: String,
    row: IndexedRow,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
): JsonElement? {
    if (terminal.startsWith("$")) return metaTerminalValue(terminal, landedBlueprint, row, blueprintsByIdentifier, index)
    val definition = blueprintsByIdentifier[landedBlueprint] ?: return null
    if (terminal in computedPropertyIds(definition)) return null // a computed id of the LANDED blueprint -> absent, never recursed into
    if (terminal !in definition.schema.properties) return null
    return row.document.properties[terminal]
}

private fun metaTerminalValue(
    terminal: String,
    landedBlueprint: String,
    row: IndexedRow,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
): JsonElement? = when (terminal) {
    "\$identifier" -> JsonPrimitive(row.identifier)
    "\$title" -> JsonPrimitive(row.title)
    "\$icon" -> row.icon?.let { JsonPrimitive(it) }
    "\$blueprint" -> JsonPrimitive(landedBlueprint)
    "\$team" -> effectiveTeamOf(row, landedBlueprint, blueprintsByIdentifier, index)
    "\$createdAt" -> JsonPrimitive(row.createdAt)
    "\$updatedAt" -> JsonPrimitive(row.updatedAt)
    else -> null // $createdBy/$updatedBy and any other meta-property: not resolved -> absent
}

// -------------------------------------------------------------------------------------------
// Calculation properties
// -------------------------------------------------------------------------------------------

/**
 * Runs [def]'s jq expression over `{identifier, title, blueprint, icon?, team?, properties,
 * relations}` — the STORED properties/relations, the EFFECTIVE team (**assumption**: no id or
 * timestamps, matching Port's own `.identifier`/`.properties.x`/`.relations.r` calculation
 * examples) — then coerces the first output STRICTLY against [def]'s declared `type`
 * ([jsonMatchesType], the same structural check [entityFindings] itself uses): a mismatched
 * shape is absent, never coerced (`tostring`/`tonumber` are the admin's own tools). Runs on
 * [JqEvaluator]'s bounded pool under its per-expression deadline ([JqEvaluator.evaluateBounded]).
 */
suspend fun calculationValue(id: String, def: CalculationPropertyDefinition, subject: ComputedSubject, jq: JqEvaluator): JsonElement? {
    val input = buildJsonObject {
        put("identifier", subject.identifier)
        put("title", subject.title)
        put("blueprint", subject.blueprint)
        subject.icon?.let { put("icon", it) }
        subject.team?.let { put("team", it) }
        put("properties", subject.document.properties)
        put("relations", subject.document.relations)
    }
    val result = jq.evaluateBounded(def.calculation, input, "${subject.blueprint}.$id") ?: return null
    return result.takeIf { jsonMatchesType(def.type, it) }
}

// -------------------------------------------------------------------------------------------
// Aggregation properties
// -------------------------------------------------------------------------------------------

private fun aggregationValue(
    def: AggregationPropertyDefinition,
    subject: ComputedSubject,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    index: EntityIndex,
    now: Long,
): JsonElement? {
    val candidates = relatedEntities(subject, def, blueprintsByIdentifier, index)
        .map { it.toQueryCandidate(def.target, blueprintsByIdentifier, index) }
        .filter { matchesQuery(def.query, it) }
    return applyCalculationSpec(def.calculationSpec, candidates, now)
}

// -------------------------------------------------------------------------------------------
// Snapshot widening — the static twin of EntityOwnership.kt's ownershipPathBlueprints
// -------------------------------------------------------------------------------------------

/**
 * Every blueprint identifier `EntityService`'s snapshot must ALSO load active rows for, so
 * [mirrorValue]/aggregation evaluation never queries the database mid-evaluation: mirror-path
 * targets hop by hop, every aggregation `target`, every `pathFilter` chain's touched blueprints
 * (both directions), plus [ownershipPathBlueprints] for each of those landed blueprints — their
 * OWN `$team` may itself be Inherited. Best-effort like its ownership twin: an unresolvable
 * prefix simply stops widening rather than failing the whole call.
 */
fun computedPathBlueprints(definition: BlueprintDefinition, blueprintsByIdentifier: Map<String, BlueprintDefinition>): Set<String> {
    val targets = mutableSetOf<String>()
    definition.mirrorProperties.values.forEach { targets += mirrorPathBlueprints(definition, it.path, blueprintsByIdentifier) }
    definition.aggregationProperties.values.forEach { agg ->
        targets += agg.target
        agg.pathFilter?.forEach { entry -> targets += pathFilterChainBlueprints(entry, blueprintsByIdentifier) }
    }
    targets.toList().forEach { identifier ->
        blueprintsByIdentifier[identifier]?.let { targets += ownershipPathBlueprints(it, blueprintsByIdentifier) }
    }
    return targets
}

private fun mirrorPathBlueprints(
    ownDefinition: BlueprintDefinition,
    path: String,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
): Set<String> {
    val segments = path.split('.').map { it.trim() }.filter { it.isNotEmpty() }
    if (segments.size <= 1) return emptySet()
    val targets = mutableSetOf<String>()
    var currentDefinition: BlueprintDefinition? = ownDefinition
    for (segment in segments.dropLast(1)) {
        val definition = currentDefinition ?: break
        val relation = definition.relations[segment] ?: break
        targets += relation.target
        currentDefinition = blueprintsByIdentifier[relation.target]
    }
    return targets
}

private fun pathFilterChainBlueprints(entry: JsonObject, blueprintsByIdentifier: Map<String, BlueprintDefinition>): Set<String> {
    val from = (entry["fromBlueprint"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptySet()
    val path = (entry["path"] as? JsonArray)?.filterIsInstance<JsonPrimitive>()?.filter { it.isString }?.map { it.content }.orEmpty()
    if (path.isEmpty()) return emptySet()
    var currentDefinition = blueprintsByIdentifier[from] ?: return emptySet()
    val targets = mutableSetOf<String>()
    for (relationId in path) {
        val relation = currentDefinition.relations[relationId] ?: break
        targets += relation.target
        currentDefinition = blueprintsByIdentifier[relation.target] ?: break
    }
    return targets
}
