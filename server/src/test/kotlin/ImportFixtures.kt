package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.CatalogLink
import ch.nokillswit.catalog.EntityProfile
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.catalog.StoredIdentity
import ch.nokillswit.conversion.EntityDocumentRow
import ch.nokillswit.conversion.EntityDocumentsResponse
import ch.nokillswit.conversion.ImportInput
import ch.nokillswit.conversion.MappingDocument
import ch.nokillswit.conversion.PortDirectory
import ch.nokillswit.conversion.importEntityDocuments
import ch.nokillswit.entities.StoredEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Helpers for the Backstage -> Port import tests ([PortImportTest], [ImportCodecsTest]): the same
 * [ExportFixtures] world (blueprints `svc`, `sys`, `dat`, `_team`, `usr`, `plain`, none of them the
 * real ontology), a pure [importDocs] over documents built with [doc], and a [stored] Port entity
 * for the merge cases. No database.
 */
internal object ImportFixtures {
    private var nextId = 5000u

    fun doc(
        kind: String,
        name: String,
        namespace: String = "default",
        title: String? = null,
        description: String? = null,
        labels: Map<String, String> = emptyMap(),
        annotations: Map<String, String> = emptyMap(),
        tags: List<String> = emptyList(),
        links: List<CatalogLink> = emptyList(),
        spec: EntitySpec = EntitySpec(),
    ) = CatalogFile(
        kind,
        CatalogFileMetadata(name, namespace, title, description, labels, annotations, tags, links),
        spec,
    )

    fun group(name: String, parent: String? = null, namespace: String = "default") =
        doc("Group", name, namespace, spec = EntitySpec(type = "team", parent = parent, children = emptyList()))

    fun user(name: String, memberOf: List<String> = emptyList(), email: String? = null) =
        doc("User", name, spec = EntitySpec(memberOf = memberOf, profile = email?.let { EntityProfile(email = it) }))

    fun system(name: String, owner: String = "group:default/squad", type: String = "product", namespace: String = "default") =
        doc("System", name, namespace, spec = EntitySpec(type = type, owner = owner))

    fun component(name: String, owner: String = "group:default/squad", spec: EntitySpec = EntitySpec(), namespace: String = "default") =
        doc(
            "Component", name, namespace,
            spec = spec.copy(type = spec.type ?: "service", lifecycle = spec.lifecycle ?: "production", owner = spec.owner ?: owner),
        )

    fun stored(
        blueprint: String,
        identifier: String,
        title: String = identifier,
        icon: String? = null,
        team: JsonElement? = null,
        properties: JsonObject = ExportFixtures.json(),
        relations: JsonObject = ExportFixtures.json(),
    ) = StoredEntity(nextId++, blueprint, identifier, title, icon, team, properties, relations)

    fun storedIdentity(kind: String, name: String, namespace: String = "default", spec: Map<String, String> = emptyMap()) =
        StoredIdentity(kind, namespace, name, spec)

    fun importDocs(
        files: List<CatalogFile>,
        existing: List<StoredEntity> = emptyList(),
        stored: List<StoredIdentity> = emptyList(),
        merge: Boolean = true,
        mapping: MappingDocument = ExportFixtures.mapping,
        defaultNamespace: String = "default",
        blueprints: Map<String, ch.nokillswit.blueprints.BlueprintDefinition> = ExportFixtures.blueprints,
        /** The `updated_at` stamp of each [existing] entity by id; an entity not listed reports none. */
        updatedAt: Map<UInt, Long> = emptyMap(),
    ): EntityDocumentsResponse {
        val directory = PortDirectory { blueprint, identifier ->
            existing.firstOrNull { it.blueprint == blueprint && it.identifier.equals(identifier, ignoreCase = true) }?.identifier
        }
        return importEntityDocuments(
            ImportInput(
                mapping = mapping,
                blueprints = blueprints,
                registry = ExportFixtures.registry,
                defaultNamespace = defaultNamespace,
                files = files,
                stored = stored,
                directory = directory,
                existingId = { blueprint, identifier ->
                    existing.firstOrNull { it.blueprint == blueprint && it.identifier.equals(identifier, ignoreCase = true) }?.id
                },
                existingUpdatedAt = { blueprint, identifier ->
                    existing.firstOrNull { it.blueprint == blueprint && it.identifier.equals(identifier, ignoreCase = true) }
                        ?.let { updatedAt[it.id] }
                },
                merge = merge,
            ),
            if (merge) existing.associateBy { it.id } else null,
        )
    }

    /** The entity document of ([blueprint], [identifier]) in [response]. */
    fun EntityDocumentsResponse.document(blueprint: String, identifier: String): JsonObject =
        assertNotNull(
            documents.firstOrNull {
                it.getValue("blueprint").jsonPrimitive().content == blueprint &&
                    it.getValue("identifier").jsonPrimitive().content == identifier
            },
            "no document for $blueprint/$identifier in ${documents.map { it["blueprint"].toString() + "/" + it["identifier"] }}",
        )

    fun EntityDocumentsResponse.row(name: String, kind: String? = null): EntityDocumentRow =
        rows.single { it.name == name && (kind == null || it.kind == kind) }

    fun JsonObject.properties() = getValue("properties").jsonObject
    fun JsonObject.relations() = getValue("relations").jsonObject

    private fun JsonElement.jsonPrimitive() = this as JsonPrimitive

    fun assertAllConverted(response: EntityDocumentsResponse) {
        val withFindings = response.rows.filter { it.findings.isNotEmpty() }.map { it.name to it.findings }
        assertEquals(emptyList(), withFindings, "no row should carry findings")
    }
}
