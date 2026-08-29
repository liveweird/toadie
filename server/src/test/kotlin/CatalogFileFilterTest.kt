package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileListFilter
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.EntityIdentity
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.catalog.foldForMatch
import ch.nokillswit.catalog.matches
import ch.nokillswit.catalog.ownerFilterTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests of the catalog-file filter machinery (catalog/CatalogFileFilter.kt): the
 * in-memory matcher the graph endpoint applies, the owner-param resolution, and the
 * diacritics fold. The SQL twin (buildCatalogFilePredicate) is covered by the list-endpoint
 * route tests against the real database, and the list-vs-graph parity test in GraphTest pins
 * the two implementations together.
 */
class CatalogFileFilterTest {

    private fun file(
        kind: String = "Component",
        name: String = "svc-a",
        namespace: String = "default",
        tags: List<String> = emptyList(),
        labels: Map<String, String> = emptyMap(),
        type: String? = "service",
        lifecycle: String? = "production",
        owner: String? = "platform",
    ) = CatalogFile(
        kind = kind,
        metadata = CatalogFileMetadata(name = name, namespace = namespace, tags = tags, labels = labels),
        spec = EntitySpec(type = type, lifecycle = lifecycle, owner = owner),
    )

    @Test
    fun `foldForMatch strips diacritics and maps the non-decomposing letters`() {
        assertEquals("zolw", foldForMatch("Żółw"))
        assertEquals("strasse", foldForMatch("STRAßE"))
        assertEquals("aeon-plain", foldForMatch("Æon-plain"))
    }

    @Test
    fun `ownerFilterTarget applies the owner defaults and folds, rejecting unparsable refs`() {
        assertEquals(EntityIdentity("group", "default", "platform"), ownerFilterTarget("platform"))
        assertEquals(EntityIdentity("group", "team-ns", "platform"), ownerFilterTarget("team-ns/platform"))
        assertEquals(EntityIdentity("user", "default", "jane"), ownerFilterTarget("user:jane"))
        assertEquals(EntityIdentity("user", "ns", "jane"), ownerFilterTarget("User:NS/Jane"))
        assertNull(ownerFilterTarget("a:b:c"))
        assertNull(ownerFilterTarget("a/b/c"))
    }

    @Test
    fun `an empty filter matches everything and each populated filter narrows`() {
        val f = file(
            tags = listOf("java"),
            labels = mapOf("example.com/tier" to "Backend"),
        )
        assertTrue(CatalogFileListFilter().matches(f))
        assertTrue(CatalogFileListFilter(name = "VC-", namespace = "DEFAULT", kind = "Component").matches(f))
        assertFalse(CatalogFileListFilter(name = "other").matches(f))
        assertFalse(CatalogFileListFilter(namespace = "elsewhere").matches(f))
        assertFalse(CatalogFileListFilter(kind = "Group").matches(f))
        assertTrue(CatalogFileListFilter(tag = "JAVA").matches(f))
        assertFalse(CatalogFileListFilter(tag = "rust").matches(f))
        assertTrue(CatalogFileListFilter(type = "SERVICE", lifecycle = "Production").matches(f))
        assertFalse(CatalogFileListFilter(type = "library").matches(f))
        assertFalse(CatalogFileListFilter(lifecycle = "deprecated").matches(f))
        // Blank values mean "no filter", mirroring optionalString's absent-when-blank.
        assertTrue(CatalogFileListFilter(name = " ", namespace = "", tag = " ", type = "", lifecycle = " ").matches(f))
    }

    @Test
    fun `the owner match resolves every stored spelling against the target identity`() {
        val target = EntityIdentity("group", "team-ns", "platform")
        for (spelling in listOf("platform", "team-ns/platform", "group:platform", "Group:Team-NS/Platform")) {
            assertTrue(
                CatalogFileListFilter(owner = target).matches(file(namespace = "team-ns", owner = spelling)),
                "spelling $spelling must resolve to the target",
            )
        }
        // A namespace-less spelling in ANOTHER file namespace resolves elsewhere.
        assertFalse(CatalogFileListFilter(owner = target).matches(file(namespace = "other", owner = "platform")))
        // A different kind is a different identity; an absent owner never matches.
        assertFalse(CatalogFileListFilter(owner = target).matches(file(namespace = "team-ns", owner = "user:platform")))
        assertFalse(CatalogFileListFilter(owner = target).matches(file(namespace = "team-ns", owner = null)))
    }

    @Test
    fun `the label filters check key presence byte-exactly and values any-of case-folded`() {
        val f = file(labels = mapOf("example.com/tier" to "Backend"))
        assertTrue(CatalogFileListFilter(label = "example.com/tier").matches(f))
        assertFalse(CatalogFileListFilter(label = "Example.com/Tier").matches(f))
        assertFalse(CatalogFileListFilter(label = "other").matches(f))
        assertTrue(CatalogFileListFilter(label = "example.com/tier", labelValues = listOf("BACKEND")).matches(f))
        assertTrue(CatalogFileListFilter(label = "example.com/tier", labelValues = listOf("edge", "backend")).matches(f))
        assertFalse(CatalogFileListFilter(label = "example.com/tier", labelValues = listOf("edge")).matches(f))
    }
}
