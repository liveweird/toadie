package ch.nokillswit

import ch.nokillswit.catalog.CatalogRegistryReader
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * `catalog/CatalogRegistryReader.kt` read directly (it was only ever reached through the catalog
 * write/Errors paths): namespace resolution — the flagged default for a blank value, a concrete
 * active value accepted, an unknown or removed one refused, and the no-default-flagged 400 — and
 * the shape of [CatalogRegistryReader.loadSnapshot]. The reader opens no transaction of its own,
 * so every call here owns one. The registries are shared suite state: every fixture is a UNIQUE
 * value removed in `finally`, and the namespace default flag is restored from a snapshot.
 */
class CatalogRegistryReaderTest {
    private val reader = CatalogRegistryReader(Json)

    private suspend fun <T> read(block: suspend CatalogRegistryReader.() -> T): T =
        inTestTransaction { reader.block() }

    @Test
    fun `a blank namespace resolves to the flagged default`() = testApplication {
        usePostgresTestcontainer()
        val default = uniqueEntityName("rr-default")
        try {
            TestNamespaces.withDefaultNamespace(default) {
                assertEquals(default, read { flaggedDefaultNamespace() })
                assertEquals(default, read { resolveNamespace("") })
            }
        } finally {
            TestNamespaces.remove(default)
        }
    }

    @Test
    fun `a concrete active namespace is accepted, an unknown or removed one is refused`() = testApplication {
        usePostgresTestcontainer()
        val active = uniqueEntityName("rr-active")
        val removed = uniqueEntityName("rr-removed")
        val unknown = uniqueEntityName("rr-unknown")
        try {
            TestNamespaces.ensure(active, removed)
            assertEquals(active, read { resolveNamespace(active) })
            assertEquals(removed, read { resolveNamespace(removed) })

            TestNamespaces.remove(removed)
            val refusedRemoved = assertFailsWith<BadRequestException> { read { resolveNamespace(removed) } }
            assertEquals(
                "metadata.namespace '$removed' is not a defined namespace — define it on the Namespaces page",
                refusedRemoved.message,
            )
            val refusedUnknown = assertFailsWith<BadRequestException> { read { resolveNamespace(unknown) } }
            assertTrue(refusedUnknown.message!!.contains("'$unknown' is not a defined namespace"))
        } finally {
            TestNamespaces.remove(active, removed)
        }
    }

    @Test
    fun `a blank namespace with no default flagged is the no-default 400`() = testApplication {
        usePostgresTestcontainer()
        val snapshot = TestNamespaces.snapshotValues()
        try {
            TestNamespaces.replaceDocument(emptyList()) // the empty document legally has no default
            assertNull(read { flaggedDefaultNamespace() })
            val refused = assertFailsWith<BadRequestException> { read { resolveNamespace("") } }
            assertEquals(
                "No default namespace is defined — mark one on the Namespaces page or specify a namespace",
                refused.message,
            )
        } finally {
            TestNamespaces.replaceDocument(snapshot)
        }
    }

    @Test
    fun `loadSnapshot carries every registry with its kinds, values and categories`() = testApplication {
        usePostgresTestcontainer()
        val label = uniqueLabel("rr-label", values = listOf("alpha", "beta"), kinds = listOf("Component", "API"))
        val annotationKey = uniqueAnnotationKey("rr-annotation", kinds = listOf("Resource"))
        val tag = uniqueTag("rr-tag")
        val category = uniqueTagCategory("rr-category", tags = listOf(tag), kinds = listOf("System"))
        val lifecycle = uniqueEntityName("rr-lifecycle")
        val namespace = uniqueEntityName("rr-namespace")
        val type = uniqueEntityName("rr-type")
        try {
            TestLifecycles.ensure(lifecycle)
            TestNamespaces.ensure(namespace)
            TestEntityTypes.withKindTypes("Component", listOf(type)) {
                val snapshot = read { loadSnapshot() }

                assertEquals(listOf("Component", "API") to listOf("alpha", "beta"), snapshot.labels[label])
                assertEquals(listOf("Resource"), snapshot.annotationKeys[annotationKey])
                assertEquals(category to listOf("System"), snapshot.tags[tag])
                assertEquals(listOf(type), snapshot.types["Component"])
                assertTrue(lifecycle in snapshot.lifecycles)
                assertTrue(namespace in snapshot.namespaces)
                // The seeded vocabulary rides the same snapshot (presence only — shared suite state).
                assertTrue("production" in snapshot.lifecycles)
                assertTrue("default" in snapshot.namespaces)
            }

            // Removed registry rows leave the snapshot: it reads active rows only.
            TestLabels.remove(label)
            TestAnnotationKeys.remove(annotationKey)
            TestTagCategories.remove(category)
            TestLifecycles.remove(lifecycle)
            TestNamespaces.remove(namespace)
            val after = read { loadSnapshot() }
            assertNull(after.labels[label])
            assertNull(after.annotationKeys[annotationKey])
            assertNull(after.tags[tag])
            assertTrue(lifecycle !in after.lifecycles)
            assertTrue(namespace !in after.namespaces)
        } finally {
            TestLabels.remove(label)
            TestAnnotationKeys.remove(annotationKey)
            TestTagCategories.remove(category)
            TestLifecycles.remove(lifecycle)
            TestNamespaces.remove(namespace)
        }
    }
}
