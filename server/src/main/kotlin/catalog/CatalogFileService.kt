package ch.nokillswit.catalog

import ch.nokillswit.annotations.AnnotationKeyService
import ch.nokillswit.dictionaries.Dictionary
import ch.nokillswit.dictionaries.DictionaryService
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.labels.LabelService
import ch.nokillswit.tags.TagCategoryService
import ch.nokillswit.types.EntityTypesService
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.plugins.isUniqueViolation
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val CatalogFileServiceKey = AttributeKey<CatalogFileService>("CatalogFileService")

/** The identity-clash row message, shared by the real import and its dry-run. */
internal const val IMPORT_CONFLICT_MESSAGE =
    "An active catalog file with this kind, namespace, and name already exists"

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

/** A successful update (rows=0 → the route's 404) plus the waived soft findings. */
data class CatalogFileUpdateResult(val rows: Int, val waived: List<SoftFinding>)

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
        // stamps updated_at and last_synced_at EQUAL, so updated_at > last_synced_at means
        // "modified in the DB since the sync"; synced_content is the baseline snapshot.
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
            CatalogFileUpdateResult(rows = rows, waived = findings)
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
            val stored = file.withNamespace(resolvedNamespace(file.metadata.namespace))
            val findings = softFindings(stored, emptySet())
            val current = CatalogFiles.select(CatalogFiles.sourceUrl)
                .where { (CatalogFiles.id eq id) and active() }
                .toList()
                .singleOrNull()
                ?: return@suspendTransaction CatalogFileUpdateResult(rows = 0, waived = findings)
            if (current[CatalogFiles.sourceUrl] == null) {
                throw BadRequestException("This file has no source reference — set one before syncing")
            }
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
            CatalogFileUpdateResult(rows = rows, waived = findings)
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
     * Report & skip: each document imports independently — sanitize → validate (a validator
     * 400 becomes INVALID with its message) → create (an identity clash — the partial unique
     * index's 23505 — becomes CONFLICT; any other storage failure ERROR). Import ALWAYS
     * waives the soft checks (`allowInvalid`): a document with unresolved references or
     * registry findings still stores, reported as CREATED_WITH_FINDINGS with the finding
     * messages — the point of the round trip is getting the batch IN so errors can be fixed
     * incrementally (the Errors report tracks them). Only structural validation and
     * namespace resolution still skip a document as INVALID. Nothing rethrows except
     * cancellation, so the batch always runs to completion and the result rows ARE the
     * outcome. The route emits the audit events for the stored rows (the repo convention:
     * audits live route-side).
     */
    suspend fun import(
        files: List<CatalogFile>,
        createdByUserId: UInt,
        // The fetch-from-URL flow's source: every stored row gets this reference AND starts
        // synced (the content IS the repo copy at import time — an import from a URL is a
        // sync). A pasted/uploaded batch passes null and the rows read "no source".
        sourceUrl: String? = null,
    ): List<ImportFileResult> {
        // The batch universe: sibling documents resolve against each other ORDER-INDEPENDENTLY
        // (a real export's entities are interdependent — the round trip must survive). Only
        // documents that sanitize, validate, and namespace-resolve contribute an identity.
        // Documented residual: a document referencing a sibling that later fails to STORE
        // (identity conflict) keeps its dangling reference — the same class as a
        // deletion-created dangling ref, and the Errors report catches it.
        val batchIdentities = batchIdentities(files)
        return files.mapIndexed { index, raw -> importOne(index, raw, createdByUserId, batchIdentities, sourceUrl) }
    }

    /** The identities every batch document may reference (shared by import and its dry-run). */
    private suspend fun batchIdentities(files: List<CatalogFile>): Set<EntityIdentity> =
        files.mapNotNull { raw ->
            val sanitized = sanitizedCatalogFile(raw)
            try {
                validateCatalogFile(sanitized)
                identityOf(resolveNamespace(sanitized))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // the document will produce its own INVALID/ERROR row
            }
        }.toSet()

    /**
     * The import's DRY-RUN (`POST …/import/check`): the identical classification —
     * structural validation, namespace resolution, identity conflicts against the workspace
     * AND within the batch, soft findings — with the insert replaced by prediction. Nothing
     * is stored, nothing is audited; every row's `fileId` stays null and the statuses read
     * as predictions (CREATED = would be created). One snapshot transaction (the errors-report
     * shape) — cheaper than the real run, semantically equal; the result is a snapshot, so
     * a concurrent write can change the real outcome.
     */
    suspend fun importCheck(files: List<CatalogFile>): List<ImportFileResult> {
        val batchIdentities = batchIdentities(files)
        return suspendTransaction(database) {
            val active = activeIdentities()
            val registries = loadRegistrySnapshot()
            // The running seen-set predicts INTRA-batch duplicates: the real run stores the
            // first occurrence and 23505-CONFLICTs the second — mirror that ordering.
            val seen = mutableSetOf<EntityIdentity>()
            files.mapIndexed { index, raw -> checkOne(index, raw, batchIdentities, active, registries, seen) }
        }
    }

    private suspend fun checkOne(
        index: Int,
        raw: CatalogFile,
        batchIdentities: Set<EntityIdentity>,
        active: Set<EntityIdentity>,
        registries: RegistrySnapshot,
        seen: MutableSet<EntityIdentity>,
    ): ImportFileResult {
        val sanitized = sanitizedCatalogFile(raw)
        fun base(f: CatalogFile) = ImportFileResult(
            index = index,
            kind = f.kind,
            namespace = f.metadata.namespace,
            name = f.metadata.name,
            status = ImportResultStatus.ERROR,
        )
        try {
            validateCatalogFile(sanitized)
        } catch (e: BadRequestException) {
            return base(sanitized).copy(status = ImportResultStatus.INVALID, message = e.message)
        }
        val stored = try {
            sanitized.withNamespace(resolvedNamespace(sanitized.metadata.namespace))
        } catch (e: BadRequestException) {
            return base(sanitized).copy(status = ImportResultStatus.INVALID, message = e.message)
        }
        val identity = identityOf(stored)
        if (identity in active || !seen.add(identity)) {
            return base(stored).copy(status = ImportResultStatus.CONFLICT, message = IMPORT_CONFLICT_MESSAGE)
        }
        val findings = checkDocument(stored, active + batchIdentities).findings
            .map { SoftFinding(it, referenceFindingMessage(it)) } +
            registryFindings(stored, registries)
        return if (findings.isEmpty()) {
            base(stored).copy(status = ImportResultStatus.CREATED)
        } else {
            base(stored).copy(
                status = ImportResultStatus.CREATED_WITH_FINDINGS,
                message = findings.joinToString("; ") { it.message },
            )
        }
    }

    private suspend fun importOne(
        index: Int,
        raw: CatalogFile,
        createdByUserId: UInt,
        batchIdentities: Set<EntityIdentity>,
        sourceUrl: String?,
    ): ImportFileResult {
        val sanitized = sanitizedCatalogFile(raw)
        fun base(f: CatalogFile) = ImportFileResult(
            index = index,
            kind = f.kind,
            namespace = f.metadata.namespace,
            name = f.metadata.name,
            status = ImportResultStatus.ERROR,
        )
        try {
            validateCatalogFile(sanitized)
        } catch (e: BadRequestException) {
            return base(sanitized).copy(status = ImportResultStatus.INVALID, message = e.message)
        }
        // Resolve blank → the flagged default (or the undefined-namespace 400) up front, so
        // the result row reports the CONCRETE namespace the document lands in. Storage-level
        // resolution failures classify as ERROR rows exactly like create's.
        val file = try {
            resolveNamespace(sanitized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BadRequestException) {
            return base(sanitized).copy(status = ImportResultStatus.INVALID, message = e.message)
        } catch (e: Exception) {
            return base(sanitized).copy(status = ImportResultStatus.ERROR, message = e.message ?: "Storage failed")
        }
        val base = base(file)
        return try {
            val saved = create(
                file,
                createdByUserId,
                batchIdentities,
                allowInvalid = true,
                sourceUrl = sourceUrl,
                markSynced = true,
            )
            if (saved.waived.isEmpty()) {
                base.copy(status = ImportResultStatus.CREATED, fileId = saved.id)
            } else {
                base.copy(
                    status = ImportResultStatus.CREATED_WITH_FINDINGS,
                    fileId = saved.id,
                    message = saved.waived.joinToString("; ") { it.message },
                )
            }
        } catch (e: CancellationException) {
            // Cancellation is not a per-document failure — a gone client must stop the batch.
            throw e
        } catch (e: BadRequestException) {
            // create()'s own rule rejections (the namespace resolution re-check) are INVALID
            // rows, exactly like the pre-flight validator's.
            base.copy(status = ImportResultStatus.INVALID, message = e.message)
        } catch (e: Exception) {
            if (e.isUniqueViolation()) {
                base.copy(
                    status = ImportResultStatus.CONFLICT,
                    message = IMPORT_CONFLICT_MESSAGE,
                )
            } else {
                base.copy(status = ImportResultStatus.ERROR, message = e.message ?: "Storage failed")
            }
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
