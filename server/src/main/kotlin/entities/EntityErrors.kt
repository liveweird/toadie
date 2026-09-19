package ch.nokillswit.entities

import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.MirrorPropertyDefinition
import ch.nokillswit.entityquery.QUERY_META_PROPERTIES
import ch.nokillswit.entityquery.QueryDiagnostic
import ch.nokillswit.entityquery.QueryException
import ch.nokillswit.entityquery.QuerySchema
import ch.nokillswit.entityquery.SavedEntityQueryService
import ch.nokillswit.entityquery.SavedEntityQueryVisibility
import ch.nokillswit.entityquery.parseEntityQuery
import ch.nokillswit.entityquery.savedQueriesVisibleTo
import ch.nokillswit.entityquery.validateEntityQuery
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The Port-world Errors report (2.5.0, `GET /api/v1/entities/errors`) — the `catalog/Errors.kt`
 * template one level over: pure checkers over a snapshot the service already loaded, no audit
 * event (the `/check`/`/errors`/export rule), any authenticated user.
 *
 * Five finding classes, one report:
 *
 * 1. **Stale entities** — the 19 existing [entityFindings] codes (`EntityValidation.kt`),
 *    unchanged and HARD on the entity's next save. Reported here purely so a workspace-wide
 *    sweep does not require opening every entity.
 * 2. **Ownership** — [OWNERSHIP_UNRESOLVED] (an Inherited entity whose effective team resolved
 *    to nothing) and [OWNERSHIP_PATH_STALE] (a blueprint's `ownership.path` itself can never
 *    resolve, given the CURRENT blueprint graph). A blueprint carrying the latter suppresses
 *    every one of its entities' [OWNERSHIP_UNRESOLVED] rows — the path is the root cause, not
 *    each individual entity.
 * 3. **Computed-property health** — [MIRROR_PATH_STALE], [AGGREGATION_PATH_STALE],
 *    [AGGREGATION_PROPERTY_STALE], [CALCULATION_COMPILE_FAILED], [CALCULATION_QUARANTINED].
 *    **Confirmed fact**: a blueprint definition itself can never go structurally invalid after
 *    the fact — every write re-validates the whole request, relation/aggregation `target`s must
 *    exist under the V27 lock, an identifier rename cascades, and a targeted/system blueprint
 *    cannot be deleted (`.claude/docs/persistence.md`). What CAN drift is exactly what no
 *    cascade covers: a mirror path's hop 2+, an aggregation `pathFilter` chain or
 *    `calculationSpec.property`/`measureTimeBy`, and an ownership path's hop 2+ — none of these
 *    are tracked as rename/delete targets, since they live inside open JSON
 *    (`pathFilter`/`calculationSpec`) or a dot-path string, so a LATER edit to the blueprint at
 *    the far end of the chain can silently break them. [CALCULATION_QUARANTINED] is
 *    INSTANCE-LOCAL: it reflects THIS server process's quarantine set
 *    (`entities/JqCalculation.kt`), cleared by a restart or the admin editing the text — a
 *    multi-instance deployment (not a documented posture today) could show it inconsistently
 *    across instances.
 * 4. **Saved queries** — every saved entity query the caller may see (own + everyone's PUBLIC,
 *    the `entityquery/SavedEntityQueryService.kt` rule), re-parsed/re-validated against the
 *    CURRENT blueprints/hierarchies. Query EVALUATION diagnostics are explicitly OUT of scope —
 *    those are per-request and already inline on the query bar.
 * 5. **Source references** (2.9.1) — [SOURCE_MISSING] (an entity row; field `source`) flags an
 *    entity with no `sourceUrl` reference — the `catalog/Errors.kt` `SOURCE_MISSING` twin, one
 *    level over: the OPPOSITE kind of report-only finding from classes 2-3 above, since the
 *    reference is OPTIONAL on writes and this never blocks a save, just a standing report entry.
 *    Never emitted on blueprint or saved-query rows.
 *
 * Blueprint rows exist because classes 2 and 3 are properties of the BLUEPRINT's definition, not
 * of any one entity — one broken mirror path affects every entity of that blueprint alike, so
 * reporting it once (on the blueprint) rather than once per entity is both cheaper and more
 * honest about where the fix belongs.
 */

const val OWNERSHIP_UNRESOLVED = "OWNERSHIP_UNRESOLVED"
const val OWNERSHIP_PATH_STALE = "OWNERSHIP_PATH_STALE"
const val MIRROR_PATH_STALE = "MIRROR_PATH_STALE"
const val AGGREGATION_PATH_STALE = "AGGREGATION_PATH_STALE"
const val AGGREGATION_PROPERTY_STALE = "AGGREGATION_PROPERTY_STALE"
const val CALCULATION_COMPILE_FAILED = "CALCULATION_COMPILE_FAILED"
const val CALCULATION_QUARANTINED = "CALCULATION_QUARANTINED"
const val SOURCE_MISSING = "SOURCE_MISSING"

@Serializable
data class EntityErrorRow(
    val id: UInt,
    val blueprintId: UInt,
    val blueprint: String,
    val blueprintTitle: String,
    val identifier: String,
    val title: String,
    /** The EFFECTIVE team ([teamValues] of [effectiveTeam]) — empty means none, never a finding on its own. */
    val team: List<String> = emptyList(),
    val findings: List<EntityFinding>,
)

@Serializable
data class BlueprintErrorRow(
    val id: UInt,
    val identifier: String,
    val title: String,
    val findings: List<EntityFinding>,
)

@Serializable
data class SavedQueryErrorRow(
    val id: UInt,
    val name: String,
    val visibility: SavedEntityQueryVisibility,
    val createdBy: UInt,
    val query: String,
    val diagnostics: List<QueryDiagnostic>,
)

/** Rows with zero findings/diagnostics are omitted from every list — a clean workspace answers three empty arrays. */
@Serializable
data class EntityErrorsReport(
    val entities: List<EntityErrorRow> = emptyList(),
    val blueprints: List<BlueprintErrorRow> = emptyList(),
    val savedQueries: List<SavedQueryErrorRow> = emptyList(),
    val checkedEntities: Int,
    val checkedBlueprints: Int,
    val checkedSavedQueries: Int,
)

// ---------------------------------------------------------------------------------------------
// Effectful reads — run inside the caller's (EntityService.errors) transaction.
// ---------------------------------------------------------------------------------------------

private typealias ErrorsActiveBlueprint = EntityService.ActiveBlueprint

/** One entity row materialized for the report — everything [entityErrorsReport] needs, decoded once. */
internal data class EntityErrorEntitySubject(
    val id: UInt,
    val blueprintId: UInt,
    val blueprint: String,
    val blueprintTitle: String,
    val identifier: String,
    val title: String,
    val team: List<String>,
    val findings: List<EntityFinding>,
    /** The entity's `sourceUrl` — threaded through so [entityErrorsReport] (pure) can decide [SOURCE_MISSING]. */
    val sourceUrl: String?,
)

/** One blueprint checked/reported for the report (the SHOWN — filter-narrowed — candidate set). */
internal data class EntityErrorBlueprintCandidate(
    val id: UInt,
    val identifier: String,
    val title: String,
    val definition: BlueprintDefinition,
)

/** One saved query visible to the caller — the minimal projection the report needs (no creator display fields). */
internal data class SavedQueryCandidate(
    val id: UInt,
    val name: String,
    val visibility: SavedEntityQueryVisibility,
    val query: String,
    val createdBy: UInt,
)

/** Everything [entityErrorsReport] needs, gathered inside [EntityService.errors]'s one transaction. */
internal data class EntityErrorSubjects(
    val entities: List<EntityErrorEntitySubject>,
    val blueprintCandidates: List<EntityErrorBlueprintCandidate>,
    /** EVERY active blueprint's definition (never narrowed by the `blueprint` filter) — resolution stays workspace-wide. */
    val definitionsByIdentifier: Map<String, BlueprintDefinition>,
    val savedQueries: List<SavedQueryCandidate>,
    val querySchema: QuerySchema?,
)

/**
 * Builds the entity-row subjects (every SHOWN [ReadSet] row, decoded once via [WorkspaceRow.full])
 * and the blueprint-row candidates (the filter-narrowed set [EntityService.errors] already
 * resolved) — the `entities/EntityWorkspaceRead.kt` idiom: a plain function taking its inputs as
 * parameters, no ambient `EntityService` receiver. Not `suspend`: [WorkspaceRow.full] and
 * [entityFindings] never touch the database themselves, but the caller runs this inside its own
 * transaction anyway (the `materializeGraph` posture) since the rows it decodes were loaded there.
 */
internal fun materializeEntityErrorSubjects(
    readSet: ReadSet,
    candidates: List<ErrorsActiveBlueprint>,
    blueprintsById: Map<UInt, ErrorsActiveBlueprint>,
    definitionsByIdentifier: Map<String, BlueprintDefinition>,
    budget: OntologyReadBudget? = null,
): Pair<List<EntityErrorEntitySubject>, List<EntityErrorBlueprintCandidate>> {
    val entities = readSet.rows.filter { it.shown }.map { row ->
        val blueprint = blueprintsById.getValue(row.blueprintId)
        val decoded = row.full()
        val effective =
            effectiveTeam(decoded.team, decoded.document, blueprint.definition, definitionsByIdentifier, readSet.snapshot.rowLookup)
        EntityErrorEntitySubject(
            id = row.id,
            blueprintId = row.blueprintId,
            blueprint = blueprint.identifier,
            blueprintTitle = blueprint.title,
            identifier = row.identifier,
            title = row.title,
            team = teamValues(effective),
            findings = entityFindings(decoded.document, blueprint.definition, readSet.snapshot.targetExists, decoded.team)
                .also { budget?.retainFindings(it) },
            sourceUrl = row.sourceUrl,
        )
    }
    val blueprintCandidates = candidates.map { EntityErrorBlueprintCandidate(it.id, it.identifier, it.title, it.definition) }
    return entities to blueprintCandidates
}

/**
 * Every saved entity query the caller may see (own + everyone's PUBLIC — [savedQueriesVisibleTo],
 * extracted from `entityquery/SavedEntityQueryService.kt`'s `list`), a sanctioned cross-feature
 * table read of [SavedEntityQueryService.EntityQueries] (`.claude/docs/persistence.md`): only the
 * five columns the report needs — no join against `users`, since [SavedQueryErrorRow] carries no
 * creator display fields.
 */
internal suspend fun loadVisibleSavedQueries(callerId: UInt): List<SavedQueryCandidate> {
    val queries = SavedEntityQueryService.EntityQueries
    return queries.selectAll()
        .where { savedQueriesVisibleTo(callerId) }
        .map {
            SavedQueryCandidate(
                id = it[queries.id].value,
                name = it[queries.name],
                visibility = SavedEntityQueryVisibility.valueOf(it[queries.visibility]),
                query = it[queries.query],
                createdBy = it[queries.createdBy].value,
            )
        }
        .toList()
}

/**
 * [EntityService.materializeGraph]/[EntityService.errors]'s shared shown-scope resolution — the
 * `catalog/CatalogFileFilter.kt` one-filter-parser rule.
 */
internal data class ShownScope(val predicate: Op<Boolean>, val candidates: List<ErrorsActiveBlueprint>)

/**
 * [EntityGraphFilter.blueprints]/[EntityGraphFilter.q]/[EntityGraphFilter.team] resolved against
 * [activeBlueprints] into ONE SQL predicate plus the candidate blueprint set the filter narrowed
 * to — shared by `EntityService.materializeGraph` and [errors]. `null` only when
 * [EntityGraphFilter.blueprints] names EXCLUSIVELY unknown identifiers (an empty graph/report,
 * never "no predicate"). An EXTENSION on [EntityService] (the `entities/EntityFilter.kt`
 * `inheritedTeamMatches` idiom) so it can reach [EntityService.active] and chain into that same
 * extension — defined here, not as a member, purely to keep `EntityService.kt` under detekt's
 * `LargeClass` threshold (the `EntityWorkspaceRead.kt` idiom, one file up).
 */
internal suspend fun EntityService.shownScope(
    filter: EntityGraphFilter,
    activeBlueprints: List<ErrorsActiveBlueprint>,
    reservation: EntityReadLedger.Reservation? = null,
): ShownScope? {
    val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
    val blueprintsByIdentifierFolded = foldedByIdentifier(activeBlueprints)

    var predicate: Op<Boolean> = active()
    val candidates: List<ErrorsActiveBlueprint>
    if (filter.blueprints.isNotEmpty()) {
        val resolved = resolveBlueprintsFilter(filter.blueprints, blueprintsByIdentifierFolded)
        if (resolved.isEmpty()) return null
        predicate = predicate and (EntityService.Entities.blueprintId inList resolved.map { it.id })
        candidates = resolved
    } else {
        candidates = activeBlueprints
    }
    filter.q?.let { q -> predicate = predicate and qPredicate(q) }
    filter.team?.let { team ->
        val inheritedIds = inheritedTeamMatches(team, candidates, blueprintsByIdentifier, reservation)
        predicate = predicate and teamPredicate(team, blueprintsByIdentifierFolded, inheritedIds)
    }
    return ShownScope(predicate, candidates)
}

/**
 * `GET …/entities/errors` (2.5.0): the Port-world twin of `catalog/Errors.kt`'s workspace report,
 * any authenticated user, no audit. An EXTENSION on [EntityService] (needs its read ledger/jq/
 * active-blueprint machinery), defined here rather than as a member for the same `LargeClass`
 * reason as [shownScope]. ONE plain read transaction through [loadReadSet] under
 * [EntityService.readLedger] (`computed` false — a report never evaluates mirror/calculation/
 * aggregation VALUES, only checks their STATIC health), reusing [shownScope] exactly as
 * `materializeGraph` does; the pure [entityErrorsReport] runs OUTSIDE it (the `checkQuery`/
 * phase-5 posture), with [JqEvaluator.calculationVerdict] as `check` — never
 * [JqEvaluator.evaluateBounded], so this endpoint never touches the jq worker pool.
 */
suspend fun EntityService.errors(filter: EntityGraphFilter, callerId: UInt): EntityErrorsReport =
    readErrorReport(filter, callerId)

/** Ontology-only findings: machine clients never read or validate users' saved queries. */
@Serializable
data class OntologyErrorsReport(
    val entities: List<EntityErrorRow>,
    val blueprints: List<BlueprintErrorRow>,
    val checkedEntities: Int,
    val checkedBlueprints: Int,
)

suspend fun EntityService.ontologyErrors(
    filter: EntityGraphFilter,
    budget: OntologyReadBudget? = null,
): OntologyErrorsReport {
    val report = readErrorReport(filter, callerId = null, budget)
    return OntologyErrorsReport(report.entities, report.blueprints, report.checkedEntities, report.checkedBlueprints)
}

private suspend fun EntityService.readErrorReport(
    filter: EntityGraphFilter,
    callerId: UInt?,
    budget: OntologyReadBudget? = null,
): EntityErrorsReport {
    readLedger.open().use { reservation ->
        try {
            val subjects = suspendTransaction(database) {
                val activeBlueprints = loadActiveBlueprints(budget)
                val blueprintsById = activeBlueprints.associateBy { it.id }
                val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
                val definitionsByIdentifier = activeBlueprints.associate { it.identifier to it.definition }
                val scope = shownScope(filter, activeBlueprints, reservation.takeIf { budget != null })
                val (entitySubjects, blueprintCandidates) = if (scope != null) {
                    val targets = narrowTargets(scope.candidates.map { it.definition }, definitionsByIdentifier, computed = false)
                    val readSet = loadReadSet(scope.predicate, targets, blueprintsByIdentifier, reservation)
                    materializeEntityErrorSubjects(readSet, scope.candidates, blueprintsById, definitionsByIdentifier, budget)
                } else {
                    emptyList<EntityErrorEntitySubject>() to emptyList()
                }
                val querySchema = if (callerId == null) null else QuerySchema(
                    activeBlueprints.associate {
                        it.identifier to GraphBlueprint(it.identifier, it.title, it.definition, it.hierarchyRelations)
                    },
                    loadActiveHierarchies(),
                )
                val savedQueries = callerId?.let { loadVisibleSavedQueries(it) }.orEmpty()
                EntityErrorSubjects(entitySubjects, blueprintCandidates, definitionsByIdentifier, savedQueries, querySchema)
            }
            return entityErrorsReport(subjects, budget, jq::calculationVerdict)
        } catch (cause: ReadBudgetExceeded) {
            throwReadBudgetHttp(cause)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pure checkers — one small function per rule (the `PropertyValidation.kt` shape).
// ---------------------------------------------------------------------------------------------

/**
 * One mirror property's static path walk — the same split as [mirrorValue]'s runtime walk, minus
 * a document/[EntityIndex] (nothing here reads an entity row): a 1-segment path walks NO hop and
 * reads its terminal off the entity's OWN blueprint (`mirrorValue` drops only the last segment, so a
 * relation and a same-named property make a working self-mirror), an unknown hop relation or inactive
 * hop target blueprint stops the walk, and the terminal must be one of the seven known meta names
 * ([QUERY_META_PROPERTIES]) or a genuine (non-computed) property of the LANDED blueprint — a
 * terminal naming a computed id or an unknown key would resolve to absent for EVERY entity, never
 * just some.
 */
internal fun mirrorPathFindings(
    ownIdentifier: String,
    id: String,
    mirror: MirrorPropertyDefinition,
    ownDefinition: BlueprintDefinition,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
): EntityFinding? {
    val field = "mirrorProperties.$id"
    val segments = mirror.path.split('.').map { it.trim() }.filter { it.isNotEmpty() }
    if (segments.isEmpty()) return EntityFinding(MIRROR_PATH_STALE, field, "path '${mirror.path}' is blank")
    val hops = segments.dropLast(1)
    val terminal = segments.last()
    var currentIdentifier = ownIdentifier
    var currentDefinition = ownDefinition
    for ((index, segment) in hops.withIndex()) {
        val relation = currentDefinition.relations[segment]
            ?: return EntityFinding(MIRROR_PATH_STALE, field, "hop ${index + 1} '$segment' is not a relation of '$currentIdentifier'")
        val target = blueprintsByIdentifier[relation.target]
            ?: return EntityFinding(MIRROR_PATH_STALE, field, "hop ${index + 1} '$segment' targets inactive blueprint '${relation.target}'")
        currentIdentifier = relation.target
        currentDefinition = target
    }
    return mirrorTerminalFinding(field, terminal, currentIdentifier, currentDefinition)
}

/** [mirrorPathFindings]'s terminal check, split out purely to keep its own [ReturnCount] under the gate. */
private fun mirrorTerminalFinding(
    field: String,
    terminal: String,
    landedIdentifier: String,
    landedDefinition: BlueprintDefinition,
): EntityFinding? {
    if (terminal.startsWith("$")) {
        return if (terminal in QUERY_META_PROPERTIES) {
            null
        } else {
            EntityFinding(MIRROR_PATH_STALE, field, "terminal '$terminal' is not a supported meta-property")
        }
    }
    if (terminal in computedPropertyIds(landedDefinition)) {
        return EntityFinding(
            MIRROR_PATH_STALE, field, "terminal '$terminal' is a computed property of '$landedIdentifier' and never resolves",
        )
    }
    if (terminal !in landedDefinition.schema.properties) {
        return EntityFinding(MIRROR_PATH_STALE, field, "terminal '$terminal' is not a property of '$landedIdentifier'")
    }
    return null
}

/**
 * One `pathFilter` entry's static walk — mirrors [relatedEntities]'s `pathFilterCandidates` split
 * without a document: `fromBlueprint`/`path` must be present, `from` must name either this
 * blueprint or [target], and the walk itself (forward from THIS blueprint, or reverse starting at
 * [target] and ending back at this blueprint) must never break. [target]'s own existence is
 * V27-guaranteed (an aggregation target can never be deleted while referenced) and never checked
 * here.
 */
internal fun aggregationPathFindings(
    field: String,
    ownIdentifier: String,
    ownDefinition: BlueprintDefinition,
    agg: AggregationPropertyDefinition,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
): List<EntityFinding> =
    agg.pathFilter.orEmpty().mapNotNull { entry ->
        aggregationPathEntryFinding(field, ownIdentifier, ownDefinition, agg.target, entry, blueprintsByIdentifier)
    }

private fun aggregationPathEntryFinding(
    field: String,
    ownIdentifier: String,
    ownDefinition: BlueprintDefinition,
    target: String,
    entry: JsonObject,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
): EntityFinding? {
    val from = (entry["fromBlueprint"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return EntityFinding(AGGREGATION_PATH_STALE, field, "pathFilter entry is missing 'fromBlueprint'")
    val path = (entry["path"] as? JsonArray)?.filterIsInstance<JsonPrimitive>()?.filter { it.isString }?.map { it.content }.orEmpty()
    if (path.isEmpty()) {
        return EntityFinding(AGGREGATION_PATH_STALE, field, "pathFilter entry for '$from' has an empty path")
    }
    if (path.size > MAX_COMPUTED_HOPS) {
        return EntityFinding(AGGREGATION_PATH_STALE, field, "pathFilter entry for '$from' exceeds $MAX_COMPUTED_HOPS hops")
    }
    return when (from) {
        ownIdentifier -> forwardPathFinding(field, ownIdentifier, ownDefinition, path, target, blueprintsByIdentifier)
        target -> reversePathFinding(field, ownIdentifier, target, path, blueprintsByIdentifier)
        else -> EntityFinding(AGGREGATION_PATH_STALE, field, "pathFilter fromBlueprint '$from' must be '$ownIdentifier' or '$target'")
    }
}

/** [AGGREGATION_PATH_STALE] on [field] with [message] — shortens every call site below under the line-length gate. */
private fun aggPathFinding(field: String, message: String) = EntityFinding(AGGREGATION_PATH_STALE, field, message)

private fun forwardPathFinding(
    field: String,
    ownIdentifier: String,
    ownDefinition: BlueprintDefinition,
    path: List<String>,
    target: String,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
): EntityFinding? {
    var currentIdentifier = ownIdentifier
    var currentDefinition = ownDefinition
    path.forEachIndexed { index, relationId ->
        val relation = currentDefinition.relations[relationId]
            ?: return aggPathFinding(field, "forward hop ${index + 1} '$relationId' is not a relation of '$currentIdentifier'")
        val targetDefinition = blueprintsByIdentifier[relation.target]
            ?: return aggPathFinding(field, "forward hop ${index + 1} targets inactive blueprint '${relation.target}'")
        if (index == path.lastIndex && relation.target != target) {
            return aggPathFinding(field, "forward path ends at '${relation.target}', not the aggregation target '$target'")
        }
        currentIdentifier = relation.target
        currentDefinition = targetDefinition
    }
    return null
}

private fun reversePathFinding(
    field: String,
    ownIdentifier: String,
    target: String,
    path: List<String>,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
): EntityFinding? {
    var currentIdentifier = target
    // target's own existence is V27-guaranteed; a defensive absent-map lookup never fires in practice.
    var currentDefinition = blueprintsByIdentifier[target] ?: return null
    path.forEachIndexed { index, relationId ->
        val relation = currentDefinition.relations[relationId]
            ?: return aggPathFinding(field, "reverse hop ${index + 1} '$relationId' is not a relation of '$currentIdentifier'")
        val targetDefinition = blueprintsByIdentifier[relation.target]
            ?: return aggPathFinding(field, "reverse hop ${index + 1} targets inactive blueprint '${relation.target}'")
        currentIdentifier = relation.target
        currentDefinition = targetDefinition
    }
    return if (currentIdentifier != ownIdentifier) {
        aggPathFinding(field, "reverse path ends at '$currentIdentifier', not this blueprint '$ownIdentifier'")
    } else {
        null
    }
}

/**
 * [AggregationCalculationSpec]'s two blueprint-dependent fields — never checked by
 * [EntityAggregation.kt] itself (it just skips a non-numeric/unparsable value at runtime): a
 * `calculationBy: "property"` spec's `property` must be a `type: number` property of [target],
 * and a non-meta `measureTimeBy` must be a property of [target] (any type — [measuredTimeMillis]
 * only requires it be a parseable date-time STRING, which this static check cannot verify, so a
 * present-but-wrong-shape property is a runtime absence, not a report finding).
 */
internal fun aggregationPropertyFindings(
    field: String,
    agg: AggregationPropertyDefinition,
    targetDefinition: BlueprintDefinition?,
): List<EntityFinding> {
    val findings = mutableListOf<EntityFinding>()
    val spec = agg.calculationSpec
    if (spec.calculationBy == "property") {
        val propertyId = spec.property
        val propertyDefinition = propertyId?.let { targetDefinition?.schema?.properties?.get(it) }
        if (propertyDefinition == null || propertyDefinition.type != "number") {
            findings += EntityFinding(
                AGGREGATION_PROPERTY_STALE, field,
                "calculationSpec.property '$propertyId' is not a number property of '${agg.target}'",
            )
        }
    }
    val measureTimeBy = spec.measureTimeBy
    if (measureTimeBy != null && measureTimeBy !in setOf("\$createdAt", "\$updatedAt")) {
        if (targetDefinition?.schema?.properties?.containsKey(measureTimeBy) != true) {
            findings += EntityFinding(
                AGGREGATION_PROPERTY_STALE, field,
                "calculationSpec.measureTimeBy '$measureTimeBy' is not a property of '${agg.target}'",
            )
        }
    }
    return findings
}

/** One aggregation property's combined path + property findings. */
internal fun aggregationFindings(
    ownIdentifier: String,
    ownDefinition: BlueprintDefinition,
    id: String,
    agg: AggregationPropertyDefinition,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
): List<EntityFinding> {
    val field = "aggregationProperties.$id"
    val pathFindings = aggregationPathFindings(field, ownIdentifier, ownDefinition, agg, blueprintsByIdentifier)
    val targetDefinition = blueprintsByIdentifier[agg.target]
    return pathFindings + aggregationPropertyFindings(field, agg, targetDefinition)
}

/**
 * One calculation property's compile verdict, via [check] ([JqEvaluator.calculationVerdict] in
 * production) — never evaluates the expression, so a hostile `calculationProperties` entry costs
 * nothing extra to report beyond what its OWN entity reads already pay.
 */
internal fun calculationFindings(
    id: String,
    blueprintIdentifier: String,
    def: CalculationPropertyDefinition,
    check: (String, String) -> CalculationVerdict,
): EntityFinding? {
    val field = "calculationProperties.$id"
    return when (val verdict = check(def.calculation, "$blueprintIdentifier.$id")) {
        CalculationVerdict.Ok -> null
        CalculationVerdict.Quarantined -> EntityFinding(
            CALCULATION_QUARANTINED, field,
            "This server instance quarantined the calculation after it exceeded its deadline — restart or edit it to retry",
        )
        is CalculationVerdict.CompileFailed -> EntityFinding(CALCULATION_COMPILE_FAILED, field, verdict.message)
    }
}

/**
 * One blueprint's `ownership.path` static walk — the same give-up rules as [ownershipHop]'s
 * runtime walk, minus a document/[RowLookup]. Critically, [inheritedTeam]'s runtime walk STOPS
 * at the FIRST hop that lands on a Direct/absent blueprint, never even looking at later path
 * segments — so this static walk must do the same: a hop's relation gone, `many`, or its target
 * blueprint inactive is stale ONLY for a hop that is actually reached (hop 1 always resolves —
 * write-time guarantees `path.first()` names a relation of THIS blueprint,
 * `BlueprintValidation.kt`'s `requirePathSegments` — so only a REACHED hop 2+ can drift); the
 * walk is stale only if it consumes the WHOLE path while every landed blueprint stayed Inherited.
 */
internal fun ownershipPathFindings(
    identifier: String,
    definition: BlueprintDefinition,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
): EntityFinding? {
    if (!isInherited(definition)) return null
    val path = definition.ownership?.path?.split('.')?.filter { it.isNotBlank() }.orEmpty()
    if (path.isEmpty()) return null // write-time guarantees non-blank when Inherited; defensive only
    return walkOwnershipPath(identifier, definition, path, blueprintsByIdentifier)
}

/** [ownershipPathFindings]'s hop-by-hop walk, split out purely to keep its own [ReturnCount] under the gate. */
private fun walkOwnershipPath(
    identifier: String,
    definition: BlueprintDefinition,
    path: List<String>,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
): EntityFinding? {
    val field = "ownership.path"
    var currentIdentifier = identifier
    var currentDefinition = definition
    for ((index, segment) in path.withIndex()) {
        val relation = currentDefinition.relations[segment]
            ?: return EntityFinding(OWNERSHIP_PATH_STALE, field, "hop ${index + 1} '$segment' is not a relation of '$currentIdentifier'")
        if (relation.many) {
            return EntityFinding(OWNERSHIP_PATH_STALE, field, "hop ${index + 1} '$segment' is a many relation and cannot be walked")
        }
        val target = blueprintsByIdentifier[relation.target]
            ?: return EntityFinding(
                OWNERSHIP_PATH_STALE, field, "hop ${index + 1} '$segment' targets inactive blueprint '${relation.target}'",
            )
        currentIdentifier = relation.target
        currentDefinition = target
        // Landing on a Direct/absent blueprint is where the RUNTIME walk stops too, ignoring any
        // remaining path segments — so this static walk must never flag those as unreachable.
        if (!isInherited(currentDefinition)) return null
    }
    return EntityFinding(OWNERSHIP_PATH_STALE, field, "path is exhausted at '$currentIdentifier', which is still Inherited")
}

/** One blueprint's every static finding, in class order: mirror -> calculation -> aggregation -> ownership. */
internal fun blueprintFindings(
    identifier: String,
    definition: BlueprintDefinition,
    blueprintsByIdentifier: Map<String, BlueprintDefinition>,
    check: (String, String) -> CalculationVerdict,
): List<EntityFinding> {
    val findings = mutableListOf<EntityFinding>()
    definition.mirrorProperties.forEach { (id, mirror) ->
        mirrorPathFindings(identifier, id, mirror, definition, blueprintsByIdentifier)?.let { findings += it }
    }
    definition.calculationProperties.forEach { (id, calc) ->
        calculationFindings(id, identifier, calc, check)?.let { findings += it }
    }
    definition.aggregationProperties.forEach { (id, agg) ->
        findings += aggregationFindings(identifier, definition, id, agg, blueprintsByIdentifier)
    }
    ownershipPathFindings(identifier, definition, blueprintsByIdentifier)?.let { findings += it }
    return findings
}

/**
 * One entity's ownership finding: [OWNERSHIP_UNRESOLVED] when its blueprint is Inherited, its
 * effective [team] is empty, and its blueprint does NOT already carry [OWNERSHIP_PATH_STALE]
 * ([pathStale]) — the suppression rule (the path is the root cause, not each individual entity).
 * A Direct/absent blueprint, or one with a non-empty effective team, never trips (the documented
 * non-goal: an unowned Direct/absent entity is unremarkable and never reported).
 */
internal fun ownershipFinding(definition: BlueprintDefinition, team: List<String>, pathStale: Boolean): EntityFinding? {
    if (!isInherited(definition) || pathStale || team.isNotEmpty()) return null
    return EntityFinding(OWNERSHIP_UNRESOLVED, "team", "This entity's Inherited ownership path did not resolve to a team")
}

/**
 * [SOURCE_MISSING] — the `catalog/Errors.kt` twin, one level over: the opposite kind of
 * report-only finding (the reference is OPTIONAL on writes, never a save blocker), entity rows
 * only. `null`/blank [sourceUrl] both mean "no reference" — the write path already folds a blank
 * submission to `null` (`sanitizedSourceUrl`), but a defensive blank check costs nothing here.
 */
internal fun sourceMissingFinding(sourceUrl: String?): EntityFinding? =
    if (sourceUrl.isNullOrBlank()) EntityFinding(SOURCE_MISSING, "source", "This entity has no source reference") else null

/** [text]'s parse+validate diagnostics against [schema] — [EntityService.checkQuery]'s posture, never evaluated. */
internal fun savedQueryDiagnostics(text: String, schema: QuerySchema): List<QueryDiagnostic> = try {
    validateEntityQuery(parseEntityQuery(text), schema)
} catch (e: QueryException) {
    e.diagnostics
}

/**
 * The whole report, built OUTSIDE any transaction (the `checkQuery`/phase-5 posture): blueprint
 * rows over [EntityErrorSubjects.blueprintCandidates] (sorted by identifier), entity rows over
 * [EntityErrorSubjects.entities] (kept in read-set order, each entity's OWN [entityFindings] plus
 * its ownership verdict), saved-query rows over [EntityErrorSubjects.savedQueries] (sorted by
 * name, case-insensitively) — every row with zero findings/diagnostics omitted.
 */
internal fun entityErrorsReport(
    subjects: EntityErrorSubjects,
    budget: OntologyReadBudget? = null,
    check: (String, String) -> CalculationVerdict,
): EntityErrorsReport {
    val blueprintFindingsById = subjects.blueprintCandidates.associate { candidate ->
        candidate.identifier to blueprintFindings(candidate.identifier, candidate.definition, subjects.definitionsByIdentifier, check)
            .also { budget?.retainFindings(it) }
    }
    val staleOwnershipBlueprints = blueprintFindingsById.filterValues { findings -> findings.any { it.code == OWNERSHIP_PATH_STALE } }.keys

    val blueprintRows = subjects.blueprintCandidates
        .sortedBy { it.identifier.lowercase() }
        .mapNotNull { candidate ->
            val findings = blueprintFindingsById.getValue(candidate.identifier)
            if (findings.isEmpty()) null else BlueprintErrorRow(candidate.id, candidate.identifier, candidate.title, findings)
        }

    val entityRows = subjects.entities.mapNotNull { subject ->
        val definition = subjects.definitionsByIdentifier[subject.blueprint]
        val ownership = definition?.let { ownershipFinding(it, subject.team, subject.blueprint in staleOwnershipBlueprints) }
        if (ownership != null) budget?.retainFindings(listOf(ownership))
        val sourceMissing = sourceMissingFinding(subject.sourceUrl)
        if (sourceMissing != null) budget?.retainFindings(listOf(sourceMissing))
        val findings = subject.findings + listOfNotNull(ownership, sourceMissing)
        if (findings.isEmpty()) {
            null
        } else {
            EntityErrorRow(
                subject.id, subject.blueprintId, subject.blueprint, subject.blueprintTitle,
                subject.identifier, subject.title, subject.team, findings,
            )
        }
    }

    val savedQueryRows = subjects.savedQueries
        .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        .mapNotNull { saved ->
            val diagnostics = savedQueryDiagnostics(saved.query, checkNotNull(subjects.querySchema))
            if (diagnostics.isEmpty()) {
                null
            } else {
                SavedQueryErrorRow(saved.id, saved.name, saved.visibility, saved.createdBy, saved.query, diagnostics)
            }
        }

    return EntityErrorsReport(
        entities = entityRows,
        blueprints = blueprintRows,
        savedQueries = savedQueryRows,
        checkedEntities = subjects.entities.size,
        checkedBlueprints = subjects.blueprintCandidates.size,
        checkedSavedQueries = subjects.savedQueries.size,
    )
}
