package ch.nokillswit.tags

import ch.nokillswit.authz.ConflictException
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val TagCategoryServiceKey = AttributeKey<TagCategoryService>("TagCategoryService")

class TagCategoryService(private val database: R2dbcDatabase) {
    object TagCategories : UIntIdTable("tag_categories") {
        // Case-folded name uniqueness is enforced by the partial unique index
        // uq_tag_categories_name_active (active rows only; V11), so a soft-deleted category
        // frees its name. Exposed table defs are query-only (not DDL), so no `.uniqueIndex()`.
        val name = varchar("name", length = MAX_CATEGORY_NAME_LENGTH)

        // JSON arrays in TEXT (the labels/V10 precedent): a category's kind/tag lists are
        // replaced whole on every save — no child-table reconcile.
        val allowedKinds = text("allowed_kinds")
        val tags = text("tags")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private val json = Json

    private fun active(): Op<Boolean> = TagCategories.markedAsDeleted eq false

    private fun rowToResponse(id: UInt, name: String, kindsJson: String, tagsJson: String) = TagCategoryResponse(
        id = id,
        name = name,
        tags = json.decodeFromString(tagsJson),
        kinds = json.decodeFromString(kindsJson),
    )

    /** Every active category, name-ordered case-insensitively (id as deterministic tiebreaker). */
    suspend fun list(): List<TagCategoryResponse> = suspendTransaction(database) {
        TagCategories.selectAll()
            .where { active() }
            .orderBy(TagCategories.name.lowerCase() to SortOrder.ASC, TagCategories.id to SortOrder.ASC)
            .map {
                rowToResponse(
                    it[TagCategories.id].value,
                    it[TagCategories.name],
                    it[TagCategories.allowedKinds],
                    it[TagCategories.tags],
                )
            }
            .toList()
    }

    /**
     * A tag belongs to exactly ONE category — enforced here, in the write's own transaction,
     * against every OTHER active category (case-folded). Service-side only: tags live inside
     * the JSON array, so no index can back it (accepted for an ADMIN-curated registry).
     */
    private suspend fun requireTagsUnclaimed(request: TagCategoryRequest, excludeId: UInt?) {
        val folded = request.tags.map { it.lowercase() }.toSet()
        var predicate: Op<Boolean> = active()
        excludeId?.let { predicate = predicate and (TagCategories.id neq it) }
        TagCategories.selectAll()
            .where { predicate }
            .map { it[TagCategories.name] to json.decodeFromString<List<String>>(it[TagCategories.tags]) }
            .toList()
            .forEach { (owner, tags) ->
                tags.firstOrNull { it.lowercase() in folded }?.let {
                    throw ConflictException("Tag '$it' already belongs to category '$owner'")
                }
            }
    }

    suspend fun create(request: TagCategoryRequest): UInt = suspendTransaction(database) {
        validateTagCategoryRequest(request) // re-checked service-side so direct callers stay guarded
        if (TagCategories.selectAll().where { active() }.count() >= MAX_TAG_CATEGORIES) {
            throw BadRequestException("The tag-category registry is full ($MAX_TAG_CATEGORIES categories)")
        }
        requireTagsUnclaimed(request, excludeId = null)
        val newRecord = TagCategories.insert {
            it[name] = request.name
            it[allowedKinds] = json.encodeToString(request.kinds)
            it[tags] = json.encodeToString(request.tags)
        }
        newRecord[TagCategories.id].value
    }

    /**
     * Whole-category replace, rename included — a stored file carrying a removed tag simply
     * goes strict-invalid on its next save (the no-grandfathering rule). Moving a tag between
     * categories is remove-then-add in TWO saves (adding it first trips the one-category 409).
     * Returns the affected-row count (0 → the route's 404).
     */
    suspend fun update(id: UInt, request: TagCategoryRequest): Int = suspendTransaction(database) {
        validateTagCategoryRequest(request) // re-checked service-side so direct callers stay guarded
        // Existence first: a missing/deleted target must 404 like every sibling registry —
        // without this check, a claimed tag in the payload would answer 409 for a row that
        // isn't there (the tag-claim check is service-side, not index-raised like the others).
        val exists = TagCategories.selectAll()
            .where { (TagCategories.id eq id) and active() }
            .count() > 0
        if (!exists) return@suspendTransaction 0
        requireTagsUnclaimed(request, excludeId = id)
        TagCategories.update({ (TagCategories.id eq id) and (TagCategories.markedAsDeleted eq false) }) {
            it[name] = request.name
            it[allowedKinds] = json.encodeToString(request.kinds)
            it[tags] = json.encodeToString(request.tags)
        }
    }

    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        TagCategories.update({ (TagCategories.id eq id) and (TagCategories.markedAsDeleted eq false) }) {
            it[markedAsDeleted] = true
        }
    }
}
