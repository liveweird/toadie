package ch.nokillswit.catalog

import ch.nokillswit.infra.concurrency.ReadBudgetExceeded
import ch.nokillswit.infra.concurrency.ReadLedger
import ch.nokillswit.infra.concurrency.throwReadBudgetHttp
import ch.nokillswit.infra.db.jsonTextAt
import ch.nokillswit.infra.db.octetLength
import ch.nokillswit.infra.fetch.MAX_FETCH_URL_LENGTH
import ch.nokillswit.infra.fetch.SourceWrite
import ch.nokillswit.infra.fetch.sanitizedSourceUrl
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val CatalogFileServiceKey = AttributeKey<CatalogFileService>("CatalogFileService")

/** The identity-clash row message, shared by the real import and its dry-run — kept
 *  byte-identical to the central 23505 mapping's detail (UNIQUE_CONSTRAINT_DETAILS in
 *  plugins/ErrorHandling.kt), so the same clash reads the same everywhere. */
internal const val IMPORT_CONFLICT_MESSAGE =
    "A catalog file with this kind, namespace, and name already exists"

data class CatalogFileListResult(
    val items: List<CatalogFileListItem>,
    val total: Long,
)

/** A stored file with its envelope (creator resolved via join, timestamps, sync state). */
data class CatalogFileDetail(
    val id: UInt,
    val revision: Long,
    val file: CatalogFile,
    val createdBy: UInt,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceUrl: String?,
    val lastSyncedAt: Long,
) {
    fun toResponse() = CatalogFileResponse(
        id = id,
        revision = revision,
        kind = file.kind,
        metadata = file.metadata,
        spec = file.spec,
        createdBy = createdBy,
        creatorName = creatorName,
        creatorDeleted = creatorDeleted,
        createdAt = createdAt,
        updatedAt = updatedAt,
        sourceUrl = sourceUrl,
        lastSyncedAt = lastSyncedAt,
    )
}

/** GET {id}/sync's read model: the reference + stamp + the baseline stored at the last sync. */
data class CatalogFileSyncState(
    val revision: Long,
    val sourceUrl: String?,
    val lastSyncedAt: Long,
    val syncedDocument: CatalogFile?,
) {
    fun toResponse() = SyncStateResponse(
        revision = revision,
        sourceUrl = sourceUrl,
        lastSyncedAt = lastSyncedAt,
        syncedDocument = syncedDocument,
    )
}

/** The dry-run's verdict on a `replaceExisting` row: would the ordinary replace change anything? */
internal enum class ReplacePrediction { CHANGED, UNCHANGED }

/** A successful create plus the soft findings the save waived (empty on a strict save). */
data class CatalogFileSaveResult(val id: UInt, val waived: List<SoftFinding>)

/**
 * A successful update (rows=0 → the route's 404) plus the waived soft findings and the
 * field-level [changes] the write made. The diff and its history event are produced in the
 * same transaction as the row mutation.
 */
data class CatalogFileUpdateResult(
    val rows: Int,
    val waived: List<SoftFinding>,
    val changes: List<FieldChange> = emptyList(),
)

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to CatalogFileService.CatalogFiles.id,
    "kind" to CatalogFileService.CatalogFiles.kind,
    "name" to CatalogFileService.CatalogFiles.name,
    "namespace" to CatalogFileService.CatalogFiles.namespace,
    "updatedAt" to CatalogFileService.CatalogFiles.updatedAt,
    // 0 = never synced, so ascending order surfaces the most-stale files first.
    "lastSyncedAt" to CatalogFileService.CatalogFiles.lastSyncedAt,
)

/** The ONE sortable whitelist — the route's `parsePaging` argument derives from the column map
 *  above, so the two can never drift apart (a mismatch used to be a runtime 500). */
val CATALOG_FILE_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The admission weight of a whole-workspace catalog read (2.19.0): `SUM(octet_length(content))`
 * times this is reserved against the process-wide [ReadLedger] before any document is decoded —
 * the raw string held while it is parsed, the typed document, and the graph/errors projection
 * built from it. Measured, not calibrated like the entity ratios (`entities/EntityReadBudget.kt`):
 * the 2026-10-25 capacity probe (`npm run probe:capacity`, 10,009 files, up to 20 concurrent
 * graph/errors reads) admitted three at a time with no `500` and no heap exhaustion.
 */
const val CATALOG_READ_FOOTPRINT_RATIO = 3L

/**
 * [CATALOG_READ_FOOTPRINT_RATIO] for [CatalogFileService.export]: its response IS the whole
 * workspace, re-encoded after the reservation closes (the response-encoding residual in
 * `.claude/docs/security.md` "Entity read memory budget"), so it is weighted for that copy too. The
 * same probe at ×3 let three exports in and one died with `OutOfMemoryError` (a `500`).
 */
const val CATALOG_EXPORT_FOOTPRINT_RATIO = 6L

/** Above this many wanted identities the export-merge read stops narrowing by name (kind and namespace still do). */
const val IDENTITY_NAME_NARROWING_LIMIT = 1000

class CatalogFileService(
    private val database: R2dbcDatabase,
    private val eventService: CatalogFileEventService = CatalogFileEventService(database),
    /**
     * The process-wide read ledger the whole-workspace reads (`errors`/`graph`/`export`) are admitted
     * against — REQUIRED so the composition root passes the one shared instance (a test seam).
     */
    private val readLedger: ReadLedger,
) {
    object CatalogFiles : UIntIdTable("catalog_files") {
        val kind = varchar("kind", length = 63).default("Component")
        // Identity uniqueness (case-insensitive per kind+namespace, active rows only) is
        // enforced by the partial unique index uq_catalog_files_entity_active in V5 — a clash
        // surfaces as 23505 → the central 409 mapping. Exposed defs are query-only.
        val name = varchar("name", length = 63)
        val namespace = varchar("namespace", length = 63).default(DEFAULT_NAMESPACE)
        // The full structured document as JSON (kotlinx) — identity columns above are
        // denormalized from it for SQL filtering/sorting.
        val content = text("content")
        val createdBy = reference("created_by", UserService.Users)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        // Monotonic per-file token for optional guarded mutations (V40). Every state-changing
        // write advances it; a no-op replacement retains it.
        val revision = long("revision").default(1)
        // The source reference & sync state (V21) — envelope columns, never part of `content`
        // (the stored document stays a pure Backstage document). 0 = never synced; a sync
        // stamps updated_at and last_synced_at EQUAL, so — while last_synced_at > 0 —
        // updated_at > last_synced_at means "modified in the DB since the sync" (a
        // changed/cleared reference resets last_synced_at to 0, the never-synced state, where
        // the predicate carries no drift meaning); synced_content is the baseline snapshot.
        val sourceUrl = varchar("source_url", length = MAX_FETCH_URL_LENGTH).nullable()
        val lastSyncedAt = long("last_synced_at").default(0)
        val syncedContent = text("synced_content").nullable()
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private val json = Json
    private val registryReader = CatalogRegistryReader(json)

    private fun active(): Op<Boolean> = CatalogFiles.markedAsDeleted eq false

    // The repo's first sanctioned cross-feature table read (see persistence.md): the creator's
    // display fields must come from the same transaction as the file rows, so the users table
    // is joined directly instead of calling UserService (which would open a second transaction).
    private fun joined() = CatalogFiles.join(
        UserService.Users,
        JoinType.INNER,
        onColumn = CatalogFiles.createdBy,
        otherColumn = UserService.Users.id,
    )

    private fun CatalogFile.withNamespace(resolved: String): CatalogFile =
        if (metadata.namespace == resolved) this else copy(metadata = metadata.copy(namespace = resolved))

    /**
     * The write path's SOFT checks, as findings: every entity reference resolved against the
     * active workspace (the ONE rulebook in Errors.kt — per-field default kinds, allowed
     * target kinds, contextual namespace, never the document ITSELF) plus the registry checks
     * against the registry reader's snapshot. Runs inside the write's own transaction. A strict save
     * rejects any finding with ONE aggregated 400; `allowInvalid=true` waives them all and
     * stores anyway (the Errors report is the net for what was waived).
     * [extraIdentities] is the import path's batch universe (sibling documents resolve
     * order-independently). A rename's "self" is the NEW identity — uniform across create,
     * update, import, and the ad-hoc check.
     */
    private suspend fun softFindings(
        stored: CatalogFile,
        extraIdentities: Set<EntityIdentity>,
        excludingId: UInt? = null,
    ): List<SoftFinding> {
        val references = checkDocument(stored, activeIdentities(excludingId) + extraIdentities).findings
            .map { SoftFinding(it, referenceFindingMessage(it)) }
        return references + registryFindings(stored, registryReader.loadSnapshot())
    }

    /**
     * Resolves a sanitized file's namespace outside a write (the registry reader's semantics,
     * own transaction) — the import path uses it so each result row reports the CONCRETE
     * namespace a blank one resolved to.
     */
    suspend fun resolveNamespace(file: CatalogFile): CatalogFile = suspendTransaction(database) {
        file.withNamespace(registryReader.resolveNamespace(file.metadata.namespace))
    }

    suspend fun create(
        file: CatalogFile,
        createdByUserId: UInt,
        extraIdentities: Set<EntityIdentity> = emptySet(),
        allowInvalid: Boolean = false,
        sourceUrl: String? = null,
        // Import-from-URL sets this: the content CAME from the repo copy, so the row starts
        // synced (stamp + baseline). An editor-typed reference does not — content and repo
        // were never compared, so the row reads "never synced".
        markSynced: Boolean = false,
        viaImport: Boolean = false,
    ): CatalogFileSaveResult = suspendTransaction(database) {
        validateCatalogFile(file) // re-checked service-side so direct callers stay guarded
        val source = sanitizedSourceUrl(sourceUrl) // re-checked service-side too
        // The stored row AND the content JSON both carry the resolved concrete namespace.
        val stored = file.withNamespace(registryReader.resolveNamespace(file.metadata.namespace))
        val findings = requireOrWaive(stored, extraIdentities, allowInvalid)
        val now = System.currentTimeMillis()
        val encoded = json.encodeToString(stored)
        val synced = markSynced && source != null
        val newRecord = CatalogFiles.insert {
            it[kind] = stored.kind
            it[name] = stored.metadata.name
            it[namespace] = stored.metadata.namespace
            it[content] = encoded
            it[createdBy] = createdByUserId
            it[createdAt] = now
            it[updatedAt] = now
            it[CatalogFiles.sourceUrl] = source
            it[lastSyncedAt] = if (synced) now else 0
            it[syncedContent] = if (synced) encoded else null
        }
        val id = newRecord[CatalogFiles.id].value
        eventService.recordInTransaction(
            id,
            createdByUserId,
            catalogFileCreationEvent(stored.kind, viaImport = viaImport),
        )
        CatalogFileSaveResult(id = id, waived = findings)
    }

    /** The strict-or-waive gate shared by create and update: throws unless waived, returns what was. */
    private suspend fun requireOrWaive(
        stored: CatalogFile,
        extraIdentities: Set<EntityIdentity>,
        allowInvalid: Boolean,
        excludingId: UInt? = null,
    ): List<SoftFinding> {
        val findings = softFindings(stored, extraIdentities, excludingId)
        if (findings.isNotEmpty() && !allowInvalid) {
            throw CatalogFileInvalidException(
                findings = findings.map { it.finding },
                detail = findings.joinToString("; ") { it.message },
            )
        }
        return findings
    }

    suspend fun read(id: UInt): CatalogFileDetail? = suspendTransaction(database) {
        joined().selectAll()
            .where { (CatalogFiles.id eq id) and active() }
            .map { it.toDetail() }
            .singleOrNull()
    }

    /**
     * The ordinary replace. [sourceWrite] picks the source-reference posture: [SourceWrite.FromRequest]
     * (the PUT — [sourceUrl] replaces the stored one, resetting the sync state when it differs),
     * or, for the import's `replaceExisting`, [SourceWrite.Keep] (a batch without a URL leaves
     * the reference and sync state alone — the ontology imports' D3 rule: a no-op then writes
     * nothing at all) / [SourceWrite.Synced] (a batch with a URL IS a repo sync, so the write
     * follows [syncFromRepo] exactly: `updated_at`, `last_synced_at` and the baseline stamped
     * together, the revision bumped, and a history event ALWAYS recorded — the field-diff UPDATED
     * event when something changed, else the empty SYNCED event a matching sync records).
     * [extraIdentities] is the import path's batch universe.
     */
    suspend fun update(
        id: UInt,
        file: CatalogFile,
        actingUserId: UInt,
        expectedRevision: Long? = null,
        allowInvalid: Boolean = false,
        sourceUrl: String? = null,
        extraIdentities: Set<EntityIdentity> = emptySet(),
        sourceWrite: SourceWrite = SourceWrite.FromRequest,
    ): CatalogFileUpdateResult =
        suspendTransaction(database) {
            validateCatalogFile(file) // re-checked service-side so direct callers stay guarded
            val requestedSource = when (sourceWrite) { // re-checked service-side too
                SourceWrite.FromRequest -> sanitizedSourceUrl(sourceUrl)
                SourceWrite.Keep -> null // resolved from the current row below
                is SourceWrite.Synced -> sanitizedSourceUrl(sourceWrite.sourceUrl)
            }
            val stored = file.withNamespace(registryReader.resolveNamespace(file.metadata.namespace))
            val findings = requireOrWaive(stored, extraIdentities, allowInvalid, excludingId = id)
            val encoded = json.encodeToString(stored)
            // The current row decides the sync-state consequences: a changed/cleared reference
            // resets the sync state (a different source was never synced from), and updatedAt
            // bumps ONLY on a content change — a reference-only edit must not read as
            // "modified in the DB since the sync" (updatedAt > lastSyncedAt is that signal).
            val current = CatalogFiles.select(CatalogFiles.content, CatalogFiles.sourceUrl, CatalogFiles.revision)
                .where { (CatalogFiles.id eq id) and active() }
                .forUpdate()
                .toList()
                .singleOrNull()
                ?: return@suspendTransaction CatalogFileUpdateResult(rows = 0, waived = findings)
            requireExpectedRevision(expectedRevision, current[CatalogFiles.revision])
            val source = if (sourceWrite == SourceWrite.Keep) current[CatalogFiles.sourceUrl] else requestedSource
            val contentChanged = current[CatalogFiles.content] != encoded
            val sourceChanged = current[CatalogFiles.sourceUrl] != source
            // A URL-bearing import row is a repo sync (see the KDoc): it takes the sync path's
            // writes and always-recorded event even when the document matched.
            val synced = sourceWrite is SourceWrite.Synced && source != null
            // The history's field-level diff — the stored JSON is only decoded when it actually
            // differs, so an unchanged save pays nothing.
            val changes = buildList {
                if (contentChanged) {
                    addAll(documentChanges(json.decodeFromString(current[CatalogFiles.content]), stored))
                }
                sourceUrlChange(current[CatalogFiles.sourceUrl], source)?.let { add(it) }
            }
            val now = System.currentTimeMillis()
            val rows = CatalogFiles.update({ (CatalogFiles.id eq id) and (CatalogFiles.markedAsDeleted eq false) }) {
                it[kind] = stored.kind
                it[name] = stored.metadata.name
                it[namespace] = stored.metadata.namespace
                it[content] = encoded
                if (contentChanged || synced) it[updatedAt] = now
                it[CatalogFiles.sourceUrl] = source
                if (synced) {
                    it[lastSyncedAt] = now
                    it[syncedContent] = encoded
                } else if (sourceChanged) {
                    it[lastSyncedAt] = 0
                    it[syncedContent] = null
                }
                if (contentChanged || sourceChanged || synced) {
                    it[revision] = current[CatalogFiles.revision] + 1
                }
            }
            val event =
                if (synced && changes.isEmpty()) catalogFileSyncEvent(changes) else catalogFileUpdateEvent(changes)
            event?.let { eventService.recordInTransaction(id, actingUserId, it) }
            CatalogFileUpdateResult(rows = rows, waived = findings, changes = changes)
        }

    /**
     * The repo→DB sync: overwrites the document with the repo copy (parsed client-side —
     * YAML stays a client concern) and stamps the sync state. Soft findings are ALWAYS
     * waived (the import posture — the repo is the source of truth; the Errors report is the
     * net), structural validation and namespace resolution stay HARD. Requires the row to
     * hold a source reference (400 otherwise); an identity rename colliding with another
     * active file surfaces as the ordinary 23505 → 409.
     */
    suspend fun syncFromRepo(
        id: UInt,
        file: CatalogFile,
        actingUserId: UInt,
        expectedRevision: Long? = null,
    ): CatalogFileUpdateResult =
        suspendTransaction(database) {
            validateCatalogFile(file) // re-checked service-side so direct callers stay guarded
            // The cheap row checks come FIRST: a 404/400 must not pay for the workspace +
            // registry snapshot that softFindings loads.
            // `content` rides along for the history's field-level diff (the sync overwrites the
            // document wholesale, so what it CHANGED is the interesting part).
            val current = CatalogFiles.select(CatalogFiles.sourceUrl, CatalogFiles.content, CatalogFiles.revision)
                .where { (CatalogFiles.id eq id) and active() }
                .forUpdate()
                .toList()
                .singleOrNull()
                ?: return@suspendTransaction CatalogFileUpdateResult(rows = 0, waived = emptyList())
            requireExpectedRevision(expectedRevision, current[CatalogFiles.revision])
            if (current[CatalogFiles.sourceUrl] == null) {
                throw BadRequestException("This file has no source reference — set one before syncing")
            }
            val stored = file.withNamespace(registryReader.resolveNamespace(file.metadata.namespace))
            val findings = softFindings(stored, emptySet())
            val now = System.currentTimeMillis()
            val encoded = json.encodeToString(stored)
            val rows = CatalogFiles.update({ (CatalogFiles.id eq id) and (CatalogFiles.markedAsDeleted eq false) }) {
                it[kind] = stored.kind
                it[name] = stored.metadata.name
                it[namespace] = stored.metadata.namespace
                it[content] = encoded
                it[updatedAt] = now
                it[lastSyncedAt] = now
                it[syncedContent] = encoded
                it[revision] = current[CatalogFiles.revision] + 1
            }
            val changes = documentChanges(json.decodeFromString(current[CatalogFiles.content]), stored)
            eventService.recordInTransaction(id, actingUserId, catalogFileSyncEvent(changes))
            CatalogFileUpdateResult(
                rows = rows,
                waived = findings,
                changes = changes,
            )
        }

    /** The sync state of one active file (null = no such file — the route's 404). */
    suspend fun syncState(id: UInt): CatalogFileSyncState? = suspendTransaction(database) {
        CatalogFiles.select(
            CatalogFiles.revision,
            CatalogFiles.sourceUrl,
            CatalogFiles.lastSyncedAt,
            CatalogFiles.syncedContent,
        )
            .where { (CatalogFiles.id eq id) and active() }
            .toList()
            .singleOrNull()
            ?.let { row ->
                CatalogFileSyncState(
                    revision = row[CatalogFiles.revision],
                    sourceUrl = row[CatalogFiles.sourceUrl],
                    lastSyncedAt = row[CatalogFiles.lastSyncedAt],
                    syncedDocument = row[CatalogFiles.syncedContent]
                        ?.let { json.decodeFromString<CatalogFile>(it) },
                )
            }
    }

    suspend fun delete(
        id: UInt,
        actingUserId: UInt,
        expectedRevision: Long? = null,
    ): Int = suspendTransaction(database) {
        val current = CatalogFiles.select(CatalogFiles.revision)
            .where { (CatalogFiles.id eq id) and active() }
            .forUpdate()
            .toList()
            .singleOrNull()
            ?: return@suspendTransaction 0
        requireExpectedRevision(expectedRevision, current[CatalogFiles.revision])
        val rows = CatalogFiles.update({ (CatalogFiles.id eq id) and (CatalogFiles.markedAsDeleted eq false) }) {
            it[markedAsDeleted] = true
            it[revision] = current[CatalogFiles.revision] + 1
        }
        if (rows > 0) {
            eventService.recordInTransaction(id, actingUserId, catalogFileDeletionEvent())
        }
        rows
    }

    /**
     * The workspace Errors report — all active files loaded and resolved in ONE transaction.
     * The [filter] (the list endpoint's filter set, matched in-memory over the decoded sources)
     * narrows which files' errors are REPORTED; references still resolve against the whole
     * workspace, so filtering never manufactures MISSING. Deliberately UNLIKE the graph, where
     * the same filter decides what is SHOWN and a hidden target takes its edge with it: a
     * report about a file must not change its verdict because of what else is on screen.
     */
    suspend fun errors(filter: CatalogFileListFilter): ErrorsReport = readWorkspace(active()) {
        val all = activeSources()
        errorsReport(
            reported = all.filter { filter.matches(it.file) },
            all = all,
            registries = registryReader.loadSnapshot(),
        )
    }

    /**
     * Ad-hoc check of one (possibly unsaved, possibly not-yet-valid) document against the
     * stored identities and the registries — the same soft findings a strict save would
     * reject (the editor's Save-anyway flow consults this after a 400). An unsaved doc is
     * deliberately NOT in the identity set, so its self-references read as missing until
     * first save. The editor's per-keystroke hot path: identity columns + the small registry
     * tables — never `content`.
     */
    suspend fun check(file: CatalogFile): DocumentCheckReport = suspendTransaction(database) {
        // A blank namespace resolves to the flagged default for the live check too; the
        // literal fallback keeps the check non-blocking when nothing is flagged.
        val ns = file.metadata.namespace.ifEmpty { registryReader.flaggedDefaultNamespace() ?: DEFAULT_NAMESPACE }
        val resolved = file.withNamespace(ns)
        val references = checkDocument(resolved, activeIdentities()).findings
        val registry = registryFindings(resolved, registryReader.loadSnapshot()).map { it.finding }
        DocumentCheckReport(findings = references + registry)
    }

    /**
     * The identity triple of every active file, from the denormalized columns alone.
     * [excludingId] drops one row's OWN identity from the set — used by [update] so a
     * rename's reference check resolves against the document as it will exist AFTER the
     * write, not against the row's still-current (pre-rename) identity read inside the same
     * transaction: without it, a sibling's reference to the OLD identity would keep resolving
     * even though nothing will carry that identity once this write commits.
     */
    private suspend fun activeIdentities(excludingId: UInt? = null): Set<EntityIdentity> =
        CatalogFiles.select(CatalogFiles.id, CatalogFiles.kind, CatalogFiles.namespace, CatalogFiles.name)
            .where { active() }
            .toList()
            .filter { excludingId == null || it[CatalogFiles.id].value != excludingId }
            .map {
                EntityIdentity(
                    kind = it[CatalogFiles.kind].lowercase(),
                    namespace = it[CatalogFiles.namespace].lowercase(),
                    name = it[CatalogFiles.name].lowercase(),
                )
            }
            .toSet()

    /**
     * The rendered-together graph — all active files in one transaction. The [filter] (the list
     * endpoint's filter set, matched in-memory over the decoded sources) selects which entities
     * are SHOWN: the graph's STORED nodes are exactly the list endpoint's rows for the same
     * query, and an edge is drawn only when both of its ends are shown. The unfiltered `all` is
     * still passed so a hidden stored target can be told apart from an absent one.
     */
    suspend fun graph(filter: CatalogFileListFilter): CatalogGraph = readWorkspace(active()) {
        val all = activeSources()
        buildGraph(
            sources = all.filter { filter.matches(it.file) },
            allSources = all,
            showsVirtual = filter::allowsVirtualTarget,
        )
    }

    /** The export payload: active documents, (namespace, name)-ordered, optionally one namespace. */
    suspend fun export(namespace: String?): ExportResponse {
        // The namespace predicate runs in SQL (stored namespaces are lowercase, so folding the
        // filter matches the case-insensitive contract) — no loading the workspace to discard it.
        var predicate: Op<Boolean> = active()
        namespace?.let { predicate = predicate and (CatalogFiles.namespace eq it.lowercase()) }
        return readWorkspace(predicate, CATALOG_EXPORT_FOOTPRINT_RATIO) {
            val files = CatalogFiles.selectAll()
                .where { predicate }
                .map { json.decodeFromString<CatalogFile>(it[CatalogFiles.content]) }
                .toList()
                .sortedWith(compareBy({ it.metadata.namespace.lowercase() }, { it.metadata.name.lowercase() }))
            ExportResponse(files = files)
        }
    }

    /**
     * The active files holding one of [identities] (lowercased `(kind, namespace, name)`), WITH their ids —
     * the conversion export's footprint-merge read. It is charged to the CALLER's [reservation] (the
     * caller keeps it open while the sources are alive, so a narrow export reads and reserves only the
     * files it can merge over): the matching rows' `content` bytes x the export footprint ratio are
     * reserved BEFORE any content crosses the wire, exactly as [export] does for the whole workspace. The SQL
     * predicate is a superset narrowing (kind, namespace and — up to [IDENTITY_NAME_NARROWING_LIMIT]
     * identities — name each `IN` their identities' values); the exact identity match is made in memory.
     */
    suspend fun readActiveSources(reservation: ReadLedger.Reservation, identities: Set<EntityIdentity>): List<CatalogSource> {
        if (identities.isEmpty()) return emptyList()
        var predicate: Op<Boolean> = active() and
            (CatalogFiles.kind.lowerCase() inList identities.map { it.kind }.toSet()) and
            (CatalogFiles.namespace inList identities.map { it.namespace }.toSet())
        if (identities.size <= IDENTITY_NAME_NARROWING_LIMIT) {
            predicate = predicate and (CatalogFiles.name.lowerCase() inList identities.map { it.name }.toSet())
        }
        val narrowed = predicate
        try {
            return suspendTransaction(database) {
                reserveContent(reservation, narrowed, CATALOG_EXPORT_FOOTPRINT_RATIO)
                activeSources(narrowed).filter { identityOf(it.file) in identities }
            }
        } catch (cause: ReadBudgetExceeded) {
            throwReadBudgetHttp(cause, "catalog")
        }
    }

    /** Reserves [ratio] x the stored `content` bytes of the rows [predicate] matches on [reservation], before they are read. */
    private suspend fun reserveContent(reservation: ReadLedger.Reservation, predicate: Op<Boolean>, ratio: Long) {
        val contentSum = Sum(octetLength(CatalogFiles.content), LongColumnType())
        val row = CatalogFiles.select(contentSum).where { predicate }.singleOrNull()
            ?: error("an aggregate SELECT without GROUP BY must always answer exactly one row")
        reservation.reserve((row.getOrNull(contentSum) ?: 0L) * ratio)
    }

    /**
     * Every active file's IDENTITY plus the plain-text `spec.<field>` projections named by
     * [specFields] — no `content` crosses the wire (each projection is one short value extracted
     * in SQL), which is why this is an identity-only read outside the read ledger
     * (`.claude/docs/security.md`, the ledger's exceptions). The conversion import uses it to tell
     * which mapping pair a REFERENCED stored file belongs to (kind plus the pair selector's field)
     * without decoding the workspace. [specFields] must be simple identifiers.
     */
    suspend fun readIdentities(specFields: Set<String>): List<StoredIdentity> = suspendTransaction(database) {
        val projections = specFields.sorted().map { it to CatalogFiles.content.jsonTextAt(listOf("spec", it)) }
        val identity = listOf<Expression<*>>(CatalogFiles.kind, CatalogFiles.namespace, CatalogFiles.name)
        val columns = identity + projections.map { it.second }
        CatalogFiles.select(columns)
            .where { active() }
            .orderBy(CatalogFiles.id to SortOrder.ASC)
            .map { row ->
                StoredIdentity(
                    kind = row[CatalogFiles.kind],
                    namespace = row[CatalogFiles.namespace],
                    name = row[CatalogFiles.name],
                    spec = projections.mapNotNull { (field, expression) -> row[expression]?.let { field to it } }.toMap(),
                )
            }
            .toList()
    }

    /**
     * Runs a whole-workspace content read ([errors]/[graph]/[export]) in one transaction after
     * admitting it against the process-wide [readLedger] (2.19.0 — `.claude/docs/security.md`
     * "Entity read memory budget"): the whole conservative estimate of the rows [predicate] matches
     * (raw bytes × [ratio]) is reserved BEFORE any `content` crosses the wire, so a workspace too
     * large on its own is a `400` and one that merely finds no room beside other in-flight reads a
     * `429` ([throwReadBudgetHttp]). The reservation is held for the whole read and released on exit.
     */
    private suspend fun <T> readWorkspace(
        predicate: Op<Boolean>,
        ratio: Long = CATALOG_READ_FOOTPRINT_RATIO,
        block: suspend () -> T,
    ): T =
        readLedger.open().use { reservation ->
            try {
                suspendTransaction(database) {
                    reserveContent(reservation, predicate, ratio)
                    block()
                }
            } catch (cause: ReadBudgetExceeded) {
                throwReadBudgetHttp(cause, "catalog")
            }
        }

    /** The predicate of the ACTIVE file holding [file]'s identity (the V5 index: kind, namespace, LOWER(name)). */
    private fun identityPredicate(file: CatalogFile): Op<Boolean> =
        active() and
            (CatalogFiles.kind eq file.kind) and
            (CatalogFiles.namespace eq file.metadata.namespace) and
            (CatalogFiles.name.lowerCase() eq file.metadata.name.lowercase())

    /**
     * The id of the ACTIVE file holding the (namespace-resolved) [file]'s identity — the
     * import's `replaceExisting` lookup. A soft-deleted twin is invisible here, exactly as it is
     * to the partial unique index, so its identity imports as a plain create.
     */
    internal suspend fun activeIdOf(file: CatalogFile): UInt? = suspendTransaction(database) {
        CatalogFiles.select(CatalogFiles.id).where { identityPredicate(file) }
            .toList().singleOrNull()?.get(CatalogFiles.id)?.value
    }

    /**
     * The dry-run's prediction of [update] for the active file holding [file]'s identity:
     * UNCHANGED exactly when the replace would record no field change — the SAME structural
     * check the real run's row status uses ([documentChanges] plus the reference's pseudo-field,
     * decoded only when the stored JSON differs from the re-encoded document, as [update] does).
     * One single-row read, only for the identities the batch actually replaces.
     */
    private suspend fun predictReplace(file: CatalogFile, sourceWrite: SourceWrite): ReplacePrediction {
        val row = CatalogFiles.select(CatalogFiles.content, CatalogFiles.sourceUrl)
            .where { identityPredicate(file) }
            .toList().singleOrNull() ?: return ReplacePrediction.CHANGED
        val source = if (sourceWrite is SourceWrite.Synced) sourceWrite.sourceUrl else row[CatalogFiles.sourceUrl]
        val changed = (row[CatalogFiles.content] != json.encodeToString(file) &&
            documentChanges(json.decodeFromString(row[CatalogFiles.content]), file).isNotEmpty()) ||
            sourceUrlChange(row[CatalogFiles.sourceUrl], source) != null
        return if (changed) ReplacePrediction.CHANGED else ReplacePrediction.UNCHANGED
    }

    /**
     * One snapshot transaction for the import DRY-RUN (see CatalogFileImport.kt — the same
     * feature, split into its own file): the active identities, the registry snapshot, an
     * in-transaction namespace resolver, and the `replaceExisting` prediction, handed to [block]
     * so the dry-run classifies against exactly what a real run would see. Internal-only seam —
     * everything it exposes stays private to this class.
     */
    internal suspend fun <T> withImportSnapshot(
        block: suspend (
            active: Set<EntityIdentity>,
            registries: RegistrySnapshot,
            resolve: suspend (CatalogFile) -> CatalogFile,
            predict: suspend (CatalogFile, SourceWrite) -> ReplacePrediction,
        ) -> T,
    ): T = suspendTransaction(database) {
        block(
            activeIdentities(),
            registryReader.loadSnapshot(),
            { file -> file.withNamespace(registryReader.resolveNamespace(file.metadata.namespace)) },
            { file, sourceWrite -> predictReplace(file, sourceWrite) },
        )
    }

    private suspend fun activeSources(predicate: Op<Boolean> = active()): List<CatalogSource> =
        CatalogFiles.selectAll()
            .where { predicate }
            .map {
                CatalogSource(
                    id = it[CatalogFiles.id].value,
                    revision = it[CatalogFiles.revision],
                    file = json.decodeFromString(it[CatalogFiles.content]),
                    sourceUrl = it[CatalogFiles.sourceUrl],
                )
            }
            .toList()

    suspend fun list(filter: CatalogFileListFilter, paging: PageRequest): CatalogFileListResult =
        suspendTransaction(database) {
            // Counted on the same join as the rows, so the two can never disagree.
            val predicate: Op<Boolean> = buildCatalogFilePredicate(filter) and active()
            val total = joined().selectAll().where { predicate }.count()
            val rows = joined().selectAll()
                .where { predicate }
                .applyPaging(paging, SORTABLE_COLUMNS)
                .map { it.toDetail().toListItem() }
                .toList()
            CatalogFileListResult(items = rows, total = total)
        }

    private fun ResultRow.toDetail(): CatalogFileDetail = CatalogFileDetail(
        id = this[CatalogFiles.id].value,
        revision = this[CatalogFiles.revision],
        file = json.decodeFromString<CatalogFile>(this[CatalogFiles.content]),
        createdBy = this[CatalogFiles.createdBy].value,
        creatorName = this[UserService.Users.name],
        creatorDeleted = this[UserService.Users.markedAsDeleted],
        createdAt = this[CatalogFiles.createdAt],
        updatedAt = this[CatalogFiles.updatedAt],
        sourceUrl = this[CatalogFiles.sourceUrl],
        lastSyncedAt = this[CatalogFiles.lastSyncedAt],
    )

    private fun CatalogFileDetail.toListItem() = CatalogFileListItem(
        id = id,
        revision = revision,
        kind = file.kind,
        name = file.metadata.name,
        namespace = file.metadata.namespace,
        title = file.metadata.title,
        type = file.spec.type,
        lifecycle = file.spec.lifecycle,
        owner = file.spec.owner,
        tags = file.metadata.tags,
        creatorName = creatorName,
        creatorDeleted = creatorDeleted,
        updatedAt = updatedAt,
        sourceUrl = sourceUrl,
        lastSyncedAt = lastSyncedAt,
    )

    private fun requireExpectedRevision(expected: Long?, current: Long) {
        if (expected != null && expected != current) {
            throw CatalogRevisionConflictException(expected, current)
        }
    }

}
