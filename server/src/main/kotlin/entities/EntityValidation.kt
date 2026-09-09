package ch.nokillswit.entities

import ch.nokillswit.blueprints.ArrayItems
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.blueprints.enumEntryMatchesArrayItemType
import ch.nokillswit.blueprints.isAbsoluteUrl
import ch.nokillswit.blueprints.jsonMatchesType
import ch.nokillswit.blueprints.requireIdentifierGrammar
import io.ktor.server.plugins.BadRequestException
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double

/**
 * The entity rulebook — split like phase 1 into request-SHAPE rules (this file, enforced by the
 * route AND re-checked by [EntityService] so direct callers stay guarded) and the PURE, DB-free
 * [entityFindings] rule table (never throws — the strict-save 400 and the stale-marker GET/list
 * read the exact same list, so they cannot disagree; see the plan's rule table in
 * `.claude/docs/port-data-model.md`). Both layers stay well under detekt's complexity/length
 * limits by following `PropertyValidation.kt`'s shape: one small function per rule.
 */

// Port's entity identifier pattern (WIDER than a blueprint identifier: unicode letters, `+`,
// `'`, `\`) — see the plan's "Port's entity wire shape" section.
private val ENTITY_IDENTIFIER_REGEX = Regex("[\\p{L}0-9@_.+:\\\\/='-]+")

private fun requireEntityIdentifier(value: String, field: String) {
    val validPattern = value.length in 1..MAX_ENTITY_IDENTIFIER_LENGTH && ENTITY_IDENTIFIER_REGEX.matches(value)
    if (!validPattern || value == "." || value == "..") {
        throw BadRequestException("$field must be 1-$MAX_ENTITY_IDENTIFIER_LENGTH characters and not '.' or '..'")
    }
}

fun validateEntityRequest(request: EntityRequest) {
    // A blueprint identifier follows the blueprint grammar (1-100 chars of [A-Za-z0-9@_.:/=-]),
    // not the wider entity identifier pattern above.
    requireIdentifierGrammar(request.blueprint, "blueprint")
    requireEntityIdentifier(request.identifier, "identifier")
    if (request.title.isEmpty() || request.title.length > MAX_ENTITY_TITLE_LENGTH) {
        throw BadRequestException("title must be 1-$MAX_ENTITY_TITLE_LENGTH characters")
    }
    request.icon?.let {
        if (it.length > MAX_ENTITY_ICON_LENGTH) throw BadRequestException("icon must be at most $MAX_ENTITY_ICON_LENGTH characters")
    }
    validateTeam(request.team)
    request.properties.keys.forEach { requireIdentifierGrammar(it, "properties key '$it'") }
    request.relations.keys.forEach { requireIdentifierGrammar(it, "relations key '$it'") }
    validateDocumentSize(request)
}

private fun validateTeam(team: JsonElement?) {
    when (team) {
        null -> Unit
        is JsonPrimitive -> requireTeamString(team)
        is JsonArray -> validateTeamArray(team)
        else -> throw BadRequestException("team must be a string or an array of strings")
    }
}

private fun validateTeamArray(team: JsonArray) {
    if (team.size > MAX_ENTITY_TEAM_ENTRIES) throw BadRequestException("team must have at most $MAX_ENTITY_TEAM_ENTRIES entries")
    team.forEach { entry ->
        if (entry !is JsonPrimitive) throw BadRequestException("team entries must be strings")
        requireTeamString(entry)
    }
}

private fun requireTeamString(value: JsonPrimitive) {
    if (!value.isString || value.content.isEmpty() || value.content.length > MAX_ENTITY_TEAM_LENGTH) {
        throw BadRequestException("team entries must be non-blank strings of at most $MAX_ENTITY_TEAM_LENGTH characters")
    }
}

private fun validateDocumentSize(request: EntityRequest) {
    val encoded = blueprintJson.encodeToString(request.toDocument())
    val bytes = encoded.toByteArray(Charsets.UTF_8).size
    if (bytes > MAX_ENTITY_DOCUMENT_BYTES) {
        throw BadRequestException("The entity document must be at most $MAX_ENTITY_DOCUMENT_BYTES bytes")
    }
}

// ---------------------------------------------------------------------------------------------
// entityFindings: the PURE rule table over a decoded document + its blueprint's definition.
// ---------------------------------------------------------------------------------------------

/**
 * Every [EntityFinding] a strict save would reject and GET/list attach as-is (the `softFindings`
 * precedent) — computed against [definition] (the entity's OWN blueprint) and [targetExists], a
 * closure over one snapshot of active entities per targeted blueprint (`EntityService` builds it
 * once per call). Never throws.
 */
fun entityFindings(
    document: EntityDocument,
    definition: BlueprintDefinition,
    targetExists: (blueprintIdentifier: String, entityIdentifier: String) -> Boolean,
): List<EntityFinding> = propertyFindings(document.properties, definition) + relationFindings(document.relations, definition, targetExists)

private fun computedPropertyIds(definition: BlueprintDefinition): Set<String> =
    definition.mirrorProperties.keys + definition.calculationProperties.keys + definition.aggregationProperties.keys

private fun propertyFindings(properties: JsonObject, definition: BlueprintDefinition): List<EntityFinding> {
    val findings = mutableListOf<EntityFinding>()
    val computed = computedPropertyIds(definition)
    properties.forEach { (id, value) ->
        val field = "properties.$id"
        val propertyDef = definition.schema.properties[id]
        if (propertyDef == null) {
            findings += unknownPropertyFinding(id, field, computed)
        } else {
            findings += propertyValueFindings(field, propertyDef, value)
        }
    }
    definition.schema.required.forEach { id ->
        if (id !in properties) findings += EntityFinding("REQUIRED_MISSING", "properties.$id", "Required property '$id' is missing")
    }
    return findings
}

private fun unknownPropertyFinding(id: String, field: String, computed: Set<String>): EntityFinding =
    if (id in computed) {
        EntityFinding("COMPUTED_PROPERTY", field, "'$id' is a computed property and cannot be set")
    } else {
        EntityFinding("UNKNOWN_PROPERTY", field, "'$id' is not a declared property of this blueprint")
    }

private fun propertyValueFindings(field: String, def: PropertyDefinition, value: JsonElement): List<EntityFinding> {
    if (!jsonMatchesType(def.type, value)) {
        return listOf(EntityFinding("TYPE_MISMATCH", field, "Value does not match type ${def.type}"))
    }
    return when (def.type) {
        "string" -> stringFindings(field, def, value as JsonPrimitive) + enumFindings(field, def, value)
        "number" -> numberFindings(field, def, value as JsonPrimitive) + enumFindings(field, def, value)
        "array" -> arrayFindings(field, def, value as JsonArray)
        "object" -> objectFindings(field, def, value as JsonObject)
        else -> emptyList()
    }
}

private fun enumFindings(field: String, def: PropertyDefinition, value: JsonPrimitive): List<EntityFinding> {
    val enum = def.enum ?: return emptyList()
    return if (value.content in enum.map { it.content }) {
        emptyList()
    } else {
        listOf(EntityFinding("ENUM_MISMATCH", field, "Value must be one of the declared enum values"))
    }
}

private fun stringFindings(field: String, def: PropertyDefinition, value: JsonPrimitive): List<EntityFinding> {
    val findings = mutableListOf<EntityFinding>()
    val length = value.content.codePointCount(0, value.content.length)
    def.minLength?.let { if (length < it) findings += EntityFinding("LENGTH_OUT_OF_RANGE", field, "Value must be at least $it characters") }
    def.maxLength?.let { if (length > it) findings += EntityFinding("LENGTH_OUT_OF_RANGE", field, "Value must be at most $it characters") }
    def.pattern?.let {
        if (!Regex(it).containsMatchIn(value.content)) {
            findings += EntityFinding("PATTERN_MISMATCH", field, "Value does not match the required pattern")
        }
    }
    checkStringFormat(field, def.format, value.content)?.let { findings += it }
    return findings
}

private fun checkStringFormat(field: String, format: String?, value: String): EntityFinding? {
    if (format == null) return null
    val valid = when (format) {
        "url" -> isAbsoluteUrl(value)
        "email", "idn-email" -> isValidEmail(value)
        "date-time" -> runCatching { OffsetDateTime.parse(value) }.isSuccess
        "timer" -> runCatching { Instant.parse(value) }.isSuccess
        "ipv4" -> isValidIpv4(value)
        "ipv6" -> isValidIpv6(value)
        // user/team/yaml/markdown/proto: free text, no format check.
        else -> true
    }
    return if (valid) null else EntityFinding("FORMAT_INVALID", field, "Value does not match format $format")
}

private fun isValidEmail(value: String): Boolean {
    val at = value.lastIndexOf('@')
    if (at <= 0 || at == value.length - 1) return false
    return value.substring(0, at).isNotEmpty() && value.substring(at + 1).isNotEmpty()
}

private fun isValidIpv4(value: String): Boolean {
    val parts = value.split(".")
    if (parts.size != 4) return false
    return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 && it.toString() == part } ?: false }
}

// A conservative charset guard before InetAddress parsing, so a bare hostname is never handed
// to it: InetAddress.getByName only avoids DNS for a literal address, and this keeps the
// characters it sees restricted to what an IPv6 (optionally IPv4-embedded) literal ever uses.
private val IPV6_LITERAL_CHARSET = Regex("^[0-9A-Fa-f:.]+$")

private fun isValidIpv6(value: String): Boolean {
    if (!value.contains(':') || !IPV6_LITERAL_CHARSET.matches(value)) return false
    return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
}

private fun numberFindings(field: String, def: PropertyDefinition, value: JsonPrimitive): List<EntityFinding> {
    val number = value.double
    val findings = mutableListOf<EntityFinding>()
    def.minimum?.let { if (number < it) findings += EntityFinding("RANGE_OUT_OF_BOUNDS", field, "Value must be >= $it") }
    def.maximum?.let { if (number > it) findings += EntityFinding("RANGE_OUT_OF_BOUNDS", field, "Value must be <= $it") }
    def.exclusiveMinimum?.let { if (number <= it) findings += EntityFinding("RANGE_OUT_OF_BOUNDS", field, "Value must be > $it") }
    def.exclusiveMaximum?.let { if (number >= it) findings += EntityFinding("RANGE_OUT_OF_BOUNDS", field, "Value must be < $it") }
    return findings
}

private fun arrayFindings(field: String, def: PropertyDefinition, value: JsonArray): List<EntityFinding> {
    val findings = mutableListOf<EntityFinding>()
    def.items?.let { items -> findings += arrayItemFindings(field, items, value) }
    def.minItems?.let { if (value.size < it) findings += EntityFinding("ARRAY_SIZE", field, "Array must have at least $it items") }
    def.maxItems?.let { if (value.size > it) findings += EntityFinding("ARRAY_SIZE", field, "Array must have at most $it items") }
    if (def.uniqueItems == true && value.toSet().size != value.size) {
        findings += EntityFinding("ARRAY_NOT_UNIQUE", field, "Array items must be unique")
    }
    return findings
}

private fun arrayItemFindings(field: String, items: ArrayItems, value: JsonArray): List<EntityFinding> {
    val findings = mutableListOf<EntityFinding>()
    value.forEachIndexed { index, element ->
        if (element !is JsonPrimitive || !enumEntryMatchesArrayItemType(items.type, element)) {
            findings += EntityFinding("TYPE_MISMATCH", field, "Array element $index does not match type ${items.type}")
            return@forEachIndexed
        }
        items.enum?.let { enum ->
            if (element.content !in enum.map { it.content }) {
                findings += EntityFinding("ENUM_MISMATCH", field, "Array element $index must be one of the declared enum values")
            }
        }
        if (items.type == "string") checkStringFormat(field, items.format, element.content)?.let { findings += it }
    }
    return findings
}

private fun objectFindings(field: String, def: PropertyDefinition, value: JsonObject): List<EntityFinding> {
    if (def.format != "labeled-url") return emptyList()
    val url = value["url"]
    val displayText = value["displayText"]
    val urlOk = url is JsonPrimitive && url.isString && isAbsoluteUrl(url.content)
    val displayOk = displayText == null || (displayText is JsonPrimitive && displayText.isString)
    val extraKeys = value.keys - setOf("url", "displayText")
    return if (urlOk && displayOk && extraKeys.isEmpty()) {
        emptyList()
    } else {
        listOf(EntityFinding("OBJECT_SHAPE", field, "Value must be {url, displayText?} with an absolute url"))
    }
}

private fun relationFindings(
    relations: JsonObject,
    definition: BlueprintDefinition,
    targetExists: (String, String) -> Boolean,
): List<EntityFinding> {
    val findings = mutableListOf<EntityFinding>()
    relations.forEach { (id, value) ->
        val field = "relations.$id"
        val relationDef = definition.relations[id]
        if (relationDef == null) {
            findings += EntityFinding("UNKNOWN_RELATION", field, "'$id' is not a declared relation of this blueprint")
        } else {
            findings += relationValueFindings(field, relationDef, value, targetExists)
        }
    }
    definition.relations.forEach { (id, relationDef) ->
        if (relationDef.required && !relationHasValue(relations[id], relationDef.many)) {
            findings += EntityFinding("RELATION_REQUIRED", "relations.$id", "Relation '$id' is required")
        }
    }
    return findings
}

private fun relationHasValue(value: JsonElement?, many: Boolean): Boolean = when {
    value == null -> false
    many -> value is JsonArray && value.isNotEmpty()
    else -> true
}

private fun relationValueFindings(
    field: String,
    def: RelationDefinition,
    value: JsonElement,
    targetExists: (String, String) -> Boolean,
): List<EntityFinding> {
    val targets = relationTargets(def, value) ?: return listOf(relationShapeFinding(field, def))
    return targets.mapNotNull { target ->
        if (targetExists(def.target, target)) null else {
            EntityFinding("RELATION_TARGET_MISSING", field, "Related entity '$target' of blueprint '${def.target}' does not exist")
        }
    }
}

private fun relationShapeFinding(field: String, def: RelationDefinition) = EntityFinding(
    "RELATION_SHAPE",
    field,
    if (def.many) "Relation must be an array of distinct strings" else "Relation must be a string",
)

/** Null when the shape itself is wrong (a `RELATION_SHAPE` finding); the target list otherwise. */
private fun relationTargets(def: RelationDefinition, value: JsonElement): List<String>? = if (def.many) {
    manyRelationTargets(value)
} else if (value is JsonPrimitive && value.isString) {
    listOf(value.content)
} else {
    null
}

private fun manyRelationTargets(value: JsonElement): List<String>? {
    if (value !is JsonArray || value.any { it !is JsonPrimitive || !it.isString }) return null
    val values = value.map { (it as JsonPrimitive).content }
    return if (values.size == values.toSet().size) values else null
}
