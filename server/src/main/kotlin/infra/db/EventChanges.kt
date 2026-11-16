package ch.nokillswit.infra.db

// The shared field-diff vocabulary of the per-resource history logs (catalog files, entities):
// a FieldChange per changed field, the params encoding that carries them, and the value
// bounds. Each resource owns how ITS document is walked into changes; this file owns what a
// change looks like and how it is encoded.

/**
 * One changed field of a document. A scalar carries [from]/[to]; a list of scalars carries
 * [added]/[removed] (bounded by the CHANGE, not by the list's length); a field whose value is
 * withheld — free text, a structured list, or an over-long value — carries none of them and is
 * reported by NAME only ("Description changed.").
 */
data class FieldChange(
    val field: String,
    val from: String? = null,
    val to: String? = null,
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
)

/**
 * The value ceiling per side. Beyond it a field degrades to name-only rather than being
 * truncated into a misleading diff — and event rows stay small.
 */
internal const val MAX_CHANGE_VALUE_LENGTH = 200

/** The source reference is envelope state, not part of the document — it diffs as a pseudo-field. */
internal const val SOURCE_URL_FIELD = "sourceUrl"

/**
 * The params encoding (Lettuce's impact-log idiom, widened to a whole document): `changed`
 * comma-joins the changed field paths in document order, and each field's values follow under
 * `<path>.from`/`.to`/`.added`/`.removed`. A name-only field contributes to `changed` alone.
 * Field paths, tags and entity references cannot contain a comma, so the joins stay unambiguous.
 */
internal fun encodeChanges(changes: List<FieldChange>): Map<String, String> {
    if (changes.isEmpty()) return emptyMap()
    return buildMap {
        put("changed", changes.joinToString(",") { it.field })
        changes.forEach { change ->
            change.from?.let { put("${change.field}.from", it) }
            change.to?.let { put("${change.field}.to", it) }
            if (change.added.isNotEmpty()) put("${change.field}.added", change.added.joinToString(","))
            if (change.removed.isNotEmpty()) put("${change.field}.removed", change.removed.joinToString(","))
        }
    }
}

/** The envelope's source-reference change, as a pseudo-field beside the document's own. */
internal fun sourceUrlChange(before: String?, after: String?): FieldChange? =
    changeFor(SOURCE_URL_FIELD, before?.let(Leaf::Scalar), after?.let(Leaf::Scalar))

/** A document leaf: compared by value, reported per its own kind. */
internal sealed interface Leaf {
    /** A single value, reported as from/to. */
    data class Scalar(val value: String) : Leaf

    /** A list of scalars, reported as added/removed. */
    data class Items(val values: List<String>) : Leaf

    /** Compared by [fingerprint], reported by NAME only — free text and structured lists. */
    data class Opaque(val fingerprint: String) : Leaf
}

internal fun changeFor(path: String, before: Leaf?, after: Leaf?): FieldChange? {
    if (before == after) return null
    return when {
        // Withheld by rule (free text, a structured list): name only. Checked FIRST because an
        // EMPTY array has no elements to classify and lands in [Leaf.Items] — so a links list
        // growing from none to one object pairs an Items side with an Opaque one.
        before is Leaf.Opaque || after is Leaf.Opaque -> FieldChange(path)
        before is Leaf.Items || after is Leaf.Items -> itemsChange(path, before, after)
        else -> scalarChange(path, (before as? Leaf.Scalar)?.value, (after as? Leaf.Scalar)?.value)
    }
}

private fun scalarChange(path: String, from: String?, to: String?): FieldChange {
    val tooLong = (from?.length ?: 0) > MAX_CHANGE_VALUE_LENGTH || (to?.length ?: 0) > MAX_CHANGE_VALUE_LENGTH
    return if (tooLong) FieldChange(path) else FieldChange(path, from = from, to = to)
}

private fun itemsChange(path: String, before: Leaf?, after: Leaf?): FieldChange? {
    val old = (before as? Leaf.Items)?.values.orEmpty()
    val new = (after as? Leaf.Items)?.values.orEmpty()
    val added = new.filterNot { it in old }
    val removed = old.filterNot { it in new }
    // A pure REORDER changes the document but moves no entry — nothing to tell (Lettuce's rule),
    // and a valueless "Tags." entry would only puzzle the reader.
    if (added.isEmpty() && removed.isEmpty()) return null
    val tooLong = added.sumOf { it.length + 1 } > MAX_CHANGE_VALUE_LENGTH ||
        removed.sumOf { it.length + 1 } > MAX_CHANGE_VALUE_LENGTH
    return if (tooLong) FieldChange(path) else FieldChange(path, added = added, removed = removed)
}
