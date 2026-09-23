package ch.nokillswit.catalog

import ch.nokillswit.annotations.AnnotationKeyService
import ch.nokillswit.dictionaries.Dictionary
import ch.nokillswit.dictionaries.DictionaryService
import ch.nokillswit.labels.LabelService
import ch.nokillswit.tags.TagCategoryService
import ch.nokillswit.types.EntityTypesService
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.selectAll

/**
 * Reads the ADMIN-curated registries used by catalog validation.
 *
 * These functions deliberately do not open transactions. The caller owns the transaction so a
 * write, import dry-run, or Errors report observes its registry data in the same snapshot as the
 * catalog rows it validates.
 */
internal class CatalogRegistryReader(private val json: Json) {
    /** The active NAMESPACE entry flagged as the default, or null when none is flagged. */
    suspend fun flaggedDefaultNamespace(): String? =
        DictionaryService.Entries.selectAll()
            .where {
                (DictionaryService.Entries.dictionary eq Dictionary.NAMESPACE.name) and
                    (DictionaryService.Entries.isDefault eq true) and
                    (DictionaryService.Entries.markedAsDeleted eq false)
            }
            .map { it[DictionaryService.Entries.value] }
            .toList()
            .singleOrNull()

    /** Resolve a stored namespace against the active NAMESPACE dictionary. */
    suspend fun resolveNamespace(namespace: String): String {
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

    /** Load one validation snapshot of every catalog registry. */
    suspend fun loadSnapshot(): RegistrySnapshot {
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
        val lifecycles = dictionaryValues(Dictionary.LIFECYCLE)
        val namespaces = dictionaryValues(Dictionary.NAMESPACE)
        return RegistrySnapshot(
            labels = labels,
            annotationKeys = annotationKeys,
            tags = tags,
            types = types,
            lifecycles = lifecycles,
            namespaces = namespaces,
        )
    }

    private suspend fun dictionaryValues(dictionary: Dictionary): Set<String> =
        DictionaryService.Entries.selectAll()
            .where {
                (DictionaryService.Entries.dictionary eq dictionary.name) and
                    (DictionaryService.Entries.markedAsDeleted eq false)
            }
            .map { it[DictionaryService.Entries.value] }
            .toList()
            .toSet()
}
