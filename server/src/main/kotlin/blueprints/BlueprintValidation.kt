package ch.nokillswit.blueprints

import ch.nokillswit.dictionaries.MAX_DICTIONARY_ENTRIES
import ch.nokillswit.infra.validation.requireNoDuplicates
import io.ktor.server.plugins.BadRequestException

/**
 * The Port blueprint rulebook — enforced by the route (the API 400 path) and re-checked by
 * [BlueprintService] (so direct service callers stay guarded). Cross-ROW rules (targets
 * exist, identifier uniqueness) stay in the service, which has the registry snapshot; this
 * file only ever looks at one request in isolation. Split from [PropertyValidation] because
 * the two halves grow independently — this file owns the blueprint-level rules, that one owns
 * a single property's.
 */

// Port documents no identifier regex explicitly; this is the UI's stated charset, applied
// uniformly to the blueprint identifier itself and every property/relation/mirror/
// calculation/aggregation id and relation/aggregation TARGET (see port-data-model.md).
private val BLUEPRINT_IDENTIFIER_REGEX = Regex("[A-Za-z0-9@_.:/=-]+")

private val AGGREGATION_AVERAGE_OF = setOf("hour", "day", "week", "month", "total")
private val AGGREGATION_FUNCS = setOf("count", "average", "sum", "min", "max", "median")
private val AGGREGATION_CALCULATION_BY = setOf("entities", "property")
private val AGGREGATION_ENTITIES_FUNCS = setOf("count", "average")
private val QUERY_COMBINATORS = setOf("and", "or")
private val OWNERSHIP_TYPES = setOf("Direct", "Inherited")

// `$` (the reserved meta-property prefix, e.g. `$identifier`) is not in the charset above, so
// no separate "must not start with $" check is needed anywhere this grammar is enforced —
// property/relation/mirror/calculation/aggregation ids included.
internal fun requireIdentifierGrammar(value: String, field: String, maxLength: Int = MAX_BLUEPRINT_IDENTIFIER_LENGTH) {
    if (value.length !in 1..maxLength || !BLUEPRINT_IDENTIFIER_REGEX.matches(value)) {
        throw BadRequestException("$field must be 1-$maxLength characters of [A-Za-z0-9@_.:/=-]")
    }
}

internal fun requireBlueprintLength(value: String, field: String, max: Int) {
    if (value.isEmpty() || value.length > max) {
        throw BadRequestException("$field must be 1-$max characters")
    }
}

private fun requirePathSegments(path: String, field: String, relationIds: Set<String>) {
    val segments = path.split('.')
    if (segments.size > 10 || segments.any { it.isBlank() }) {
        throw BadRequestException("$field must have 1-10 non-blank dot-separated segments")
    }
    if (segments.first() !in relationIds) {
        throw BadRequestException("$field must start with a relation of this blueprint")
    }
    segments.dropLast(1).forEach {
        if (it.startsWith("$")) throw BadRequestException("$field may only end in a meta-property")
    }
}

fun validateBlueprintRequest(request: BlueprintRequest) {
    requireIdentifierGrammar(request.identifier, "identifier")
    requireBlueprintLength(request.title, "title", MAX_BLUEPRINT_TITLE_LENGTH)
    request.description?.let { requireOptionalLength(it, "description", MAX_BLUEPRINT_DESCRIPTION_LENGTH) }
    request.icon?.let { requireOptionalLength(it, "icon", MAX_BLUEPRINT_ICON_LENGTH) }
    validateFamilyCaps(request)
    validateSchema(request.schema)
    validateIdentifierNamespace(request)
    request.schema.properties.forEach { (id, def) -> validateProperty(id, def) }
    validateRelations(request.relations)
    validateMirrorProperties(request.mirrorProperties, request.relations.keys)
    validateCalculationProperties(request.calculationProperties)
    validateAggregationProperties(request.aggregationProperties)
    request.ownership?.let { validateOwnership(it, request.relations.keys) }
    validateHierarchyRelations(request)
    validateDefinitionSize(request)
}

/**
 * A Toadie-only extension (`.claude/docs/port-data-model.md`), so this rule lives here rather
 * than in [validateDefinitionSize]'s Port-shaped byte budget: each `hierarchyRelations` entry
 * maps a hierarchy identifier to a key of THIS request's own `relations` map, and that relation
 * must be single-valued — a many-relation names a set of parents, not one, so it can never
 * define a tree. Two different hierarchy identifiers may legitimately point at the SAME
 * relation (one relation serving several parallel hierarchies at once, e.g. a `cluster`
 * relation rooting both a composition and a deployment tree). The hierarchy identifier itself
 * is only shape-checked here (non-blank, the shared entry-count cap) — whether it actually
 * names an ACTIVE `hierarchies` dictionary value is a registry lookup, checked service-side
 * under the V27 lock ([BlueprintService]), the same split as every other soft/registry rule.
 */
private fun validateHierarchyRelations(request: BlueprintRequest) {
    val hierarchyRelations = request.hierarchyRelations ?: return
    checkMax(hierarchyRelations.size, MAX_DICTIONARY_ENTRIES, "hierarchyRelations")
    hierarchyRelations.forEach { (hierarchyId, relationKey) ->
        if (hierarchyId.isBlank()) {
            throw BadRequestException("hierarchyRelations keys must not be blank")
        }
        val relation = request.relations[relationKey]
            ?: throw BadRequestException("hierarchyRelations.$hierarchyId must name a relation of this blueprint")
        if (relation.many) {
            throw BadRequestException("hierarchyRelations.$hierarchyId must name a single relation")
        }
    }
}

private fun requireOptionalLength(value: String, field: String, max: Int) {
    if (value.length > max) throw BadRequestException("$field must be at most $max characters")
}

private fun checkMax(size: Int, max: Int, field: String) {
    if (size > max) throw BadRequestException("$field must have at most $max entries")
}

private fun validateFamilyCaps(request: BlueprintRequest) {
    checkMax(request.schema.properties.size, MAX_BLUEPRINT_PROPERTIES, "schema.properties")
    checkMax(request.relations.size, MAX_BLUEPRINT_RELATIONS, "relations")
    checkMax(request.mirrorProperties.size, MAX_BLUEPRINT_MIRROR_PROPERTIES, "mirrorProperties")
    checkMax(request.calculationProperties.size, MAX_BLUEPRINT_CALCULATION_PROPERTIES, "calculationProperties")
    checkMax(request.aggregationProperties.size, MAX_BLUEPRINT_AGGREGATION_PROPERTIES, "aggregationProperties")
}

private fun validateSchema(schema: BlueprintSchema) {
    requireNoDuplicates(schema.required, "schema.required must not contain duplicates")
    schema.required.forEach {
        if (it !in schema.properties) {
            throw BadRequestException("schema.required entry '$it' is not a declared property")
        }
    }
}

/** One identifier namespace across schema.properties ∪ mirror ∪ calculation ∪ aggregation. */
private fun validateIdentifierNamespace(request: BlueprintRequest) {
    val seen = mutableSetOf<String>()
    listOf(
        request.schema.properties.keys,
        request.mirrorProperties.keys,
        request.calculationProperties.keys,
        request.aggregationProperties.keys,
    ).forEach { ids ->
        ids.forEach {
            if (!seen.add(it)) {
                throw BadRequestException(
                    "Identifier '$it' is used by more than one property/mirror/calculation/aggregation field",
                )
            }
        }
    }
}

private fun validateRelations(relations: Map<String, RelationDefinition>) {
    relations.forEach { (id, relation) ->
        requireIdentifierGrammar(id, "relations key '$id'")
        requireBlueprintLength(relation.title, "relations['$id'].title", MAX_BLUEPRINT_TITLE_LENGTH)
        requireIdentifierGrammar(relation.target, "relations['$id'].target")
        if (relation.required && relation.many) {
            throw BadRequestException("relations['$id'] cannot be both required and many")
        }
    }
}

private fun validateMirrorProperties(mirrors: Map<String, MirrorPropertyDefinition>, relationIds: Set<String>) {
    mirrors.forEach { (id, mirror) ->
        requireIdentifierGrammar(id, "mirrorProperties key '$id'")
        requireBlueprintLength(mirror.title, "mirrorProperties['$id'].title", MAX_BLUEPRINT_TITLE_LENGTH)
        requirePathSegments(mirror.path, "mirrorProperties['$id'].path", relationIds)
    }
}

private fun validateCalculationProperties(calculations: Map<String, CalculationPropertyDefinition>) {
    calculations.forEach { (id, calc) -> validateCalculationProperty(id, calc) }
}

private fun validateCalculationProperty(id: String, calc: CalculationPropertyDefinition) {
    requireIdentifierGrammar(id, "calculationProperties key '$id'")
    requireBlueprintLength(calc.title, "calculationProperties['$id'].title", MAX_BLUEPRINT_TITLE_LENGTH)
    if (calc.type !in PROPERTY_TYPES) {
        throw BadRequestException("calculationProperties['$id'].type must be one of ${PROPERTY_TYPES.joinToString()}")
    }
    validateCalculationFormatSpec(id, calc)
    if (calc.calculation.isEmpty() || calc.calculation.length > MAX_BLUEPRINT_CALCULATION_LENGTH) {
        throw BadRequestException(
            "calculationProperties['$id'].calculation must be 1-$MAX_BLUEPRINT_CALCULATION_LENGTH characters",
        )
    }
    calc.colors?.values?.forEach { requireEnumColor(it, "calculationProperties['$id'].colors") }
}

// A calculation property's `type` reuses PropertyDefinition's format/spec applicability:
// string gets the full string format/spec whitelist, object gets its own, everything else
// carries neither (mirrors PropertyValidation's per-type table, one level up).
private fun validateCalculationFormatSpec(id: String, calc: CalculationPropertyDefinition) {
    val formats = when (calc.type) {
        "string" -> STRING_FORMATS
        "object" -> OBJECT_FORMATS
        else -> emptySet()
    }
    if (calc.format != null) {
        if (calc.type !in setOf("string", "object") || calc.format !in formats) {
            throw BadRequestException("calculationProperties['$id'].format does not apply to type ${calc.type}")
        }
    }
    if (calc.spec != null) {
        if (calc.type !in setOf("string", "object") || calc.spec !in SPEC_VALUES) {
            throw BadRequestException("calculationProperties['$id'].spec does not apply to type ${calc.type}")
        }
    }
}

private fun validateAggregationProperties(aggregations: Map<String, AggregationPropertyDefinition>) {
    aggregations.forEach { (id, agg) -> validateAggregationProperty(id, agg) }
}

private fun validateAggregationProperty(id: String, agg: AggregationPropertyDefinition) {
    requireIdentifierGrammar(id, "aggregationProperties key '$id'")
    requireBlueprintLength(agg.title, "aggregationProperties['$id'].title", MAX_BLUEPRINT_TITLE_LENGTH)
    requireIdentifierGrammar(agg.target, "aggregationProperties['$id'].target")
    validateCalculationSpec(id, agg.calculationSpec)
    agg.query?.let {
        if (it.combinator !in QUERY_COMBINATORS) {
            throw BadRequestException("aggregationProperties['$id'].query.combinator must be one of and, or")
        }
    }
    // pathFilter/query.rules entries are typed List<JsonObject> — a non-object entry never
    // decodes in the first place, so no runtime shape check is needed here.
}

private fun validateCalculationSpec(id: String, spec: AggregationCalculationSpec) {
    if (spec.calculationBy !in AGGREGATION_CALCULATION_BY) {
        throw BadRequestException(
            "aggregationProperties['$id'].calculationSpec.calculationBy must be one of entities, property",
        )
    }
    if (spec.func !in AGGREGATION_FUNCS) {
        throw BadRequestException(
            "aggregationProperties['$id'].calculationSpec.func must be one of ${AGGREGATION_FUNCS.joinToString()}",
        )
    }
    validateCalculationByMatrix(id, spec)
    spec.averageOf?.let {
        if (it !in AGGREGATION_AVERAGE_OF) {
            throw BadRequestException(
                "aggregationProperties['$id'].calculationSpec.averageOf must be one of ${AGGREGATION_AVERAGE_OF.joinToString()}",
            )
        }
    }
}

private fun validateCalculationByMatrix(id: String, spec: AggregationCalculationSpec) {
    val prefix = "aggregationProperties['$id'].calculationSpec"
    when (spec.calculationBy) {
        "entities" -> {
            if (spec.func !in AGGREGATION_ENTITIES_FUNCS) {
                throw BadRequestException("$prefix.func must be one of ${AGGREGATION_ENTITIES_FUNCS.joinToString()} for entities")
            }
            if (spec.property != null) throw BadRequestException("$prefix.property must be absent for calculationBy entities")
        }
        "property" -> {
            if (spec.property == null) throw BadRequestException("$prefix.property is required for calculationBy property")
            if (spec.func == "count") throw BadRequestException("$prefix.func must not be count for calculationBy property")
        }
    }
}

private fun validateOwnership(ownership: OwnershipDefinition, relationIds: Set<String>) {
    if (ownership.type !in OWNERSHIP_TYPES) {
        throw BadRequestException("ownership.type must be one of ${OWNERSHIP_TYPES.joinToString()}")
    }
    ownership.title?.let { requireBlueprintLength(it, "ownership.title", MAX_BLUEPRINT_TITLE_LENGTH) }
    when (ownership.type) {
        "Direct" -> if (ownership.path != null) {
            throw BadRequestException("ownership.path must be absent when type is Direct")
        }
        "Inherited" -> {
            val path = ownership.path ?: throw BadRequestException("ownership.path is required when type is Inherited")
            requirePathSegments(path, "ownership.path", relationIds)
        }
    }
}

private fun validateDefinitionSize(request: BlueprintRequest) {
    val encoded = blueprintJson.encodeToString(request.toDefinition())
    val bytes = encoded.toByteArray(Charsets.UTF_8).size
    if (bytes > MAX_BLUEPRINT_DEFINITION_BYTES) {
        throw BadRequestException("The blueprint definition must be at most $MAX_BLUEPRINT_DEFINITION_BYTES bytes")
    }
}
