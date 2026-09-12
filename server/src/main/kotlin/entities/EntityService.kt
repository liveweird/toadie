package ch.nokillswit.entities

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintService
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.SYSTEM_USER_BLUEPRINT
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.jsonStringOrArrayContainsFolded
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import io.r2dbc.spi.IsolationLevel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val EntityServiceKey = AttributeKey<EntityService>("EntityService")

/** `team`: single value, case-insensitive, blank = absent (`infra/paging/QueryParams.kt`'s `optionalString` idiom); repetition is a 400. */
data class EntityFilter(val blueprint: String?, val q: String?, val team: String? = null)

/** `GET …/entities/graph`'s filter: `blueprints` is the repeated any-of param (IN semantics). */
data class EntityGraphFilter(val blueprints: List<String>, val q: String?, val team: String? = null)

data class EntityListResult(val items: List<EntityResponse>, val total: Long)

/** [EntityService.update]'s outcome: affected-row count plus what the rename cascaded (for the audit). */
data class EntityUpdateResult(val affected: Int, val cascaded: List<String>, val renamedFrom: String?)

/** [EntityService.delete]'s outcome: affected-row count plus the deleted identity (for the audit). */
data class EntityDeleteResult(val affected: Int, val blueprint: String?, val identifier: String?)

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to EntityService.Entities.id,
    "identifier" to EntityService.Entities.identifier,
    "title" to EntityService.Entities.title,
    "updatedAt" to EntityService.Entities.updatedAt,
)

/** The ONE sortable whitelist — mirrors `catalog/CatalogFileService.kt`'s `CATALOG_FILE_SORT_FIELDS`. */
val ENTITY_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/** The blueprint identifier whose entities' `team` a rename/delete widens its scan for — `null` for an ordinary blueprint. */
private fun systemFormatFor(blueprintIdentifier: String): String? = when (blueprintIdentifier) {
    SYSTEM_TEAM_BLUEPRINT -> "team"
    SYSTEM_USER_BLUEPRINT -> "user"
    else -> null
}

class EntityService(private val database: R2dbcDatabase, private val jq: JqEvaluator = JqEvaluator()) {
    object Entities : UIntIdTable("entities") {
        // Case-folded identifier uniqueness PER BLUEPRINT is enforced by the partial unique
        // index uq_entities_blueprint_identifier_active (active rows only; V28) — a
        // soft-deleted entity frees its identifier within the same blueprint. FK to
        // blueprints.id (not the identifier), so a blueprint rename never touches entities.
        val blueprintId = reference("blueprint_id", BlueprintService.Blueprints)
        val identifier = varchar("identifier", length = MAX_ENTITY_IDENTIFIER_LENGTH)
        val title = varchar("title", length = MAX_ENTITY_TITLE_LENGTH)
        val icon = varchar("icon", length = MAX_ENTITY_ICON_LENGTH).nullable()
        // The JSON value EXACTLY as sent ("x" or ["x","y"]); NULL = absent. STORED (Direct/
        // absent ownership) — an Inherited blueprint's entities never write this column, and
        // effectiveTeam() computes what a caller sees instead (entities/EntityOwnership.kt).
        val team = text("team").nullable()
        // {properties, relations} as one blueprintJson document (the blueprints.definition
        // precedent).
        val document = text("document")
        val createdBy = reference("created_by", UserService.Users)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    /**
     * One global lock order — `blueprints` before `entities` (`.claude/docs/persistence.md`):
     * SHARE on `blueprints` (self-compatible; entity writers only ever serialize against EACH
     * OTHER, on `entities`) yet conflicting with a blueprint writer's SHARE ROW EXCLUSIVE, so an
     * entity is never validated against a definition mid-change nor attached to a blueprint
     * being deleted. READ COMMITTED so a writer that waited for either lock sees the prior
     * writer's committed rows before deciding.
     */
    private suspend fun <T> writeTransaction(block: suspend R2dbcTransaction.() -> T): T =
        suspendTransaction(database, transactionIsolation = IsolationLevel.READ_COMMITTED) {
            exec("LOCK TABLE blueprints IN SHARE MODE")
            exec("LOCK TABLE entities IN SHARE ROW EXCLUSIVE MODE")
            block()
        }

    private fun active(): Op<Boolean> = Entities.markedAsDeleted eq false

    private fun activeBlueprints(): Op<Boolean> = BlueprintService.Blueprints.markedAsDeleted eq false

    // The sanctioned cross-feature table reads (persistence.md): the creator's display fields
    // and the blueprint's identifier must come from the same transaction as the entity row.
    private fun joined() = Entities
        .join(UserService.Users, JoinType.INNER, onColumn = Entities.createdBy, otherColumn = UserService.Users.id)
        .join(BlueprintService.Blueprints, JoinType.INNER, onColumn = Entities.blueprintId, otherColumn = BlueprintService.Blueprints.id)

    private data class ActiveBlueprint(
        val id: UInt,
        val identifier: String,
        val title: String,
        val definition: BlueprintDefinition,
        val hierarchyRelation: String?,
    )

    private suspend fun loadActiveBlueprints(): List<ActiveBlueprint> =
        BlueprintService.Blueprints.selectAll().where { activeBlueprints() }
            .map {
                ActiveBlueprint(
                    it[BlueprintService.Blueprints.id].value,
                    it[BlueprintService.Blueprints.identifier],
                    it[BlueprintService.Blueprints.title],
                    blueprintJson.decodeFromString<BlueprintDefinition>(it[BlueprintService.Blueprints.definition]),
                    it[BlueprintService.Blueprints.hierarchyRelation],
                )
            }
            .toList()

    private data class SnapshotRow(
        val blueprintId: UInt,
        val blueprint: String,
        val identifier: String,
        val title: String,
        val icon: String?,
        val teamRaw: String?,
        val documentRaw: String,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        // Decoded ONCE per row regardless of how many computed properties/findings ask for it
        // (today's rowLookup re-decoded per call) — entities/EntityComputed.kt's IndexedRow.
        val decoded: IndexedRow by lazy {
            IndexedRow(
                blueprint = blueprint,
                identifier = identifier,
                title = title,
                icon = icon,
                createdAt = createdAt,
                updatedAt = updatedAt,
                document = blueprintJson.decodeFromString(documentRaw),
                team = teamRaw?.let { blueprintJson.decodeFromString<JsonElement>(it) },
            )
        }
    }

    /**
     * One snapshot of active rows for every blueprint [targetExists]/[rowLookup]/computed-property
     * evaluation might be asked about: relation targets, `_team`/`_user` (format-team/user
     * property checks and the team finding), every blueprint an Inherited `ownership.path` might
     * walk through, and — when [computed] widens it — every blueprint a mirror/aggregation
     * property might touch ([computedPathBlueprints]) — loaded ONCE per call
     * (`.claude/docs/persistence.md`), rows decoded lazily and once each ([SnapshotRow.decoded]).
     * Implements [EntityIndex] so `EntityComputed.kt`/`EntityAggregation.kt` never query the
     * database mid-evaluation; [inbound] is itself `by lazy` so `update`/`graph` (which never ask
     * for it) never pay for building it.
     */
    private class EntitySnapshot(
        rows: List<SnapshotRow>,
        blueprintsByIdentifier: Map<String, ActiveBlueprint>,
        private val blueprintsById: Map<UInt, ActiveBlueprint>,
    ) : EntityIndex {
        private data class Key(val blueprintId: UInt, val identifier: String)

        private val rowsByKey: Map<Key, SnapshotRow> = rows.associateBy { Key(it.blueprintId, it.identifier) }
        private val idByIdentifier: Map<String, UInt> = blueprintsByIdentifier.mapValues { it.value.id }

        val targetExists: TargetExists = { targetBlueprint, entityId ->
            val id = idByIdentifier[targetBlueprint]
            id != null && Key(id, entityId) in rowsByKey
        }

        override val rowLookup: RowLookup = { blueprint, identifier -> row(blueprint, identifier)?.asOwned() }

        override fun row(blueprint: String, identifier: String): IndexedRow? {
            val id = idByIdentifier[blueprint] ?: return null
            return rowsByKey[Key(id, identifier)]?.decoded
        }

        private val inboundIndex: Map<Key, List<Inbound>> by lazy {
            val index = mutableMapOf<Key, MutableList<Inbound>>()
            rowsByKey.values.forEach { sourceRow ->
                val sourceBlueprint = blueprintsById[sourceRow.blueprintId] ?: return@forEach
                sourceBlueprint.definition.relations.forEach { (relationId, relationDef) ->
                    val targetId = idByIdentifier[relationDef.target] ?: return@forEach
                    val values = hopTargetIdentifiers(sourceRow.decoded.document.relations[relationId], relationDef.many)
                    values.forEach { value ->
                        index.getOrPut(Key(targetId, value)) { mutableListOf() } += Inbound(sourceRow.decoded, relationId)
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

    /** Everything [toResponse] needs beyond one row's own columns — built once per call. */
    private data class EntityContext(
        val blueprintsById: Map<UInt, ActiveBlueprint>,
        val definitionsByIdentifier: Map<String, BlueprintDefinition>,
        val snapshot: EntitySnapshot,
        val jq: JqEvaluator,
        val now: Long,
    )

    /**
     * [computed]: whether the snapshot must ALSO cover every blueprint a mirror/aggregation
     * property might touch ([computedPathBlueprints]) — true for `list`/`read`/`create` (their
     * responses evaluate computed properties), false for `update` (204, no body) and `graph`
     * (nodes carry no `properties` at all).
     */
    private suspend fun loadSnapshot(
        definitions: Collection<BlueprintDefinition>,
        blueprintsByIdentifier: Map<String, ActiveBlueprint>,
        computed: Boolean,
    ): EntitySnapshot {
        val definitionsByIdentifier = blueprintsByIdentifier.mapValues { it.value.definition }
        val relationTargets = definitions.flatMap { it.relations.values.map { relation -> relation.target } }.toSet()
        val ownershipTargets = definitions.flatMap { ownershipPathBlueprints(it, definitionsByIdentifier) }.toSet()
        val computedTargets =
            if (computed) definitions.flatMap { computedPathBlueprints(it, definitionsByIdentifier) }.toSet() else emptySet()
        val targetIdentifiers = relationTargets + ownershipTargets + computedTargets + SYSTEM_TEAM_BLUEPRINT + SYSTEM_USER_BLUEPRINT
        val targetIds = targetIdentifiers.mapNotNull { blueprintsByIdentifier[it]?.id }.distinct()
        val blueprintsById = blueprintsByIdentifier.values.associateBy { it.id }
        val rows = if (targetIds.isEmpty()) {
            emptyList()
        } else {
            Entities.selectAll().where { (Entities.blueprintId inList targetIds) and active() }
                .map {
                    val blueprint = blueprintsById.getValue(it[Entities.blueprintId].value)
                    SnapshotRow(
                        blueprintId = blueprint.id,
                        blueprint = blueprint.identifier,
                        identifier = it[Entities.identifier],
                        title = it[Entities.title],
                        icon = it[Entities.icon],
                        teamRaw = it[Entities.team],
                        documentRaw = it[Entities.document],
                        createdAt = it[Entities.createdAt],
                        updatedAt = it[Entities.updatedAt],
                    )
                }
                .toList()
        }
        return EntitySnapshot(rows, blueprintsByIdentifier, blueprintsById)
    }

    /** One entity row as [toResponse] needs it — decoded ONCE, before computed-property evaluation runs OUTSIDE the transaction. */
    private data class RawEntity(
        val id: UInt,
        val blueprintId: UInt,
        val identifier: String,
        val title: String,
        val icon: String?,
        val document: EntityDocument,
        val storedTeam: JsonElement?,
        val createdBy: UInt,
        val creatorName: String,
        val creatorDeleted: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private fun ResultRow.toRawEntity(): RawEntity = RawEntity(
        id = this[Entities.id].value,
        blueprintId = this[Entities.blueprintId].value,
        identifier = this[Entities.identifier],
        title = this[Entities.title],
        icon = this[Entities.icon],
        document = blueprintJson.decodeFromString(this[Entities.document]),
        storedTeam = this[Entities.team]?.let { blueprintJson.decodeFromString<JsonElement>(it) },
        createdBy = this[Entities.createdBy].value,
        creatorName = this[UserService.Users.name],
        creatorDeleted = this[UserService.Users.markedAsDeleted],
        createdAt = this[Entities.createdAt],
        updatedAt = this[Entities.updatedAt],
    )

    /**
     * A plain function over an in-memory [RawEntity] (choice 1, `.claude/docs/persistence.md`):
     * `list`/`read`/`create` materialize [RawEntity] rows plus the [EntityContext] snapshot INSIDE
     * their transaction, then map them through this function AFTER it closes, so a pathological
     * jq expression or a large aggregation fan-out never pins a pooled R2DBC connection or the
     * entity write lock. The effective team is computed FIRST — it feeds the response `team`
     * field, the jq calculation input, and every `$team` mirror/aggregation terminal alike.
     * `findings` is unchanged: computed against the STORED document, never the computed values.
     * `suspend` since v1.28.1: computed-property evaluation now runs on [JqEvaluator]'s bounded
     * pool ([JqEvaluator.evaluateBounded]) rather than blocking this coroutine's own thread.
     */
    private suspend fun toResponse(raw: RawEntity, context: EntityContext): EntityResponse {
        // A blueprint can only be deleted once its active entity count is 0 (BlueprintService.
        // delete), so any active entity's blueprint is guaranteed active here.
        val blueprint = context.blueprintsById[raw.blueprintId]
            ?: error("entity ${raw.id} references blueprint ${raw.blueprintId}, which is not active")
        val effectiveTeamValue =
            effectiveTeam(raw.storedTeam, raw.document, blueprint.definition, context.definitionsByIdentifier, context.snapshot.rowLookup)
        val subject = ComputedSubject(
            blueprint = blueprint.identifier,
            identifier = raw.identifier,
            title = raw.title,
            icon = raw.icon,
            team = effectiveTeamValue,
            document = raw.document,
            createdAt = raw.createdAt,
            updatedAt = raw.updatedAt,
        )
        val computed = computedProperties(
            subject, blueprint.definition, context.definitionsByIdentifier, context.snapshot, context.jq, context.now,
        )
        return EntityResponse(
            id = raw.id,
            blueprint = blueprint.identifier,
            blueprintId = raw.blueprintId,
            identifier = raw.identifier,
            title = raw.title,
            icon = raw.icon,
            team = effectiveTeamValue,
            properties = JsonObject(raw.document.properties + computed),
            relations = raw.document.relations,
            findings = entityFindings(raw.document, blueprint.definition, context.snapshot.targetExists, raw.storedTeam),
            createdBy = raw.createdBy,
            creatorName = raw.creatorName,
            creatorDeleted = raw.creatorDeleted,
            createdAt = raw.createdAt,
            updatedAt = raw.updatedAt,
        )
    }

    /**
     * `_team` self-match: `Entities.blueprintId eq <the _team blueprint>` AND
     * `LOWER(identifier) = LOWER(value)` — so a team-filtered graph keeps the team node.
     */
    private fun teamSelfMatch(value: String, blueprintsByIdentifierFolded: Map<String, ActiveBlueprint>): Op<Boolean>? =
        blueprintsByIdentifierFolded[SYSTEM_TEAM_BLUEPRINT.lowercase()]?.let { teamBlueprint ->
            (Entities.blueprintId eq teamBlueprint.id) and (LowerCase(Entities.identifier) eq stringParam(value.lowercase()))
        }

    private fun teamPredicate(team: String, blueprintsByIdentifierFolded: Map<String, ActiveBlueprint>): Op<Boolean> {
        val membership = Entities.team.jsonStringOrArrayContainsFolded(team)
        val selfMatch = teamSelfMatch(team, blueprintsByIdentifierFolded)
        return if (selfMatch == null) membership else membership or selfMatch
    }

    private data class Materialized<T>(val payload: T, val context: EntityContext)

    /**
     * `q` substring-matches identifier OR title; an unknown `blueprint` identifier is empty
     * (never a 404/400). Rows and the snapshot materialize inside ONE transaction; computed
     * properties evaluate over them AFTER it closes (choice 1, `.claude/docs/persistence.md`).
     */
    suspend fun list(filter: EntityFilter, paging: PageRequest): EntityListResult {
        val now = System.currentTimeMillis()
        val materialized = suspendTransaction(database) {
            val activeBlueprints = loadActiveBlueprints()
            val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
            val blueprintsById = activeBlueprints.associateBy { it.id }
            val definitionsByIdentifier = activeBlueprints.associate { it.identifier to it.definition }
            // The list filter is the ONE case-insensitive lookup (blueprint identifiers are
            // unique case-insensitively); every other identifier-keyed map here (targetExists)
            // stays byte-exact.
            val blueprintsByIdentifierFolded = activeBlueprints.associateBy { it.identifier.lowercase() }
            val filterBlueprint = filter.blueprint?.let { blueprintsByIdentifierFolded[it.lowercase()] }
            val unknownBlueprint = filter.blueprint != null && filterBlueprint == null

            var predicate: Op<Boolean> = active()
            filterBlueprint?.let { predicate = predicate and (Entities.blueprintId eq it.id) }
            filter.q?.let { q ->
                predicate = predicate and (Entities.identifier.containsNormalized(q) or Entities.title.containsNormalized(q))
            }
            filter.team?.let { team -> predicate = predicate and teamPredicate(team, blueprintsByIdentifierFolded) }

            val total = if (unknownBlueprint) 0L else joined().selectAll().where { predicate }.count()
            val rows = if (unknownBlueprint) {
                emptyList()
            } else {
                joined().selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).map { it.toRawEntity() }.toList()
            }
            val definitions = rows.mapNotNull { blueprintsById[it.blueprintId]?.definition }
            val snapshot = loadSnapshot(definitions, blueprintsByIdentifier, computed = true)
            val context = EntityContext(blueprintsById, definitionsByIdentifier, snapshot, jq, now)
            Materialized(rows to total, context)
        }
        val (rows, total) = materialized.payload
        return EntityListResult(rows.map { toResponse(it, materialized.context) }, total)
    }

    /**
     * `GET …/entities/graph`: a plain read (no write-lock — reads never wait behind the
     * blueprints/entities writer lock). `blueprints` folds case-insensitively against the
     * active blueprint identifiers (the list filter's own idiom); if the filter names at
     * least one blueprint and NONE resolve, the graph is empty rather than "no predicate"
     * (an empty resolved-id set must narrow to nothing, not widen to everything). Loads ONLY
     * the rows the filter shows — an edge needs both ends shown, so a hidden row can never
     * contribute a node or an edge (`catalog/Graph.kt`'s rule, one level down) — with no
     * users join (the graph never needs creator display fields). Ownership edges follow the
     * SAME both-ends rule via [EntityGraphSource.team], the EFFECTIVE team.
     */
    suspend fun graph(filter: EntityGraphFilter): EntityGraph = suspendTransaction(database) {
        val activeBlueprints = loadActiveBlueprints()
        val blueprintsById = activeBlueprints.associateBy { it.id }
        val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
        val definitionsByIdentifier = activeBlueprints.associate { it.identifier to it.definition }
        val blueprintsByIdentifierFolded = activeBlueprints.associateBy { it.identifier.lowercase() }

        var predicate: Op<Boolean> = active()
        if (filter.blueprints.isNotEmpty()) {
            val ids = filter.blueprints.mapNotNull { blueprintsByIdentifierFolded[it.lowercase()]?.id }.distinct()
            if (ids.isEmpty()) return@suspendTransaction EntityGraph(emptyList(), emptyList())
            predicate = predicate and (Entities.blueprintId inList ids)
        }
        filter.q?.let { q -> predicate = predicate and (Entities.identifier.containsNormalized(q) or Entities.title.containsNormalized(q)) }
        filter.team?.let { team -> predicate = predicate and teamPredicate(team, blueprintsByIdentifierFolded) }

        data class RawSource(
            val id: UInt,
            val blueprintId: UInt,
            val identifier: String,
            val title: String,
            val icon: String?,
            val document: EntityDocument,
            val storedTeam: JsonElement?,
        )

        val rawSources = Entities.selectAll().where { predicate }.map {
            RawSource(
                id = it[Entities.id].value,
                blueprintId = it[Entities.blueprintId].value,
                identifier = it[Entities.identifier],
                title = it[Entities.title],
                icon = it[Entities.icon],
                document = blueprintJson.decodeFromString(it[Entities.document]),
                storedTeam = it[Entities.team]?.let { t -> blueprintJson.decodeFromString<JsonElement>(t) },
            )
        }.toList()

        val shownDefinitions = rawSources.mapNotNull { blueprintsById[it.blueprintId]?.definition }
        val snapshot = loadSnapshot(shownDefinitions, blueprintsByIdentifier, computed = false)
        val storedTeamById = rawSources.associate { it.id to it.storedTeam }

        val sources = rawSources.map { raw ->
            val definition = blueprintsById.getValue(raw.blueprintId).definition
            EntityGraphSource(
                id = raw.id,
                blueprintId = raw.blueprintId,
                identifier = raw.identifier,
                title = raw.title,
                icon = raw.icon,
                document = raw.document,
                team = teamValues(effectiveTeam(raw.storedTeam, raw.document, definition, definitionsByIdentifier, snapshot.rowLookup)),
            )
        }

        val graphBlueprintsById = blueprintsById.mapValues {
            GraphBlueprint(it.value.identifier, it.value.title, it.value.definition, it.value.hierarchyRelation)
        }
        buildEntityGraph(sources, graphBlueprintsById) { source ->
            val blueprint = blueprintsById.getValue(source.blueprintId)
            entityFindings(source.document, blueprint.definition, snapshot.targetExists, storedTeamById[source.id]).size
        }
    }

    suspend fun read(id: UInt): EntityResponse? {
        val now = System.currentTimeMillis()
        val materialized = suspendTransaction(database) {
            val row = joined().selectAll().where { (Entities.id eq id) and active() }.singleOrNull() ?: return@suspendTransaction null
            val activeBlueprints = loadActiveBlueprints()
            val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
            val blueprintsById = activeBlueprints.associateBy { it.id }
            val definitionsByIdentifier = activeBlueprints.associate { it.identifier to it.definition }
            val definition = blueprintsById[row[Entities.blueprintId].value]?.definition
            val snapshot = loadSnapshot(listOfNotNull(definition), blueprintsByIdentifier, computed = true)
            val context = EntityContext(blueprintsById, definitionsByIdentifier, snapshot, jq, now)
            Materialized(row.toRawEntity(), context)
        } ?: return null
        return toResponse(materialized.payload, materialized.context)
    }

    /**
     * The read seam bulk import needs (`entities/EntityImport.kt`): active blueprint
     * definitions plus every active entity's identity, in ONE plain transaction (no write
     * lock — a snapshot read, the same posture as [graph]). [EntityImportSnapshot] resolves
     * relation/team/format targets BYTE-EXACT (the `targetExists` convention every other
     * snapshot in this file follows) and existing-row lookups case-insensitively (the
     * partial-unique-index convention).
     */
    suspend fun importSnapshot(): EntityImportSnapshot = suspendTransaction(database) {
        val activeBlueprints = loadActiveBlueprints()
        val blueprints = activeBlueprints.map { EntityImportBlueprint(it.id, it.identifier, it.definition) }
        val rows = Entities.selectAll().where { active() }
            .map { Triple(it[Entities.blueprintId].value, it[Entities.identifier], it[Entities.id].value) }
            .toList()
        val identifiersByBlueprint = rows.groupBy({ it.first }, { it.second to it.third })
        val countsByBlueprint = rows.groupingBy { it.first }.eachCount().mapValues { it.value.toLong() }
        EntityImportSnapshot(blueprints, identifiersByBlueprint, rows.size.toLong(), countsByBlueprint)
    }

    suspend fun create(request: EntityRequest, callerId: UInt): EntityResponse {
        validateEntityRequest(request) // re-checked service-side so direct callers stay guarded
        val now = System.currentTimeMillis()
        val materialized = writeTransaction {
            val activeBlueprints = loadActiveBlueprints()
            val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
            val blueprint = blueprintsByIdentifier[request.blueprint] ?: throw BadRequestException("Unknown blueprint")
            checkCaps(blueprint.id)
            val document = request.toDocument()
            val snapshot = loadSnapshot(listOf(blueprint.definition), blueprintsByIdentifier, computed = true)
            val findings = entityFindings(document, blueprint.definition, snapshot.targetExists, request.team)
            requireNoFindings(findings)
            val id = insertRow(request, blueprint.id, document, callerId, now)
            val row = joined().selectAll().where { Entities.id eq id }.singleOrNull()
                ?: error("entity $id vanished between insert and read-back")
            val blueprintsById = activeBlueprints.associateBy { it.id } + (blueprint.id to blueprint)
            val definitionsByIdentifier =
                activeBlueprints.associate { it.identifier to it.definition } + (blueprint.identifier to blueprint.definition)
            val context = EntityContext(blueprintsById, definitionsByIdentifier, snapshot, jq, now)
            Materialized(row.toRawEntity(), context)
        }
        return toResponse(materialized.payload, materialized.context)
    }

    private suspend fun checkCaps(blueprintId: UInt) {
        if (Entities.selectAll().where { active() }.count() >= MAX_ENTITIES_TOTAL) {
            throw BadRequestException("The entity registry is full ($MAX_ENTITIES_TOTAL entities)")
        }
        if (Entities.selectAll().where { (Entities.blueprintId eq blueprintId) and active() }.count() >= MAX_ENTITIES_PER_BLUEPRINT) {
            throw BadRequestException("This blueprint's entities are full ($MAX_ENTITIES_PER_BLUEPRINT entities)")
        }
    }

    private fun requireNoFindings(findings: List<EntityFinding>) {
        if (findings.isNotEmpty()) {
            throw EntityInvalidException(findings)
        }
    }

    private suspend fun insertRow(request: EntityRequest, blueprintId: UInt, document: EntityDocument, callerId: UInt, now: Long): UInt =
        Entities.insert {
            it[Entities.blueprintId] = blueprintId
            it[identifier] = request.identifier
            it[title] = request.title
            it[icon] = request.icon
            it[team] = request.team?.let { team -> blueprintJson.encodeToString(team) }
            it[Entities.document] = blueprintJson.encodeToString(document)
            it[createdBy] = callerId
            it[createdAt] = now
            it[updatedAt] = now
        }[Entities.id].value

    /**
     * Row missing → affected 0 (the route's 404), decided BEFORE validation — the `LensService`/
     * `BlueprintService` order. `request.blueprint` must equal the stored row's blueprint
     * (400 otherwise: an entity never moves blueprints). An identifier rename cascades into
     * every OTHER active entity whose blueprint has a relation targeting THIS blueprint and
     * whose relation value names the old identifier, in this same locked transaction — plus,
     * when this entity's OWN blueprint is `_team`/`_user`, every OTHER active entity's `team`
     * column / `format: team|user` property values naming the old identifier (Phase 4).
     */
    suspend fun update(id: UInt, request: EntityRequest): EntityUpdateResult = writeTransaction {
        val row = Entities.selectAll().where { (Entities.id eq id) and active() }.singleOrNull()
            ?: return@writeTransaction EntityUpdateResult(0, emptyList(), null)
        validateEntityRequest(request) // re-checked service-side so direct callers stay guarded
        val activeBlueprints = loadActiveBlueprints()
        val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
        val blueprintsById = activeBlueprints.associateBy { it.id }
        val currentBlueprint = blueprintsById[row[Entities.blueprintId].value]
            ?: error("entity $id references a blueprint that is not active")
        if (request.blueprint != currentBlueprint.identifier) {
            throw BadRequestException("blueprint must be '${currentBlueprint.identifier}' and cannot be changed")
        }
        val document = request.toDocument()
        val baseSnapshot = loadSnapshot(listOf(currentBlueprint.definition), blueprintsByIdentifier, computed = false)
        // The SELECT backing baseSnapshot runs before THIS row's identifier rename is written,
        // so a self-blueprint relation naming the row's own NEW identifier would be wrongly
        // rejected; treat it as a synthetic self-match.
        val targetExists: TargetExists = { targetBlueprint, entityId ->
            (targetBlueprint == currentBlueprint.identifier && entityId == request.identifier) ||
                baseSnapshot.targetExists(targetBlueprint, entityId)
        }
        requireNoFindings(entityFindings(document, currentBlueprint.definition, targetExists, request.team))

        val currentIdentifier = row[Entities.identifier]
        val renamed = currentIdentifier != request.identifier
        val cascaded = if (renamed) {
            cascadeRename(activeBlueprints, currentBlueprint.identifier, currentIdentifier, request.identifier)
        } else {
            emptyList()
        }
        Entities.update({ (Entities.id eq id) and active() }) {
            it[identifier] = request.identifier
            it[title] = request.title
            it[icon] = request.icon
            it[team] = request.team?.let { team -> blueprintJson.encodeToString(team) }
            it[Entities.document] = blueprintJson.encodeToString(document)
            it[updatedAt] = System.currentTimeMillis()
        }
        EntityUpdateResult(1, cascaded, if (renamed) currentIdentifier else null)
    }

    /**
     * Rewrites every OTHER active entity referencing [oldIdentifier] within
     * [blueprintIdentifier]; returns "blueprint/identifier" strings. For an ordinary blueprint,
     * the candidate set stays scoped to REFERRER blueprints (a relation targeting
     * [blueprintIdentifier]) — the phase-2 shape. For `_team`/`_user` (Phase 4), the `team`
     * column and `format: team|user` property values can live on ANY entity regardless of its
     * own blueprint's relations, so the scan widens to every active entity.
     */
    private suspend fun cascadeRename(
        activeBlueprints: List<ActiveBlueprint>,
        blueprintIdentifier: String,
        oldIdentifier: String,
        newIdentifier: String,
    ): List<String> {
        val format = systemFormatFor(blueprintIdentifier)
        val candidateBlueprintIds =
            if (format != null) activeBlueprints.map { it.id } else referrerBlueprintIds(activeBlueprints, blueprintIdentifier)
        if (candidateBlueprintIds.isEmpty()) return emptyList()
        val blueprintsById = activeBlueprints.associateBy { it.id }
        val now = System.currentTimeMillis()
        val cascaded = mutableListOf<String>()
        candidateEntities(candidateBlueprintIds).forEach { candidate ->
            val candidateBlueprint = blueprintsById.getValue(candidate[Entities.blueprintId].value)
            val candidateDocument = blueprintJson.decodeFromString<EntityDocument>(candidate[Entities.document])
            val storedTeam = candidate[Entities.team]?.let { blueprintJson.decodeFromString<JsonElement>(it) }
            val relationHit = blueprintIdentifier to oldIdentifier in entityTargets(candidateDocument, candidateBlueprint.definition)
            val teamHit = format == "team" && storedTeam != null && oldIdentifier in teamValues(storedTeam)
            val formatHit = format != null && oldIdentifier in formatTargets(candidateDocument, candidateBlueprint.definition, format)
            if (!relationHit && !teamHit && !formatHit) return@forEach

            var newDocument = candidateDocument
            if (relationHit) {
                newDocument = withEntityTargetRenamed(
                    newDocument, candidateBlueprint.definition, blueprintIdentifier, oldIdentifier, newIdentifier,
                )
            }
            if (formatHit) {
                newDocument = withFormatTargetRenamed(newDocument, candidateBlueprint.definition, format!!, oldIdentifier, newIdentifier)
            }
            val newTeam = if (teamHit) withTeamRenamed(storedTeam, oldIdentifier, newIdentifier) else storedTeam
            Entities.update({ Entities.id eq candidate[Entities.id] }) {
                it[document] = blueprintJson.encodeToString(newDocument)
                it[team] = newTeam?.let { t -> blueprintJson.encodeToString(t) }
                it[updatedAt] = now
            }
            cascaded += "${candidateBlueprint.identifier}/${candidate[Entities.identifier]}"
        }
        return cascaded
    }

    private fun referrerBlueprintIds(activeBlueprints: List<ActiveBlueprint>, targetIdentifier: String): List<UInt> =
        activeBlueprints
            .filter { targetIdentifier in it.definition.relations.values.map { relation -> relation.target } }
            .map { it.id }

    private suspend fun candidateEntities(blueprintIds: List<UInt>): List<ResultRow> =
        Entities.selectAll().where { (Entities.blueprintId inList blueprintIds) and active() }.toList()

    /**
     * Referrers = other active entities naming this one through a relation targeting this
     * blueprint, plus — for `_team`/`_user` (Phase 4) — the `team` column / `format: team|user`
     * property values; a self-reference never blocks.
     */
    suspend fun delete(id: UInt): EntityDeleteResult = writeTransaction {
        val row = Entities.selectAll().where { (Entities.id eq id) and active() }.singleOrNull()
            ?: return@writeTransaction EntityDeleteResult(0, null, null)
        val activeBlueprints = loadActiveBlueprints()
        val blueprintsById = activeBlueprints.associateBy { it.id }
        val blueprintId = row[Entities.blueprintId].value
        val blueprint = blueprintsById[blueprintId] ?: error("entity $id references blueprint $blueprintId, which is not active")
        val identifier = row[Entities.identifier]
        val referrers = findReferrers(id, activeBlueprints, blueprintsById, blueprint.identifier, identifier)
        if (referrers.isNotEmpty()) {
            val target = "${blueprint.identifier}/$identifier"
            throw ConflictException("Entity '$target' is the target of relations in: ${referrers.joinToString()}")
        }
        val affected = Entities.update({ (Entities.id eq id) and active() }) { it[markedAsDeleted] = true }
        EntityDeleteResult(affected, blueprint.identifier, identifier)
    }

    private suspend fun findReferrers(
        id: UInt,
        activeBlueprints: List<ActiveBlueprint>,
        blueprintsById: Map<UInt, ActiveBlueprint>,
        blueprintIdentifier: String,
        identifier: String,
    ): List<String> {
        val format = systemFormatFor(blueprintIdentifier)
        val candidateBlueprintIds =
            if (format != null) activeBlueprints.map { it.id } else referrerBlueprintIds(activeBlueprints, blueprintIdentifier)
        if (candidateBlueprintIds.isEmpty()) return emptyList()
        return Entities.selectAll()
            .where { (Entities.blueprintId inList candidateBlueprintIds) and active() and (Entities.id neq id) }
            .toList()
            .mapNotNull { candidate -> referrerLabel(candidate, blueprintsById, blueprintIdentifier, identifier, format) }
    }

    private fun referrerLabel(
        candidate: ResultRow,
        blueprintsById: Map<UInt, ActiveBlueprint>,
        blueprintIdentifier: String,
        identifier: String,
        format: String?,
    ): String? {
        val candidateBlueprint = blueprintsById.getValue(candidate[Entities.blueprintId].value)
        val candidateDocument = blueprintJson.decodeFromString<EntityDocument>(candidate[Entities.document])
        val relationHit = blueprintIdentifier to identifier in entityTargets(candidateDocument, candidateBlueprint.definition)
        val storedTeam = candidate[Entities.team]?.let { blueprintJson.decodeFromString<JsonElement>(it) }
        val teamHit = format == "team" && storedTeam != null && identifier in teamValues(storedTeam)
        val formatHit = format != null && identifier in formatTargets(candidateDocument, candidateBlueprint.definition, format)
        if (!relationHit && !teamHit && !formatHit) return null
        return "${candidateBlueprint.identifier}/${candidate[Entities.identifier]}"
    }
}
