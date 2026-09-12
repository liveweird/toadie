package ch.nokillswit.infra.validation

import io.ktor.server.plugins.BadRequestException

/**
 * Canonical single-line identity fields (names and the like): trimmed, and control characters
 * are a clean 400 instead of stored garbage. Ported from Lettuce.
 */
fun sanitizeSingleLine(value: String, field: String): String {
    val trimmed = value.trim()
    if (trimmed.any { it.isISOControl() }) {
        throw BadRequestException("$field must not contain control characters")
    }
    return trimmed
}

/**
 * Rejects a collection carrying a duplicate, optionally folded before comparison (e.g. a
 * case-insensitive check) — the hand-typed `size != toSet().size` check repeated across the
 * registry validators, unified here. [message] is the caller's exact rejection text.
 */
fun requireNoDuplicates(values: Collection<String>, message: String, fold: (String) -> String = { it }) {
    val folded = values.map(fold)
    if (folded.size != folded.toSet().size) {
        throw BadRequestException(message)
    }
}
