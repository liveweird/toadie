package ch.nokillswit.conversion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal const val MAX_MAPPING_PAIRS = 100
internal const val MAX_PAIR_RULES = 200
internal const val MAX_MAPPING_STRING_LENGTH = 2000
internal const val MAX_VALUE_MAP_ENTRIES = 200
internal const val MAX_MAPPING_LIST_ENTRIES = 400

/** [document] is non-null only when the structure is fully well-formed ([findings] empty). */
internal class ParsedMapping(val document: MappingDocument?, val findings: List<MappingFinding>)

/**
 * Reads a JSON value into the typed [MappingDocument] model, STRICTLY: unknown members, wrong
 * types, JSON `null` anywhere, out-of-range sizes and the reserved `jq` codec are all `SCHEMA`
 * findings with a JSON-pointer path — never an exception, and every defect in the document is
 * reported in one pass (a rule's mistakes do not hide the next pair's). Semantic checks against
 * the live blueprints and registries are `MappingValidation.kt`'s job and only run on a document
 * this parser accepted.
 */
internal fun parseMappingDocument(element: JsonElement): ParsedMapping {
    val reader = MappingReader()
    val document = reader.document(element)
    return ParsedMapping(if (reader.findings.isEmpty()) document else null, reader.findings.toList())
}

/** RFC 6901: `~` -> `~0`, `/` -> `~1`, one `/segment` per step. */
internal fun pointer(base: String, segment: Any): String =
    "$base/${segment.toString().replace("~", "~0").replace("/", "~1")}"

private val NO_LOSSY = Lossy(emptyList(), emptyList(), emptyList())
private val DOCUMENT_MEMBERS = setOf("schemaVersion", "apiVersion", "pairs")
private val PAIR_MEMBERS = setOf("blueprint", "kind", "select", "rules", "lossy")
private val RULE_MEMBERS = setOf("port", "backstage", "codec", "direction", "prefix", "exportDefault", "importIgnore", "map", "value")
private val PORT_ADDRESS_MEMBERS = setOf("meta", "property", "relation", "reverse")
private val BACKSTAGE_ADDRESS_MEMBERS = setOf("metadata", "label", "annotation", "tags", "links", "link", "profile", "spec")

/** The option members each codec defines — a rule may carry only its own codec's. */
private val CODEC_OPTIONS = mapOf(
    "copy" to setOf("prefix", "exportDefault", "importIgnore"),
    "valueMap" to setOf("map"),
    "tagCategory" to emptySet(),
    "ref" to emptySet(),
    "derive" to emptySet(),
    "link" to emptySet(),
    "constant" to setOf("value"),
)

private class MappingReader {
    val findings = mutableListOf<MappingFinding>()

    fun fail(path: String, message: String) {
        findings += MappingFinding(MappingFindingCode.SCHEMA, path, message)
    }

    fun document(element: JsonElement): MappingDocument? {
        val root = objectAt(element, "") ?: return null
        unknownMembers(root, "", DOCUMENT_MEMBERS)
        val version = schemaVersion(root)
        val apiVersion = string(root["apiVersion"], "/apiVersion", required = true)
        if (apiVersion != null && apiVersion != MAPPING_API_VERSION) {
            fail("/apiVersion", "apiVersion must be $MAPPING_API_VERSION")
        }
        val pairs = list(root["pairs"], "/pairs", required = true, max = MAX_MAPPING_PAIRS) { el, path -> pair(el, path) }
        if (version == null || apiVersion == null || pairs == null) return null
        return MappingDocument(version, apiVersion, pairs)
    }

    private fun schemaVersion(root: JsonObject): Int? {
        val element = root["schemaVersion"]
        if (element == null) {
            fail("/schemaVersion", "schemaVersion is required")
            return null
        }
        val value = (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (value == null) {
            fail("/schemaVersion", "schemaVersion must be an integer")
            return null
        }
        if (value != MAPPING_SCHEMA_VERSION) {
            fail("/schemaVersion", "unsupported schemaVersion $value (supported: $MAPPING_SCHEMA_VERSION)")
            return null
        }
        return value
    }

    private fun pair(element: JsonElement, path: String): MappingPair? {
        val obj = objectAt(element, path) ?: return null
        unknownMembers(obj, path, PAIR_MEMBERS)
        val blueprint = string(obj["blueprint"], pointer(path, "blueprint"), required = true)
        val kind = string(obj["kind"], pointer(path, "kind"), required = true)
        // Optional parts need no null bookkeeping: a defect in one already recorded a finding, and a
        // document with findings is discarded whole.
        val select = obj["select"]?.let { selector(it, pointer(path, "select")) }
        val rules = list(obj["rules"], pointer(path, "rules"), required = true, max = MAX_PAIR_RULES) { el, p -> rule(el, p) }
        val lossy = obj["lossy"]?.let { lossy(it, pointer(path, "lossy")) } ?: NO_LOSSY
        return if (blueprint != null && kind != null && rules != null) MappingPair(blueprint, kind, select, rules, lossy) else null
    }

    private fun selector(element: JsonElement, path: String): PairSelector? {
        val obj = objectAt(element, path) ?: return null
        unknownMembers(obj, path, setOf("spec", "in"))
        val spec = string(obj["spec"], pointer(path, "spec"), required = true)
        val values = list(obj["in"], pointer(path, "in"), required = true, max = MAX_MAPPING_LIST_ENTRIES) { el, p ->
            string(el, p, required = true)
        }
        if (values != null && values.isEmpty()) fail(pointer(path, "in"), "in must list at least one value")
        return if (spec != null && !values.isNullOrEmpty()) PairSelector(spec, values) else null
    }

    private fun lossy(element: JsonElement, path: String): Lossy? {
        val obj = objectAt(element, path) ?: return null
        unknownMembers(obj, path, setOf("properties", "relations", "backstage"))
        fun names(member: String) = if (obj.containsKey(member)) {
            list(obj[member], pointer(path, member), required = true, max = MAX_MAPPING_LIST_ENTRIES) { el, p ->
                string(el, p, required = true)
            }
        } else {
            emptyList()
        }
        val properties = names("properties")
        val relations = names("relations")
        val backstage = names("backstage")
        return if (properties != null && relations != null && backstage != null) Lossy(properties, relations, backstage) else null
    }

    private fun rule(element: JsonElement, path: String): MappingRule? {
        val obj = objectAt(element, path) ?: return null
        unknownMembers(obj, path, RULE_MEMBERS)
        val codec = codec(obj, path)
        val direction = direction(obj["direction"], pointer(path, "direction"), codec)
        val isConstant = codec is Codec.Constant
        val port = when {
            isConstant && obj.containsKey("port") -> {
                fail(pointer(path, "port"), "a constant rule has no Port side — omit port")
                null
            }
            isConstant -> null
            else -> obj["port"]?.let { portAddress(it, pointer(path, "port")) }
                ?: run {
                    if (!obj.containsKey("port")) fail(pointer(path, "port"), "port is required")
                    null
                }
        }
        val backstage = obj["backstage"]?.let { backstageAddress(it, pointer(path, "backstage")) }
            ?: run {
                if (!obj.containsKey("backstage")) fail(pointer(path, "backstage"), "backstage is required")
                null
            }
        val portMissing = !isConstant && port == null
        if (codec == null || direction == null || backstage == null || portMissing) return null
        return MappingRule(port, backstage, codec, direction)
    }

    private fun direction(element: JsonElement?, path: String, codec: Codec?): Direction? {
        if (element == null) return codec?.defaultDirection ?: Direction.BOTH
        val text = string(element, path, required = true) ?: return null
        val direction = Direction.entries.firstOrNull { it.wire == text }
        if (direction == null) fail(path, "direction must be one of ${Direction.entries.joinToString { it.wire }}")
        return direction
    }

    private fun codec(obj: JsonObject, path: String): Codec? {
        val codecPath = pointer(path, "codec")
        val name = string(obj["codec"], codecPath, required = true) ?: return null
        if (name == Codec.RESERVED_JQ) {
            fail(codecPath, "the jq codec is reserved and not supported yet")
            return null
        }
        val options = CODEC_OPTIONS[name]
        if (options == null) {
            fail(codecPath, "unknown codec '$name' (one of ${CODEC_OPTIONS.keys.joinToString()})")
            return null
        }
        (RULE_MEMBERS - setOf("port", "backstage", "codec", "direction") - options)
            .filter { obj.containsKey(it) }
            .forEach { fail(pointer(path, it), "$it does not apply to codec '$name'") }
        return when (name) {
            "copy" -> copyCodec(obj, path)
            "valueMap" -> valueMapCodec(obj, path)
            "constant" -> string(obj["value"], pointer(path, "value"), required = true)?.let { Codec.Constant(it) }
            "tagCategory" -> Codec.TagCategory
            "ref" -> Codec.Ref
            "derive" -> Codec.Derive
            else -> Codec.Link
        }
    }

    private fun copyCodec(obj: JsonObject, path: String): Codec? {
        val prefix = optionalString(obj, "prefix", path)
        val exportDefault = optionalString(obj, "exportDefault", path)
        val ignore = if (obj.containsKey("importIgnore")) {
            list(obj["importIgnore"], pointer(path, "importIgnore"), required = true, max = MAX_MAPPING_LIST_ENTRIES) { el, p ->
                string(el, p, required = true)
            }
        } else {
            emptyList()
        }
        val prefixBroken = obj.containsKey("prefix") && prefix == null
        val defaultBroken = obj.containsKey("exportDefault") && exportDefault == null
        if (prefixBroken || defaultBroken || ignore == null) return null
        return Codec.Copy(prefix, exportDefault, ignore)
    }

    private fun optionalString(obj: JsonObject, member: String, path: String): String? =
        if (obj.containsKey(member)) string(obj[member], pointer(path, member), required = true) else null

    private fun valueMapCodec(obj: JsonObject, path: String): Codec? {
        val mapPath = pointer(path, "map")
        val map = objectAt(obj["map"] ?: run { fail(mapPath, "map is required"); return null }, mapPath) ?: return null
        if (map.isEmpty()) fail(mapPath, "map must have at least one entry")
        if (map.size > MAX_VALUE_MAP_ENTRIES) fail(mapPath, "map must have at most $MAX_VALUE_MAP_ENTRIES entries")
        val entries = map.entries.mapNotNull { (key, value) -> string(value, pointer(mapPath, key), required = true)?.let { key to it } }
        val wellFormed = entries.size == map.size && map.isNotEmpty() && map.size <= MAX_VALUE_MAP_ENTRIES
        return if (wellFormed) Codec.ValueMap(entries.toMap()) else null
    }

    private fun portAddress(element: JsonElement, path: String): PortAddress? {
        val (member, value) = singleMember(element, path, PORT_ADDRESS_MEMBERS) ?: return null
        val at = pointer(path, member)
        return when (member) {
            "meta" -> portMeta(value, at)
            "property" -> string(value, at, required = true)?.let { PortAddress.Property(it) }
            "relation" -> string(value, at, required = true)?.let { PortAddress.Relation(it) }
            else -> reverse(value, at)
        }
    }

    private fun portMeta(value: JsonElement, at: String): PortAddress? {
        val text = string(value, at, required = true) ?: return null
        val meta = PortMeta.entries.firstOrNull { it.wire == text }
        if (meta == null) fail(at, "meta must be one of ${PortMeta.entries.joinToString { it.wire }}")
        return meta?.let { PortAddress.Meta(it) }
    }

    private fun reverse(element: JsonElement, path: String): PortAddress? {
        val obj = objectAt(element, path) ?: return null
        unknownMembers(obj, path, setOf("blueprint", "relation"))
        val blueprint = string(obj["blueprint"], pointer(path, "blueprint"), required = true)
        val relation = string(obj["relation"], pointer(path, "relation"), required = true)
        return if (blueprint != null && relation != null) PortAddress.Reverse(blueprint, relation) else null
    }

    private fun backstageAddress(element: JsonElement, path: String): BackstageAddress? {
        val (member, value) = singleMember(element, path, BACKSTAGE_ADDRESS_MEMBERS) ?: return null
        val at = pointer(path, member)
        return when (member) {
            "metadata" -> enumOf(value, at, MetadataField.entries, { it.wire }, { BackstageAddress.Metadata(it) })
            "profile" -> enumOf(value, at, ProfileField.entries, { it.wire }, { BackstageAddress.Profile(it) })
            "label" -> string(value, at, required = true)?.let { BackstageAddress.Label(it) }
            "annotation" -> string(value, at, required = true)?.let { BackstageAddress.Annotation(it) }
            "tags" -> string(value, at, required = true)?.let { BackstageAddress.Tags(it) }
            "spec" -> string(value, at, required = true)?.let { BackstageAddress.Spec(it) }
            "links" -> linksRest(value, at)
            else -> link(value, at)
        }
    }

    private fun linksRest(value: JsonElement, at: String): BackstageAddress? {
        val text = string(value, at, required = true) ?: return null
        if (text != "rest") fail(at, "links must be \"rest\"")
        return if (text == "rest") BackstageAddress.LinksRest else null
    }

    private fun link(element: JsonElement, path: String): BackstageAddress? {
        val obj = objectAt(element, path) ?: return null
        unknownMembers(obj, path, setOf("title", "icon"))
        val title = string(obj["title"], pointer(path, "title"), required = true)
        val icon = optionalString(obj, "icon", path)
        return if (title != null && (icon != null || !obj.containsKey("icon"))) BackstageAddress.Link(title, icon) else null
    }

    private fun <T : Any> enumOf(
        element: JsonElement,
        path: String,
        entries: List<T>,
        wire: (T) -> String,
        build: (T) -> BackstageAddress,
    ): BackstageAddress? {
        val text = string(element, path, required = true) ?: return null
        val entry = entries.firstOrNull { wire(it) == text }
        if (entry == null) fail(path, "must be one of ${entries.joinToString { wire(it) }}")
        return entry?.let(build)
    }

    /** An address is an object with exactly ONE member, drawn from [members]. */
    private fun singleMember(element: JsonElement, path: String, members: Set<String>): Pair<String, JsonElement>? {
        val obj = objectAt(element, path) ?: return null
        if (obj.size != 1) {
            fail(path, "an address has exactly one member (one of ${members.joinToString()})")
            return null
        }
        val (member, value) = obj.entries.single()
        if (member !in members) {
            fail(pointer(path, member), "unknown address member (one of ${members.joinToString()})")
            return null
        }
        return member to value
    }

    private fun objectAt(element: JsonElement?, path: String): JsonObject? {
        if (element is JsonObject) return element
        fail(path, if (element is JsonNull) "null is not allowed — must be an object" else "must be an object")
        return null
    }

    private fun unknownMembers(obj: JsonObject, path: String, allowed: Set<String>) {
        obj.keys.filter { it !in allowed }.forEach { fail(pointer(path, it), "unknown member '$it'") }
    }

    private fun string(element: JsonElement?, path: String, required: Boolean): String? {
        if (element == null) {
            if (required) fail(path, "is required")
            return null
        }
        if (element is JsonNull) {
            fail(path, "null is not allowed — must be a string")
            return null
        }
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            fail(path, "must be a string")
            return null
        }
        val text = primitive.content
        return when {
            text.isBlank() -> { fail(path, "must not be blank"); null }
            text.length > MAX_MAPPING_STRING_LENGTH -> { fail(path, "must be at most $MAX_MAPPING_STRING_LENGTH characters"); null }
            text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } -> {
                fail(path, "must not contain control characters")
                null
            }
            else -> text
        }
    }

    private fun <T : Any> list(
        element: JsonElement?,
        path: String,
        required: Boolean,
        max: Int,
        item: (JsonElement, String) -> T?,
    ): List<T>? {
        if (element == null) {
            if (required) fail(path, "is required")
            return null
        }
        val array = element as? JsonArray
        if (array == null) {
            fail(path, if (element is JsonNull) "null is not allowed — must be an array" else "must be an array")
            return null
        }
        if (array.size > max) {
            fail(path, "must have at most $max entries")
            return null
        }
        val items = array.mapIndexedNotNull { index, el -> item(el, pointer(path, index)) }
        return if (items.size == array.size) items else null
    }
}
