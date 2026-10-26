package ch.nokillswit.conversion

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.CatalogLink
import ch.nokillswit.catalog.EntityProfile
import ch.nokillswit.catalog.SPEC_SHAPES
import ch.nokillswit.catalog.SpecShape
import ch.nokillswit.catalog.specOf

/**
 * Accumulates ONE Backstage document while a pair's export rules run (`Codecs.kt`). Rules write
 * LEAVES; this class owns what a repeated write means — the first value for a text leaf wins (the
 * mapping contract: several rules onto one address, first that yields a value wins), reference
 * lists concatenate in write order without duplicates, and a list field that any rule targeted is
 * PRESENT in the built spec even when empty (Group `children`, User `memberOf`: Backstage wants
 * them present, and `EntitySpec` models absence as `null` only on those two).
 */
internal class DocBuilder {
    var title: String? = null
    var description: String? = null
    var namespace: String? = null
    val labels = LinkedHashMap<String, String>()
    val annotations = LinkedHashMap<String, String>()
    val tags = LinkedHashSet<String>()
    val links = mutableListOf<CatalogLink>()
    var displayName: String? = null
    var email: String? = null
    var picture: String? = null

    /** Spec text fields and single-reference fields, by `EntitySpec` field name. */
    private val strings = LinkedHashMap<String, String>()

    /** Spec reference-list fields; an entry exists once any rule targeted the field, even with no value. */
    private val lists = LinkedHashMap<String, MutableList<String>>()

    /** Writes a text [value] at [address]; a repeated write to the same leaf keeps the first. Non-text addresses are not text targets. */
    fun putText(address: BackstageAddress, value: String) {
        when (address) {
            is BackstageAddress.Metadata -> when (address.field) {
                MetadataField.TITLE -> if (title == null) title = value
                MetadataField.DESCRIPTION -> if (description == null) description = value
                MetadataField.NAMESPACE -> if (namespace == null) namespace = value
            }
            is BackstageAddress.Label -> labels.putIfAbsent(address.key, value)
            is BackstageAddress.Annotation -> annotations.putIfAbsent(address.key, value)
            is BackstageAddress.Profile -> when (address.field) {
                ProfileField.DISPLAY_NAME -> if (displayName == null) displayName = value
                ProfileField.EMAIL -> if (email == null) email = value
                ProfileField.PICTURE -> if (picture == null) picture = value
            }
            is BackstageAddress.Spec -> strings.putIfAbsent(address.field, value)
            is BackstageAddress.Tags, BackstageAddress.LinksRest, is BackstageAddress.Link -> Unit
        }
    }

    /** Writes [refs] onto the reference spec field [field]: a single field keeps its first reference, a list appends the new ones. */
    fun putRefs(field: String, refs: List<String>) {
        if (SPEC_SHAPES[field] == SpecShape.REF) {
            refs.firstOrNull()?.let { strings.putIfAbsent(field, it) }
        } else {
            val list = lists.getOrPut(field) { mutableListOf() }
            refs.forEach { if (it !in list) list += it }
        }
    }

    fun toFile(kind: String, name: String, namespace: String): CatalogFile {
        val profile = if (displayName == null && email == null && picture == null) null else EntityProfile(displayName, email, picture)
        return CatalogFile(
            kind = kind,
            metadata = CatalogFileMetadata(
                name = name,
                namespace = namespace,
                title = title,
                description = description,
                labels = labels.toMap(),
                annotations = annotations.toMap(),
                tags = tags.toList(),
                links = links.toList(),
            ),
            spec = specOf(strings, lists, profile),
        )
    }
}
