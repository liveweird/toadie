package ch.nokillswit.blueprints

import io.ktor.server.plugins.BadRequestException
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A single [PropertyDefinition]'s rules — split from [BlueprintValidation], which owns the
 * blueprint-level rules (family caps, cross-family identifier uniqueness, relations, mirror/
 * calculation/aggregation, ownership). Copies `catalog/CatalogFileValidation.kt`'s shape: a
 * data-driven applicability table plus one small thrower per rule, to stay well under
 * detekt's complexity/length limits despite Port's wide per-type field matrix.
 */
internal val PROPERTY_TYPES = setOf("string", "number", "boolean", "array", "object")
internal val STRING_FORMATS = setOf(
    "url", "email", "idn-email", "user", "team", "date-time", "timer", "yaml", "markdown", "proto", "ipv4", "ipv6",
)
internal val OBJECT_FORMATS = setOf("labeled-url")
internal val SPEC_VALUES = setOf("open-api", "async-api", "embedded-url")
private val DATE_FORMATS = setOf("relative", "12-hour", "24-hour", "YYYY-MM-DD HH:mm")
private val ARRAY_ITEM_TYPES = setOf("string", "number", "boolean", "object")
internal val ENUM_COLORS = setOf(
    "blue", "turquoise", "orange", "purple", "pink", "yellow", "green", "red",
    "darkGray", "lightGray", "bronze", "gold", "silver", "paleBlue",
)

internal fun requireEnumColor(value: String, field: String) {
    if (value !in ENUM_COLORS) throw BadRequestException("$field value '$value' must be one of the 14 enum colors")
}

/** One PropertyDefinition field: whether it's present on [def], and which types may carry it. */
private class PropertyField(val name: String, val isPresent: (PropertyDefinition) -> Boolean, val appliesTo: Set<String>)

private fun field(name: String, isPresent: (PropertyDefinition) -> Boolean, appliesTo: Set<String>) =
    PropertyField(name, isPresent, appliesTo)

private val PROPERTY_FIELDS: List<PropertyField> = listOf(
    field("format", { it.format != null }, setOf("string", "object")),
    field("date_format", { it.dateFormat != null }, setOf("string")),
    field("pattern", { it.pattern != null }, setOf("string")),
    field("minLength", { it.minLength != null }, setOf("string")),
    field("maxLength", { it.maxLength != null }, setOf("string")),
    field("enum", { it.enum != null }, setOf("string", "number")),
    field("enumColors", { it.enumColors != null }, setOf("string", "number")),
    field("spec", { it.spec != null }, setOf("string", "object")),
    field("specAuthentication", { it.specAuthentication != null }, setOf("string")),
    field("minimum", { it.minimum != null }, setOf("number")),
    field("maximum", { it.maximum != null }, setOf("number")),
    field("exclusiveMinimum", { it.exclusiveMinimum != null }, setOf("number")),
    field("exclusiveMaximum", { it.exclusiveMaximum != null }, setOf("number")),
    field("items", { it.items != null }, setOf("array")),
    field("minItems", { it.minItems != null }, setOf("array")),
    field("maxItems", { it.maxItems != null }, setOf("array")),
    field("uniqueItems", { it.uniqueItems != null }, setOf("array")),
    field("properties", { it.properties != null }, setOf("object")),
    field("patternProperties", { it.patternProperties != null }, setOf("object")),
    field("additionalProperties", { it.additionalProperties != null }, setOf("object")),
)

fun validateProperty(id: String, def: PropertyDefinition) {
    requireIdentifierGrammar(id, "schema.properties key '$id'")
    if (def.type !in PROPERTY_TYPES) {
        throw BadRequestException("schema.properties['$id'].type must be one of ${PROPERTY_TYPES.joinToString()}")
    }
    def.title?.let { requireBlueprintLength(it, "schema.properties['$id'].title", MAX_BLUEPRINT_TITLE_LENGTH) }
    def.description?.let {
        if (it.length > MAX_BLUEPRINT_DESCRIPTION_LENGTH) {
            throw BadRequestException(
                "schema.properties['$id'].description must be at most $MAX_BLUEPRINT_DESCRIPTION_LENGTH characters",
            )
        }
    }
    def.icon?.let {
        if (it.length > MAX_BLUEPRINT_ICON_LENGTH) {
            throw BadRequestException("schema.properties['$id'].icon must be at most $MAX_BLUEPRINT_ICON_LENGTH characters")
        }
    }
    validateFieldApplicability(id, def)
    validateByType(id, def)
    validateEnum("schema.properties['$id']", def.type, def.enum, def.enumColors)
    validateDefault(id, def)
}

private fun validateFieldApplicability(id: String, def: PropertyDefinition) {
    for (propertyField in PROPERTY_FIELDS) {
        if (propertyField.isPresent(def) && def.type !in propertyField.appliesTo) {
            throw BadRequestException("schema.properties['$id'].${propertyField.name} does not apply to type ${def.type}")
        }
    }
}

private fun validateByType(id: String, def: PropertyDefinition) {
    when (def.type) {
        "string" -> validateStringProperty(id, def)
        "number" -> validateNumberProperty(id, def)
        "array" -> validateArrayProperty(id, def)
        "object" -> validateObjectProperty(id, def)
    }
}

private fun validateStringProperty(id: String, def: PropertyDefinition) {
    def.format?.let {
        if (it !in STRING_FORMATS) {
            throw BadRequestException("schema.properties['$id'].format must be one of ${STRING_FORMATS.joinToString()}")
        }
    }
    if (def.dateFormat != null && def.format != "date-time") {
        throw BadRequestException("schema.properties['$id'].date_format only applies with format date-time")
    }
    def.dateFormat?.let {
        if (it !in DATE_FORMATS) {
            throw BadRequestException("schema.properties['$id'].date_format must be one of ${DATE_FORMATS.joinToString()}")
        }
    }
    def.pattern?.let { requireValidPattern(id, it) }
    validateMinMaxLength(id, def.minLength, def.maxLength)
    def.spec?.let {
        if (it !in SPEC_VALUES) {
            throw BadRequestException("schema.properties['$id'].spec must be one of ${SPEC_VALUES.joinToString()}")
        }
    }
    def.specAuthentication?.let { validateSpecAuthentication(id, def.spec, it) }
}

private fun requireValidPattern(id: String, pattern: String) {
    if (pattern.isEmpty() || pattern.length > MAX_BLUEPRINT_PATTERN_LENGTH) {
        throw BadRequestException("schema.properties['$id'].pattern must be 1-$MAX_BLUEPRINT_PATTERN_LENGTH characters")
    }
    runCatching { Regex(pattern) }
        .onFailure { throw BadRequestException("schema.properties['$id'].pattern is not a valid regular expression") }
}

private fun validateMinMaxLength(id: String, minLength: Int?, maxLength: Int?) {
    if (minLength != null && minLength < 0) {
        throw BadRequestException("schema.properties['$id'].minLength must be >= 0")
    }
    if (minLength != null && maxLength != null && minLength > maxLength) {
        throw BadRequestException("schema.properties['$id'].minLength must be <= maxLength")
    }
}

private fun validateSpecAuthentication(id: String, spec: String?, auth: SpecAuthentication) {
    if (spec == null) throw BadRequestException("schema.properties['$id'].specAuthentication requires spec")
    if (!isAbsoluteUrl(auth.authorizationUrl) || !isAbsoluteUrl(auth.tokenUrl)) {
        throw BadRequestException("schema.properties['$id'].specAuthentication urls must be absolute")
    }
}

private fun isAbsoluteUrl(value: String): Boolean = try {
    value.isNotEmpty() && URI(value).isAbsolute
} catch (_: URISyntaxException) {
    false
}

private fun validateNumberProperty(id: String, def: PropertyDefinition) {
    if (def.minimum != null && def.maximum != null && def.minimum > def.maximum) {
        throw BadRequestException("schema.properties['$id'].minimum must be <= maximum")
    }
    validateExclusiveBound(id, "minimum", "exclusiveMinimum", def.minimum, def.exclusiveMinimum)
    validateExclusiveBound(id, "maximum", "exclusiveMaximum", def.maximum, def.exclusiveMaximum)
    if (def.exclusiveMinimum != null && def.exclusiveMaximum != null && def.exclusiveMinimum >= def.exclusiveMaximum) {
        throw BadRequestException("schema.properties['$id'].exclusiveMinimum must be < exclusiveMaximum")
    }
}

private fun validateExclusiveBound(id: String, field: String, exclusiveField: String, inclusive: Double?, exclusive: Double?) {
    if (inclusive != null && exclusive != null) {
        throw BadRequestException("schema.properties['$id'] must not set both $field and $exclusiveField")
    }
}

private fun validateArrayProperty(id: String, def: PropertyDefinition) {
    val items = def.items ?: throw BadRequestException("schema.properties['$id'].items is required for type array")
    if (items.type !in ARRAY_ITEM_TYPES) {
        throw BadRequestException("schema.properties['$id'].items.type must be one of ${ARRAY_ITEM_TYPES.joinToString()}")
    }
    if (items.format != null && items.type != "string") {
        throw BadRequestException("schema.properties['$id'].items.format only applies to string items")
    }
    items.format?.let {
        if (it !in STRING_FORMATS) {
            throw BadRequestException("schema.properties['$id'].items.format must be one of ${STRING_FORMATS.joinToString()}")
        }
    }
    validateEnum("schema.properties['$id'].items", items.type, items.enum, items.enumColors)
    validateMinMaxItems(id, def.minItems, def.maxItems)
}

private fun validateMinMaxItems(id: String, minItems: Int?, maxItems: Int?) {
    if (minItems != null && minItems < 0) {
        throw BadRequestException("schema.properties['$id'].minItems must be >= 0")
    }
    if (minItems != null && maxItems != null && minItems > maxItems) {
        throw BadRequestException("schema.properties['$id'].minItems must be <= maxItems")
    }
}

private fun validateObjectProperty(id: String, def: PropertyDefinition) {
    def.format?.let {
        if (it !in OBJECT_FORMATS) {
            throw BadRequestException("schema.properties['$id'].format must be one of ${OBJECT_FORMATS.joinToString()}")
        }
    }
    def.spec?.let {
        if (it !in SPEC_VALUES) {
            throw BadRequestException("schema.properties['$id'].spec must be one of ${SPEC_VALUES.joinToString()}")
        }
    }
    def.properties?.let { requireJsonObjectValues(id, "properties", it) }
    def.patternProperties?.let { requireJsonObjectValues(id, "patternProperties", it) }
    def.additionalProperties?.let {
        val isBoolean = it is JsonPrimitive && it.booleanOrNull != null
        if (!isBoolean && it !is JsonObject) {
            throw BadRequestException("schema.properties['$id'].additionalProperties must be a boolean or an object")
        }
    }
}

private fun requireJsonObjectValues(id: String, field: String, obj: JsonObject) {
    obj.values.forEach {
        if (it !is JsonObject) throw BadRequestException("schema.properties['$id'].$field values must be objects")
    }
}

/** Non-empty, entries match [type], no duplicates, `enumColors` keys ⊆ enum, colors ∈ the 14. */
internal fun validateEnum(field: String, type: String, enum: List<JsonPrimitive>?, colors: Map<String, String>?) {
    if (enum == null) {
        if (colors != null) throw BadRequestException("$field.enumColors requires enum")
        return
    }
    if (enum.isEmpty()) throw BadRequestException("$field.enum must not be empty")
    if (enum.size > MAX_BLUEPRINT_ENUM_ENTRIES) {
        throw BadRequestException("$field.enum must have at most $MAX_BLUEPRINT_ENUM_ENTRIES entries")
    }
    enum.forEach { entry ->
        if (!enumEntryMatchesType(type, entry)) throw BadRequestException("$field.enum entries must be of type $type")
    }
    val values = enum.map { it.content }
    if (values.size != values.toSet().size) throw BadRequestException("$field.enum must not contain duplicates")
    colors?.let { validateEnumColors(field, values.toSet(), it) }
}

private fun validateEnumColors(field: String, enumValues: Set<String>, colors: Map<String, String>) {
    colors.forEach { (key, value) ->
        if (key !in enumValues) throw BadRequestException("$field.enumColors key '$key' is not in enum")
        requireEnumColor(value, "$field.enumColors")
    }
}

private fun enumEntryMatchesType(type: String, entry: JsonPrimitive): Boolean = when (type) {
    "string" -> entry.isString
    "number" -> !entry.isString && entry.doubleOrNull != null
    else -> false
}

/** JSON kind matches `type`; array elements match `items.type`; with an enum, default ∈ enum. */
internal fun validateDefault(id: String, def: PropertyDefinition) {
    val default = def.default ?: return
    if (!defaultMatchesType(def.type, default)) {
        throw BadRequestException("schema.properties['$id'].default does not match type ${def.type}")
    }
    if (default is JsonArray) validateDefaultArrayElements(id, def.items?.type, default)
    def.enum?.let { enum ->
        val enumValues = enum.map { it.content }.toSet()
        if (default is JsonPrimitive && default.content !in enumValues) {
            throw BadRequestException("schema.properties['$id'].default must be one of the declared enum values")
        }
    }
}

private fun defaultMatchesType(type: String, default: JsonElement): Boolean = when (type) {
    "string" -> default is JsonPrimitive && default.isString
    "number" -> default is JsonPrimitive && !default.isString && default.doubleOrNull != null
    "boolean" -> default is JsonPrimitive && default.booleanOrNull != null
    "array" -> default is JsonArray
    "object" -> default is JsonObject
    else -> false
}

private fun validateDefaultArrayElements(id: String, itemsType: String?, default: JsonArray) {
    if (itemsType == null) return
    default.forEach { element ->
        if (element is JsonPrimitive && !enumEntryMatchesArrayItemType(itemsType, element)) {
            throw BadRequestException("schema.properties['$id'].default array elements must be of type $itemsType")
        }
    }
}

private fun enumEntryMatchesArrayItemType(itemsType: String, element: JsonPrimitive): Boolean = when (itemsType) {
    "string" -> element.isString
    "number" -> !element.isString && element.doubleOrNull != null
    "boolean" -> element.booleanOrNull != null
    // object items are never JsonPrimitive, so this branch is unreachable for them; treat
    // anything else as unchecked rather than duplicating the object-shape rule here.
    else -> true
}
