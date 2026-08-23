package ch.nokillswit.catalog

import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
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

data class CatalogFileListFilter(
    val name: String? = null,
    val namespace: String? = null,
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
        metadata = file.metadata,
        spec = file.spec,
        createdBy = createdBy,
        creatorName = creatorName,
        creatorDeleted = creatorDeleted,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to CatalogFileService.CatalogFiles.id,
    "name" to CatalogFileService.CatalogFiles.name,
    "namespace" to CatalogFileService.CatalogFiles.namespace,
    "updatedAt" to CatalogFileService.CatalogFiles.updatedAt,
)

class CatalogFileService(val database: R2dbcDatabase) {
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

    suspend fun create(file: CatalogFile, createdByUserId: UInt): UInt = suspendTransaction(database) {
        validate(file)
        val now = System.currentTimeMillis()
        val newRecord = CatalogFiles.insert {
            it[kind] = "Component"
            it[name] = file.metadata.name
            it[namespace] = file.metadata.namespace
            it[content] = json.encodeToString(file)
            it[createdBy] = createdByUserId
            it[createdAt] = now
            it[updatedAt] = now
        }
        newRecord[CatalogFiles.id].value
    }

    suspend fun read(id: UInt): CatalogFileDetail? = suspendTransaction(database) {
        joined().selectAll()
            .where { (CatalogFiles.id eq id) and active() }
            .map { it.toDetail() }
            .singleOrNull()
    }

    suspend fun update(id: UInt, file: CatalogFile): Int = suspendTransaction(database) {
        validate(file)
        CatalogFiles.update({ (CatalogFiles.id eq id) and (CatalogFiles.markedAsDeleted eq false) }) {
            it[name] = file.metadata.name
            it[namespace] = file.metadata.namespace
            it[content] = json.encodeToString(file)
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        CatalogFiles.update({ (CatalogFiles.id eq id) and (CatalogFiles.markedAsDeleted eq false) }) {
            it[markedAsDeleted] = true
        }
    }

    suspend fun list(filter: CatalogFileListFilter, paging: PageRequest): CatalogFileListResult =
        suspendTransaction(database) {
            val predicate: Op<Boolean> = buildPredicate(filter) and active()
            val total = CatalogFiles.selectAll().where { predicate }.count()
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
        name = file.metadata.name,
        namespace = file.metadata.namespace,
        title = file.metadata.title,
        type = file.spec.type,
        lifecycle = file.spec.lifecycle,
        owner = file.spec.owner,
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
        return op
    }

    // Same rules the route enforces (single source in CatalogFile.kt) — re-checked here so
    // direct service callers stay guarded too.
    private fun validate(file: CatalogFile) = validateCatalogFile(file)
}
