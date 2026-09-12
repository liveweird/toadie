package ch.nokillswit.blueprints

import ch.nokillswit.infra.validation.requireNoDuplicates
import ch.nokillswit.infra.validation.sanitizeSingleLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phase 1 of Toadie's move from Backstage's fixed System Model to Port.io's ontology
 * (`.claude/docs/port-data-model.md`): user-definable **blueprints** — an entity-kind
 * definition following Port's blueprint schema. Nothing here attaches entities to a
 * blueprint yet; this is a standalone ADMIN-curated registry (the `labels/`/`tags/`
 * template), with the catalog editor's live-preview split (`catalog/`) for its own page.
 *
 * Wire shape is a hybrid: a typed skeleton for everything Port gives fixed structure to,
 * and [JsonElement]/[JsonObject]/[JsonPrimitive] only for Port's genuinely open sub-trees
 * (`default`, `enum`, an object property's `properties`/`patternProperties`/
 * `additionalProperties`, and an aggregation's `query.rules`/`pathFilter`) — those are
 * stored and shape-checked only; nothing evaluates jq or JSON-Schema semantics.
 *
 * Out of scope (see the plan): Port's `teamInheritance`/`changelogDestination` (platform
 * features — carrying them is a strict 400, since the strict `DefaultJson` used for request
 * decoding already rejects unknown keys), Port's default/system blueprints (no seed), icon
 * pictures (`icon` is Port's icon NAME, a free string), and entities. Mirror/calculation/
 * aggregation properties are stored and shape-checked here only; their VALUES are evaluated per
 * entity at read time by `entities/EntityComputed.kt` (phase 5, v1.27.0) — this file never
 * evaluates jq or JSON-Schema semantics itself.
 */
const val MAX_BLUEPRINTS = 200
const val MAX_BLUEPRINT_IDENTIFIER_LENGTH = 100
const val MAX_BLUEPRINT_TITLE_LENGTH = 100
const val MAX_BLUEPRINT_DESCRIPTION_LENGTH = 2000

// Port's icon field is a free icon NAME (never a picture); capped generously alongside title.
const val MAX_BLUEPRINT_ICON_LENGTH = 100

const val MAX_BLUEPRINT_PROPERTIES = 200
const val MAX_BLUEPRINT_RELATIONS = 100
const val MAX_BLUEPRINT_MIRROR_PROPERTIES = 100
const val MAX_BLUEPRINT_CALCULATION_PROPERTIES = 100
const val MAX_BLUEPRINT_AGGREGATION_PROPERTIES = 100
const val MAX_BLUEPRINT_ENUM_ENTRIES = 200
const val MAX_BLUEPRINT_PATTERN_LENGTH = 500
const val MAX_BLUEPRINT_CALCULATION_LENGTH = 10_000

// The whole stored definition document (schema + relations + mirror/calculation/aggregation
// properties + ownership), serialized — a sanity ceiling independent of the per-family caps.
const val MAX_BLUEPRINT_DEFINITION_BYTES = 256 * 1024

/**
 * Both the STORED encoding (the `blueprints.definition` TEXT column) and every HTTP response
 * (via the route layer's `respondBlueprint` `TextContent` trick — the `respondProblem`
 * precedent, `plugins/ErrorHandling.kt:48`): unset optionals are ABSENT, never explicit
 * `null` — `DefaultJson` (installed by `plugins/Serialization.kt`) is strict and
 * `explicitNulls`, which is right for REQUEST decoding (Port-shape strictness for free) but
 * wrong for what we hand back to a Port-shaped consumer.
 */
internal val blueprintJson = Json { encodeDefaults = true; explicitNulls = false }

/** One property's open sub-schema: every field nullable — only `type` is always present. */
@Serializable
data class PropertyDefinition(
    val type: String,
    val title: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val default: JsonElement? = null,
    val format: String? = null,
    @SerialName("date_format") val dateFormat: String? = null,
    val pattern: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val enum: List<JsonPrimitive>? = null,
    val enumColors: Map<String, String>? = null,
    val spec: String? = null,
    val specAuthentication: SpecAuthentication? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val exclusiveMinimum: Double? = null,
    val exclusiveMaximum: Double? = null,
    val items: ArrayItems? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val uniqueItems: Boolean? = null,
    // Object-type only, and open JSON-Schema sub-trees: shape-checked (values must be
    // objects), never interpreted.
    val properties: JsonObject? = null,
    val patternProperties: JsonObject? = null,
    val additionalProperties: JsonElement? = null,
)

@Serializable
data class ArrayItems(
    val type: String,
    val format: String? = null,
    val enum: List<JsonPrimitive>? = null,
    val enumColors: Map<String, String>? = null,
)

@Serializable
data class SpecAuthentication(
    val authorizationUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val authorizationScope: List<String> = emptyList(),
)

@Serializable
data class BlueprintSchema(
    val properties: Map<String, PropertyDefinition> = emptyMap(),
    val required: List<String> = emptyList(),
)

// No defaults: Port always emits both booleans, and "required && many" is a validation rule
// (a relation cannot be both), not a shape one.
@Serializable
data class RelationDefinition(
    val title: String,
    val description: String? = null,
    val target: String,
    val required: Boolean,
    val many: Boolean,
)

@Serializable
data class MirrorPropertyDefinition(
    val title: String,
    val path: String,
)

@Serializable
data class CalculationPropertyDefinition(
    val title: String,
    val type: String,
    val format: String? = null,
    val spec: String? = null,
    // A jq expression — stored and shape-checked (length only) here; evaluated per entity at
    // read time by `entities/JqCalculation.kt`/`entities/EntityComputed.kt` (phase 5, v1.27.0).
    val calculation: String,
    val colorized: Boolean? = null,
    val colors: Map<String, String>? = null,
)

@Serializable
data class AggregationCalculationSpec(
    val calculationBy: String,
    val func: String,
    val property: String? = null,
    val averageOf: String? = null,
    val measureTimeBy: String? = null,
)

@Serializable
data class AggregationQuery(
    val combinator: String,
    val rules: List<JsonObject> = emptyList(),
)

@Serializable
data class AggregationPropertyDefinition(
    val title: String,
    val target: String,
    val calculationSpec: AggregationCalculationSpec,
    val query: AggregationQuery? = null,
    val pathFilter: List<JsonObject>? = null,
)

@Serializable
data class OwnershipDefinition(
    val type: String,
    val title: String? = null,
    val path: String? = null,
)

/**
 * Everything a blueprint's definition holds BESIDES the identity columns (identifier, title,
 * description, icon — denormalized in their own `blueprints` columns): the single JSON
 * document stored in `blueprints.definition` (the `catalog_files.content`/`lenses.filters`
 * precedent).
 */
@Serializable
data class BlueprintDefinition(
    val schema: BlueprintSchema = BlueprintSchema(),
    val relations: Map<String, RelationDefinition> = emptyMap(),
    val mirrorProperties: Map<String, MirrorPropertyDefinition> = emptyMap(),
    val calculationProperties: Map<String, CalculationPropertyDefinition> = emptyMap(),
    val aggregationProperties: Map<String, AggregationPropertyDefinition> = emptyMap(),
    val ownership: OwnershipDefinition? = null,
)

@Serializable
data class BlueprintRequest(
    val identifier: String,
    val title: String,
    val description: String? = null,
    val icon: String? = null,
    val schema: BlueprintSchema = BlueprintSchema(),
    val relations: Map<String, RelationDefinition> = emptyMap(),
    val mirrorProperties: Map<String, MirrorPropertyDefinition> = emptyMap(),
    val calculationProperties: Map<String, CalculationPropertyDefinition> = emptyMap(),
    val aggregationProperties: Map<String, AggregationPropertyDefinition> = emptyMap(),
    val ownership: OwnershipDefinition? = null,
    // Port migration phase 3 (.claude/docs/port-data-model.md): a Toadie-only extension, NOT
    // part of the Port document — stored beside `definition`, absent when unset (v1.32.0: a
    // MAP, one entry per PARALLEL entity hierarchy — the pre-1.32 single `hierarchyRelation`
    // named exactly one). Each key must be an ACTIVE value of the `hierarchies` dictionary
    // (checked service-side, under the V27 lock); each value must name a key of `relations`
    // above whose `many` is false (validateBlueprintRequest) — two different hierarchy keys
    // may legitimately share one relation value.
    val hierarchyRelations: Map<String, String>? = null,
)

/** The flattened request/response shape: identity columns + [BlueprintDefinition]'s fields. */
@Serializable
data class BlueprintResponse(
    val id: UInt,
    val identifier: String,
    val title: String,
    val description: String? = null,
    val icon: String? = null,
    val schema: BlueprintSchema = BlueprintSchema(),
    val relations: Map<String, RelationDefinition> = emptyMap(),
    val mirrorProperties: Map<String, MirrorPropertyDefinition> = emptyMap(),
    val calculationProperties: Map<String, CalculationPropertyDefinition> = emptyMap(),
    val aggregationProperties: Map<String, AggregationPropertyDefinition> = emptyMap(),
    val ownership: OwnershipDefinition? = null,
    val hierarchyRelations: Map<String, String>? = null,
    val createdBy: UInt,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    // Phase 4 (v1.26.0): `_team`/`_user`, seeded by V31 — response-only (no default, so a
    // request echoing it is a strict-decode 400); see blueprints/SystemBlueprints.kt.
    val system: Boolean,
)

@Serializable
data class BlueprintList(val items: List<BlueprintResponse>)

/** The non-identity fields as the stored [BlueprintDefinition] document. */
fun BlueprintRequest.toDefinition(): BlueprintDefinition = BlueprintDefinition(
    schema = schema,
    relations = relations,
    mirrorProperties = mirrorProperties,
    calculationProperties = calculationProperties,
    aggregationProperties = aggregationProperties,
    ownership = ownership,
)

/**
 * Trims the identifier/title (control characters → 400) and every nested title the same way;
 * descriptions trim without rejecting control characters (the catalog's multi-line-friendly
 * convention — descriptions may carry newlines). Map keys (property/relation/mirror/
 * calculation/aggregation ids) are never rewritten — they are identifiers, not free text, and
 * the grammar validator gives a clear 400 for anything a silent rewrite would have masked.
 */
fun sanitizedBlueprintRequest(request: BlueprintRequest): BlueprintRequest = request.copy(
    identifier = sanitizeSingleLine(request.identifier, "identifier"),
    title = sanitizeSingleLine(request.title, "title"),
    description = request.description?.trim(),
    icon = request.icon?.trim(),
    schema = request.schema.copy(properties = request.schema.properties.mapValues { (_, def) -> sanitizedProperty(def) }),
    relations = request.relations.mapValues { (_, relation) -> sanitizedRelation(relation) },
    mirrorProperties = request.mirrorProperties.mapValues { (_, mirror) -> sanitizedMirror(mirror) },
    calculationProperties = request.calculationProperties.mapValues { (_, calc) -> sanitizedCalculation(calc) },
    aggregationProperties = request.aggregationProperties.mapValues { (_, agg) -> sanitizedAggregation(agg) },
    ownership = request.ownership?.let { sanitizedOwnership(it) },
    hierarchyRelations = sanitizedHierarchyRelations(request.hierarchyRelations),
)

/**
 * Trims every value; keys are dictionary VALUES (not free-standing identifiers like every other
 * map in this request), so they are trimmed AND lowercase-folded — the same normalization
 * `dictionaries/Dictionary.kt`'s `normalizeDictionaryValue` applies to a stored hierarchy value,
 * so a differently-cased key still matches the registry lookup. An empty map normalizes to
 * `null` (absent on the wire), matching every other optional collection's "omitted means unset"
 * convention here.
 */
private fun sanitizedHierarchyRelations(hierarchyRelations: Map<String, String>?): Map<String, String>? {
    if (hierarchyRelations.isNullOrEmpty()) return null
    val folded = hierarchyRelations.entries.map { (k, v) -> k.trim().lowercase() to v.trim() }
    // Two request keys folding onto one hierarchy (`Composition` + `composition`) would otherwise
    // silently keep the last one — the codebase's rule is to REJECT duplicates, never collapse them.
    requireNoDuplicates(folded.map { it.first }, "hierarchyRelations keys must be unique")
    return folded.toMap()
}

private fun sanitizedProperty(def: PropertyDefinition): PropertyDefinition = def.copy(
    title = def.title?.let { sanitizeSingleLine(it, "schema.properties title") },
    description = def.description?.trim(),
    icon = def.icon?.trim(),
)

private fun sanitizedRelation(relation: RelationDefinition): RelationDefinition = relation.copy(
    title = sanitizeSingleLine(relation.title, "relations title"),
    description = relation.description?.trim(),
    target = relation.target.trim(),
)

private fun sanitizedMirror(mirror: MirrorPropertyDefinition): MirrorPropertyDefinition = mirror.copy(
    title = sanitizeSingleLine(mirror.title, "mirrorProperties title"),
    path = mirror.path.trim(),
)

private fun sanitizedCalculation(calc: CalculationPropertyDefinition): CalculationPropertyDefinition = calc.copy(
    title = sanitizeSingleLine(calc.title, "calculationProperties title"),
)

private fun sanitizedAggregation(agg: AggregationPropertyDefinition): AggregationPropertyDefinition = agg.copy(
    title = sanitizeSingleLine(agg.title, "aggregationProperties title"),
    target = agg.target.trim(),
)

private fun sanitizedOwnership(ownership: OwnershipDefinition): OwnershipDefinition = ownership.copy(
    title = ownership.title?.let { sanitizeSingleLine(it, "ownership title") },
    path = ownership.path?.trim(),
)
