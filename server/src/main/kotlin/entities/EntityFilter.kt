package ch.nokillswit.entities

import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.jsonStringOrArrayContainsFolded
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.r2dbc.selectAll

// The entity list/graph filter set, shared by `EntityService.list`/`EntityService.graph` — the
// `catalog/CatalogFileFilter.kt` shape, one level down: SQL predicates (this file) and the
// service methods that fold them into ONE shared `predicate` live side by side by convention,
// so a filter added to one endpoint and not the other is a bug.

/** `team`: single value, case-insensitive, blank = absent (`infra/paging/QueryParams.kt`'s `optionalString` idiom); repetition is a 400. */
data class EntityFilter(val blueprint: String?, val q: String?, val team: String? = null)

/**
 * `GET …/entities/graph`'s filter: `blueprints` is the repeated any-of param (IN semantics).
 * [query] (phase 7, 2.0.0 — `.claude/docs/entity-query-language.md`) is the optional entity
 * query text; `null` = no query, never evaluated. The route rejects a query longer than
 * [ch.nokillswit.entityquery.MAX_QUERY_LENGTH] before this filter is even built.
 */
data class EntityGraphFilter(val blueprints: List<String>, val q: String?, val team: String? = null, val query: String? = null)

private typealias ActiveBlueprint = EntityService.ActiveBlueprint
private typealias Entities = EntityService.Entities

/** Case-folded blueprint-identifier lookup — identifiers are unique case-insensitively, the ONE folded map [list]/[graph] build. */
internal fun foldedByIdentifier(blueprints: List<ActiveBlueprint>): Map<String, ActiveBlueprint> =
    blueprints.associateBy { it.identifier.lowercase() }

/** The list endpoint's `blueprint` equality filter: `null` when unset, or when it resolves to nothing (an unknown-blueprint filter). */
internal fun resolveBlueprintFilter(blueprint: String?, folded: Map<String, ActiveBlueprint>): ActiveBlueprint? =
    blueprint?.let { folded[it.lowercase()] }

/** The graph endpoint's `blueprints` any-of/IN filter: case-insensitive, unknown identifiers ignored rather than rejected. */
internal fun resolveBlueprintsFilter(blueprints: List<String>, folded: Map<String, ActiveBlueprint>): List<ActiveBlueprint> =
    blueprints.mapNotNull { folded[it.lowercase()] }.distinct()

/** `q` substring-matches `identifier` OR `title`, case- and accent-insensitively (the list/graph endpoints' shared free-text filter). */
internal fun qPredicate(q: String): Op<Boolean> =
    Entities.identifier.containsNormalized(q) or Entities.title.containsNormalized(q)

/**
 * `_team` self-match: `Entities.blueprintId eq <the _team blueprint>` AND
 * `LOWER(identifier) = LOWER(value)` — so a team-filtered graph keeps the team node.
 */
private fun teamSelfMatch(value: String, blueprintsByIdentifierFolded: Map<String, ActiveBlueprint>): Op<Boolean>? =
    blueprintsByIdentifierFolded[SYSTEM_TEAM_BLUEPRINT.lowercase()]?.let { teamBlueprint ->
        (Entities.blueprintId eq teamBlueprint.id) and
            (LowerCase(Entities.identifier) eq stringParam(value.lowercase()))
    }

/**
 * ONE SQL predicate covering all three ways a row matches `team`: the STORED
 * `Entities.team` column (Direct/absent ownership), the `_team` self-match, and —
 * v1.30.0 — an explicit `id IN (…)` disjunct for Inherited rows whose EFFECTIVE team was
 * resolved in memory by [inheritedTeamMatches] over the same committed read. The `IN`
 * disjunct is added only when [inheritedIds] is non-empty, so an entirely Direct/absent
 * scope never renders `id IN ()`. `count()`/paging/sort stay pure SQL either way.
 */
internal fun teamPredicate(
    team: String,
    blueprintsByIdentifierFolded: Map<String, ActiveBlueprint>,
    inheritedIds: List<UInt>,
): Op<Boolean> {
    val membership = Entities.team.jsonStringOrArrayContainsFolded(team)
    val selfMatch = teamSelfMatch(team, blueprintsByIdentifierFolded)
    var predicate = if (selfMatch == null) membership else membership or selfMatch
    if (inheritedIds.isNotEmpty()) {
        predicate = predicate or (Entities.id inList inheritedIds)
    }
    return predicate
}

/**
 * Resolves which of [candidates]' active entities have an EFFECTIVE team ([effectiveTeam])
 * matching [team] — the read-side counterpart of the Inherited ownership rule
 * (`entities/EntityOwnership.kt`): Inherited blueprints store no `team` column at all, so
 * the SQL predicate above can never see them. Runs INSIDE the caller's existing plain read
 * transaction (no extra lock), over the SAME snapshot every other read builds — via
 * [EntityService.rowLookupFor], the one narrow accessor into `EntityService`'s otherwise-private
 * snapshot machinery — so a multi-hop Inherited chain resolves without querying the database
 * mid-walk. Returns `emptyList()` — with NO query issued — when no candidate blueprint is
 * Inherited, so an all-Direct/absent scope pays nothing extra. Declared as an extension on
 * [EntityService] (rather than a free function) purely to reach [EntityService.rowLookupFor];
 * `Entities`/`ActiveBlueprint` are ordinary parameters/globals like every other helper here.
 */
internal suspend fun EntityService.inheritedTeamMatches(
    team: String,
    candidates: List<ActiveBlueprint>,
    blueprintsByIdentifier: Map<String, ActiveBlueprint>,
): List<UInt> {
    val inherited = candidates.filter { isInherited(it.definition) }
    if (inherited.isEmpty()) return emptyList()
    val inheritedById = inherited.associateBy { it.id }
    val definitionsByIdentifier = blueprintsByIdentifier.mapValues { it.value.definition }
    val rowLookup = rowLookupFor(inherited.map { it.definition }, blueprintsByIdentifier)
    return Entities.selectAll()
        .where { (Entities.blueprintId inList inherited.map { it.id }) and (Entities.markedAsDeleted eq false) }
        .map { row ->
            val definition = inheritedById.getValue(row[Entities.blueprintId].value).definition
            val document = blueprintJson.decodeFromString<EntityDocument>(row[Entities.document])
            val storedTeam = row[Entities.team]?.let { blueprintJson.decodeFromString<JsonElement>(it) }
            val effective = effectiveTeam(storedTeam, document, definition, definitionsByIdentifier, rowLookup)
            row[Entities.id].value to effective
        }
        .toList()
        .filter { (_, effective) -> teamValueMatches(effective, team) }
        .map { it.first }
}
