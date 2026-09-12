package ch.nokillswit.infra.validation

import kotlinx.serialization.json.Json

/**
 * The one findings-bearing `400` shape (v1.31.0 — unifying the entity and catalog-file strict
 * -save rejections): a strict save whose rejection must surface the FULL list of violated
 * checks on the RFC 7807 body, not just an aggregated `detail` string, so the SPA can read the
 * findings straight off the failed save's own response instead of asking a sibling `/check`
 * endpoint. `plugins/ErrorHandling.kt` maps every subtype through ONE `exception<
 * InvalidPayloadException>` handler; each feature owns its own problem DTO
 * (`EntityInvalidProblem`, `CatalogFileInvalidProblem`) and encodes it here via [problemJson] —
 * the handler never knows the concrete shape.
 */
abstract class InvalidPayloadException(message: String) : RuntimeException(message) {
    /** Encodes this exception's own findings-bearing problem DTO as a JSON string body. */
    abstract fun problemJson(title: String, status: Int, instance: String?): String
}

/**
 * The shared `explicitNulls = false` encoder every [InvalidPayloadException] subtype's problem
 * DTO uses — the `plugins/ErrorHandling.kt` `problemSerializer` config, duplicated here so this
 * package stays free of a `plugins` dependency.
 */
val invalidPayloadJson: Json = Json { encodeDefaults = true; explicitNulls = false }
