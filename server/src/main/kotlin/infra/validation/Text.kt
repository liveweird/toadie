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
 * Canonical multi-line free-text fields (a saved entity query's text and the like): trimmed,
 * with `\n`/`\r`/`\t` allowed (the field spans lines by design) but every OTHER ISO control
 * character rejected as a clean 400 instead of stored garbage — the [sanitizeSingleLine]
 * sibling for fields that are allowed to wrap.
 */
fun sanitizeMultiLine(value: String, field: String): String {
    val trimmed = value.trim()
    if (trimmed.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
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

/**
 * A bulk-import DOCUMENT (or the sync/create/update body itself) never carries `sourceUrl` —
 * it is row state for the WHOLE request (`entities/Entity.kt`'s `EntityRequest.sourceUrl`,
 * `blueprints/Blueprint.kt`'s `BlueprintRequest.sourceUrl`), never a document member. Extracted
 * from the two byte-identical per-feature checks so callers pass their own request's
 * `sourceUrl` field; thrown before the row's ordinary validation so the message is specific
 * rather than a generic unknown-member decode failure.
 */
fun requireNoDocumentSourceUrl(sourceUrl: String?) {
    if (sourceUrl != null) {
        throw BadRequestException("sourceUrl is row state set for the whole request, not a document member")
    }
}
