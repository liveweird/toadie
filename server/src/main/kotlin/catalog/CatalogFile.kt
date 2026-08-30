package ch.nokillswit.catalog

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

/**
 * One catalog-info.yaml document — a Backstage entity of one of the supported landscape kinds.
 * Shape and validation rules mirror the descriptor reference
 * (`.claude/docs/backstage-descriptor-format.md`). `spec` is ONE flat superset of every kind's
 * fields (no oneOf/discriminator — hostile to the 3.0 tooling); the per-kind table in
 * CatalogFileValidation.kt enforces required and forbidden fields, so stored documents stay
 * canonical. References are validated by GRAMMAR only — resolving them is the Errors report
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

/**
 * The create/replace request body: the Backstage document plus the OPTIONAL source file
 * reference (the https URL of the file's canonical copy in a GitLab/GitHub repo). The
 * document half is stored and exported PURE — the reference is envelope state, which is why
 * this wrapper exists instead of a field on [CatalogFile]. PUT semantics are full-replace:
 * an omitted/blank sourceUrl CLEARS the stored reference, and a changed/cleared reference
 * resets the sync state (last-synced stamp + baseline).
 */
@Serializable
data class CatalogFileWriteRequest(
    val kind: String = "Component",
    val metadata: CatalogFileMetadata,
    val spec: EntitySpec,
    val sourceUrl: String? = null,
) {
    fun document() = CatalogFile(kind = kind, metadata = metadata, spec = spec)
}

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
    val sourceUrl: String?,
    /** Epoch millis of the last repo→DB sync; 0 = never. */
    val lastSyncedAt: Long,
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
    val sourceUrl: String?,
    /** Epoch millis of the last repo→DB sync; 0 = never — the client derives "modified in
     *  the DB since the sync" from `updatedAt > lastSyncedAt` (a sync stamps both equal). */
    val lastSyncedAt: Long,
)

/** GET /files/{id}/sync — the sync state incl. the baseline document stored at the last sync. */
@Serializable
data class SyncStateResponse(
    val sourceUrl: String?,
    /** Epoch millis; 0 = never synced. */
    val lastSyncedAt: Long,
    /** The document as stored at the last sync — the DB-vs-repo comparison baseline; null = never. */
    val syncedDocument: CatalogFile?,
)

/** POST /files/{id}/sync — the repo copy, parsed client-side (YAML stays a client concern). */
@Serializable
data class SyncCatalogFileRequest(val document: CatalogFile)

typealias CatalogFilePageResponse = PageResponse<CatalogFileListItem>
