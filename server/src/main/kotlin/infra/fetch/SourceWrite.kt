package ch.nokillswit.infra.fetch

/**
 * The shared write-path policy for a record's `sourceUrl`/`lastSyncedAt`/`synced_content`
 * envelope — one level below `catalog/CatalogFileService.kt`'s own inline handling, common to
 * `entities/EntityService.kt` (2.9.0, V37) and `blueprints/BlueprintService.kt` (2.10.0, V38):
 * [FromRequest] is the ordinary create/PUT posture (the submitted `sourceUrl`, resetting the sync
 * stamp when it differs from the stored one); [Keep] leaves all three columns untouched (an
 * import row replaced without a batch `sourceUrl`, D3); [Synced] is a fetch-backed write (a batch
 * import with a `sourceUrl`, or the record's own `POST …/{id}/sync`) — stamps the reference, the
 * sync timestamp, and the baseline document together. Relocated here (from `entities/Entity.kt`)
 * once a second consumer needed the identical policy, byte-identical, rather than a second copy.
 */
sealed interface SourceWrite {
    data object FromRequest : SourceWrite

    data object Keep : SourceWrite

    data class Synced(val sourceUrl: String) : SourceWrite
}
