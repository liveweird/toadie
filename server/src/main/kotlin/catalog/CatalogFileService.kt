package ch.nokillswit.catalog

import ch.nokillswit.annotations.AnnotationKeyService
import ch.nokillswit.dictionaries.Dictionary
import ch.nokillswit.dictionaries.DictionaryService
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.labels.LabelService
import ch.nokillswit.tags.TagCategoryService
import ch.nokillswit.types.EntityTypesService
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
    val sourceUrl: String?,
    val lastSyncedAt: Long,
    val syncedDocument: CatalogFile?,
) {
    fun toResponse() = SyncStateResponse(
        sourceUrl = sourceUrl,
        lastSyncedAt = lastSyncedAt,
        syncedDocument = syncedDocument,
    )
}

/** A successful create plus the soft findings the save waived (empty on a strict save). */
data class CatalogFileSaveResult(val id: UInt, val waived: List<SoftFinding>)

/**
 * A successful update (rows=0 → the route's 404) plus the waived soft findings and the
 * field-level [changes] the write made. The diff is computed here because this is the only
 * place holding both sides in one transaction; the ROUTE turns it into the history event (see
 * the consistency model in `.claude/docs/persistence.md`).
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

class CatalogFileService(private val database: R2dbcDatabase) {
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

    /** The active NAMESPACE entry flagged as the default, or null when none is (V9). */
    private suspend fun flaggedDefaultNamespace(): String? =
        DictionaryService.Entries.selectAll()
            .where {
                (DictionaryService.Entries.dictionary eq Dictionary.NAMESPACE.name) and
                    (DictionaryService.Entries.isDefault eq true) and
                    (DictionaryService.Entries.markedAsDeleted eq false)
            }
            .map { it[DictionaryService.Entries.value] }
            .toList()
            .singleOrNull()

    /**
     * The second sanctioned cross-feature table read (see persistence.md): STRICT namespace
     * enforcement — every write (create, update, import) resolves its (sanitized: folded,
     * possibly blank) namespace inside the write's own transaction. Blank means "the
     * ADMIN-flagged default entry" (none flagged → 400); a concrete value must be an ACTIVE
     * dictionary entry. There is no grandfathering: a stored file whose namespace was since
     * removed cannot be saved until it is re-added or changed (a deliberate product decision).
     */
    private suspend fun resolvedNamespace(namespace: String): String {
        if (namespace.isEmpty()) {
            return flaggedDefaultNamespace() ?: throw BadRequestException(
                "No default namespace is defined — mark one on the Namespaces page or specify a namespace",
            )
        }
        val defined = DictionaryService.Entries.selectAll()
            .where {
                (DictionaryService.Entries.dictionary eq Dictionary.NAMESPACE.name) and
                    (DictionaryService.Entries.value eq namespace) and
                    (DictionaryService.Entries.markedAsDeleted eq false)
            }
            .count() > 0
        if (!defined) {
            throw BadRequestException(
                "metadata.namespace '$namespace' is not a defined namespace — define it on the Namespaces page",
            )
        }
        return namespace
    }

    /**
     * The third sanctioned cross-feature table read (see persistence.md) — one snapshot of
     * the five soft-check registries (labels, tag categories, per-kind type dictionaries,
     * the LIFECYCLE dictionary, annotation keys) plus the NAMESPACE dictionary values,
     * taken inside the calling write/report transaction. The soft rules themselves are the
     * pure `registryFindings` in Errors.kt: every value must be allowed by its ADMIN-curated
     * registry for the file's kind (byte-exact, no grandfathering; an empty registry allows
     * nothing). A violation is a SOFT finding — it rejects a strict save but is waivable via
     * `allowInvalid` (see [softFindings]). The namespaces set is different: it feeds ONLY
     * the report-only `storedDocumentFindings` (namespace resolution stays a HARD write rule
     * via [resolvedNamespace]).
     */
    private suspend fun loadRegistrySnapshot(): RegistrySnapshot {
        val labels = LabelService.Labels.selectAll()
            .where { LabelService.Labels.markedAsDeleted eq false }
            .map {
                it[LabelService.Labels.key] to Pair(
                    json.decodeFromString<List<String>>(it[LabelService.Labels.allowedKinds]),
                    json.decodeFromString<List<String>>(it[LabelService.Labels.allowedValues]),
                )
            }
            .toList()
            .toMap()
        val annotationKeys = AnnotationKeyService.AnnotationKeys.selectAll()
            .where { AnnotationKeyService.AnnotationKeys.markedAsDeleted eq false }
            .map {
                it[AnnotationKeyService.AnnotationKeys.key] to
                    json.decodeFromString<List<String>>(it[AnnotationKeyService.AnnotationKeys.allowedKinds])
            }
            .toList()
            .toMap()
        // tag -> (owning category name, its allowed kinds); categories are disjoint by the
        // one-category-per-tag rule, so a plain map suffices.
        val tags = mutableMapOf<String, Pair<String, List<String>>>()
        TagCategoryService.TagCategories.selectAll()
            .where { TagCategoryService.TagCategories.markedAsDeleted eq false }
            .toList()
            .forEach { row ->
                val category = row[TagCategoryService.TagCategories.name]
                val kinds = json.decodeFromString<List<String>>(row[TagCategoryService.TagCategories.allowedKinds])
                json.decodeFromString<List<String>>(row[TagCategoryService.TagCategories.tags])
                    .forEach { tags[it] = category to kinds }
            }
        val types = EntityTypesService.EntityTypes.selectAll()
            .where { EntityTypesService.EntityTypes.markedAsDeleted eq false }
            .map {
                it[EntityTypesService.EntityTypes.kind] to
                    json.decodeFromString<List<String>>(it[EntityTypesService.EntityTypes.types])
            }
            .toList()
            .toMap()
        val lifecycles = DictionaryService.Entries.selectAll()
            .where {
                (DictionaryService.Entries.dictionary eq Dictionary.LIFECYCLE.name) and
                    (DictionaryService.Entries.markedAsDeleted eq false)
            }
            .map { it[DictionaryService.Entries.value] }
            .toList()
            .toSet()
        val namespaces = DictionaryService.Entries.selectAll()
            .where {
                (DictionaryService.Entries.dictionary eq Dictionary.NAMESPACE.name) and
                    (DictionaryService.Entries.markedAsDeleted eq false)
            }
            .map { it[DictionaryService.Entries.value] }
            .toList()
            .toSet()
        return RegistrySnapshot(
            labels = labels,
            annotationKeys = annotationKeys,
            tags = tags,
            types = types,
            lifecycles = lifecycles,
            namespaces = namespaces,
        )
    }

    private fun CatalogFile.withNamespace(resolved: String): CatalogFile =
        if (metadata.namespace == resolved) this else copy(metadata = metadata.copy(namespace = resolved))

    /**
     * The write path's SOFT checks, as findings: every entity reference resolved against the
     * active workspace (the ONE rulebook in Errors.kt — per-field default kinds, allowed
     * target kinds, contextual namespace, never the document ITSELF) plus the registry checks
     * against [loadRegistrySnapshot]. Runs inside the write's own transaction. A strict save
     * rejects any finding with ONE aggregated 400; `allowInvalid=true` waives them all and
     * stores anyway (the Errors report is the net for what was waived).
     * [extraIdentities] is the import path's batch universe (sibling documents resolve
     * order-independently). A rename's "self" is the NEW identity — uniform across create,
     * update, import, and the ad-hoc check.
     */
    private suspend fun softFindings(stored: CatalogFile, extraIdentities: Set<EntityIdentity>): List<SoftFinding> {
        val references = checkDocument(stored, activeIdentities() + extraIdentities).findings
            .map { SoftFinding(it, referenceFindingMessage(it)) }
        return references + registryFindings(stored, loadRegistrySnapshot())
    }

    /**
     * Resolves a sanitized file's namespace outside a write ([resolvedNamespace] semantics,
     * own transaction) — the import path uses it so each result row reports the CONCRETE
     * namespace a blank one resolved to.
     */
    suspend fun resolveNamespace(file: CatalogFile): CatalogFile = suspendTransaction(database) {
        file.withNamespace(resolvedNamespace(file.metadata.namespace))
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
    ): CatalogFileSaveResult = suspendTransaction(database) {
        validateCatalogFile(file) // re-checked service-side so direct callers stay guarded
        val source = sanitizedSourceUrl(sourceUrl) // re-checked service-side too
        // The stored row AND the content JSON both carry the resolved concrete namespace.
        val stored = file.withNamespace(resolvedNamespace(file.metadata.namespace))
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
        CatalogFileSaveResult(id = newRecord[CatalogFiles.id].value, waived = findings)
    }

    /** The strict-or-waive gate shared by create and update: throws unless waived, returns what was. */
    private suspend fun requireOrWaive(
        stored: CatalogFile,
        extraIdentities: Set<EntityIdentity>,
        allowInvalid: Boolean,
    ): List<SoftFinding> {
        val findings = softFindings(stored, extraIdentities)
        if (findings.isNotEmpty() && !allowInvalid) {
            throw BadRequestException(findings.joinToString("; ") { it.message })
        }
        return findings
    }

    suspend fun read(id: UInt): CatalogFileDetail? = suspendTransaction(database) {
        joined().selectAll()
            .where { (CatalogFiles.id eq id) and active() }
            .map { it.toDetail() }
            .singleOrNull()
    }

    suspend fun update(
        id: UInt,
        file: CatalogFile,
        allowInvalid: Boolean = false,
        sourceUrl: String? = null,
    ): CatalogFileUpdateResult =
        suspendTransaction(database) {
            validateCatalogFile(file) // re-checked service-side so direct callers stay guarded
            val source = sanitizedSourceUrl(sourceUrl) // re-checked service-side too
            val stored = file.withNamespace(resolvedNamespace(file.metadata.namespace))
            val findings = requireOrWaive(stored, emptySet(), allowInvalid)
            val encoded = json.encodeToString(stored)
            // The current row decides the sync-state consequences: a changed/cleared reference
            // resets the sync state (a different source was never synced from), and updatedAt
            // bumps ONLY on a content change — a reference-only edit must not read as
            // "modified in the DB since the sync" (updatedAt > lastSyncedAt is that signal).
            val current = CatalogFiles.select(CatalogFiles.content, CatalogFiles.sourceUrl)
                .where { (CatalogFiles.id eq id) and active() }
                .toList()
                .singleOrNull()
                ?: return@suspendTransaction CatalogFileUpdateResult(rows = 0, waived = findings)
            val contentChanged = current[CatalogFiles.content] != encoded
            val sourceChanged = current[CatalogFiles.sourceUrl] != source
            // The history's field-level diff — the stored JSON is only decoded when it actually
            // differs, so an unchanged save pays nothing.
            val changes = buildList {
                if (contentChanged) {
                    addAll(documentChanges(json.decodeFromString(current[CatalogFiles.content]), stored))
                }
                sourceUrlChange(current[CatalogFiles.sourceUrl], source)?.let { add(it) }
            }
            val rows = CatalogFiles.update({ (CatalogFiles.id eq id) and (CatalogFiles.markedAsDeleted eq false) }) {
                it[kind] = stored.kind
                it[name] = stored.metadata.name
                it[namespace] = stored.metadata.namespace
                it[content] = encoded
                if (contentChanged) it[updatedAt] = System.currentTimeMillis()
                it[CatalogFiles.sourceUrl] = source
                if (sourceChanged) {
                    it[lastSyncedAt] = 0
                    it[syncedContent] = null
                }
            }
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
    suspend fun syncFromRepo(id: UInt, file: CatalogFile): CatalogFileUpdateResult =
        suspendTransaction(database) {
            validateCatalogFile(file) // re-checked service-side so direct callers stay guarded
            // The cheap row checks come FIRST: a 404/400 must not pay for the workspace +
            // registry snapshot that softFindings loads.
            // `content` rides along for the history's field-level diff (the sync overwrites the
            // document wholesale, so what it CHANGED is the interesting part).
            val current = CatalogFiles.select(CatalogFiles.sourceUrl, CatalogFiles.content)
                .where { (CatalogFiles.id eq id) and active() }
                .toList()
                .singleOrNull()
                ?: return@suspendTransaction CatalogFileUpdateResult(rows = 0, waived = emptyList())
            if (current[CatalogFiles.sourceUrl] == null) {
                throw BadRequestException("This file has no source reference — set one before syncing")
            }
            val stored = file.withNamespace(resolvedNamespace(file.metadata.namespace))
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
            }
            CatalogFileUpdateResult(
                rows = rows,
                waived = findings,
                changes = documentChanges(json.decodeFromString(current[CatalogFiles.content]), stored),
            )
        }

    /** The sync state of one active file (null = no such file — the route's 404). */
    suspend fun syncState(id: UInt): CatalogFileSyncState? = suspendTransaction(database) {
        CatalogFiles.select(CatalogFiles.sourceUrl, CatalogFiles.lastSyncedAt, CatalogFiles.syncedContent)
            .where { (CatalogFiles.id eq id) and active() }
            .toList()
            .singleOrNull()
            ?.let { row ->
                CatalogFileSyncState(
                    sourceUrl = row[CatalogFiles.sourceUrl],
                    lastSyncedAt = row[CatalogFiles.lastSyncedAt],
                    syncedDocument = row[CatalogFiles.syncedContent]
                        ?.let { json.decodeFromString<CatalogFile>(it) },
                )
            }
    }

    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        CatalogFiles.update({ (CatalogFiles.id eq id) and (CatalogFiles.markedAsDeleted eq false) }) {
            it[markedAsDeleted] = true
        }
    }

    /**
     * The workspace Errors report — all active files loaded and resolved in ONE transaction.
     * The [filter] (the list endpoint's filter set, matched in-memory over the decoded
     * sources — the graph's shape) narrows which files' errors are REPORTED; references
     * still resolve against the whole workspace, so filtering never manufactures MISSING.
     */
    suspend fun errors(filter: CatalogFileListFilter): ErrorsReport = suspendTransaction(database) {
        val all = activeSources()
        errorsReport(reported = all.filter { filter.matches(it.file) }, all = all, registries = loadRegistrySnapshot())
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
        val ns = file.metadata.namespace.ifEmpty { flaggedDefaultNamespace() ?: DEFAULT_NAMESPACE }
        val resolved = file.withNamespace(ns)
        val references = checkDocument(resolved, activeIdentities()).findings
        val registry = registryFindings(resolved, loadRegistrySnapshot()).map { it.finding }
        DocumentCheckReport(findings = references + registry)
    }

    /** The identity triple of every active file, from the denormalized columns alone. */
    private suspend fun activeIdentities(): Set<EntityIdentity> =
        CatalogFiles.select(CatalogFiles.kind, CatalogFiles.namespace, CatalogFiles.name)
            .where { active() }
            .map {
                EntityIdentity(
                    kind = it[CatalogFiles.kind].lowercase(),
                    namespace = it[CatalogFiles.namespace].lowercase(),
                    name = it[CatalogFiles.name].lowercase(),
                )
            }
            .toList()
            .toSet()

    /**
     * The rendered-together graph — all active files in one transaction. The [filter] (the
     * list endpoint's filter set, matched in-memory over the decoded sources) narrows which
     * files' references are EXPANDED; targets still resolve against the whole workspace
     * (a stored file filtered out elsewhere appears as a STORED node when pointed at).
     */
    suspend fun graph(filter: CatalogFileListFilter): CatalogGraph = suspendTransaction(database) {
        val all = activeSources()
        buildGraph(sources = all.filter { filter.matches(it.file) }, allSources = all)
    }

    /** The export payload: active documents, (namespace, name)-ordered, optionally one namespace. */
    suspend fun export(namespace: String?): ExportResponse = suspendTransaction(database) {
        // The namespace predicate runs in SQL (stored namespaces are lowercase, so folding the
        // filter matches the case-insensitive contract) — no loading the workspace to discard it.
        var predicate: Op<Boolean> = active()
        namespace?.let { predicate = predicate and (CatalogFiles.namespace eq it.lowercase()) }
        val files = CatalogFiles.selectAll()
            .where { predicate }
            .map { json.decodeFromString<CatalogFile>(it[CatalogFiles.content]) }
            .toList()
            .sortedWith(compareBy({ it.metadata.namespace.lowercase() }, { it.metadata.name.lowercase() }))
        ExportResponse(files = files)
    }

    /**
     * One snapshot transaction for the import DRY-RUN (see CatalogFileImport.kt — the same
     * feature, split into its own file): the active identities, the registry snapshot, and
     * an in-transaction namespace resolver, handed to [block] so the dry-run classifies
     * against exactly what a real run would see. Internal-only seam — everything it exposes
     * stays private to this class.
     */
    internal suspend fun <T> withImportSnapshot(
        block: suspend (
            active: Set<EntityIdentity>,
            registries: RegistrySnapshot,
            resolve: suspend (CatalogFile) -> CatalogFile,
        ) -> T,
    ): T = suspendTransaction(database) {
        block(activeIdentities(), loadRegistrySnapshot()) { file ->
            file.withNamespace(resolvedNamespace(file.metadata.namespace))
        }
    }

    private suspend fun activeSources(): List<CatalogSource> =
        CatalogFiles.selectAll()
            .where { active() }
            .map {
                CatalogSource(
                    id = it[CatalogFiles.id].value,
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

}
