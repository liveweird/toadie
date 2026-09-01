package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileListFilter
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.EntityIdentity
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.catalog.allowsVirtualTarget
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
        assertTrue(CatalogFileListFilter(name = "VC-", namespace = "DEFAULT", kinds = listOf("Component")).matches(f))
        assertFalse(CatalogFileListFilter(name = "other").matches(f))
        assertFalse(CatalogFileListFilter(namespace = "elsewhere").matches(f))
        assertFalse(CatalogFileListFilter(kinds = listOf("Group")).matches(f))
        // kinds is any-of: a set containing the file's kind matches.
        assertTrue(CatalogFileListFilter(kinds = listOf("Group", "Component")).matches(f))
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

    @Test
    fun `a MISSING target is judged on its identity only - content filters never hide it`() {
        val ghost = EntityIdentity("api", "team-ns", "billing")
        // No filter at all shows it; so does a kind list containing its (canonical-cased) kind.
        assertTrue(CatalogFileListFilter().allowsVirtualTarget(ghost))
        assertTrue(CatalogFileListFilter(kinds = listOf("API", "Component")).allowsVirtualTarget(ghost))
        assertFalse(CatalogFileListFilter(kinds = listOf("Component")).allowsVirtualTarget(ghost))
        // Namespace and name are the other two slots a reference identity carries — the name
        // box narrows a MISSING node with the list's own folded-substring rule.
        assertTrue(CatalogFileListFilter(namespace = "Team-NS").allowsVirtualTarget(ghost))
        assertFalse(CatalogFileListFilter(namespace = "other").allowsVirtualTarget(ghost))
        assertTrue(CatalogFileListFilter(name = "BILL").allowsVirtualTarget(ghost))
        assertFalse(CatalogFileListFilter(name = "ledger").allowsVirtualTarget(ghost))
        // It has no document, so the content filters have nothing to match — hiding a dangling
        // reference behind a tag filter would hide exactly the problem worth seeing.
        assertTrue(
            CatalogFileListFilter(tag = "billing", type = "service", lifecycle = "production", label = "tier")
                .allowsVirtualTarget(ghost),
        )
    }
}
