package ch.nokillswit.entities

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.SYSTEM_USER_BLUEPRINT
import ch.nokillswit.blueprints.blueprintJson
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/**
 * `EntityService`'s consolidated READ machinery (2.4.0 — `.claude/docs/persistence.md` "Entity
 * read memory budget"), split into its own file purely to keep `EntityService.kt` under the
 * repo's `LargeClass` threshold (`config/detekt/detekt.yml`) — every declaration here is either
 * a plain top-level helper or takes its `EntityService.ActiveBlueprint`/`EntityService.Entities`
 * inputs as parameters, the SAME `entities/EntityFilter.kt` idiom (file-local `typealias`es —
 * uniquely named, since two files of one package may not both declare a private `Entities` —,
 * no ambient `EntityService` receiver, ordinary suspend functions run inside the CALLER's
 * already-open transaction).
 */

private typealias ReadActiveBlueprint = EntityService.ActiveBlueprint
private typealias ReadEntities = EntityService.Entities

private fun active(): Op<Boolean> = ReadEntities.markedAsDeleted eq false

/**
 * `octet_length(column)` — the STORED byte length, read straight off PostgreSQL without ever
 * bringing the value itself into the application layer ([loadReadSet]'s up-front admission
 * charge). A private, EntityService-owned copy: the write-side workspace-byte-budget check
 * defines its OWN `octetLengthOf` (this service's read side and the write side never share the
 * helper, by design — see the feature's own scaffold comment).
 */
private fun octetLengthOf(column: Expression<*>): CustomFunction<Long?> = CustomFunction("octet_length", LongColumnType(), column)

/** Decodes ONLY the `relations` (or `properties`) top-level key of a stored document — see [WorkspaceRow]. */
@kotlinx.serialization.Serializable
private data class DocumentRelationsOnly(val relations: JsonObject = JsonObject(emptyMap()))

@kotlinx.serialization.Serializable
private data class DocumentPropertiesOnly(val properties: JsonObject = JsonObject(emptyMap()))

/** `blueprintJson`'s settings, but tolerating the ONE sibling top-level key each partial decode above ignores. */
private val partialDocumentJson = Json(blueprintJson) { ignoreUnknownKeys = true }

/** [WorkspaceRow]'s raw, undecoded columns — bundled into ONE param so the constructor stays under the repo's `LongParameterList` cap. */
internal data class RawEntityColumns(
    val id: UInt,
    val blueprintId: UInt,
    val blueprint: String,
    val identifier: String,
    val title: String,
    val icon: String?,
    val teamRaw: String?,
    val documentRaw: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceUrl: String?,
)

/**
 * One row loaded by [loadReadSet] — RAW only inside its own transaction, no decode
 * (`.claude/docs/persistence.md` "Entity read memory budget"). [skeleton] decodes `relations` +
 * `team` and charges [reservation] exactly once (memoized); [full] additionally decodes
 * `properties` and charges again, exactly once. [view] is the [EntityRowView] every
 * computed-property/aggregation/query lookup actually touches — its `properties` accessor is the
 * ONE thing in this whole read path that can still throw [ReadBudgetExceeded] after the
 * transaction that loaded the row has already closed (the query-evaluation path).
 */
internal class WorkspaceRow(
    private val raw: RawEntityColumns,
    /** Whether this row matched the CALLER's own predicate (as opposed to being loaded only as a validation/lookup target). */
    val shown: Boolean,
    private val reservation: EntityReadLedger.Reservation?,
) {
    val id: UInt get() = raw.id
    val blueprintId: UInt get() = raw.blueprintId
    val blueprint: String get() = raw.blueprint
    val identifier: String get() = raw.identifier
    val title: String get() = raw.title
    val icon: String? get() = raw.icon
    val createdAt: Long get() = raw.createdAt
    val updatedAt: Long get() = raw.updatedAt
    /** The `source_url` column, for [SOURCE_MISSING] (`EntityErrors.kt`) — a bare column read, no sync/baseline state. */
    val sourceUrl: String? get() = raw.sourceUrl

    private var skeletonRow: IndexedRow? = null
    private var fullRow: IndexedRow? = null

    fun skeleton(): IndexedRow {
        skeletonRow?.let { return it }
        val relations = partialDocumentJson.decodeFromString<DocumentRelationsOnly>(raw.documentRaw).relations
        val team = raw.teamRaw?.let { blueprintJson.decodeFromString<JsonElement>(it) }
        reservation?.charge(estimatedHeapBytes(relations) + (team?.let(::estimatedHeapBytes) ?: 0L))
        val document = EntityDocument(JsonObject(emptyMap()), relations)
        val row = IndexedRow(blueprint, identifier, title, icon, createdAt, updatedAt, document, team)
        skeletonRow = row
        return row
    }

    fun full(): IndexedRow {
        fullRow?.let { return it }
        val base = skeleton()
        val properties = partialDocumentJson.decodeFromString<DocumentPropertiesOnly>(raw.documentRaw).properties
        reservation?.charge(estimatedHeapBytes(properties))
        val row = base.copy(document = EntityDocument(properties, base.document.relations))
        fullRow = row
        return row
    }

    val view: EntityRowView = WorkspaceRowView(this)
}

private class WorkspaceRowView(private val owner: WorkspaceRow) : EntityRowView {
    override val blueprint get() = owner.blueprint
    override val identifier get() = owner.identifier
    override val title get() = owner.title
    override val icon get() = owner.icon
    override val createdAt get() = owner.createdAt
    override val updatedAt get() = owner.updatedAt
    override val team get() = owner.skeleton().team
    override val relations get() = owner.skeleton().document.relations
    override val properties get() = owner.full().document.properties
}

private fun ResultRow.toWorkspaceRow(
    blueprintsById: Map<UInt, ReadActiveBlueprint>,
    shownIds: Set<UInt>,
    reservation: EntityReadLedger.Reservation?,
): WorkspaceRow {
    val blueprint = blueprintsById.getValue(this[ReadEntities.blueprintId].value)
    val id = this[ReadEntities.id].value
    val raw = RawEntityColumns(
        id = id,
        blueprintId = blueprint.id,
        blueprint = blueprint.identifier,
        identifier = this[ReadEntities.identifier],
        title = this[ReadEntities.title],
        icon = this[ReadEntities.icon],
        teamRaw = this[ReadEntities.team],
        documentRaw = this[ReadEntities.document],
        createdAt = this[ReadEntities.createdAt],
        updatedAt = this[ReadEntities.updatedAt],
        sourceUrl = this[ReadEntities.sourceUrl],
    )
    return WorkspaceRow(raw, shown = id in shownIds, reservation = reservation)
}

/**
 * One snapshot of active rows for every blueprint [targetExists]/[rowLookup]/computed-property
 * evaluation might be asked about: relation targets, `_team`/`_user` (format-team/user property
 * checks and the team finding), every blueprint an Inherited `ownership.path` might walk through,
 * and — when the caller's target set widens it — every blueprint a mirror/aggregation property
 * might touch ([computedPathBlueprints]) — loaded ONCE per call by [loadReadSet]
 * (`.claude/docs/persistence.md`), rows decoded lazily and once each
 * ([WorkspaceRow.skeleton]/[WorkspaceRow.full]). Implements [EntityIndex] so
 * `EntityComputed.kt`/`EntityAggregation.kt` never query the database mid-evaluation; [inbound]
 * is itself `by lazy` so `update`/`graph` (which never ask for it) never pay for building it, and
 * building it charges only [WorkspaceRow.skeleton] (relations), never `properties`.
 */
internal class EntitySnapshot(
    rows: List<WorkspaceRow>,
    blueprintsByIdentifier: Map<String, ReadActiveBlueprint>,
    private val blueprintsById: Map<UInt, ReadActiveBlueprint>,
) : EntityIndex {
    private data class Key(val blueprintId: UInt, val identifier: String)

    private val rowsByKey: Map<Key, WorkspaceRow> = rows.associateBy { Key(it.blueprintId, it.identifier) }
    private val idByIdentifier: Map<String, UInt> = blueprintsByIdentifier.mapValues { it.value.id }

    val targetExists: TargetExists = { targetBlueprint, entityId ->
        val id = idByIdentifier[targetBlueprint]
        id != null && Key(id, entityId) in rowsByKey
    }

    override val rowLookup: RowLookup = { blueprint, identifier -> row(blueprint, identifier)?.asOwned() }

    override fun row(blueprint: String, identifier: String): EntityRowView? {
        val id = idByIdentifier[blueprint] ?: return null
        return rowsByKey[Key(id, identifier)]?.view
    }

    private val inboundIndex: Map<Key, List<Inbound>> by lazy {
        val index = mutableMapOf<Key, MutableList<Inbound>>()
        rowsByKey.values.forEach { sourceRow ->
            val sourceBlueprint = blueprintsById[sourceRow.blueprintId] ?: return@forEach
            val sourceView = sourceRow.view
            sourceBlueprint.definition.relations.forEach { (relationId, relationDef) ->
                val targetId = idByIdentifier[relationDef.target] ?: return@forEach
                val values = hopTargetIdentifiers(sourceView.relations[relationId], relationDef.many)
                values.forEach { value ->
                    index.getOrPut(Key(targetId, value)) { mutableListOf() } += Inbound(sourceView, relationId)
                }
            }
        }
        index
    }

    override fun inbound(blueprint: String, identifier: String): List<Inbound> {
        val id = idByIdentifier[blueprint] ?: return emptyList()
        return inboundIndex[Key(id, identifier)].orEmpty()
    }
}

/**
 * The narrower ("targets only") set [loadReadSet] widens a `shownPredicate`-only read with:
 * relation targets, `_team`/`_user`, every blueprint an Inherited `ownership.path` might walk
 * through, and — when [computed] — every blueprint a mirror/aggregation property might touch
 * ([computedPathBlueprints]). The pure set computation the pre-2.4.0 `loadSnapshot` used to do
 * inline (`.claude/docs/persistence.md`).
 */
internal fun narrowTargets(
    definitions: Collection<BlueprintDefinition>,
    definitionsByIdentifier: Map<String, BlueprintDefinition>,
    computed: Boolean,
): Set<String> {
    val relationTargets = definitions.flatMap { it.relations.values.map { relation -> relation.target } }.toSet()
    val ownershipTargets = definitions.flatMap { ownershipPathBlueprints(it, definitionsByIdentifier) }.toSet()
    val computedTargets =
        if (computed) definitions.flatMap { computedPathBlueprints(it, definitionsByIdentifier) }.toSet() else emptySet()
    return relationTargets + ownershipTargets + computedTargets + SYSTEM_TEAM_BLUEPRINT + SYSTEM_USER_BLUEPRINT
}

internal class ReadSet(val rows: List<WorkspaceRow>, val snapshot: EntitySnapshot)

/**
 * ONE consolidated entity read (2.4.0 — `.claude/docs/persistence.md` "Entity read memory
 * budget"), replacing the former `loadSnapshot`/`loadWorkspaceSnapshot`/graph's own raw row
 * SELECT: loads every row [shownPredicate] matches PLUS every active row of [targetIdentifiers]
 * inside ONE transaction (the CALLER's — this is an ordinary suspend function, not a method on
 * `EntityService`, so it runs inside whatever `suspendTransaction`/`writeTransaction` the caller
 * already opened). BEFORE a single document/team value crosses the wire, [reservation] is
 * charged the exact `SUM(octet_length(document)) + SUM(octet_length(team))` of the combined row
 * set — an over-budget workspace is refused (`ReadBudgetExceeded`) before its bytes are ever
 * fetched. Rows come back RAW ([WorkspaceRow.skeleton]/[WorkspaceRow.full] decode — and charge
 * the SAME [reservation] again, lazily — only when actually touched, after this call returns).
 * [reservation] = `null` exempts writers (`create`/`update`, already serialized by the V28 lock)
 * from both the admission check and the later decode charges.
 */
internal suspend fun loadReadSet(
    shownPredicate: Op<Boolean>,
    targetIdentifiers: Set<String>,
    blueprintsByIdentifier: Map<String, ReadActiveBlueprint>,
    reservation: EntityReadLedger.Reservation?,
): ReadSet {
    val blueprintsById = blueprintsByIdentifier.values.associateBy { it.id }
    val targetIds = targetIdentifiers.mapNotNull { blueprintsByIdentifier[it]?.id }
    val targetPredicate: Op<Boolean> = if (targetIds.isEmpty()) Op.FALSE else ReadEntities.blueprintId inList targetIds
    val combinedPredicate = active() and (shownPredicate or targetPredicate)

    val shownIds = ReadEntities.select(ReadEntities.id).where { active() and shownPredicate }
        .map { it[ReadEntities.id].value }.toList().toSet()

    reservation?.let {
        val docLen = octetLengthOf(ReadEntities.document)
        val teamLen = octetLengthOf(ReadEntities.team)
        val total = ReadEntities.select(docLen, teamLen).where { combinedPredicate }
            .map { row -> (row[docLen] ?: 0L) + (row[teamLen] ?: 0L) }
            .toList()
            .sum()
        it.charge(total)
    }

    val rows = ReadEntities.selectAll().where { combinedPredicate }
        .orderBy(ReadEntities.id, SortOrder.ASC)
        .map { it.toWorkspaceRow(blueprintsById, shownIds, reservation) }
        .toList()

    return ReadSet(rows, EntitySnapshot(rows, blueprintsByIdentifier, blueprintsById))
}
