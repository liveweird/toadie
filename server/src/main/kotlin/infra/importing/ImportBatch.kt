package ch.nokillswit.infra.importing

import ch.nokillswit.blueprints.blueprintJson
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Shared vocabulary for the ontology bulk-import endpoints (blueprints/entities — phase 6 of
 * Toadie's Port data-model move, v1.28.0; see `.claude/docs/port-data-model.md` "Import and
 * export"). The `catalog/Import.kt`/`catalog/CatalogFileImport.kt` precedent, one level up:
 * report-and-skip, ONE shared classification for the real run and its dry-run. Unlike the
 * catalog import (which decodes its whole batch as one typed `List<CatalogFile>` via
 * ContentNegotiation), a blueprint/entity document is decoded PER ROW from a raw [JsonObject] so
 * one malformed document is that row's `INVALID`, never a whole-request `400` — only a
 * non-object array element (which can never decode to `JsonObject` at all) is a request-level
 * `400`, thrown by ContentNegotiation before either planner ever runs.
 */
const val MAX_IMPORT_DOCUMENTS = 200

@Serializable
enum class OntologyImportStatus {
    /** Stored as a brand-new row. */
    CREATED,

    /** An existing row was replaced in place (`replaceExisting: true`). */
    UPDATED,

    /** An existing row was found but `replaceExisting` is off — nothing stored (renders gray, not red: by design, not a failure). */
    EXISTS,

    /** Failed shape/registry/reference validation, or a cycle through a mandatory reference. */
    INVALID,

    /** An in-batch duplicate of an earlier document in this same request. */
    CONFLICT,

    /** An unexpected storage failure, or a pass-2 residual (stored WITHOUT its deferred parts — a concurrent-change residual). */
    ERROR,
}

/**
 * Every decode failure reports this FIXED message rather than the real exception text — the
 * kotlinx/`SerializationException` message carries internal structure (FQCNs, field paths),
 * the same MT-007 concern `plugins/ErrorHandling.kt` documents for the whole-request path.
 */
const val IMPORT_SCHEMA_MESSAGE = "Document does not match the expected schema"

/**
 * Strict per-row decode: null on any decode failure (never throws), so the caller can classify
 * the row `INVALID` with [IMPORT_SCHEMA_MESSAGE] instead of letting the exception escape and
 * fail the whole batch. Reuses [blueprintJson] (`explicitNulls = false`, `ignoreUnknownKeys =
 * false`) — the same strict Port-shape decode `BlueprintRequest`/`EntityRequest` already use.
 */
internal inline fun <reified T> decodeDocument(obj: JsonObject): T? = try {
    blueprintJson.decodeFromJsonElement(obj)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** Best-effort identifier/blueprint for a row that failed to decode — a raw string member, else null. */
fun rawString(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun requireBatchSize(size: Int) {
    if (size > MAX_IMPORT_DOCUMENTS) {
        throw BadRequestException("documents must have at most $MAX_IMPORT_DOCUMENTS entries")
    }
}

/**
 * One [orderWithDeferral] run's result: the write order, each index's deferred sibling keys,
 * and — if [onForced] rejected a forced pick — where and why it stopped.
 */
data class OrderedBatch<K>(val order: List<Int>, val deferredByIndex: Map<Int, Set<K>>, val stoppedAt: Pair<Int, String>?)

/**
 * The Kahn topological-sort skeleton shared by `blueprints/BlueprintImport.kt`'s
 * `orderCandidates` and `entities/EntityImport.kt`'s `attemptOrdering`: [indices] are the
 * candidate positions still to place; [dependencies] maps each index to the sibling KEYS it
 * references (a blueprint identifier, or an entity's `(blueprint, identifier)` pair) — already
 * filtered by the caller to keys naming another candidate BEING STORED IN THIS BATCH, since an
 * already-registered target is always satisfied; [keyIndex] resolves a key back to ITS OWN index
 * so this function can track satisfaction internally as it places nodes, without either caller
 * exposing its `satisfied` bookkeeping.
 *
 * Each step picks the lowest-index READY candidate (every dependency already placed); when
 * nothing is ready it force-picks the lowest remaining index instead (input-order tie-breaking,
 * the ONE fairness rule both planners share), computes THAT index's still-unmet dependencies,
 * and offers [onForced] the chance to reject the whole ordering rather than deferring —
 * returning a non-null message stops the sort immediately and reports it as
 * [OrderedBatch.stoppedAt] (the entity planner's mandatory-reference-cycle rejection). The
 * blueprint planner's callback never rejects, so every forced pick there is simply deferred to
 * a second write pass. The per-kind edge collection ([dependencies]) and what a stop MEANS to
 * the caller stay out of this function on purpose — it only runs the mechanical loop.
 */
fun <K> orderWithDeferral(
    indices: List<Int>,
    dependencies: Map<Int, List<K>>,
    keyIndex: Map<K, Int>,
    onForced: (index: Int, deferred: Set<K>) -> String? = { _, _ -> null },
): OrderedBatch<K> {
    val satisfied = mutableSetOf<Int>()
    val remaining = indices.toMutableList()
    val order = mutableListOf<Int>()
    val deferredByIndex = mutableMapOf<Int, Set<K>>()
    fun isSatisfied(key: K) = keyIndex[key]?.let { it in satisfied } ?: true

    while (remaining.isNotEmpty()) {
        val ready = remaining.filter { idx -> dependencies[idx].orEmpty().all(::isSatisfied) }
        val pick = if (ready.isNotEmpty()) ready.min() else remaining.min()
        if (ready.isEmpty()) {
            val deferred = dependencies[pick].orEmpty().filterNot(::isSatisfied).toSet()
            val violation = onForced(pick, deferred)
            if (violation != null) return OrderedBatch(order, deferredByIndex, pick to violation)
            deferredByIndex[pick] = deferred
        }
        order += pick
        satisfied += pick
        remaining.remove(pick)
    }
    return OrderedBatch(order, deferredByIndex, stoppedAt = null)
}
