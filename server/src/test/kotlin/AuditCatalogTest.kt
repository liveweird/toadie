package ch.nokillswit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins doc/code parity for the audit event catalog: `.claude/docs/observability.md`'s
 * "Emitted today:" bullet list must name exactly the `audit("…")` events the server actually
 * emits — no more (a phantom entry like the former `password_reset.store_failed` or the
 * former `refresh.rejected` reason `predates_password_change`), no fewer (an emitted event the
 * doc never mentions, like the former `password_reset.link_sent`/`notification_failed`/
 * `rejected` or `session.rejected`). Pure, no database: a plain regex sweep over the source
 * tree and the doc file.
 */
class AuditCatalogTest {

    // Multi-line-aware: `audit(` and its literal event-name string may sit on different
    // source lines (the multi-argument call convention used throughout the routes).
    private val codeEventRegex = Regex("""audit\(\s*"([a-z_.]+)"""")
    private val docTokenRegex = Regex("`([^`]+)`")
    private val eventNameShape = Regex("""^[a-z_]+\.[a-z_.]+$""")

    // Every area prefix an audit event name is known to start with — used to tell a real
    // event-name token in the doc's bullet list apart from an unrelated backticked identifier
    // (a field name, a class, a table) that happens to contain a dot. A new audit AREA must be
    // added here too, or the doc→code direction silently skips its entries (the code→doc
    // direction below still catches an undocumented event regardless).
    private val areaPrefixes = listOf(
        "login.",
        "logout",
        "password_reset.",
        "refresh.",
        "session.",
        "password.",
        "user.",
        "catalog_file.",
        "dictionary.",
        "label.",
        "annotation_key.",
        "tag_category.",
        "lens.",
        "blueprint.",
        "entity.",
        "entity_types.",
        "authz.",
    )

    private fun codeEvents(): Set<String> {
        val root = Path.of("src/main/kotlin")
        val events = mutableSetOf<String>()
        Files.walk(root).use { stream ->
            stream
                .filter { it.name.endsWith(".kt") }
                .forEach { path -> events += codeEventRegex.findAll(path.readText()).map { it.groupValues[1] } }
        }
        return events
    }

    private fun docText(): String {
        val doc = Path.of("../.claude/docs/observability.md").readText()
        val start = doc.indexOf("Emitted today:")
        val end = doc.indexOf("**Not audit events.**")
        check(start >= 0 && end > start) { "observability.md's 'Emitted today:' section markers moved — update this test" }
        return doc.substring(start, end)
    }

    @Test
    fun `every audit event literal in code is documented, and every documented event name is real`() {
        val code = codeEvents()

        // Direction 1 (code -> doc): every emitted event literal must appear in the doc,
        // spelled out in backticks (not merely as a bare substring of something else).
        // No shape filter here — this also covers the one dot-less event name, `logout`.
        val fullDoc = Files.readString(Path.of("../.claude/docs/observability.md"))
        val undocumented = code.filterNot { fullDoc.contains("`$it`") }.toSet()
        assertTrue(
            undocumented.isEmpty(),
            "audit() events emitted in code but missing from observability.md (add them, with their real " +
                "fields, wrapped in backticks): $undocumented",
        )

        // Direction 2 (doc -> code): every backticked, dotted, event-shaped token in the
        // "Emitted today:" bullet list that starts with a known area prefix must be an event
        // the code actually emits — otherwise it is a phantom entry (the former
        // `password_reset.store_failed`, or a stale audited `reason` value).
        val docEventTokens = docTokenRegex.findAll(docText())
            .map { it.groupValues[1] }
            .filter { eventNameShape.matches(it) }
            .filter { token -> areaPrefixes.any { prefix -> token.startsWith(prefix) } }
            .toSet()
        val phantom = docEventTokens - code
        assertTrue(
            phantom.isEmpty(),
            "event names documented in observability.md's 'Emitted today:' list but never emitted in code " +
                "(remove them, or fix the literal): $phantom",
        )
    }
}
