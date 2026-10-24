package ch.nokillswit

import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.catalog.CatalogFileListFilter
import ch.nokillswit.catalog.importCheck
import ch.nokillswit.infra.concurrency.ReadLedger
import ch.nokillswit.infra.paging.PageRequest
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The catalog's whole-workspace reads (`graph`, `errors`, `export`) join the process-wide read
 * ledger (2.19.0 — `.claude/docs/security.md` "Entity read memory budget"): admitted for their
 * whole estimate before any `content` is decoded, `400` when the workspace cannot fit on its own,
 * `429` when other reads hold the room. The ledger capacity itself is the seam
 * (`TestCatalogFiles.tunedService(readLedger)`) — no timing, no sleeps.
 */
class CatalogFileReadBudgetTest {

    private val everything = CatalogFileListFilter()

    @Test
    fun `graph, errors and export refuse a workspace that cannot fit the budget with 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cat-rb-own")
        client.createCatalogFile(componentFile(uniqueEntityName("rb-own"), namespace = uniqueNamespace("rbns")))
        val ledger = ReadLedger(capacity = 1)
        val tuned = TestCatalogFiles.tunedService(readLedger = ledger)
        assertFailsWith<BadRequestException> { tuned.graph(everything) }
        assertFailsWith<BadRequestException> { tuned.errors(everything) }
        assertFailsWith<BadRequestException> { tuned.export(null) }
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `graph, errors and export answer 429 while a sibling read holds the room and succeed once it is released`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("cat-rb-busy")
            val ns = uniqueNamespace("rbns")
            client.createCatalogFile(componentFile(uniqueEntityName("rb-busy"), namespace = ns))
            // A generous capacity with all but one byte already held by ANOTHER reservation: this
            // read's own estimate would fit an empty ledger, so the refusal is contention (429),
            // never the own-request 400.
            val capacity = 8L * 1024 * 1024
            val ledger = ReadLedger(capacity = capacity)
            val tuned = TestCatalogFiles.tunedService(readLedger = ledger)
            val other = ledger.open().apply { reserve(capacity - 1) }
            try {
                assertFailsWith<TooManyRequestsException> { tuned.graph(everything) }
                assertFailsWith<TooManyRequestsException> { tuned.errors(everything) }
                assertFailsWith<TooManyRequestsException> { tuned.export(ns) }
            } finally {
                other.close()
            }
            assertNotNull(tuned.graph(everything))
            assertNotNull(tuned.errors(everything))
            assertEquals(1, tuned.export(ns).files.size)
            assertEquals(0L, ledger.inFlightBytes)
        }

    @Test
    fun `a catalog read is decided entirely at admission`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cat-rb-admit")
        val ns = uniqueNamespace("rbns")
        repeat(3) { client.createCatalogFile(componentFile(uniqueEntityName("rb-admit"), namespace = ns)) }
        // The unconstrained read's peak IS its admission estimate (a catalog read has no decode charges).
        val probe = ReadLedger()
        assertEquals(3, TestCatalogFiles.tunedService(readLedger = probe).export(ns).files.size)
        val estimate = probe.peak
        assertTrue(estimate > 0)
        // Exactly that capacity admits the whole read ...
        val exact = ReadLedger(capacity = estimate)
        assertEquals(3, TestCatalogFiles.tunedService(readLedger = exact).export(ns).files.size)
        assertEquals(estimate, exact.peak)
        assertEquals(0L, exact.inFlightBytes)
        // ... and one byte less refuses it up front, before any document is read.
        val tight = ReadLedger(capacity = estimate - 1)
        assertFailsWith<BadRequestException> { TestCatalogFiles.tunedService(readLedger = tight).export(ns) }
        assertEquals(0L, tight.peak)
    }

    @Test
    fun `the paged list, a single read, check and the import dry-run never touch the ledger`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cat-rb-light")
        val name = uniqueEntityName("rb-light")
        val file = componentFile(name, namespace = uniqueNamespace("rbns"))
        val id = client.createCatalogFile(file).id
        val ledger = ReadLedger(capacity = 1)
        val tuned = TestCatalogFiles.tunedService(readLedger = ledger)
        assertTrue(tuned.list(everything, PageRequest(1, 20, emptyList())).total >= 1)
        assertNotNull(tuned.read(id))
        tuned.check(file)
        tuned.importCheck(listOf(file))
        assertEquals(0L, ledger.peak)
    }
}
