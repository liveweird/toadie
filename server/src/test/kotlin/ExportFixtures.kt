package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.CatalogSource
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.catalog.RegistrySnapshot
import ch.nokillswit.conversion.CatalogDocumentsResponse
import ch.nokillswit.conversion.ExportInput
import ch.nokillswit.conversion.MappingDocument
import ch.nokillswit.conversion.exportCatalogDocuments
import ch.nokillswit.conversion.parseMappingDocument
import ch.nokillswit.entities.ExportEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertNotNull

/**
 * A small hand-built Port world for the conversion export tests ([CodecsTest], [BackstageExportTest]):
 * blueprints named `svc`, `sys`, `dat`, `_team`, `usr` and `plain`, none of them the real ontology —
 * the engine must work for ANY mapping, so its tests must not lean on the sample's names. The
 * mapping is one document ([MAPPING]) the tests parse once; [convert] runs the pure export over a
 * list of entities built with [entity].
 */
internal object ExportFixtures {
    private fun blueprint(json: String): BlueprintDefinition = blueprintJson.decodeFromString(json)

    val blueprints: Map<String, BlueprintDefinition> = mapOf(
        "svc" to blueprint(
            """
            {"schema": {"properties": {
               "type": {"type": "string", "enum": ["service", "website"]},
               "lifecycle": {"type": "string", "enum": ["experimental", "production"]},
               "repo": {"type": "string", "format": "url"},
               "tier": {"type": "string", "enum": ["gold", "silver"]},
               "pii": {"type": "boolean"},
               "stack": {"type": "array", "uniqueItems": true, "items": {"type": "string", "enum": ["java", "kotlin", "sql"]}},
               "links": {"type": "array", "items": {"type": "object"}},
               "note": {"type": "string"},
               "replicas": {"type": "number"},
               "ext": {"type": "boolean"},
               "ci": {"type": "string", "format": "url"},
               "blurb": {"type": "string"}}},
             "relations": {
               "system": {"title": "System", "target": "sys", "required": false, "many": false},
               "deps": {"title": "Deps", "target": "svc", "required": false, "many": true},
               "uses": {"title": "Uses", "target": "dat", "required": false, "many": true}},
             "ownership": {"type": "Direct"}}
            """.trimIndent(),
        ),
        "sys" to blueprint(
            """
            {"schema": {"properties": {"type": {"type": "string", "enum": ["product", "capability"]}, "ext": {"type": "boolean"}}},
             "relations": {"parent": {"title": "Parent", "target": "sys", "required": false, "many": false}},
             "ownership": {"type": "Direct"}}
            """.trimIndent(),
        ),
        "dat" to blueprint(
            """
            {"schema": {"properties": {"type": {"type": "string"}}},
             "relations": {"system": {"title": "System", "target": "sys", "required": false, "many": false},
                           "deps": {"title": "Deps", "target": "dat", "required": false, "many": true}},
             "ownership": {"type": "Direct"}}
            """.trimIndent(),
        ),
        "_team" to blueprint(
            """
            {"schema": {"properties": {"type": {"type": "string", "enum": ["team"]}}},
             "relations": {"parent": {"title": "Parent", "target": "_team", "required": false, "many": false}}}
            """.trimIndent(),
        ),
        "usr" to blueprint(
            """
            {"schema": {"properties": {"mail": {"type": "string"}}},
             "relations": {"team": {"title": "Team", "target": "_team", "required": false, "many": true}}}
            """.trimIndent(),
        ),
        "lib" to blueprint("""{"schema": {"properties": {}}, "ownership": {"type": "Direct"}}"""),
        "plain" to blueprint("""{"schema": {"properties": {"name": {"type": "string"}}}}"""),
    )

    val registry = RegistrySnapshot(
        labels = mapOf(
            "tier" to (listOf("Component") to listOf("gold", "silver")),
            "pii" to (listOf("Component") to listOf("yes", "no")),
        ),
        annotationKeys = mapOf("example.com/source" to listOf("Component")),
        tags = mapOf(
            "java" to ("Languages" to listOf("Component")),
            "kotlin" to ("Languages" to listOf("Component")),
            "sql" to ("Languages" to listOf("Component")),
        ),
        types = mapOf("Component" to listOf("service", "website"), "System" to listOf("product", "capability"), "Group" to listOf("team")),
        lifecycles = setOf("experimental", "production"),
        namespaces = setOf("default", "external"),
    )

    /** The fixture mapping: every codec at least once, two ref rules onto `dependsOn`, derive onto `children`/`members`. */
    const val MAPPING = """
    {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
      {"blueprint": "svc", "kind": "Component", "rules": [
        {"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "copy"},
        {"port": {"property": "lifecycle"}, "backstage": {"spec": "lifecycle"}, "codec": "copy"},
        {"port": {"meta": "title"}, "backstage": {"metadata": "title"}, "codec": "copy"},
        {"port": {"property": "blurb"}, "backstage": {"metadata": "description"}, "codec": "copy"},
        {"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"},
        {"port": {"property": "repo"}, "backstage": {"annotation": "example.com/source"}, "codec": "copy", "prefix": "url:"},
        {"port": {"property": "tier"}, "backstage": {"label": "tier"}, "codec": "copy"},
        {"port": {"property": "pii"}, "backstage": {"label": "pii"}, "codec": "valueMap", "map": {"true": "yes", "false": "no"}},
        {"port": {"property": "stack"}, "backstage": {"tags": "Languages"}, "codec": "tagCategory"},
        {"port": {"property": "links"}, "backstage": {"links": "rest"}, "codec": "copy"},
        {"port": {"property": "ci"}, "backstage": {"link": {"title": "CI", "icon": "dashboard"}}, "codec": "link"},
        {"port": {"property": "ext"}, "backstage": {"metadata": "namespace"}, "codec": "valueMap", "map": {"true": "external", "false": "default"}},
        {"port": {"relation": "system"}, "backstage": {"spec": "system"}, "codec": "ref"},
        {"port": {"relation": "deps"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"},
        {"port": {"relation": "uses"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}
      ], "lossy": {"properties": ["note", "replicas"],
                   "backstage": ["spec.subcomponentOf", "spec.providesApis", "spec.consumesApis", "spec.dependencyOf"]}},
      {"blueprint": "sys", "kind": "System", "rules": [
        {"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "copy"},
        {"port": {"meta": "title"}, "backstage": {"metadata": "title"}, "codec": "copy"},
        {"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"},
        {"port": {"property": "ext"}, "backstage": {"metadata": "namespace"}, "codec": "valueMap", "map": {"true": "external", "false": "default"}}
      ], "lossy": {"relations": ["parent"], "backstage": ["metadata.description", "spec.domain"]}},
      {"blueprint": "dat", "kind": "Resource", "rules": [
        {"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "copy", "exportDefault": "database"},
        {"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"},
        {"port": {"relation": "system"}, "backstage": {"spec": "system"}, "codec": "ref"},
        {"port": {"relation": "deps"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}
      ], "lossy": {"backstage": ["metadata.title", "metadata.description", "spec.dependencyOf"]}},
      {"blueprint": "_team", "kind": "Group", "rules": [
        {"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "copy"},
        {"port": {"meta": "title"}, "backstage": {"profile": "displayName"}, "codec": "copy", "direction": "export"},
        {"port": {"relation": "parent"}, "backstage": {"spec": "parent"}, "codec": "ref"},
        {"port": {"reverse": {"blueprint": "_team", "relation": "parent"}}, "backstage": {"spec": "children"}, "codec": "derive"},
        {"port": {"reverse": {"blueprint": "usr", "relation": "team"}}, "backstage": {"spec": "members"}, "codec": "derive"}
      ], "lossy": {"backstage": ["metadata.title", "metadata.description", "profile.email", "profile.picture"]}},
      {"blueprint": "lib", "kind": "Component", "select": {"spec": "type", "in": ["library"]}, "rules": [
        {"backstage": {"spec": "type"}, "codec": "constant", "value": "library"},
        {"backstage": {"spec": "lifecycle"}, "codec": "constant", "value": "production"},
        {"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"}
      ], "lossy": {"backstage": ["metadata.title", "metadata.description", "spec.system", "spec.subcomponentOf",
                                 "spec.providesApis", "spec.consumesApis", "spec.dependsOn", "spec.dependencyOf"]}},
      {"blueprint": "usr", "kind": "User", "rules": [
        {"port": {"property": "mail"}, "backstage": {"profile": "email"}, "codec": "copy"},
        {"port": {"relation": "team"}, "backstage": {"spec": "memberOf"}, "codec": "ref"}
      ], "lossy": {"backstage": ["metadata.title", "metadata.description", "profile.displayName", "profile.picture"]}}
    ]}
    """

    val mapping: MappingDocument by lazy { parse(MAPPING) }

    fun parse(text: String): MappingDocument = parse(Json.parseToJsonElement(text))

    fun parse(element: JsonElement): MappingDocument {
        val parsed = parseMappingDocument(element)
        return assertNotNull(parsed.document, "fixture mapping must parse: ${parsed.findings}")
    }

    /** The fixture mapping without the pair of [blueprint] — leaves its entities unmapped and every reference to them dangling. */
    fun withoutPair(blueprint: String): MappingDocument {
        val root = Json.parseToJsonElement(MAPPING).jsonObject
        val pairs = root.getValue("pairs").jsonArray.filter { it.jsonObject.getValue("blueprint").jsonPrimitive.content != blueprint }
        return parse(JsonObject(root + ("pairs" to JsonArray(pairs))))
    }

    /** Builds a JSON object from `"key" to value` pairs: strings, booleans, numbers, lists of strings, null, or a ready [JsonElement]. */
    fun json(vararg members: Pair<String, Any?>): JsonObject = JsonObject(members.associate { (k, v) -> k to element(v) })

    private fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::element))
        else -> error("unsupported fixture value $value")
    }

    private var nextId = 1u

    fun entity(
        blueprint: String,
        identifier: String,
        title: String = identifier,
        properties: JsonObject = JsonObject(emptyMap()),
        relations: JsonObject = JsonObject(emptyMap()),
        team: List<String> = emptyList(),
        shown: Boolean = true,
        icon: String? = null,
    ) = ExportEntity(nextId++, blueprint, identifier, title, icon, team, properties, relations, shown)

    fun file(
        kind: String,
        name: String,
        namespace: String = "default",
        metadata: CatalogFileMetadata = CatalogFileMetadata(name = name, namespace = namespace),
        spec: EntitySpec = EntitySpec(),
    ) = CatalogFile(kind, metadata.copy(name = name, namespace = namespace), spec)

    fun convert(
        entities: List<ExportEntity>,
        mapping: MappingDocument = this.mapping,
        existing: List<CatalogSource>? = null,
        defaultNamespace: String = "default",
        registry: RegistrySnapshot = this.registry,
    ): CatalogDocumentsResponse = exportCatalogDocuments(
        ExportInput(mapping, blueprints, registry, defaultNamespace, entities, existing),
    )
}
