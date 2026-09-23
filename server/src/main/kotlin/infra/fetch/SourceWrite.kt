package ch.nokillswit.infra.fetch

import io.ktor.server.plugins.BadRequestException

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
 * A guarded source sync named a reference that is no longer current. Kept separate from
 * identity/unique conflicts so clients can offer reload/retry behavior from a stable RFC 7807
 * type without parsing the human-readable detail or receiving either URL in the response.
 */
internal class SourceReferenceConflictException : RuntimeException(
    "Source reference changed while syncing; reload the record and try again",
)

/**
 * Validates an optional v1-compatible source guard and compares it with the source reference
 * read under the caller's existing write lock. A missing guard preserves legacy behavior.
 */
internal fun requireExpectedSourceUrl(expectedSourceUrl: String?, currentSourceUrl: String?) {
    if (expectedSourceUrl == null) return
    val expected = sanitizedSourceUrl(expectedSourceUrl)
        ?: throw BadRequestException(SOURCE_URL_INVALID_DETAIL)
    if (expected != currentSourceUrl) throw SourceReferenceConflictException()
}

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
