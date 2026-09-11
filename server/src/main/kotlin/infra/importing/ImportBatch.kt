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
