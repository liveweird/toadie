package ch.nokillswit.catalog

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

/**
 * One catalog-info.yaml document — a Backstage entity of one of the supported landscape kinds.
 * Shape and validation rules mirror the descriptor reference
 * (`.claude/docs/backstage-descriptor-format.md`). `spec` is ONE flat superset of every kind's
 * fields (no oneOf/discriminator — hostile to the 3.0 tooling); the per-kind table in
 * CatalogFileValidation.kt enforces required and forbidden fields, so stored documents stay
 * canonical. References are validated by GRAMMAR only — resolving them is the cross-check
 * feature's job.
 */
@Serializable
data class CatalogFile(
    // Default keeps pre-kind stored content (and old clients) decoding as Components.
    val kind: String = "Component",
    val metadata: CatalogFileMetadata,
    val spec: EntitySpec,
)

@Serializable
data class CatalogFileMetadata(
    val name: String,
    // Folded to lowercase by [sanitizedCatalogFile] (Backstage renders namespaces lowercase).
    // Blank/omitted resolves to the ADMIN-flagged default dictionary entry at write time
    // (CatalogFileService.resolveNamespace) — stored files always hold the concrete value.
    val namespace: String = "",
    val title: String? = null,
    val description: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val links: List<CatalogLink> = emptyList(),
)

@Serializable
data class CatalogLink(
    val url: String,
    val title: String? = null,
    val icon: String? = null,
)

/**
 * The superset of every supported kind's spec fields. The Component fields keep their
 * non-nullable empty-list defaults for stored-content back-compat; `children`/`memberOf` are
 * nullable lists ON PURPOSE — Backstage requires them PRESENT (possibly empty) on Group/User,
 * so null models absence.
 */
@Serializable
data class EntitySpec(
    val type: String? = null,
    val lifecycle: String? = null,
    val owner: String? = null,
    val system: String? = null,
    val subcomponentOf: String? = null,
    val providesApis: List<String> = emptyList(),
    val consumesApis: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val dependencyOf: List<String> = emptyList(),
    val definition: String? = null,
    val profile: EntityProfile? = null,
    val parent: String? = null,
    val children: List<String>? = null,
    val members: List<String> = emptyList(),
    val memberOf: List<String>? = null,
    val domain: String? = null,
    val subdomainOf: String? = null,
)

@Serializable
data class EntityProfile(
    val displayName: String? = null,
    val email: String? = null,
    val picture: String? = null,
)

const val DEFAULT_NAMESPACE = "default"

/** The kinds the editor supports, in canonical casing (identity matching stays lowercase). */
val SUPPORTED_KINDS = listOf("Component", "API", "System", "Domain", "Resource", "Group", "User")

@Serializable
data class CatalogFileResponse(
    val id: UInt,
    val kind: String,
    val metadata: CatalogFileMetadata,
    val spec: EntitySpec,
    val createdBy: UInt,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The flatter list-row shape: identity + the display fields, creator resolved via join. */
@Serializable
data class CatalogFileListItem(
    val id: UInt,
    val kind: String,
    val name: String,
    val namespace: String,
    val title: String?,
    val type: String?,
    val lifecycle: String?,
    val owner: String?,
    val tags: List<String>,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val updatedAt: Long,
)

typealias CatalogFilePageResponse = PageResponse<CatalogFileListItem>
