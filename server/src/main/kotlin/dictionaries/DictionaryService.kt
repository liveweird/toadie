package ch.nokillswit.dictionaries

import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val DictionaryServiceKey = AttributeKey<DictionaryService>("DictionaryService")

/** What a whole-document replace actually did — carried into the `dictionary.updated` audit event. */
data class DictionaryReplaceCounts(val added: Int, val renamed: Int, val removed: Int)

class DictionaryService(private val database: R2dbcDatabase) {
    object Entries : UIntIdTable("dictionary_entries") {
        // Per-dictionary value uniqueness is enforced by the partial unique index
        // uq_dictionary_entries_value_active (active rows only; V7), so a soft-deleted
        // entry frees its value. Exposed table defs are query-only (not DDL), so no
        // `.uniqueIndex()` here.
        val dictionary = varchar("dictionary", length = 30)
        val position = integer("position")
        val value = varchar("value", length = 63)

        // At most one active default per dictionary — backstopped by the V9 partial unique
        // index; the exactly-one rule for non-empty documents lives in validateDictionaryUpdate.
        val isDefault = bool("is_default").default(false)
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private fun active(): Op<Boolean> = Entries.markedAsDeleted eq false

    /** The dictionary's active entries in the admin-curated order (id as deterministic tiebreaker). */
    suspend fun read(dict: Dictionary): List<DictionaryEntry> = suspendTransaction(database) {
        Entries.selectAll()
            .where { (Entries.dictionary eq dict.name) and active() }
            .orderBy(Entries.position to SortOrder.ASC, Entries.id to SortOrder.ASC)
            .map { row ->
                DictionaryEntry(
                    id = row[Entries.id].value,
                    value = row[Entries.value],
                    isDefault = row[Entries.isDefault],
                )
            }
            .toList()
    }

    /**
     * Whole-document replace (Lettuce's dictionary idiom): an item carrying an `id` updates
     * that active entry in place (rename keeps identity), an id-less item inserts, and an
     * active entry missing from the payload is soft-deleted — never physically removed.
     * Positions are rewritten from payload order — the payload's array order IS the order;
     * there is no reorder endpoint. Known inherited limitation: swapping two values in one
     * save trips the partial unique index mid-statement (23505 → 409) — rename through a
     * temporary value in two saves instead.
     */
    suspend fun replace(dict: Dictionary, request: DictionaryUpdateRequest): DictionaryReplaceCounts =
        suspendTransaction(database) {
            validateDictionaryUpdate(request)

            // Snapshot the ACTIVE rows only (id -> stored value): a payload id pointing at a
            // soft-deleted entry is a foreign id (400) — deleted entries are never
            // resurrected; re-adding the same value mints a NEW id.
            val existing: Map<UInt, String> =
                Entries.select(Entries.id, Entries.value)
                    .where { (Entries.dictionary eq dict.name) and active() }
                    .map { it[Entries.id].value to it[Entries.value] }
                    .toList()
                    .toMap()

            val payloadIds = request.items.mapNotNull { it.id }
            requirePayloadIds(payloadIds, existing.keys)

            // Soft-delete FIRST: frees those values under the partial unique index before the
            // upserts run, so "remove X + add new X" and "rename onto a just-removed value"
            // succeed in one save. The dead rows keep their stale position on purpose.
            val toSoftDelete = existing.keys - payloadIds.toSet()
            if (toSoftDelete.isNotEmpty()) {
                Entries.update({ Entries.id inList toSoftDelete }) { it[markedAsDeleted] = true }
            }

            // Clear every active default flag BEFORE the upserts: moving the flag between two
            // rows in one save would otherwise transiently hold two flagged rows mid-statement
            // and trip the V9 partial unique index (the value-swap 409, but for the core flow).
            Entries.update({ (Entries.dictionary eq dict.name) and active() }) { it[isDefault] = false }

            request.items.forEachIndexed { index, item ->
                val normalized = normalizeDictionaryValue(item.value)
                if (item.id != null) {
                    Entries.update({ (Entries.id eq item.id) and active() }) {
                        it[position] = index
                        it[value] = normalized
                        it[isDefault] = item.isDefault
                    }
                } else {
                    Entries.insert {
                        it[dictionary] = dict.name
                        it[position] = index
                        it[value] = normalized
                        it[isDefault] = item.isDefault
                    }
                }
            }

            DictionaryReplaceCounts(
                added = request.items.count { it.id == null },
                renamed = request.items.count {
                    it.id != null && existing[it.id] != normalizeDictionaryValue(it.value)
                },
                removed = toSoftDelete.size,
            )
        }

    private fun requirePayloadIds(payloadIds: List<UInt>, existingIds: Set<UInt>) {
        if (payloadIds.size != payloadIds.toSet().size) {
            throw BadRequestException("Duplicate entry id in payload")
        }
        val foreign = payloadIds.filterNot { it in existingIds }
        if (foreign.isNotEmpty()) {
            throw BadRequestException("Unknown entry id(s) for this dictionary: ${foreign.joinToString()}")
        }
    }
}
