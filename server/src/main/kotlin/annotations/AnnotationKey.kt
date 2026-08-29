package ch.nokillswit.annotations

import ch.nokillswit.catalog.MAX_ENTITY_PART_LENGTH
import ch.nokillswit.catalog.MAX_KEY_PREFIX_LENGTH
import ch.nokillswit.catalog.SERVER_WRITTEN_ANNOTATIONS
import ch.nokillswit.catalog.canonicalizedKinds
import ch.nokillswit.catalog.validateAllowedKinds
import ch.nokillswit.catalog.validateKey
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The ADMIN-curated annotation-key registry's wire shapes — the labels registry's sibling
 * with the value dimension dropped: one row = one allowed `metadata.annotations` KEY with
 * the entity kinds it may be applied to. Annotation VALUES stay free strings (the
 * descriptor format's ≤5000-char rule, unchanged) — only keys are gated. The registry is
 * the whitelist every catalog-file write's annotation keys are checked against (strictly,
 * no grandfathering; an empty registry means no file may carry annotations). Key grammar
 * comes from the descriptor format (`catalog/CatalogFileValidation.kt` owns the rule), and
 * the server-written keys (`SERVER_WRITTEN_ANNOTATIONS`) are rejected here too —
 * registering a key the file writes always refuse would be a trap.
 */
@Serializable
data class AnnotationKeyResponse(
    val id: UInt,
    val key: String,
    val kinds: List<String>,
)

@Serializable
data class AnnotationKeyList(val items: List<AnnotationKeyResponse>)

@Serializable
data class AnnotationKeyRequest(
    val key: String,
    val kinds: List<String>,
)

const val MAX_ANNOTATION_KEYS = 200
const val MAX_ANNOTATION_KEY_LENGTH = MAX_KEY_PREFIX_LENGTH + 1 + MAX_ENTITY_PART_LENGTH

/**
 * Trims the key and normalizes kinds to canonical casing and order. The key keeps its case
 * — the grammar allows uppercase name parts, and silently rewriting what the admin typed
 * would mask a mistake (the sanitizer convention).
 */
fun sanitizedAnnotationKeyRequest(request: AnnotationKeyRequest): AnnotationKeyRequest = AnnotationKeyRequest(
    key = request.key.trim(),
    kinds = canonicalizedKinds(request.kinds),
)

/** The registry's validation rules — enforced by the route AND re-checked by the service. */
fun validateAnnotationKeyRequest(request: AnnotationKeyRequest) {
    validateKey(request.key, "key")
    if (request.key in SERVER_WRITTEN_ANNOTATIONS) {
        throw BadRequestException("'${request.key}' is written by the catalog server itself and cannot be registered")
    }
    validateAllowedKinds(request.kinds)
}
