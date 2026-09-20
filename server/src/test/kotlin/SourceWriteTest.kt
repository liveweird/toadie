package ch.nokillswit

import ch.nokillswit.infra.fetch.SourceColumns
import ch.nokillswit.infra.fetch.SourceWrite
import ch.nokillswit.infra.fetch.resolveSourceColumns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The pure dispatch [resolveSourceColumns] describes — no DB, extracted from
 * `entities/EntitySync.kt`'s and `blueprints/BlueprintSync.kt`'s byte-identical `replaceRow`
 * `when (source)` blocks (`.claude/docs/persistence.md` "V37"/"V38"). One case per branch,
 * crossed with changed/unchanged/cleared `sourceUrl` where that distinction applies, plus the
 * one case proving [baseline] is invoked ONLY for [SourceWrite.Synced].
 */
class SourceWriteTest {

    private val current = SourceColumns(sourceUrl = "https://example.com/a.yaml", lastSyncedAt = 111L, syncedContent = "{\"a\":1}")

    private fun failIfInvoked(): String = error("baseline must not be invoked for this branch")

    @Test
    fun `FromRequest with an unchanged url keeps the current stamp and baseline`() {
        val resolved = resolveSourceColumns(SourceWrite.FromRequest, current.sourceUrl, current, now = 999L, baseline = ::failIfInvoked)
        assertEquals(current, resolved)
    }

    @Test
    fun `FromRequest with a changed url resets the stamp and baseline`() {
        val resolved = resolveSourceColumns(
            SourceWrite.FromRequest,
            "https://example.com/b.yaml",
            current,
            now = 999L,
            baseline = ::failIfInvoked,
        )
        assertEquals(SourceColumns("https://example.com/b.yaml", 0L, null), resolved)
    }

    @Test
    fun `FromRequest clearing the url resets the stamp and baseline`() {
        val resolved = resolveSourceColumns(SourceWrite.FromRequest, null, current, now = 999L, baseline = ::failIfInvoked)
        assertEquals(SourceColumns(null, 0L, null), resolved)
    }

    @Test
    fun `Keep leaves every column untouched regardless of the request url`() {
        val resolved = resolveSourceColumns(
            SourceWrite.Keep, "https://example.com/ignored.yaml", current, now = 999L, baseline = ::failIfInvoked,
        )
        assertEquals(current, resolved)
    }

    @Test
    fun `Keep leaves every column untouched when the request url is null`() {
        val resolved = resolveSourceColumns(SourceWrite.Keep, null, current, now = 999L, baseline = ::failIfInvoked)
        assertEquals(current, resolved)
    }

    @Test
    fun `Synced stamps now and the fetched sourceUrl and invokes baseline exactly once`() {
        var invocations = 0
        val resolved = resolveSourceColumns(SourceWrite.Synced("https://example.com/synced.yaml"), null, current, now = 999L) {
            invocations++
            "{\"synced\":true}"
        }
        assertEquals(SourceColumns("https://example.com/synced.yaml", 999L, "{\"synced\":true}"), resolved)
        assertEquals(1, invocations)
    }

    @Test
    fun `baseline is never invoked for FromRequest or Keep`() {
        var invoked = false
        resolveSourceColumns(SourceWrite.FromRequest, current.sourceUrl, current, now = 999L) { invoked = true; "unused" }
        resolveSourceColumns(SourceWrite.Keep, current.sourceUrl, current, now = 999L) { invoked = true; "unused" }
        assertFalse(invoked)
    }
}
