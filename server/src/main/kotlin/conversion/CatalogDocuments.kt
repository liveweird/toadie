package ch.nokillswit.conversion

import ch.nokillswit.catalog.CatalogFile
import kotlinx.serialization.Serializable

/**
 * The wire shapes of the Port -> Backstage conversion (`GET …/conversion-mappings/{id}/catalog-documents`,
 * phase 2). A pure, unaudited read: [CatalogDocumentsResponse.files] are exactly the documents
 * `POST /api/v1/files/import` accepts (INVALID rows are not in it), and [CatalogDocumentsResponse.rows]
 * report what happened to every SELECTED entity, one row each, in entity-id order.
 *
 * **No `null` member on the wire** (the phase-1 posture): the route encodes the response with
 * `blueprintJson` (`explicitNulls = false`), so an optional member below that has no value is an
 * ABSENT member, and so is an unset `title`/`children`/... inside a document; an empty list that is
 * meaningful (a Group's `children`) is still written. [ConversionRow.findings] and
 * [ConversionRow.unmapped] are always present (possibly empty).
 */
@Serializable
enum class ConversionRowStatus {
    /** Converted with nothing to report. */
    CONVERTED,

    /** Converted, but at least one [ConversionFinding] says something was dropped or could not be rendered. */
    CONVERTED_WITH_FINDINGS,

    /** The entity's blueprint has no pair in the mapping — nothing to convert (a Port-only blueprint). */
    SKIPPED,

    /** A document was built but cannot be stored (descriptor-format violation, identity clash) — NOT in `files`. */
    INVALID,
}

/**
 * What a row's findings say. Per-row and report-only: none of them blocks the other rows.
 * [OWNER_TRUNCATED]: a multi-team Port entity maps onto the single-owner `spec.owner`, so only the
 * first team survives. [TARGET_UNMAPPED]: a relation value whose target blueprint has no pair
 * (a mapping that validates cannot produce it; it is the defensive twin of the validator's code).
 * [TARGET_MISSING]: a relation names an entity that no longer exists (a stale entity) — the
 * reference is dropped. [TARGET_INVALID]: the target exists but its own row is INVALID (a name or namespace
 * the catalog refuses, or an identity several entities share), so the reference is dropped rather than
 * written dangling. [VALUE_UNMAPPED]: a `valueMap` rule met a value its map does not list.
 * [NO_PAIR]: see [ConversionRowStatus.SKIPPED]. [IDENTITY_CONFLICT]: two selected entities convert
 * to one Backstage identity — the later one is not emitted. [DOCUMENT_INVALID]: the descriptor
 * format refuses the built document (`message` carries the validator's reason).
 * The import-only codes: [REFERENCE_DROPPED] a reference field entry that could not become a
 * relation value, [OWNER_DROPPED] the same for the owner, [NAMESPACE_UNMAPPED] the document sits
 * in a namespace the mapping cannot place (SKIPPED).
 */
@Serializable
enum class ConversionFindingCode {
    OWNER_TRUNCATED,
    TARGET_UNMAPPED,
    TARGET_MISSING,
    TARGET_INVALID,
    VALUE_UNMAPPED,
    NO_PAIR,
    IDENTITY_CONFLICT,
    DOCUMENT_INVALID,

    /** Import: a reference could not become a relation (unresolvable, unmapped kind, untaken blueprint, or no such Port entity). */
    REFERENCE_DROPPED,

    /** Import: the owner reference could not be turned into a team — the stored team, if any, is kept. */
    OWNER_DROPPED,

    /** Import: the document's namespace is one the mapping does not carry — the document is skipped. */
    NAMESPACE_UNMAPPED,
}

/**
 * [field] is the field concerned, or empty for the whole document: on an EXPORT row the Port-side
 * field (`team`, `relations.<id>`, `properties.<id>`), on an IMPORT row the Backstage-side one
 * (`spec.owner`, `metadata.labels.<key>`, `metadata.namespace`, ...).
 */
@Serializable
data class ConversionFinding(val code: ConversionFindingCode, val field: String, val message: String)

@Serializable
data class ConversionRow(
    /** Position in [CatalogDocumentsResponse.rows]. */
    val index: Int,
    val status: ConversionRowStatus,
    val blueprint: String,
    val identifier: String,
    val entityId: UInt,
    /** The Backstage identity the entity converted to; absent on a [ConversionRowStatus.SKIPPED] row. */
    val kind: String? = null,
    val namespace: String? = null,
    val name: String? = null,
    /** The stored catalog file of the same identity the document was merged over; absent without `merge` or a match. */
    val existingId: UInt? = null,
    val findings: List<ConversionFinding>,
    /** Port-side fields the entity carries that no rule of the pair converts: `properties.<id>`, `relations.<id>`, `team`. */
    val unmapped: List<String>,
)

@Serializable
data class CatalogDocumentsResponse(
    val files: List<CatalogFile>,
    val rows: List<ConversionRow>,
)
