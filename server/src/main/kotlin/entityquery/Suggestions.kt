package ch.nokillswit.entityquery

/**
 * "Did you mean …?" suggestions for the validator's `UNKNOWN_*` diagnostics
 * (`.claude/docs/entity-query-language.md`, PR1 P1.4): fold both sides (trim + lowercase),
 * prefer an exact folded match, else the nearest candidate by Levenshtein distance, a prefix
 * match, or a substring match — ties broken by smaller distance then alphabetically. Pure, no
 * database.
 */

private fun fold(value: String): String = value.trim().lowercase()

/**
 * Classic iterative (two-row) Levenshtein edit distance between [a] and [b] — O(a.length *
 * b.length) time, O(b.length) space; no recursion, so no stack risk on the short identifiers
 * this package ever compares.
 */
internal fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

/**
 * The general form behind [suggest]: matches [input] against [candidates] labelled by an
 * arbitrary payload, so a caller can suggest a DIFFERENT string than the one matched against
 * (`QueryValidator.kt`'s label suggestion matches a mistyped label against blueprint TITLES too,
 * but always suggests the IDENTIFIER). Returns the winning candidate's display text paired with
 * its payload; `null` when nothing qualifies.
 */
internal fun <T> bestMatch(input: String, candidates: Collection<Pair<String, T>>): Pair<String, T>? {
    if (candidates.isEmpty() || input.length > MAX_SUGGESTION_INPUT_CHARS) return null
    val foldedInput = fold(input)
    candidates.firstOrNull { fold(it.first) == foldedInput }?.let { return it }

    val threshold = maxOf(2, foldedInput.length / 3)
    return candidates
        .map { candidate -> candidate to levenshtein(foldedInput, fold(candidate.first)) }
        .filter { (candidate, distance) -> qualifies(foldedInput, fold(candidate.first), distance, threshold) }
        .minWithOrNull(compareBy({ it.second }, { fold(it.first.first) }))
        ?.first
}

private fun qualifies(foldedInput: String, foldedCandidate: String, distance: Int, threshold: Int): Boolean =
    distance <= threshold ||
        foldedCandidate.startsWith(foldedInput) ||
        (foldedInput.length >= 3 && foldedCandidate.contains(foldedInput))

/**
 * The validator's `UNKNOWN_LABEL`/`UNKNOWN_RELATION`/`UNKNOWN_PROPERTY`/`UNKNOWN_VARIABLE` "did
 * you mean" lookup: the exact folded match wins outright, otherwise the nearest [candidates]
 * entry within [levenshtein] distance `max(2, input.length / 3)`, or a prefix match, or (when
 * [input] is at least 3 characters) a substring match — ties broken by smaller distance then
 * alphabetically; `null` when nothing qualifies.
 */
internal fun suggest(input: String, candidates: Collection<String>): String? =
    bestMatch(input, candidates.map { it to it })?.first
