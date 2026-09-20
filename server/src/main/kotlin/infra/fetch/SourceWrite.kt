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

/** The three source-reference columns a write resolves together — one record's [SourceWrite] envelope. */
data class SourceColumns(val sourceUrl: String?, val lastSyncedAt: Long, val syncedContent: String?)

/**
 * The PURE dispatch [SourceWrite] describes, extracted from `entities/EntitySync.kt`'s and
 * `blueprints/BlueprintSync.kt`'s byte-identical `replaceRow` `when (source)` blocks (verified
 * identical when extracted — `.claude/docs/persistence.md` "V37"/"V38"): [SourceWrite.FromRequest]
 * keeps [current]'s stamp/baseline when [requestSourceUrl] is unchanged, else resets both to
 * `0`/`null`; [SourceWrite.Keep] leaves [current] untouched; [SourceWrite.Synced] stamps [now] and
 * invokes [baseline] — the ONLY branch that does, since it is the one write that needs one.
 * `catalog/CatalogFileService.kt`'s own sync predates this type, always waives, and has no [Keep]
 * branch — it stays its own inline copy rather than a third consumer.
 */
fun resolveSourceColumns(
    source: SourceWrite,
    requestSourceUrl: String?,
    current: SourceColumns,
    now: Long,
    baseline: () -> String,
): SourceColumns = when (source) {
    SourceWrite.FromRequest -> if (requestSourceUrl != current.sourceUrl) {
        SourceColumns(requestSourceUrl, 0L, null)
    } else {
        current
    }
    SourceWrite.Keep -> current
    is SourceWrite.Synced -> SourceColumns(source.sourceUrl, now, baseline())
}
