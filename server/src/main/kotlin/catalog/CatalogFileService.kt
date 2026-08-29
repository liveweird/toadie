package ch.nokillswit.catalog

import ch.nokillswit.annotations.AnnotationKeyService
import ch.nokillswit.dictionaries.Dictionary
import ch.nokillswit.dictionaries.DictionaryService
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.jsonArrayContains
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

data class CatalogFileListFilter(
    val name: String? = null,
    val namespace: String? = null,
    /** Canonical-cased kind (the route validates against SUPPORTED_KINDS). */
    val kind: String? = null,
    /** Exact tag membership against metadata.tags (folded — tags are stored lowercase). */
    val tag: String? = null,
)

data class CatalogFileListResult(
    val items: List<CatalogFileListItem>,
    val total: Long,
)

/** A stored file with its envelope (creator resolved via join, timestamps). */
data class CatalogFileDetail(
    val id: UInt,
    val file: CatalogFile,
    val createdBy: UInt,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
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
     * the LIFECYCLE dictionary, annotation keys), taken inside the calling write/report
     * transaction. The rules themselves are the pure `registryFindings` in CrossCheck.kt:
     * every value must be allowed by its ADMIN-curated registry for the file's kind
     * (byte-exact, no grandfathering; an empty registry allows nothing). A violation is a
     * SOFT finding — it rejects a strict save but is waivable via `allowInvalid` (see
     * [softFindings]).
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
        return RegistrySnapshot(
            labels = labels,
            annotationKeys = annotationKeys,
            tags = tags,
            types = types,
            lifecycles = lifecycles,
        )
    }

    private fun CatalogFile.withNamespace(resolved: String): CatalogFile =
        if (metadata.namespace == resolved) this else copy(metadata = metadata.copy(namespace = resolved))

    /**
     * The write path's SOFT checks, as findings: every entity reference resolved against the
     * active workspace (the ONE rulebook in CrossCheck.kt — per-field default kinds, allowed
     * target kinds, contextual namespace, never the document ITSELF) plus the registry checks
     * against [loadRegistrySnapshot]. Runs inside the write's own transaction. A strict save
     * rejects any finding with ONE aggregated 400; `allowInvalid=true` waives them all and
     * stores anyway (the cross-check report is the net for what was waived).
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
    ): CatalogFileSaveResult = suspendTransaction(database) {
        validateCatalogFile(file) // re-checked service-side so direct callers stay guarded
        // The stored row AND the content JSON both carry the resolved concrete namespace.
        val stored = file.withNamespace(resolvedNamespace(file.metadata.namespace))
        val findings = requireOrWaive(stored, extraIdentities, allowInvalid)
        val now = System.currentTimeMillis()
        val newRecord = CatalogFiles.insert {
            it[kind] = stored.kind
            it[name] = stored.metadata.name
            it[namespace] = stored.metadata.namespace
            it[content] = json.encodeToString(stored)
            it[createdBy] = createdByUserId
            it[createdAt] = now
            it[updatedAt] = now
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

    suspend fun update(id: UInt, file: CatalogFile, allowInvalid: Boolean = false): CatalogFileUpdateResult =
        suspendTransaction(database) {
            validateCatalogFile(file) // re-checked service-side so direct callers stay guarded
            val stored = file.withNamespace(resolvedNamespace(file.metadata.namespace))
            val findings = requireOrWaive(stored, emptySet(), allowInvalid)
            val rows = CatalogFiles.update({ (CatalogFiles.id eq id) and (CatalogFiles.markedAsDeleted eq false) }) {
                it[kind] = stored.kind
                it[name] = stored.metadata.name
                it[namespace] = stored.metadata.namespace
                it[content] = json.encodeToString(stored)
                it[updatedAt] = System.currentTimeMillis()
            }
            CatalogFileUpdateResult(rows = rows, waived = findings)
        }

    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        CatalogFiles.update({ (CatalogFiles.id eq id) and (CatalogFiles.markedAsDeleted eq false) }) {
            it[markedAsDeleted] = true
        }
    }

    /** The workspace cross-check report — all active files loaded and resolved in ONE transaction. */
    suspend fun crossCheck(): CrossCheckReport = suspendTransaction(database) {
        crossCheckAll(activeSources(), loadRegistrySnapshot())
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
     * The rendered-together graph — all active files in one transaction. A [namespace] narrows
     * which files' references are EXPANDED; targets still resolve against the whole workspace
     * (a stored file elsewhere appears as a STORED node when pointed at).
     */
    suspend fun graph(namespace: String?): CatalogGraph = suspendTransaction(database) {
        val all = activeSources()
        val folded = namespace?.lowercase()
        val rendered = if (folded == null) all else all.filter { it.file.metadata.namespace.lowercase() == folded }
        buildGraph(sources = rendered, allSources = all)
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
     * incrementally (the cross-check report tracks them). Only structural validation and
     * namespace resolution still skip a document as INVALID. Nothing rethrows except
     * cancellation, so the batch always runs to completion and the result rows ARE the
     * outcome. The route emits the audit events for the stored rows (the repo convention:
     * audits live route-side).
     */
    suspend fun import(files: List<CatalogFile>, createdByUserId: UInt): List<ImportFileResult> {
        // The batch universe: sibling documents resolve against each other ORDER-INDEPENDENTLY
        // (a real export's entities are interdependent — the round trip must survive). Only
        // documents that sanitize, validate, and namespace-resolve contribute an identity.
        // Documented residual: a document referencing a sibling that later fails to STORE
        // (identity conflict) keeps its dangling reference — the same class as a
        // deletion-created dangling ref, and the cross-check report catches it.
        val batchIdentities = files.mapNotNull { raw ->
            val sanitized = sanitizedCatalogFile(raw)
            try {
                validateCatalogFile(sanitized)
                identityOf(resolveNamespace(sanitized))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // the document will produce its own INVALID/ERROR row below
            }
        }.toSet()
        return files.mapIndexed { index, raw -> importOne(index, raw, createdByUserId, batchIdentities) }
    }

    private suspend fun importOne(
        index: Int,
        raw: CatalogFile,
        createdByUserId: UInt,
        batchIdentities: Set<EntityIdentity>,
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
            val saved = create(file, createdByUserId, batchIdentities, allowInvalid = true)
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
                    message = "An active catalog file with this kind, namespace, and name already exists",
                )
            } else {
                base.copy(status = ImportResultStatus.ERROR, message = e.message ?: "Storage failed")
            }
        }
    }

    private suspend fun activeSources(): List<CrossCheckSource> =
        CatalogFiles.selectAll()
            .where { active() }
            .map { CrossCheckSource(id = it[CatalogFiles.id].value, file = json.decodeFromString(it[CatalogFiles.content])) }
            .toList()

    suspend fun list(filter: CatalogFileListFilter, paging: PageRequest): CatalogFileListResult =
        suspendTransaction(database) {
            // Counted on the same join as the rows, so the two can never disagree.
            val predicate: Op<Boolean> = buildPredicate(filter) and active()
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
    )

    private fun buildPredicate(filter: CatalogFileListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.name?.takeIf { it.isNotBlank() }?.let {
            op = op and (CatalogFiles.name.containsNormalized(it))
        }
        filter.namespace?.takeIf { it.isNotBlank() }?.let {
            // Stored namespaces are lowercase (sanitizedCatalogFile) — fold the filter too.
            op = op and (CatalogFiles.namespace eq it.lowercase())
        }
        filter.kind?.let {
            op = op and (CatalogFiles.kind eq it)
        }
        filter.tag?.takeIf { it.isNotBlank() }?.let {
            // Exact membership inside the content JSON (tags are stored lowercase — fold).
            op = op and CatalogFiles.content.jsonArrayContains(listOf("metadata", "tags"), it.lowercase())
        }
        return op
    }
}
