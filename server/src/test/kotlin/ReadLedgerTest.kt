package ch.nokillswit

import ch.nokillswit.infra.concurrency.ReadBudgetExceeded
import ch.nokillswit.infra.concurrency.ReadLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure tests of `infra/concurrency/ReadLedger.kt` (`.claude/docs/security.md` "Entity read memory
 * budget"): [ReadLedger]'s own-vs-contention distinction plus release-on-close. No database.
 */
class ReadLedgerTest {

    @Test
    fun `a reservation charges against the shared ledger and releases on close`() {
        val ledger = ReadLedger(capacity = 1000)
        ledger.open().use { reservation ->
            reservation.charge(500)
            assertEquals(500L, ledger.inFlightBytes)
            assertEquals(500L, reservation.charged)
        }
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `a charge that alone exceeds capacity is the caller's own request`() {
        val ledger = ReadLedger(capacity = 100)
        ledger.open().use { reservation ->
            val error = assertFailsWith<ReadBudgetExceeded> { reservation.charge(200) }
            assertTrue(error.ownRequest)
        }
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `a charge that fits alone but not alongside another reservation is contention, not the caller's own request`() {
        val ledger = ReadLedger(capacity = 100)
        ledger.open().use { first ->
            first.charge(60)
            ledger.open().use { second ->
                val error = assertFailsWith<ReadBudgetExceeded> { second.charge(60) }
                assertFalse(error.ownRequest)
            }
        }
    }

    @Test
    fun `peak tracks the highest observed in-flight total across reservations`() {
        val ledger = ReadLedger(capacity = 1000)
        ledger.open().use { first ->
            first.charge(300)
            ledger.open().use { second ->
                second.charge(200)
                assertEquals(500L, ledger.peak)
            }
        }
        // peak is never reduced by closing reservations
        assertEquals(500L, ledger.peak)
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `a non-positive charge is a no-op`() {
        val ledger = ReadLedger(capacity = 10)
        ledger.open().use { reservation ->
            reservation.charge(0)
            reservation.charge(-5)
            assertEquals(0L, ledger.inFlightBytes)
        }
    }
}
