package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileEventType
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.CatalogLink
import ch.nokillswit.catalog.EntityProfile
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.catalog.MAX_CHANGE_VALUE_LENGTH
import ch.nokillswit.catalog.catalogFileCreationEvent
import ch.nokillswit.catalog.catalogFileDeletionEvent
import ch.nokillswit.catalog.catalogFileSyncEvent
import ch.nokillswit.catalog.catalogFileUpdateEvent
import ch.nokillswit.catalog.documentChanges
import ch.nokillswit.catalog.encodeChanges
import ch.nokillswit.catalog.sourceUrlChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure descriptor/diff matrix — no DB, no testApplication (Lettuce's `ImpactLogEventsTest`
 * shape). This is the primary suite for the field-level history: the route test only proves the
 * wiring.
 */
class CatalogFileEventsTest {

    private val defaultSpec = EntitySpec(type = "service", lifecycle = "production", owner = "group:default/platform")

    private fun file(
        name: String = "checkout",
        namespace: String = "default",
        title: String? = null,
        description: String? = null,
        labels: Map<String, String> = emptyMap(),
        annotations: Map<String, String> = emptyMap(),
        tags: List<String> = emptyList(),
        links: List<CatalogLink> = emptyList(),
        spec: EntitySpec = defaultSpec,
        kind: String = "Component",
    ) = CatalogFile(
        kind = kind,
        metadata = CatalogFileMetadata(
            name = name,
            namespace = namespace,
            title = title,
            description = description,
            labels = labels,
            annotations = annotations,
            tags = tags,
            links = links,
        ),
        spec = spec,
    )

    /** The changed field paths of a diff, in order. */
    private fun changed(before: CatalogFile, after: CatalogFile) = documentChanges(before, after).map { it.field }

    @Test
    fun `an identical document yields no changes and no event`() {
        assertEquals(emptyList(), documentChanges(file(), file()))
        assertNull(catalogFileUpdateEvent(emptyList()))
    }

    @Test
    fun `a scalar change carries from and to`() {
        val changes = documentChanges(
            file(),
            file(spec = EntitySpec(type = "website", lifecycle = "production", owner = "group:default/payments")),
        )
        assertEquals(listOf("spec.type", "spec.owner"), changes.map { it.field })
        val owner = changes.single { it.field == "spec.owner" }
        assertEquals("group:default/platform", owner.from)
        assertEquals("group:default/payments", owner.to)
    }

    @Test
    fun `a kind change reads as from-to, not as a bare set`() {
        // The stored JSON omits the "Component" default — the diff encoder must not, or every
        // kind change would render as "set to API".
        val change = documentChanges(file(), file(kind = "API")).single { it.field == "kind" }
        assertEquals("Component", change.from)
        assertEquals("API", change.to)
    }

    @Test
    fun `setting and clearing a scalar leaves the other side null`() {
        val set = documentChanges(file(), file(title = "Checkout")).single()
        assertEquals("metadata.title", set.field)
        assertNull(set.from)
        assertEquals("Checkout", set.to)

        val cleared = documentChanges(file(title = "Checkout"), file()).single()
        assertEquals("Checkout", cleared.from)
        assertNull(cleared.to)
    }

    @Test
    fun `a list of scalars carries added and removed, not the whole list`() {
        val change = documentChanges(
            file(tags = listOf("legacy", "payments")),
            file(tags = listOf("payments", "billing")),
        ).single()
        assertEquals("metadata.tags", change.field)
        assertEquals(listOf("billing"), change.added)
        assertEquals(listOf("legacy"), change.removed)
        // The untouched entry rides neither side.
        assertNull(change.from)
        assertNull(change.to)
    }

    @Test
    fun `reordering a list moves no entry, so it reports nothing`() {
        assertEquals(
            emptyList(),
            documentChanges(file(tags = listOf("billing", "payments")), file(tags = listOf("payments", "billing"))),
        )
    }

    @Test
    fun `maps diff per key, so labels and annotations name the entry that moved`() {
        val changes = documentChanges(
            file(labels = mapOf("tier" to "gold", "team" to "core"), annotations = mapOf("docs" to "old")),
            file(labels = mapOf("tier" to "silver"), annotations = mapOf("docs" to "new")),
        )
        assertEquals(
            listOf("metadata.labels.tier", "metadata.labels.team", "metadata.annotations.docs"),
            changes.map { it.field },
        )
        val tier = changes.first()
        assertEquals("gold", tier.from)
        assertEquals("silver", tier.to)
        // A removed key keeps its old value on the `from` side.
        assertEquals("core", changes[1].from)
        assertNull(changes[1].to)
    }

    @Test
    fun `nested objects recurse to their own paths`() {
        val change = documentChanges(
            file(spec = EntitySpec(profile = EntityProfile(email = "a@example.com"))),
            file(spec = EntitySpec(profile = EntityProfile(email = "b@example.com"))),
        ).single()
        assertEquals("spec.profile.email", change.field)
        assertEquals("b@example.com", change.to)
    }

    @Test
    fun `free text is recorded by name only — its content never rides an event`() {
        val changes = documentChanges(
            file(description = "The old story of this service."),
            file(description = "A brand new confidential story."),
        )
        val change = changes.single()
        assertEquals("metadata.description", change.field)
        assertNull(change.from)
        assertNull(change.to)
        val params = encodeChanges(changes)
        assertEquals("metadata.description", params["changed"])
        assertTrue(params.values.none { it.contains("story") }, "description text leaked into params: $params")
    }

    @Test
    fun `an API definition is free text too`() {
        val changes = documentChanges(
            file(spec = EntitySpec(definition = "openapi: 3.0.0")),
            file(spec = EntitySpec(definition = "openapi: 3.1.0")),
        )
        assertEquals(listOf("spec.definition"), changes.map { it.field })
        assertTrue(encodeChanges(changes).values.none { it.contains("openapi") })
    }

    @Test
    fun `a structured list is recorded by name only`() {
        val changes = documentChanges(
            file(),
            file(links = listOf(CatalogLink(url = "https://example.com", title = "Runbook"))),
        )
        val change = changes.single()
        assertEquals("metadata.links", change.field)
        assertTrue(change.added.isEmpty() && change.removed.isEmpty())
        assertNull(change.to)
    }

    @Test
    fun `an over-long value degrades to name only instead of being truncated`() {
        val long = "x".repeat(MAX_CHANGE_VALUE_LENGTH + 1)
        val change = documentChanges(file(), file(title = long)).single()
        assertEquals("metadata.title", change.field)
        assertNull(change.to)

        // …and the same ceiling applies to a list's added/removed side.
        val many = (1..40).map { "dependency-with-a-longish-name-$it" }
        val list = documentChanges(file(), file(spec = defaultSpec.copy(dependsOn = many)))
            .single { it.field == "spec.dependsOn" }
        assertTrue(list.added.isEmpty())
    }

    @Test
    fun `the source reference diffs as a pseudo-field beside the document`() {
        assertNull(sourceUrlChange("https://example.com/a.yaml", "https://example.com/a.yaml"))
        val set = assertNotNull(sourceUrlChange(null, "https://example.com/a.yaml"))
        assertEquals("sourceUrl", set.field)
        assertNull(set.from)
        assertEquals("https://example.com/a.yaml", set.to)
        assertNull(assertNotNull(sourceUrlChange("https://example.com/a.yaml", null)).to)
    }

    @Test
    fun `encoding lists the changed paths and their companions`() {
        val changes = documentChanges(
            file(tags = listOf("legacy")),
            file(title = "Checkout", tags = listOf("billing")),
        )
        val params = encodeChanges(changes)
        assertEquals("metadata.title,metadata.tags", params["changed"])
        assertEquals("Checkout", params["metadata.title.to"])
        assertFalse(params.containsKey("metadata.title.from"))
        assertEquals("billing", params["metadata.tags.added"])
        assertEquals("legacy", params["metadata.tags.removed"])
        assertEquals(emptyMap(), encodeChanges(emptyList()))
    }

    @Test
    fun `a creation event names the kind, and the import loop marks its origin`() {
        val created = catalogFileCreationEvent("Component")
        assertEquals(CatalogFileEventType.CREATED, created.type)
        assertEquals(mapOf("kind" to "Component"), created.params)
        assertEquals(
            mapOf("kind" to "API", "origin" to "import"),
            catalogFileCreationEvent("API", viaImport = true).params,
        )
    }

    @Test
    fun `a sync is recorded even when the repo copy matched, unlike a no-op save`() {
        val synced = catalogFileSyncEvent(emptyList())
        assertEquals(CatalogFileEventType.SYNCED, synced.type)
        assertEquals(emptyMap(), synced.params)

        val updated = assertNotNull(catalogFileUpdateEvent(documentChanges(file(), file(title = "Checkout"))))
        assertEquals(CatalogFileEventType.UPDATED, updated.type)
        assertEquals("metadata.title", updated.params["changed"])
    }

    @Test
    fun `a deletion event carries no params`() {
        assertEquals(CatalogFileEventType.DELETED, catalogFileDeletionEvent().type)
        assertEquals(emptyMap(), catalogFileDeletionEvent().params)
    }

    @Test
    fun `renaming reports the identity fields`() {
        assertEquals(
            listOf("metadata.name", "metadata.namespace"),
            changed(file(), file(name = "checkout-v2", namespace = "external")),
        )
    }
}
