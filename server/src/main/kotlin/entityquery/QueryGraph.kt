package ch.nokillswit.entityquery

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.entities.GraphBlueprint
import ch.nokillswit.entities.IndexedRow
import ch.nokillswit.entities.QueryCandidate
import ch.nokillswit.entities.RowLookup
import ch.nokillswit.entities.asOwned
import ch.nokillswit.entities.effectiveTeam
import ch.nokillswit.entities.hopTargetIdentifiers
import ch.nokillswit.entities.teamValues

/**
 * The in-memory graph `QueryEvaluator.kt` walks — one snapshot per query, built from the SAME
 * `IndexedRow`/`GraphBlueprint` shapes `entities/EntityComputed.kt` and `entities/EntityGraph.kt`
 * already use, so the service (PR2) can hand the evaluator the workspace snapshot it already
 * loads for computed properties. Pure, DB-free: every lookup here is a map read over indexes
 * built once at construction (or lazily, on first use).
 */

/**
 * One matched row: [row]'s content plus its owning [blueprint] definition. [key] is the
 * evaluator's join/dedupe identity — (blueprint identifier, entity identifier), both byte-exact.
 * [team]/[candidate] are lazy: most rows in a large graph are never bound to a variable whose
 * WHERE/RETURN actually needs them.
 */
class QueryRow internal constructor(
    val row: IndexedRow,
    val blueprint: GraphBlueprint,
    private val definitionsByIdentifier: Map<String, BlueprintDefinition>,
    private val rowLookup: RowLookup,
) {
    /** (blueprint identifier, entity identifier) — byte-exact, never folded. */
    val key: Pair<String, String> = blueprint.identifier to row.identifier

    /** The EFFECTIVE team (`entities/EntityOwnership.kt`'s `effectiveTeam`) — Direct's stored value, or the Inherited walk. */
    val team: List<String> by lazy {
        teamValues(effectiveTeam(row.team, row.document, blueprint.definition, definitionsByIdentifier, rowLookup))
    }

    /** The `QueryValues.kt`/`AggregationQuery.kt` meta+property resolution shape for this row. */
    val candidate: QueryCandidate by lazy {
        QueryCandidate(
            identifier = row.identifier,
            title = row.title,
            blueprint = blueprint.identifier,
            icon = row.icon,
            team = team,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
            properties = row.document.properties,
        )
    }

    /** Identity is [key] alone — the evaluator dedupes/joins on it regardless of which cached instance it holds. */
    override fun equals(other: Any?): Boolean = other is QueryRow && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "QueryRow(${key.first}|${key.second})"
}

/**
 * The evaluator's read-only view of one query's workspace snapshot. [blueprints] is exact-
 * identifier keyed; every method here folds case itself wherever the language folds it (blueprint
 * identifiers, hierarchy ids) — relation keys and entity identifiers stay byte-exact, matching
 * the language's own case rules (`.claude/docs/entity-query-language.md`).
 */
interface QueryGraph {
    val blueprints: Map<String, GraphBlueprint>
    val hierarchies: Set<String>

    fun allRows(): Collection<QueryRow>

    /** Every row of the blueprint named by [blueprintFolded] (case-insensitive); empty for an unknown blueprint. */
    fun rows(blueprintFolded: String): Collection<QueryRow>

    /** One row by (case-insensitive) blueprint + byte-exact entity identifier; null when it does not resolve. */
    fun row(blueprintFolded: String, identifier: String): QueryRow?

    /** [row]'s own relation VALUE(S) under the byte-exact [relationKey], landed as rows of the relation's target blueprint. */
    fun outgoing(row: QueryRow, relationKey: String): List<QueryRow>

    /** Every row whose OWN relation value names [row] — (source row, the relation key it used). */
    fun incoming(row: QueryRow): List<Pair<QueryRow, String>>

    /** The `_team` rows of [row]'s EFFECTIVE team. */
    fun owners(row: QueryRow): List<QueryRow>

    /** Every row whose EFFECTIVE team includes [teamRow]'s identifier — lazily indexed on first call. */
    fun ownedBy(teamRow: QueryRow): List<QueryRow>

    /** [blueprint]'s `hierarchyRelations` entry for [hierarchyIdFolded] (case-insensitive key lookup); null when it names none. */
    fun hierarchyRelation(blueprint: GraphBlueprint, hierarchyIdFolded: String): String?
}

/** The evaluator's one implementation: everything indexed once (or lazily) from [rows]/[blueprints]/[hierarchies]. */
class InMemoryQueryGraph(
    rows: List<IndexedRow>,
    override val blueprints: Map<String, GraphBlueprint>,
    override val hierarchies: Set<String>,
) : QueryGraph {
    private val foldedBlueprints: Map<String, GraphBlueprint> = blueprints.values.associateBy { it.identifier.lowercase() }
    private val definitionsByIdentifier: Map<String, BlueprintDefinition> = blueprints.values.associate { it.identifier to it.definition }

    private val rowLookup: RowLookup = { bp, id -> rowsByKey[bp to id]?.row?.asOwned() }

    private val rowsByKey: Map<Pair<String, String>, QueryRow> = rows.mapNotNull { r ->
        val bp = blueprints[r.blueprint] ?: return@mapNotNull null
        (bp.identifier to r.identifier) to QueryRow(r, bp, definitionsByIdentifier, rowLookup)
    }.toMap()

    private val rowsByFoldedBlueprint: Map<String, List<QueryRow>> by lazy {
        rowsByKey.values.groupBy { it.blueprint.identifier.lowercase() }
    }

    private val rowsByFoldedBlueprintAndIdentifier: Map<Pair<String, String>, QueryRow> by lazy {
        rowsByKey.values.associateBy { it.blueprint.identifier.lowercase() to it.row.identifier }
    }

    private val teamBlueprint: GraphBlueprint? by lazy { foldedBlueprints[SYSTEM_TEAM_BLUEPRINT.lowercase()] }

    private val teamRowsByIdentifier: Map<String, QueryRow> by lazy {
        val bp = teamBlueprint ?: return@lazy emptyMap()
        rowsByFoldedBlueprint[bp.identifier.lowercase()].orEmpty().associateBy { it.row.identifier }
    }

    private val incomingIndex: Map<Pair<String, String>, List<Pair<QueryRow, String>>> by lazy { buildIncomingIndex() }

    private val ownedByIndex: Map<String, List<QueryRow>> by lazy { buildOwnedByIndex() }

    override fun allRows(): Collection<QueryRow> = rowsByKey.values

    override fun rows(blueprintFolded: String): Collection<QueryRow> = rowsByFoldedBlueprint[blueprintFolded.lowercase()].orEmpty()

    override fun row(blueprintFolded: String, identifier: String): QueryRow? =
        rowsByFoldedBlueprintAndIdentifier[blueprintFolded.lowercase() to identifier]

    override fun outgoing(row: QueryRow, relationKey: String): List<QueryRow> {
        val relation = row.blueprint.definition.relations[relationKey] ?: return emptyList()
        val target = foldedBlueprints[relation.target.lowercase()] ?: return emptyList()
        val ids = hopTargetIdentifiers(row.row.document.relations[relationKey], relation.many)
        return ids.mapNotNull { rowsByKey[target.identifier to it] }
    }

    override fun incoming(row: QueryRow): List<Pair<QueryRow, String>> = incomingIndex[row.key].orEmpty()

    // Byte-exact like `buildEntityGraph`'s `$team` edges and the write-time `TEAM_TARGET_MISSING` check —
    // a team value is a validated `_team` identifier, not a folded search term.
    override fun owners(row: QueryRow): List<QueryRow> = row.team.mapNotNull { teamRowsByIdentifier[it] }

    override fun ownedBy(teamRow: QueryRow): List<QueryRow> = ownedByIndex[teamRow.row.identifier].orEmpty()

    override fun hierarchyRelation(blueprint: GraphBlueprint, hierarchyIdFolded: String): String? {
        val folded = hierarchyIdFolded.lowercase()
        return blueprint.hierarchyRelations.entries.firstOrNull { it.key.lowercase() == folded }?.value
    }

    private fun buildIncomingIndex(): Map<Pair<String, String>, List<Pair<QueryRow, String>>> {
        val map = mutableMapOf<Pair<String, String>, MutableList<Pair<QueryRow, String>>>()
        rowsByKey.values.forEach { source ->
            source.blueprint.definition.relations.forEach { (relationKey, relation) ->
                val target = foldedBlueprints[relation.target.lowercase()] ?: return@forEach
                hopTargetIdentifiers(source.row.document.relations[relationKey], relation.many).forEach { id ->
                    val targetRow = rowsByKey[target.identifier to id] ?: return@forEach
                    map.getOrPut(targetRow.key) { mutableListOf() } += source to relationKey
                }
            }
        }
        return map
    }

    private fun buildOwnedByIndex(): Map<String, List<QueryRow>> {
        val map = mutableMapOf<String, MutableList<QueryRow>>()
        rowsByKey.values.forEach { r -> r.team.forEach { teamId -> map.getOrPut(teamId) { mutableListOf() } += r } }
        return map
    }
}
