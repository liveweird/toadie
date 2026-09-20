package ch.nokillswit.blueprints

import ch.nokillswit.entities.OntologyReadBudget
import ch.nokillswit.entities.decodeForRead
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.dictionaries.Dictionary
import ch.nokillswit.dictionaries.DictionaryService
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.entities.EntityService
import ch.nokillswit.infra.db.bumpOntologyRevision
import ch.nokillswit.infra.db.currentOntologyRevision
import ch.nokillswit.infra.db.lockingTransaction
import ch.nokillswit.infra.db.ontologyRevisionExpression
import ch.nokillswit.infra.fetch.MAX_FETCH_URL_LENGTH
import ch.nokillswit.infra.fetch.SourceWrite
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/** The V27 lock this service's every mutation runs under (`.claude/docs/persistence.md`). */
private const val LOCK_BLUEPRINTS_SHARE_ROW_EXCLUSIVE = "LOCK TABLE blueprints IN SHARE ROW EXCLUSIVE MODE"

val BlueprintServiceKey = AttributeKey<BlueprintService>("BlueprintService")

/** [BlueprintService.update]'s outcome: the affected-row count plus what the rename cascaded (for the audit). */
data class BlueprintUpdateResult(val affected: Int, val cascaded: List<String>, val renamedFrom: String?, val system: Boolean)

/** [BlueprintService.delete]'s outcome: the affected-row count plus the deleted identifier (for the audit). */
data class BlueprintDeleteResult(val affected: Int, val identifier: String?)

/** The `blueprints.hierarchy_relations` column's JSON-object-in-TEXT codec (V34). */
private fun decodeHierarchyRelations(raw: String): Map<String, String> = blueprintJson.decodeFromString(raw)

/**
 * Widened to `internal` (from `private`) so `blueprints/BlueprintSync.kt`'s `replaceRow` can
 * reuse it (the same column, one write path down).
 */
internal fun encodeHierarchyRelations(hierarchyRelations: Map<String, String>?): String =
    blueprintJson.encodeToString(hierarchyRelations ?: emptyMap())

/** [BlueprintService.listPage]'s outcome — [revision] is the V39 ontology counter as of the SAME statement as [items]. */
data class BlueprintPageResult(val items: List<BlueprintResponse>, val total: Long, val revision: Long)

class BlueprintService(internal val database: R2dbcDatabase) {
    object Blueprints : UIntIdTable("blueprints") {
        // Case-folded identifier uniqueness is enforced by the partial unique index
        // uq_blueprints_identifier_active (active rows only; V27), so a soft-deleted
        // blueprint frees its identifier. Exposed table defs are query-only (not DDL).
        val identifier = varchar("identifier", length = MAX_BLUEPRINT_IDENTIFIER_LENGTH)
        val title = varchar("title", length = MAX_BLUEPRINT_TITLE_LENGTH)
        val description = varchar("description", length = MAX_BLUEPRINT_DESCRIPTION_LENGTH).nullable()
        val icon = varchar("icon", length = MAX_BLUEPRINT_ICON_LENGTH).nullable()
        // Everything else (schema/relations/mirror/calculation/aggregation/ownership) as one
        // JSON document in TEXT (the catalog_files.content/lenses.filters precedent).
        val definition = text("definition")
        // Port migration phase 3 (V29), widened to a MAP in V34: a Toadie-only view extension
        // stored BESIDE the Port document, never inside it — so `definition`/`toDefinition()`
        // stay byte-identical. One JSON object in TEXT (the `definition` column's own idiom),
        // hierarchy identifier -> relation key; "{}" when unset.
        val hierarchyRelations = text("hierarchy_relations").default("{}")
        // Phase 4 (V31): `_team`/`_user`, seeded by the migration — never set by application
        // code (see blueprints/SystemBlueprints.kt for the protections this flag gates).
        val isSystem = bool("is_system").default(false)
        val createdBy = reference("created_by", UserService.Users)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
        // Source references & HTTP re-sync (2.10.0, V38 — the `entities` envelope, one level
        // down): the https URL of the blueprint's canonical remote copy; NULL = none set.
        val sourceUrl = varchar("source_url", length = MAX_FETCH_URL_LENGTH).nullable()
        // Epoch millis of the last HTTP->DB sync; 0 = never (the entities.last_synced_at idiom).
        val lastSyncedAt = long("last_synced_at").default(0)
        // The request-shaped document snapshot taken at sync time — the sync modal's baseline. NULL = never synced.
        val syncedContent = text("synced_content").nullable()
    }

    /**
     * Serializes the cross-row target-existence/rename-cascade/delete-409 invariants across
     * every application instance sharing this database — the tag-category table-lock idiom
     * (`.claude/docs/persistence.md`, "cooperating writer protocol"), via the shared
     * [lockingTransaction] helper (`infra/db/Locking.kt`). Widened to `internal` (from
     * `private`) so `blueprints/BlueprintSync.kt` (the `entities/EntitySync.kt` extension-file
     * idiom, kept out of this class purely to stay under detekt's `LargeClass` threshold) can
     * reach it.
     */
    internal suspend fun <T> writeTransaction(block: suspend R2dbcTransaction.() -> T): T =
        lockingTransaction(database, LOCK_BLUEPRINTS_SHARE_ROW_EXCLUSIVE, block = block)

    internal fun active(): Op<Boolean> = Blueprints.markedAsDeleted eq false

    // The sanctioned cross-feature table read (persistence.md): the creator's display fields
    // must come from the same transaction as the blueprint row, so the users table is joined
    // directly instead of calling UserService (which would open a second transaction).
    private fun joined() = Blueprints.join(
        UserService.Users,
        JoinType.INNER,
        onColumn = Blueprints.createdBy,
        otherColumn = UserService.Users.id,
    )

    private fun ResultRow.toResponse(
        definition: BlueprintDefinition = blueprintJson.decodeFromString(this[Blueprints.definition]),
    ): BlueprintResponse {
        return BlueprintResponse(
            id = this[Blueprints.id].value,
            identifier = this[Blueprints.identifier],
            title = this[Blueprints.title],
            description = this[Blueprints.description],
            icon = this[Blueprints.icon],
            schema = definition.schema,
            relations = definition.relations,
            mirrorProperties = definition.mirrorProperties,
            calculationProperties = definition.calculationProperties,
            aggregationProperties = definition.aggregationProperties,
            ownership = definition.ownership,
            hierarchyRelations = decodeHierarchyRelations(this[Blueprints.hierarchyRelations]).ifEmpty { null },
            createdBy = this[Blueprints.createdBy].value,
            creatorName = this[UserService.Users.name],
            creatorDeleted = this[UserService.Users.markedAsDeleted],
            createdAt = this[Blueprints.createdAt],
            updatedAt = this[Blueprints.updatedAt],
            system = this[Blueprints.isSystem],
            sourceUrl = this[Blueprints.sourceUrl],
            lastSyncedAt = this[Blueprints.lastSyncedAt],
        )
    }

    /** Every active blueprint, identifier-ordered case-insensitively (id as tiebreaker). */
    suspend fun list(): List<BlueprintResponse> = suspendTransaction(database) {
        joined().selectAll()
            .where { active() }
            .orderBy(Blueprints.identifier.lowerCase() to SortOrder.ASC, Blueprints.id to SortOrder.ASC)
            .map { it.toResponse() }
            .toList()
    }

    /**
     * SQL-paged integration read: decode only this page, never the complete registry. [ontologyRevisionExpression]
     * rides the SAME row-select statement as [items] (`.claude/docs/persistence.md` "V39") so the two can never
     * straddle a concurrent commit; a page with zero rows has no row to read it off and falls back to
     * [currentOntologyRevision] — a direct read, since there is nothing for it to be inconsistent WITH.
     */
    suspend fun listPage(paging: PageRequest): BlueprintPageResult = suspendTransaction(database) {
        val total = Blueprints.selectAll().where { active() }.count()
        val budget = OntologyReadBudget()
        val revisionColumn = ontologyRevisionExpression()
        val rows = joined().select(joined().columns + revisionColumn).where { active() }
            .applyPaging(paging, mapOf("id" to Blueprints.id))
            .toList()
        val items = rows.map { row -> row.toResponse(decodeForRead(row[Blueprints.definition], budget)) }
        val revision = rows.firstOrNull()?.get(revisionColumn) ?: currentOntologyRevision()
        BlueprintPageResult(items, total, revision)
    }

    suspend fun read(id: UInt, budget: OntologyReadBudget? = null): BlueprintResponse? = suspendTransaction(database) {
        joined().selectAll().where { (Blueprints.id eq id) and active() }
            .map { it.toResponse(decodeForRead(it[Blueprints.definition], budget)) }.singleOrNull()
    }

    /**
     * One row's id, identifier, decoded definition, hierarchy relations map, and source-reference
     * columns — the snapshot every mutation loads once. Widened to `internal` (from `private`) so
     * `blueprints/BlueprintSync.kt` can read `current.sourceUrl`/`lastSyncedAt`/`syncedContent`
     * when deciding the [SourceWrite.FromRequest] reset rule (the `entities/EntitySync.kt`
     * `replaceRow` precedent, one level down).
     */
    internal data class ActiveRow(
        val id: UInt,
        val identifier: String,
        val definition: BlueprintDefinition,
        val hierarchyRelations: Map<String, String>,
        val isSystem: Boolean,
        val sourceUrl: String?,
        val lastSyncedAt: Long,
        val syncedContent: String?,
    )

    internal suspend fun activeRows(): List<ActiveRow> = Blueprints.selectAll().where { active() }
        .map {
            ActiveRow(
                it[Blueprints.id].value,
                it[Blueprints.identifier],
                blueprintJson.decodeFromString<BlueprintDefinition>(it[Blueprints.definition]),
                decodeHierarchyRelations(it[Blueprints.hierarchyRelations]),
                it[Blueprints.isSystem],
                it[Blueprints.sourceUrl],
                it[Blueprints.lastSyncedAt],
                it[Blueprints.syncedContent],
            )
        }
        .toList()

    private fun requireTargetsExist(definition: BlueprintDefinition, self: String, known: Set<String>) {
        val unknown = blueprintTargets(definition).filterNot { it == self || it in known }
        if (unknown.isNotEmpty()) {
            throw BadRequestException("Unknown relation/aggregation target(s): ${unknown.joinToString()}")
        }
    }

    /**
     * Every `hierarchyRelations` KEY must be an ACTIVE `hierarchies` dictionary value
     * ([Dictionary.HIERARCHY]) — a sanctioned cross-feature table read of
     * [DictionaryService.Entries] (`.claude/docs/persistence.md`), run inside the SAME
     * [writeTransaction] as the rest of create/update so a concurrent dictionary replace that
     * removes a value in flight is serialized against this check the instant it, too, takes the
     * `blueprints` lock (a follow-up — today the dictionary write takes no such lock, so this is
     * a plain committed read, the same posture as [requireTargetsExist]'s `known` snapshot).
     */
    private fun hierarchyDictionaryPredicate(): Op<Boolean> =
        (DictionaryService.Entries.dictionary eq Dictionary.HIERARCHY.name) and (DictionaryService.Entries.markedAsDeleted eq false)

    private suspend fun requireKnownHierarchies(hierarchyRelations: Map<String, String>?) {
        if (hierarchyRelations.isNullOrEmpty()) return
        val known = DictionaryService.Entries
            .selectAll()
            .where { hierarchyDictionaryPredicate() }
            .map { it[DictionaryService.Entries.value] }
            .toList()
            .toSet()
        hierarchyRelations.keys.firstOrNull { it !in known }?.let {
            throw BadRequestException("hierarchyRelations names an unknown hierarchy '$it'")
        }
    }

    /** Active `hierarchies` dictionary values — a plain, lock-free read like [list] (the import planner's snapshot). */
    suspend fun knownHierarchies(): Set<String> = suspendTransaction(database) {
        DictionaryService.Entries
            .selectAll()
            .where { hierarchyDictionaryPredicate() }
            .map { it[DictionaryService.Entries.value] }
            .toList()
            .toSet()
    }

    suspend fun create(
        request: BlueprintRequest,
        callerId: UInt,
        source: SourceWrite = SourceWrite.FromRequest,
    ): BlueprintResponse {
        validateBlueprintRequest(request) // re-checked service-side so direct callers stay guarded
        return writeTransaction {
            // Identifiers starting with `_` are reserved for Port's own system blueprints
            // (V31's `_team`/`_user`); a new one can never be created through this route.
            if (isSystemIdentifier(request.identifier)) {
                throw BadRequestException("Identifier '${request.identifier}' is reserved for system blueprints")
            }
            if (Blueprints.selectAll().where { active() }.count() >= MAX_BLUEPRINTS) {
                throw BadRequestException("The blueprint registry is full ($MAX_BLUEPRINTS blueprints)")
            }
            requireKnownHierarchies(request.hierarchyRelations)
            val known = activeRows().map { it.identifier }.toSet()
            val definition = request.toDefinition()
            requireTargetsExist(definition, self = request.identifier, known = known)
            val now = System.currentTimeMillis()
            val id = insertRow(request, definition, callerId, now, source)
            bumpOntologyRevision() // V39, ch.nokillswit.infra.db.OntologyRevision — one bump per committed write
            joined().selectAll().where { Blueprints.id eq id }.map { it.toResponse() }.singleOrNull()
                ?: error("blueprint $id vanished between insert and read-back")
        }
    }

    private suspend fun insertRow(
        request: BlueprintRequest,
        definition: BlueprintDefinition,
        callerId: UInt,
        now: Long,
        source: SourceWrite,
    ): UInt {
        val (sourceUrlValue, lastSyncedAtValue, syncedContentValue) = when (source) {
            SourceWrite.FromRequest -> Triple(request.sourceUrl, 0L, null)
            // Keep only ever reaches create via an import row with NO batch sourceUrl:
            // requireNoDocumentSourceUrl guarantees request.sourceUrl == null on every such path.
            SourceWrite.Keep -> Triple(null, 0L, null)
            is SourceWrite.Synced -> Triple(source.sourceUrl, now, baselineJson(request, definition))
        }
        return Blueprints.insert {
            it[identifier] = request.identifier
            it[title] = request.title
            it[description] = request.description
            it[icon] = request.icon
            it[Blueprints.definition] = blueprintJson.encodeToString(definition)
            it[hierarchyRelations] = encodeHierarchyRelations(request.hierarchyRelations)
            it[createdBy] = callerId
            it[createdAt] = now
            it[updatedAt] = now
            it[Blueprints.sourceUrl] = sourceUrlValue
            it[Blueprints.lastSyncedAt] = lastSyncedAtValue
            it[Blueprints.syncedContent] = syncedContentValue
        }[Blueprints.id].value
    }

    /**
     * Row missing → affected 0 (the route's 404), decided BEFORE validation — the LensService
     * order, needed here because the target/rename logic itself depends on the current row.
     * Targets are checked against the OTHER rows' identifiers plus the NEW identifier (a
     * self-relation survives a rename: when the identifier changes, the SUBMITTED definition's
     * own self-targets are rewritten too, before the existence check and the store — so a
     * caller need not update its own self-reference text to match a rename). Renaming
     * (byte-exact identifier change) cascades the same rewrite onto every OTHER active row's
     * targets in this same locked transaction.
     */
    suspend fun update(
        id: UInt,
        request: BlueprintRequest,
        source: SourceWrite = SourceWrite.FromRequest,
    ): BlueprintUpdateResult = writeTransaction {
        val rows = activeRows()
        val current = rows.firstOrNull { it.id == id }
            ?: return@writeTransaction BlueprintUpdateResult(0, emptyList(), null, false)
        applyUpdate(id, rows, current, request, source)
    }

    /**
     * The shared body of [update] and `blueprints/BlueprintSync.kt`'s `syncFromSource`, once the
     * row is known to exist: validation, the system-extension rule, the self-reference rename
     * rewrite, the target-existence check, and the rename cascade — parameterized only by
     * [source] (the `sourceUrl`/sync-stamp write policy, `.claude/docs/persistence.md` "V38").
     * [rows] is the FULL active-row snapshot the caller already loaded (needed for the rename
     * cascade's `others`); [current] is [id]'s own row within it.
     */
    internal suspend fun applyUpdate(
        id: UInt,
        rows: List<ActiveRow>,
        current: ActiveRow,
        request: BlueprintRequest,
        source: SourceWrite,
    ): BlueprintUpdateResult {
        validateBlueprintRequest(request) // re-checked service-side so direct callers stay guarded
        if (current.isSystem) {
            // No rename, no removal/retyping of the base shape — the rename cascade below
            // never runs for a system row because validateSystemExtension already rejected
            // an identifier change.
            validateSystemExtension(current.identifier, request)
        }
        requireKnownHierarchies(request.hierarchyRelations)
        val others = rows.filterNot { it.id == id }
        val renamed = current.identifier != request.identifier
        val definition = request.toDefinition().let {
            if (renamed) withTargetRenamed(it, current.identifier, request.identifier) else it
        }
        requireTargetsExist(definition, self = request.identifier, known = others.map { it.identifier }.toSet())

        val now = System.currentTimeMillis()
        val affected = replaceRow(id, current, request, definition, source, now)
        if (affected == 0) return BlueprintUpdateResult(0, emptyList(), null, current.isSystem)
        // V39 — a PUT always bumps, even a byte-identical resubmission (updatedAt already does
        // the same unconditionally, `.claude/docs/persistence.md`'s "D2" rule); shared by
        // `update` and `blueprints/BlueprintSync.kt`'s `syncFromSource`, which both call this.
        bumpOntologyRevision()

        val cascaded = if (renamed) cascadeRename(others, current.identifier, request.identifier) else emptyList()
        return BlueprintUpdateResult(affected, cascaded, if (renamed) current.identifier else null, current.isSystem)
    }

    /** Rewrites every OTHER active row targeting [oldIdentifier], returning the rewritten identifiers. */
    private suspend fun cascadeRename(others: List<ActiveRow>, oldIdentifier: String, newIdentifier: String): List<String> {
        val now = System.currentTimeMillis()
        val affected = others.filter { oldIdentifier in blueprintTargets(it.definition) }
        affected.forEach { row ->
            val rewritten = withTargetRenamed(row.definition, oldIdentifier, newIdentifier)
            Blueprints.update({ Blueprints.id eq row.id }) {
                it[definition] = blueprintJson.encodeToString(rewritten)
                it[updatedAt] = now
            }
        }
        return affected.map { it.identifier }
    }

    /**
     * Dependents = other active rows targeting this identifier; a self-relation never blocks.
     * A blueprint with active ENTITIES (`entities/EntityService.kt` — a sanctioned cross-feature
     * table read, held under this same SHARE ROW EXCLUSIVE lock so no entity write races it) is
     * 409 before the referrer check: deleting it out from under live instances would strand
     * every one of them without a definition to validate against.
     */
    suspend fun delete(id: UInt): BlueprintDeleteResult = writeTransaction {
        val rows = activeRows()
        val current = rows.firstOrNull { it.id == id } ?: return@writeTransaction BlueprintDeleteResult(0, null)
        if (current.isSystem) {
            throw ConflictException("Blueprint '${current.identifier}' is a system blueprint and cannot be deleted")
        }
        val activeEntities = EntityService.Entities
            .selectAll()
            .where { (EntityService.Entities.blueprintId eq id) and (EntityService.Entities.markedAsDeleted eq false) }
            .count()
        if (activeEntities > 0) {
            throw ConflictException("Blueprint '${current.identifier}' has $activeEntities active entities")
        }
        val dependents = rows.filterNot { it.id == id }.filter { current.identifier in blueprintTargets(it.definition) }
        if (dependents.isNotEmpty()) {
            throw ConflictException(
                "Blueprint '${current.identifier}' is the target of relations in: ${dependents.joinToString { it.identifier }}",
            )
        }
        val affected = Blueprints.update({ (Blueprints.id eq id) and active() }) { it[markedAsDeleted] = true }
        if (affected > 0) bumpOntologyRevision() // V39
        BlueprintDeleteResult(affected, current.identifier)
    }
}
