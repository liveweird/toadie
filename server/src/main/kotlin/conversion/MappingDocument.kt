package ch.nokillswit.conversion

/**
 * The conversion mapping document (schemaVersion 1) — the DATA that says how a Port blueprint's
 * entities correspond to a Backstage kind's catalog files. The rule that no code branches on
 * ontology names, and everything else about the format, lives in `.claude/docs/conversion-mapping.md`;
 * the default document is `sample-data/port/commerce-payments/mapping/backstage.json`.
 *
 * Wire shape (the stored TEXT is this JSON re-serialised compactly — never a serialized Kotlin
 * class; the hand-written `MappingParser.kt` is the one reader, so every structural defect is a
 * positioned finding):
 *
 * ```
 * { "schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1",
 *   "pairs": [ { "blueprint": "<id>", "kind": "<Kind>", "select": {"spec": "type", "in": [..]},
 *                "rules": [ { "port": <PortAddress>, "backstage": <BackstageAddress>,
 *                             "codec": "...", "direction": "both|export|import", ...codec options } ],
 *                "lossy": { "properties": [..], "relations": [..], "backstage": [..] } } ] }
 * ```
 *
 * **Codecs are typed**: a sealed [Codec] whose subtype carries exactly its own options, so a
 * rule can never carry an option its codec does not define (the parser rejects the member). The
 * reserved `jq` codec name is refused with a "not supported yet" finding until an expression
 * codec ships. **Direction** defaults per codec ([Codec.defaultDirection]): `derive` and
 * `constant` only make sense on export, everything else round-trips.
 *
 * **Engine-owned, never configurable**: identifier <-> `metadata.name`, blueprint <-> kind (+ the
 * pair's selector), the namespace default, and the fact that computed (mirror/calculation/
 * aggregation) properties are never mapped.
 *
 * **Selector rule**: among the pairs of one kind, those WITH a [PairSelector] are tried in
 * order; the single selector-less pair is the fallback. Two selector-less pairs for one kind are
 * `AMBIGUOUS_SELECTOR`.
 *
 * **Several rules onto one Backstage address**: allowed. On export they are tried in document
 * order and the first that yields a value wins (a copy rule's `exportDefault` yields its default
 * when the value is absent, so it belongs last); on import each rule reads the field it points
 * at. `ref` rules partition/concatenate lists by relation.
 */
const val MAPPING_SCHEMA_VERSION = 1
const val MAPPING_API_VERSION = "backstage.io/v1alpha1"

data class MappingDocument(
    val schemaVersion: Int,
    val apiVersion: String,
    val pairs: List<MappingPair>,
)

/** One blueprint <-> kind correspondence. */
data class MappingPair(
    val blueprint: String,
    val kind: String,
    val select: PairSelector?,
    val rules: List<MappingRule>,
    val lossy: Lossy,
)

/** `{"spec": "type", "in": [..]}` — the pair applies to a Backstage file whose `spec.<spec>` is one of [values]. */
data class PairSelector(val spec: String, val values: List<String>)

/**
 * What the pair deliberately does NOT carry. [properties]/[relations] are Port-side ids;
 * [backstage] lists Backstage-side fields (`metadata.description`, `spec.dependencyOf`,
 * `profile.picture`, ...) — the Backstage twin of the Port-side declaration, so a field is
 * either mapped or consciously dropped, never silently forgotten on either side.
 */
data class Lossy(
    val properties: List<String>,
    val relations: List<String>,
    val backstage: List<String>,
)

enum class Direction(val wire: String) {
    BOTH("both"),
    EXPORT("export"),
    IMPORT("import"),
}

/** [port] is null only for the Port-less `constant` codec. */
data class MappingRule(
    val port: PortAddress?,
    val backstage: BackstageAddress,
    val codec: Codec,
    val direction: Direction,
) {
    /** True when the rule writes its Backstage address on EXPORT (Port -> Backstage). */
    val writesOnExport: Boolean get() = direction != Direction.IMPORT

    /**
     * True when the rule READS its Backstage address on IMPORT (Backstage -> Port): any rule that is
     * not export-only. `derive` and `constant` are export-only by construction (a `constant` still
     * takes part in pair SELECTION, which is not a rule's import half).
     */
    val readsOnImport: Boolean get() = direction != Direction.EXPORT && codec !is Codec.Derive && codec !is Codec.Constant
}

enum class PortMeta(val wire: String) {
    TITLE("title"),
    ICON("icon"),
    TEAM("team"),
}

/** Where a rule reads/writes on the Port side. */
sealed interface PortAddress {
    data class Meta(val field: PortMeta) : PortAddress
    data class Property(val id: String) : PortAddress
    data class Relation(val id: String) : PortAddress

    /** Derive-only: the entities of [blueprint] whose [relation] points at THIS pair's blueprint. */
    data class Reverse(val blueprint: String, val relation: String) : PortAddress
}

enum class MetadataField(val wire: String) {
    TITLE("title"),
    DESCRIPTION("description"),
    NAMESPACE("namespace"),
}

enum class ProfileField(val wire: String) {
    DISPLAY_NAME("displayName"),
    EMAIL("email"),
    PICTURE("picture"),
}

/** Where a rule reads/writes on the Backstage side. */
sealed interface BackstageAddress {
    data class Metadata(val field: MetadataField) : BackstageAddress
    data class Label(val key: String) : BackstageAddress
    data class Annotation(val key: String) : BackstageAddress

    /** The tags of the named registry tag category. */
    data class Tags(val category: String) : BackstageAddress

    /** Every `metadata.links` entry no `link` rule claims by title. */
    data object LinksRest : BackstageAddress

    /** One `metadata.links` entry, identified by its [title]. */
    data class Link(val title: String, val icon: String?) : BackstageAddress
    data class Profile(val field: ProfileField) : BackstageAddress

    /** A (non-profile) `EntitySpec` field by name. */
    data class Spec(val field: String) : BackstageAddress
}

/** The rule's value transformation. */
sealed interface Codec {
    val wire: String
    val defaultDirection: Direction get() = Direction.BOTH

    /**
     * Verbatim value copy. [prefix] is prepended on export and stripped on import;
     * [exportDefault] is written on export when the Port value is absent; [importIgnore] lists
     * Backstage values import skips (a placeholder such as `dir:.`).
     */
    data class Copy(
        val prefix: String?,
        val exportDefault: String?,
        val importIgnore: List<String>,
    ) : Codec {
        override val wire get() = "copy"
    }

    /** Enum/boolean value <-> Backstage string through a bijective [map] (booleans keyed "true"/"false"). */
    data class ValueMap(val map: Map<String, String>) : Codec {
        override val wire get() = "valueMap"
    }

    /** Property values <-> the tags of the registry category named by the rule's `tags` address. */
    data object TagCategory : Codec {
        override val wire get() = "tagCategory"
    }

    /** A relation (or the `team` meta field) <-> an entity-reference spec field. */
    data object Ref : Codec {
        override val wire get() = "ref"
    }

    /** Export-only: a spec reference list derived from the REVERSE side of another blueprint's relation. */
    data object Derive : Codec {
        override val wire get() = "derive"
        override val defaultDirection get() = Direction.EXPORT
    }

    /** A named URL property <-> one `metadata.links` entry, by title. */
    data object Link : Codec {
        override val wire get() = "link"
    }

    /** Export-only fixed value; Port-less. Pairs with a selector to pin a discriminating `spec.type`. */
    data class Constant(val value: String) : Codec {
        override val wire get() = "constant"
        override val defaultDirection get() = Direction.EXPORT
    }

    companion object {
        /** The reserved expression codec — refused with a "not supported yet" finding. */
        const val RESERVED_JQ = "jq"
    }
}
