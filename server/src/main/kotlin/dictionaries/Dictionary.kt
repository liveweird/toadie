package ch.nokillswit.dictionaries

import ch.nokillswit.catalog.MAX_ENTITY_PART_LENGTH
import ch.nokillswit.catalog.NAMESPACE_REGEX
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The closed set of global dictionaries (Lettuce's dictionaries, single-valued — Toadie's
 * entries are technical identifiers, so the multilingual `translations` plumbing was
 * deliberately not ported). `slug` is the public URL segment (`/api/v1/dictionaries/{slug}`);
 * the enum name is the value stored in `dictionary_entries.dictionary` (the application enum
 * is the whitelist — no DB CHECK).
 */
enum class Dictionary(val slug: String) {
    // The allowed catalog-file namespaces: every catalog-file write (create, update, import)
    // requires its namespace to be an ACTIVE entry here (CatalogFileService enforces it).
    NAMESPACE("namespaces");

    companion object {
        fun fromSlug(slug: String): Dictionary? = entries.firstOrNull { it.slug == slug }
    }
}

@Serializable
data class DictionaryEntry(val id: UInt, val value: String)

@Serializable
data class DictionaryEntryList(val items: List<DictionaryEntry>)

/** PUT item: `id` present = update that active entry in place; absent = insert a new one. */
@Serializable
data class DictionaryEntryInput(val id: UInt? = null, val value: String)

@Serializable
data class DictionaryUpdateRequest(val items: List<DictionaryEntryInput>)

const val MAX_DICTIONARY_ENTRIES = 200

/**
 * Single home of the payload rules — enforced by the route and re-checked by the service
 * (the validateCatalogFile pattern). Values are compared in their STORED form — trimmed and
 * folded to lowercase, the namespace convention (a second dictionary with different value
 * rules branches per [Dictionary] here). Because the PUT is a whole-document replace,
 * payload-level uniqueness IS post-save active-set uniqueness; the partial unique index
 * stays as the DB backstop. The value grammar is the catalog's namespace grammar
 * ([NAMESPACE_REGEX], ≤[MAX_ENTITY_PART_LENGTH] chars — also the column width).
 */
fun validateDictionaryUpdate(request: DictionaryUpdateRequest) {
    if (request.items.size > MAX_DICTIONARY_ENTRIES) {
        throw BadRequestException("A dictionary may hold at most $MAX_DICTIONARY_ENTRIES entries")
    }
    val normalized = request.items.map { normalizeDictionaryValue(it.value) }
    normalized.forEach { value ->
        if (value.length !in 1..MAX_ENTITY_PART_LENGTH || !NAMESPACE_REGEX.matches(value)) {
            throw BadRequestException(
                "Dictionary values must be 1-$MAX_ENTITY_PART_LENGTH lowercase alphanumeric characters " +
                    "with single dash separators",
            )
        }
    }
    if (normalized.size != normalized.toSet().size) {
        throw BadRequestException("Dictionary values must be unique")
    }
}

/** The stored form of a payload value: trimmed and lowercase-folded (the namespace convention). */
fun normalizeDictionaryValue(value: String): String = value.trim().lowercase()
