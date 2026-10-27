package ch.nokillswit.conversion

import ch.nokillswit.infra.validation.InvalidPayloadException
import ch.nokillswit.infra.validation.invalidPayloadJson
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The stored conversion-mapping registry's wire shapes (phase 1 of the Backstage <-> Port
 * conversion). The mapping DOCUMENT itself (`MappingDocument.kt`) travels as an open JSON
 * object — the typed model is built from it by the hand-written parser so every defect is a
 * positioned [MappingFinding] — while everything around it is a plain closed DTO.
 *
 * **No `null` members on the wire**: every response member below is non-nullable, and the parser
 * rejects `null` anywhere inside a document, so a stored document round-trips exactly.
 */
const val MAX_CONVERSION_MAPPINGS = 20
const val MAX_CONVERSION_MAPPING_NAME_LENGTH = 100
const val MAX_CONVERSION_MAPPING_DOCUMENT_BYTES = 256 * 1024

/** The blocking validation codes — a document carrying one is refused (`400`) on write. */
enum class MappingFindingCode {
    SCHEMA,
    UNKNOWN_BLUEPRINT,
    UNKNOWN_PROPERTY,
    UNKNOWN_RELATION,
    COMPUTED_PROPERTY,
    UNKNOWN_KIND,
    FIELD_NOT_ALLOWED,
    AMBIGUOUS_SELECTOR,
    AMBIGUOUS_REF_PARTITION,
    DUPLICATE_IMPORT_TARGET,
    UNDECLARED_FIELD,
    CODEC_TYPE_MISMATCH,
    REQUIRED_UNMAPPED,
    TARGET_UNMAPPED,
    REF_KIND_MISMATCH,
}

/** The report-only drift codes — they never block a write; they say the mapping and the registries disagree. */
enum class MappingDriftCode {
    VOCABULARY_DRIFT,
    REGISTRY_KEY_MISSING,
    REGISTRY_KIND_MISMATCH,
    BACKSTAGE_FIELD_UNMAPPED,
}

/** [path] is a JSON pointer (RFC 6901) into the mapping document; "" is the document itself. */
@Serializable
data class MappingFinding(val code: MappingFindingCode, val path: String, val message: String)

@Serializable
data class MappingDrift(val code: MappingDriftCode, val path: String, val message: String)

/** The verdict of validating one document against the current blueprints and registries. */
data class MappingVerdict(val findings: List<MappingFinding>, val drift: List<MappingDrift>)

@Serializable
data class ConversionMappingRequest(
    val name: String,
    val document: JsonElement,
)

/**
 * One stored mapping. [findings] and [drift] are RECOMPUTED on every read against the current
 * blueprints and registries (a stored mapping goes stale when a blueprint or registry is edited
 * after the fact); on a fresh write [findings] is always empty (a document with findings is
 * refused), and [drift] is what the write observed.
 */
@Serializable
data class ConversionMapping(
    val id: UInt,
    val name: String,
    val document: JsonObject,
    val findings: List<MappingFinding>,
    val drift: List<MappingDrift>,
    val createdBy: UInt,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ConversionMappingList(val items: List<ConversionMapping>)

/** The mapping-write `400` body: [ch.nokillswit.plugins.ProblemDetail]'s members plus the full [findings] list. */
@Serializable
data class ConversionMappingInvalidProblem(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val findings: List<MappingFinding>,
)

/** Thrown by the service when a document carries blocking findings — the shared findings-bearing `400`. */
class ConversionMappingInvalidException(val findings: List<MappingFinding>) :
    InvalidPayloadException("The mapping document has ${findings.size} blocking finding(s)") {
    override fun problemJson(title: String, status: Int, instance: String?): String =
        invalidPayloadJson.encodeToString(
            ConversionMappingInvalidProblem.serializer(),
            ConversionMappingInvalidProblem(
                title = title,
                status = status,
                detail = message,
                instance = instance,
                findings = findings,
            ),
        )
}

/** Trims the name (control characters -> 400); the document is data and is never rewritten. */
fun sanitizedConversionMappingRequest(request: ConversionMappingRequest): ConversionMappingRequest =
    request.copy(name = sanitizeSingleLine(request.name, "name"))

/** The name rules — shared by the service's create/update; the document is checked by the validator. */
fun validateConversionMappingName(name: String) {
    if (name.isEmpty()) throw BadRequestException("name must not be blank")
    if (name.length > MAX_CONVERSION_MAPPING_NAME_LENGTH) {
        throw BadRequestException("name must be at most $MAX_CONVERSION_MAPPING_NAME_LENGTH characters")
    }
}
