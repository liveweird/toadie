package ch.nokillswit.entities

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintService
import ch.nokillswit.blueprints.BlueprintTiers
import ch.nokillswit.blueprints.decodeTiers
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.SYSTEM_USER_BLUEPRINT
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.dictionaries.Dictionary
import ch.nokillswit.dictionaries.DictionaryService
import ch.nokillswit.entityquery.DEFAULT_ENTITY_QUERY_DEADLINE_MILLIS
import ch.nokillswit.entityquery.MAX_CONCURRENT_ENTITY_QUERIES
import ch.nokillswit.entityquery.QuerySchema
import ch.nokillswit.infra.db.bumpOntologyRevision
import ch.nokillswit.infra.db.lockingTransaction
import ch.nokillswit.infra.db.octetLength
import ch.nokillswit.infra.db.ontologyReadTransaction
import ch.nokillswit.infra.fetch.MAX_FETCH_URL_LENGTH
import ch.nokillswit.infra.fetch.SourceWrite
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val EntityServiceKey = AttributeKey<EntityService>("EntityService")

/** [EntityService.list]'s outcome — [items], [total], and V39 [revision] share one read snapshot. */
data class EntityListResult(val items: List<EntityResponse>, val total: Long, val revision: Long)

/** [EntityService.update]'s outcome: affected-row count plus what the rename cascaded (for the audit). */
data class EntityUpdateResult(val affected: Int, val cascaded: List<String>, val renamedFrom: String?)

/** [EntityService.delete]'s outcome: affected-row count plus the deleted identity (for the audit). */
data class EntityDeleteResult(val affected: Int, val blueprint: String?, val identifier: String?)

internal val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to EntityService.Entities.id,
    "identifier" to EntityService.Entities.identifier,
    "title" to EntityService.Entities.title,
    "updatedAt" to EntityService.Entities.updatedAt,
    "lastSyncedAt" to EntityService.Entities.lastSyncedAt,
)

/** The ONE sortable whitelist — mirrors `catalog/CatalogFileService.kt`'s `CATALOG_FILE_SORT_FIELDS`. */
val ENTITY_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/** The blueprint identifier whose entities' `team` a rename/delete widens its scan for — `null` for an ordinary blueprint. */
private fun systemFormatFor(blueprintIdentifier: String): String? = when (blueprintIdentifier) {
    SYSTEM_TEAM_BLUEPRINT -> "team"
    SYSTEM_USER_BLUEPRINT -> "user"
    else -> null
}

/** The V28 fixed global lock order this service's every mutation runs under (`.claude/docs/persistence.md`). */
private const val LOCK_BLUEPRINTS_SHARE = "LOCK TABLE blueprints IN SHARE MODE"
private const val LOCK_ENTITIES_SHARE_ROW_EXCLUSIVE = "LOCK TABLE entities IN SHARE ROW EXCLUSIVE MODE"

class EntityService(
    internal val database: R2dbcDatabase,
    internal val jq: JqEvaluator = JqEvaluator(),
    /** `entityQuery.deadlineMillis` (`infra/db/Database.kt`) — the per-`query` evaluation budget (`graph`). */
    internal val queryDeadlineMillis: Long = DEFAULT_ENTITY_QUERY_DEADLINE_MILLIS,
    /** The evaluation budget's clock — injectable so tests pin the deadline and cancellation paths deterministically. */
    internal val queryClock: () -> Long = System::nanoTime,
    /** In-flight query evaluations this instance allows at once (each holds a decoded workspace) — a test seam. */
    queryPermits: Int = MAX_CONCURRENT_ENTITY_QUERIES,
    /** The workspace document byte budget every create/replace/import is checked against — a test seam. */
    internal val workspaceDocumentBytes: Long = MAX_WORKSPACE_DOCUMENT_BYTES,
    /** The process-wide read ledger every graph/list/read charges (`EntityReadBudget.kt`) — a test seam. */
    internal val readLedger: EntityReadLedger = EntityReadLedger(),
) {
    /** `tryAcquire` only — a saturated instance answers `429` immediately rather than queueing a workspace load. */
    internal val querySlots = Semaphore(queryPermits)

    init {
        // The pool ([QUERY_DISPATCHER]) has exactly MAX_CONCURRENT_ENTITY_QUERIES threads: more
        // permits than threads would queue evaluations, each holding a decoded workspace.
        require(queryPermits in 1..MAX_CONCURRENT_ENTITY_QUERIES) { "queryPermits must be between 1 and $MAX_CONCURRENT_ENTITY_QUERIES" }
        require(workspaceDocumentBytes > 0) { "workspaceDocumentBytes must be positive" }
    }

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
        // Source references & HTTP re-sync (2.9.0, V37 — the `catalog_files` envelope one level
        // down): the https URL of the entity's canonical remote copy; NULL = none set.
        val sourceUrl = varchar("source_url", length = MAX_FETCH_URL_LENGTH).nullable()
        // Epoch millis of the last HTTP->DB sync; 0 = never (the catalog_files.last_synced_at idiom).
        val lastSyncedAt = long("last_synced_at").default(0)
        // The request-shaped document snapshot taken at sync time — the sync modal's baseline. NULL = never synced.
        val syncedContent = text("synced_content").nullable()
    }

    /**
     * One global lock order — `blueprints` before `entities` (`.claude/docs/persistence.md`):
     * SHARE on `blueprints` (self-compatible; entity writers only ever serialize against EACH
     * OTHER, on `entities`) yet conflicting with a blueprint writer's SHARE ROW EXCLUSIVE, so an
     * entity is never validated against a definition mid-change nor attached to a blueprint
     * being deleted — via the shared [lockingTransaction] helper (`infra/db/Locking.kt`), which
     * executes [LOCK_BLUEPRINTS_SHARE] then [LOCK_ENTITIES_SHARE_ROW_EXCLUSIVE] in that fixed
     * order.
     */
    internal suspend fun <T> writeTransaction(block: suspend R2dbcTransaction.() -> T): T =
        lockingTransaction(database, LOCK_BLUEPRINTS_SHARE, LOCK_ENTITIES_SHARE_ROW_EXCLUSIVE, block = block)

    internal fun active(): Op<Boolean> = Entities.markedAsDeleted eq false

    internal fun activeBlueprints(): Op<Boolean> = BlueprintService.Blueprints.markedAsDeleted eq false

    // The sanctioned cross-feature table reads (persistence.md): the creator's display fields
    // and the blueprint's identifier must come from the same transaction as the entity row.
    internal fun joined() = Entities
        .join(UserService.Users, JoinType.INNER, onColumn = Entities.createdBy, otherColumn = UserService.Users.id)
        .join(BlueprintService.Blueprints, JoinType.INNER, onColumn = Entities.blueprintId, otherColumn = BlueprintService.Blueprints.id)

    // Widened to internal (from private) so entities/EntityFilter.kt's team/blueprint filter
    // helpers — which need to read `.id`/`.definition` — can take it as a parameter; the ONE
    // deliberate crack in this class's otherwise-private state (`.claude/docs/persistence.md`).
    internal data class ActiveBlueprint(
        val id: UInt,
        val identifier: String,
        val title: String,
        val definition: BlueprintDefinition,
        /** Hierarchy identifier -> relation key (V34) — decoded from `blueprints.hierarchy_relations`. */
        val hierarchyRelations: Map<String, String>,
        /** Fill-in tiers (2.18.0, V44) — threaded through for the query engine; never read by validation or findings. */
        val tiers: BlueprintTiers,
        /** The blueprint's source reference (2.10.0, V38) — threaded through for `entities/EntityErrors.kt`'s `SOURCE_MISSING`. */
        val sourceUrl: String?,
    )

    internal suspend fun loadActiveBlueprints(budget: OntologyReadBudget? = null): List<ActiveBlueprint> =
        BlueprintService.Blueprints.selectAll().where { activeBlueprints() }
            .map {
                ActiveBlueprint(
                    it[BlueprintService.Blueprints.id].value,
                    it[BlueprintService.Blueprints.identifier],
                    it[BlueprintService.Blueprints.title],
                    decodeForRead<BlueprintDefinition>(it[BlueprintService.Blueprints.definition], budget),
                    blueprintJson.decodeFromString<Map<String, String>>(it[BlueprintService.Blueprints.hierarchyRelations]),
                    decodeTiers(it[BlueprintService.Blueprints.tiers]) ?: BlueprintTiers(),
                    it[BlueprintService.Blueprints.sourceUrl],
                )
            }
            .toList()

    /** Everything [toResponse] needs beyond one row's own columns — built once per call. */
    private data class EntityContext(
        val blueprintsById: Map<UInt, ActiveBlueprint>,
        val definitionsByIdentifier: Map<String, BlueprintDefinition>,
        val snapshot: EntitySnapshot,
        val jq: JqEvaluator,
        val now: Long,
    )

    /** One read of the active blueprints + `HIERARCHY` values as the validator's [QuerySchema] — no entity rows. */
    internal suspend fun loadQuerySchema(): QuerySchema {
        val graphBlueprintsByIdentifier = loadActiveBlueprints().associate {
            it.identifier to GraphBlueprint(it.identifier, it.title, it.definition, it.hierarchyRelations, it.tiers)
        }
        return QuerySchema(graphBlueprintsByIdentifier, loadActiveHierarchies())
    }

    /**
     * The active `HIERARCHY` dictionary values a `[:hierarchyId]` query edge type may name — a
     * sanctioned cross-feature table read of [DictionaryService.Entries] (`.claude/docs/
     * persistence.md`), run inside the SAME transaction as [graph]'s other reads (the
     * `blueprints/BlueprintService.kt` `hierarchyDictionaryPredicate`/`knownHierarchies`
     * precedent, which opens its own separate transaction — this one shares the caller's).
     */
    internal suspend fun loadActiveHierarchies(): Set<String> {
        val hierarchyDictionary = DictionaryService.Entries.dictionary eq Dictionary.HIERARCHY.name
        val active = DictionaryService.Entries.markedAsDeleted eq false
        return DictionaryService.Entries
            .selectAll()
            .where { hierarchyDictionary and active }
            .map { it[DictionaryService.Entries.value] }
            .toList()
            .toSet()
    }

    /**
     * `entities/EntityFilter.kt`'s [inheritedTeamMatches] access point into this class's private
     * read-set machinery — it only ever needs the resulting [RowLookup] to walk an Inherited
     * ownership path, never [EntitySnapshot]/[WorkspaceRow] themselves, so this ONE narrow
     * accessor is internal rather than [loadReadSet] (or its private return type) directly.
     * A null reservation preserves REST's existing filter-resolution exemption. Integration
     * reads pass their reservation because the lookup retains decoded target skeletons while
     * matching candidates, even though candidate full documents are transient.
     */
    internal suspend fun rowLookupFor(
        definitions: Collection<BlueprintDefinition>,
        blueprintsByIdentifier: Map<String, ActiveBlueprint>,
        reservation: EntityReadLedger.Reservation? = null,
    ): RowLookup {
        val definitionsByIdentifier = blueprintsByIdentifier.mapValues { it.value.definition }
        val targets = narrowTargets(definitions, definitionsByIdentifier, computed = false)
        return loadReadSet(Op.FALSE, targets, blueprintsByIdentifier, reservation).snapshot.rowLookup
    }

    /** One entity row as [toResponse] needs it — decoded ONCE, before computed-property evaluation runs OUTSIDE the transaction. */
    internal data class RawEntity(
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
        val sourceUrl: String?,
        val lastSyncedAt: Long,
    )

    internal fun ResultRow.toRawEntity(budget: OntologyReadBudget? = null): RawEntity = RawEntity(
        id = this[Entities.id].value,
        blueprintId = this[Entities.blueprintId].value,
        identifier = this[Entities.identifier],
        title = this[Entities.title],
        icon = this[Entities.icon],
        document = decodeForRead(this[Entities.document], budget),
        storedTeam = this[Entities.team]?.let { blueprintJson.decodeFromString<JsonElement>(it) },
        createdBy = this[Entities.createdBy].value,
        creatorName = this[UserService.Users.name],
        creatorDeleted = this[UserService.Users.markedAsDeleted],
        createdAt = this[Entities.createdAt],
        updatedAt = this[Entities.updatedAt],
        sourceUrl = this[Entities.sourceUrl],
        lastSyncedAt = this[Entities.lastSyncedAt],
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
    private suspend fun toResponse(raw: RawEntity, context: EntityContext, budget: OntologyReadBudget? = null): EntityResponse {
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
            subject, blueprint.definition, context.definitionsByIdentifier, context.snapshot, context.jq, context.now, budget,
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
            findings = entityFindings(raw.document, blueprint.definition, context.snapshot.targetExists, raw.storedTeam)
                .also { budget?.retainFindings(it) },
            createdBy = raw.createdBy,
            creatorName = raw.creatorName,
            creatorDeleted = raw.creatorDeleted,
            createdAt = raw.createdAt,
            updatedAt = raw.updatedAt,
            sourceUrl = raw.sourceUrl,
            lastSyncedAt = raw.lastSyncedAt,
        )
    }

    // The list/graph filter machinery — teamSelfMatch, teamPredicate, inheritedTeamMatches, the
    // `q` predicate, and the blueprint folded-lookup helpers — lives in `entities/EntityFilter.kt`
    // (the `catalog/CatalogFileFilter.kt` shape), called from [list]/[graph] below.

    private data class Materialized<T>(val payload: T, val context: EntityContext)

    /**
     * `q` substring-matches identifier OR title; an unknown `blueprint` identifier is empty
     * (never a 404/400). Rows, totals, definitions, targets, revision, and the empty-page
     * revision fallback materialize inside ONE REPEATABLE READ transaction; computed properties
     * evaluate over them AFTER it closes (choice 1, `.claude/docs/persistence.md`).
     * `team` matches the EFFECTIVE team (v1.30.0): [inheritedTeamMatches] resolves the matching
     * Inherited entity ids from the SAME committed read first, then folds them into the ONE
     * SQL [predicate] via [teamPredicate] — `count()` and the page rows stay one shared
     * predicate, so `total`/paging never disagree with the Inherited half of the match.
     */
    suspend fun list(
        filter: EntityFilter,
        paging: PageRequest,
        budget: OntologyReadBudget? = null,
    ): EntityListResult {
        val now = System.currentTimeMillis()
        readLedger.open().use { reservation ->
            try {
                val materialized = ontologyReadTransaction(database) {
                    val activeBlueprints = loadActiveBlueprints(budget)
                    val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
                    val blueprintsById = activeBlueprints.associateBy { it.id }
                    val definitionsByIdentifier = activeBlueprints.associate { it.identifier to it.definition }
                    // The list filter is the ONE case-insensitive lookup (blueprint identifiers are
                    // unique case-insensitively); every other identifier-keyed map here (targetExists)
                    // stays byte-exact.
                    val blueprintsByIdentifierFolded = foldedByIdentifier(activeBlueprints)
                    val filterBlueprint = resolveBlueprintFilter(filter.blueprint, blueprintsByIdentifierFolded)
                    val unknownBlueprint = filter.blueprint != null && filterBlueprint == null

                    var predicate: Op<Boolean> = active()
                    filterBlueprint?.let { predicate = predicate and (Entities.blueprintId eq it.id) }
                    filter.q?.let { q -> predicate = predicate and qPredicate(q) }
                    if (filter.team != null && !unknownBlueprint) {
                        val candidates = filterBlueprint?.let { listOf(it) } ?: activeBlueprints
                        val inheritedIds = inheritedTeamMatches(
                            filter.team, candidates, blueprintsByIdentifier, reservation.takeIf { budget != null },
                        )
                        predicate = predicate and teamPredicate(filter.team, blueprintsByIdentifierFolded, inheritedIds)
                    }

                    val total = if (unknownBlueprint) 0L else joined().selectAll().where { predicate }.count()
                    val (rows, revision) = entityPageRowsWithRevision(predicate, unknownBlueprint, paging, budget)
                    // REST bounds the page by row count; integration additionally caps decoded
                    // page documents with budget. The shared read
                    // budget's ledger charge here covers the TARGET rows a computed property might
                    // widen to, which is what can scale unboundedly across the workspace.
                    val definitions = rows.mapNotNull { blueprintsById[it.blueprintId]?.definition }
                    val targets = narrowTargets(definitions, definitionsByIdentifier, computed = true)
                    val readSet = loadReadSet(Op.FALSE, targets, blueprintsByIdentifier, reservation)
                    val context = EntityContext(blueprintsById, definitionsByIdentifier, readSet.snapshot, jq, now)
                    Materialized(Triple(rows, total, revision), context)
                }
                val (rows, total, revision) = materialized.payload
                return EntityListResult(rows.map { toResponse(it, materialized.context, budget) }, total, revision)
            } catch (cause: ReadBudgetExceeded) {
                throwReadBudgetHttp(cause)
            }
        }
    }

    suspend fun read(id: UInt, budget: OntologyReadBudget? = null): EntityResponse? {
        val now = System.currentTimeMillis()
        readLedger.open().use { reservation ->
            try {
                val materialized = suspendTransaction(database) {
                    val row = joined().selectAll().where { (Entities.id eq id) and active() }.singleOrNull()
                        ?: return@suspendTransaction null
                    val activeBlueprints = loadActiveBlueprints(budget)
                    val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
                    val blueprintsById = activeBlueprints.associateBy { it.id }
                    val definitionsByIdentifier = activeBlueprints.associate { it.identifier to it.definition }
                    val definition = blueprintsById[row[Entities.blueprintId].value]?.definition
                    val targets = narrowTargets(listOfNotNull(definition), definitionsByIdentifier, computed = true)
                    val readSet = loadReadSet(Op.FALSE, targets, blueprintsByIdentifier, reservation)
                    val context = EntityContext(blueprintsById, definitionsByIdentifier, readSet.snapshot, jq, now)
                    Materialized(row.toRawEntity(budget), context)
                } ?: return null
                return toResponse(materialized.payload, materialized.context, budget)
            } catch (cause: ReadBudgetExceeded) {
                throwReadBudgetHttp(cause)
            }
        }
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
        val documentBytesExpr = octetLength(Entities.document)
        val teamBytesExpr = octetLength(Entities.team)
        val rows = Entities.select(Entities.blueprintId, Entities.identifier, Entities.id, documentBytesExpr, teamBytesExpr)
            .where { active() }
            .map {
                ImportSnapshotRow(
                    it[Entities.blueprintId].value,
                    it[Entities.identifier],
                    it[Entities.id].value,
                    (it.getOrNull(documentBytesExpr) ?: 0L) + (it.getOrNull(teamBytesExpr) ?: 0L),
                )
            }
            .toList()
        val identifiersByBlueprint = rows.groupBy({ it.blueprintId }, { it.identifier to it.id })
        val countsByBlueprint = rows.groupingBy { it.blueprintId }.eachCount().mapValues { it.value.toLong() }
        val bytesById = rows.associate { it.id to it.bytes }
        EntityImportSnapshot(
            blueprints, identifiersByBlueprint, rows.size.toLong(), countsByBlueprint,
            documentBytes = rows.sumOf { it.bytes }, bytesById = bytesById,
        )
    }

    /** One [importSnapshot] row's identity plus its stored document+team byte size — never the text (`.claude/docs/persistence.md`). */
    private data class ImportSnapshotRow(val blueprintId: UInt, val identifier: String, val id: UInt, val bytes: Long)

    suspend fun create(
        request: EntityRequest,
        callerId: UInt,
        source: SourceWrite = SourceWrite.FromRequest,
    ): EntityResponse {
        validateEntityRequest(request) // re-checked service-side so direct callers stay guarded
        val now = System.currentTimeMillis()
        val materialized = writeTransaction {
            val activeBlueprints = loadActiveBlueprints()
            val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
            val blueprint = blueprintsByIdentifier[request.blueprint] ?: throw BadRequestException("Unknown blueprint")
            checkCaps(blueprint.id, documentByteSize(request).toLong())
            val document = request.toDocument()
            val definitionsByIdentifier =
                activeBlueprints.associate { it.identifier to it.definition } + (blueprint.identifier to blueprint.definition)
            // Writers are exempt from the read budget (`reservation = null`) — already serialized
            // by the V28 lock, at most one raw candidate set at a time (`.claude/docs/persistence.md`).
            val targets = narrowTargets(listOf(blueprint.definition), definitionsByIdentifier, computed = true)
            val readSet = loadReadSet(Op.FALSE, targets, blueprintsByIdentifier, reservation = null)
            val findings = entityFindings(document, blueprint.definition, readSet.snapshot.targetExists, request.team)
            requireNoFindings(findings)
            val id = insertRow(request, blueprint.id, document, callerId, now, source)
            bumpOntologyRevision() // V39, ch.nokillswit.infra.db.OntologyRevision — one bump per committed write
            val row = joined().selectAll().where { Entities.id eq id }.singleOrNull()
                ?: error("entity $id vanished between insert and read-back")
            val blueprintsById = activeBlueprints.associateBy { it.id } + (blueprint.id to blueprint)
            val context = EntityContext(blueprintsById, definitionsByIdentifier, readSet.snapshot, jq, now)
            Materialized(row.toRawEntity(), context)
        }
        return toResponse(materialized.payload, materialized.context)
    }

    private suspend fun checkCaps(blueprintId: UInt, incomingBytes: Long) {
        if (Entities.selectAll().where { active() }.count() >= MAX_ENTITIES_TOTAL) {
            throw BadRequestException("The entity registry is full ($MAX_ENTITIES_TOTAL entities)")
        }
        if (Entities.selectAll().where { (Entities.blueprintId eq blueprintId) and active() }.count() >= MAX_ENTITIES_PER_BLUEPRINT) {
            throw BadRequestException("This blueprint's entities are full ($MAX_ENTITIES_PER_BLUEPRINT entities)")
        }
        checkDocumentByteBudget(incomingBytes)
    }

    /**
     * The workspace-wide document+team byte budget (2.4.0, `.claude/docs/persistence.md` "Entity
     * targets under concurrency (V28)"): `SUM(octet_length(document)) + SUM(octet_length(team))`
     * over active rows, read under the SAME V28 lock [checkCaps]/[update] already hold, so the
     * count is never stale mid-write. [replacingBytes] is the row's OWN current contribution
     * (already loaded by the caller) — a replace/update only grows the workspace by the
     * DIFFERENCE, so a shrinking edit frees room for a later create.
     */
    private suspend fun checkDocumentByteBudget(incomingBytes: Long, replacingBytes: Long = 0L) {
        val documentSum = Sum(octetLength(Entities.document), LongColumnType())
        val teamSum = Sum(octetLength(Entities.team), LongColumnType())
        val row = Entities.select(documentSum, teamSum).where { active() }.singleOrNull()
            ?: error("an aggregate SELECT without GROUP BY must always answer exactly one row")
        val total = (row.getOrNull(documentSum) ?: 0L) + (row.getOrNull(teamSum) ?: 0L)
        if (total - replacingBytes + incomingBytes > workspaceDocumentBytes) {
            throw BadRequestException("The entity workspace is full ($workspaceDocumentBytes bytes of documents)")
        }
    }

    private fun requireNoFindings(findings: List<EntityFinding>) {
        if (findings.isNotEmpty()) {
            throw EntityInvalidException(findings)
        }
    }

    private suspend fun insertRow(
        request: EntityRequest,
        blueprintId: UInt,
        document: EntityDocument,
        callerId: UInt,
        now: Long,
        source: SourceWrite,
    ): UInt {
        val (sourceUrlValue, lastSyncedAtValue, syncedContentValue) = when (source) {
            SourceWrite.FromRequest -> Triple(request.sourceUrl, 0L, null)
            // Keep only ever reaches create via an import row with NO batch sourceUrl:
            // requireNoDocumentSourceUrl guarantees request.sourceUrl == null on every such path.
            SourceWrite.Keep -> Triple(null, 0L, null)
            is SourceWrite.Synced -> Triple(source.sourceUrl, now, baselineJson(request, document))
        }
        return Entities.insert {
            it[Entities.blueprintId] = blueprintId
            it[identifier] = request.identifier
            it[title] = request.title
            it[icon] = request.icon
            it[team] = request.team?.let { team -> blueprintJson.encodeToString(team) }
            it[Entities.document] = blueprintJson.encodeToString(document)
            it[createdBy] = callerId
            it[createdAt] = now
            it[updatedAt] = now
            it[Entities.sourceUrl] = sourceUrlValue
            it[Entities.lastSyncedAt] = lastSyncedAtValue
            it[Entities.syncedContent] = syncedContentValue
        }[Entities.id].value
    }

    /**
     * Row missing → affected 0 (the route's 404), decided BEFORE validation — the `LensService`/
     * `BlueprintService` order. `request.blueprint` must equal the stored row's blueprint
     * (400 otherwise: an entity never moves blueprints). An identifier rename cascades into
     * every OTHER active entity whose blueprint has a relation targeting THIS blueprint and
     * whose relation value names the old identifier, in this same locked transaction — plus,
     * when this entity's OWN blueprint is `_team`/`_user`, every OTHER active entity's `team`
     * column / `format: team|user` property values naming the old identifier (Phase 4).
     */
    suspend fun update(
        id: UInt,
        request: EntityRequest,
        source: SourceWrite = SourceWrite.FromRequest,
    ): EntityUpdateResult = writeTransaction {
        val row = Entities.selectAll().where { (Entities.id eq id) and active() }.singleOrNull()
            ?: return@writeTransaction EntityUpdateResult(0, emptyList(), null)
        applyUpdate(id, row, request, source)
    }

    /**
     * The shared body of [update] and `entities/EntitySync.kt`'s `syncFromSource`, once the row
     * is known to exist: validation, the blueprint-immutability rule, the self-reference rename
     * rewrite, findings, and the rename cascade — parameterized only by [source] (the
     * `sourceUrl`/sync-stamp write policy, `.claude/docs/persistence.md` "V37").
     */
    internal suspend fun applyUpdate(id: UInt, row: ResultRow, request: EntityRequest, source: SourceWrite): EntityUpdateResult {
        validateEntityRequest(request) // re-checked service-side so direct callers stay guarded
        val activeBlueprints = loadActiveBlueprints()
        val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
        val blueprintsById = activeBlueprints.associateBy { it.id }
        val currentBlueprint = blueprintsById[row[Entities.blueprintId].value]
            ?: error("entity $id references a blueprint that is not active")
        if (request.blueprint != currentBlueprint.identifier) {
            throw BadRequestException("blueprint must be '${currentBlueprint.identifier}' and cannot be changed")
        }
        val replacingBytes = row[Entities.document].toByteArray(Charsets.UTF_8).size.toLong() +
            (row[Entities.team]?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)
        var document = request.toDocument()
        var team = request.team
        val currentIdentifier = row[Entities.identifier]
        val renamed = currentIdentifier != request.identifier
        // A rename must also rewrite this SAME entity's own self-reference before validation:
        // the submitted document/team typically still names the OLD identifier (a client that
        // renamed the identifier field but forgot its own self-relation/team), and both the
        // synthetic self-match below and baseSnapshot's pre-rename read would otherwise let that
        // stale reference pass entityFindings and be stored unchanged.
        if (renamed) {
            document = withEntityTargetRenamed(
                document, currentBlueprint.definition, currentBlueprint.identifier, currentIdentifier, request.identifier,
            )
            systemFormatFor(currentBlueprint.identifier)?.let { format ->
                document = withFormatTargetRenamed(document, currentBlueprint.definition, format, currentIdentifier, request.identifier)
                if (format == "team") {
                    team = withTeamRenamed(team, currentIdentifier, request.identifier)
                }
            }
        }
        val now = System.currentTimeMillis()
        // Budgeted on the bytes that will actually be stored — AFTER the self-reference rewrite,
        // so a rename never drifts from what `octet_length` reports on the next write.
        checkDocumentByteBudget(documentByteSize(document, team).toLong(), replacingBytes)
        val definitionsByIdentifierForTargets = blueprintsByIdentifier.mapValues { it.value.definition }
        val baseTargets = narrowTargets(listOf(currentBlueprint.definition), definitionsByIdentifierForTargets, computed = false)
        val baseSnapshot = loadReadSet(Op.FALSE, baseTargets, blueprintsByIdentifier, reservation = null).snapshot
        // The SELECT backing baseSnapshot runs before THIS row's identifier rename is written,
        // so a self-blueprint relation naming the row's own NEW identifier would be wrongly
        // rejected; treat it as a synthetic self-match.
        val targetExists: TargetExists = { targetBlueprint, entityId ->
            (targetBlueprint == currentBlueprint.identifier && entityId == request.identifier) ||
                baseSnapshot.targetExists(targetBlueprint, entityId)
        }
        requireNoFindings(entityFindings(document, currentBlueprint.definition, targetExists, team))

        val finalRequest = request.copy(team = team, properties = document.properties, relations = document.relations)
        val affected = replaceRow(id, row, finalRequest, source, now)
        if (affected == 0) return EntityUpdateResult(0, emptyList(), null)
        // V39 — a PUT always bumps, even a byte-identical resubmission (updatedAt already does
        // the same unconditionally, `.claude/docs/persistence.md`'s "D2" rule); shared by
        // `update` and `entities/EntitySync.kt`'s `syncFromSource`, which both call this.
        bumpOntologyRevision()

        val cascaded = if (renamed) {
            cascadeRename(activeBlueprints, currentBlueprint.identifier, currentIdentifier, request.identifier, excludingId = id)
        } else {
            emptyList()
        }
        return EntityUpdateResult(affected, cascaded, if (renamed) currentIdentifier else null)
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
        excludingId: UInt,
    ): List<String> {
        val format = systemFormatFor(blueprintIdentifier)
        val candidateBlueprintIds =
            if (format != null) activeBlueprints.map { it.id } else referrerBlueprintIds(activeBlueprints, blueprintIdentifier)
        if (candidateBlueprintIds.isEmpty()) return emptyList()
        val blueprintsById = activeBlueprints.associateBy { it.id }
        val now = System.currentTimeMillis()
        val cascaded = mutableListOf<String>()
        // The renamed row itself is never a cascade candidate: its own self-references are rewritten
        // in [update] before validation, and counting it here would inflate the audited `cascaded`.
        candidateEntities(candidateBlueprintIds).filter { it[Entities.id].value != excludingId }.forEach { candidate ->
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
            throw EntityReferencedException(target, referrers)
        }
        val affected = Entities.update({ (Entities.id eq id) and active() }) { it[markedAsDeleted] = true }
        if (affected > 0) bumpOntologyRevision() // V39
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
