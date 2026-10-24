package ch.nokillswit

import ch.nokillswit.infra.concurrency.ReadBudgetExceeded
import ch.nokillswit.infra.concurrency.ReadLedger
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * Pure tests of `infra/concurrency/ReadLedger.kt` (`.claude/docs/security.md` "Entity read memory
 * budget"): [ReadLedger]'s own-vs-contention distinction, release-on-close and (2.19.0) the
 * all-or-nothing prepaid admission. No database.
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

    @Test
    fun `a reservation admits its whole estimate at once and releases it on close`() {
        val ledger = ReadLedger(capacity = 1000)
        ledger.open().use { reservation ->
            reservation.reserve(700)
            assertEquals(700L, ledger.inFlightBytes)
            assertEquals(700L, reservation.reserved)
            assertEquals(700L, ledger.peak)
        }
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `an estimate that alone exceeds capacity is refused at admission as the caller's own request`() {
        val ledger = ReadLedger(capacity = 100)
        ledger.open().use { reservation ->
            val error = assertFailsWith<ReadBudgetExceeded> { reservation.reserve(101) }
            assertTrue(error.ownRequest)
            assertEquals(0L, reservation.reserved)
        }
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `an estimate that fits alone but not beside a sibling is refused as contention and the sibling keeps its room`() {
        val ledger = ReadLedger(capacity = 100)
        ledger.open().use { first ->
            first.reserve(60)
            ledger.open().use { second ->
                val error = assertFailsWith<ReadBudgetExceeded> { second.reserve(60) }
                assertFalse(error.ownRequest)
                assertEquals(0L, second.reserved)
            }
            assertEquals(60L, ledger.inFlightBytes)
        }
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `decode charges draw down the prepaid estimate without moving the shared total`() {
        val ledger = ReadLedger(capacity = 1000)
        ledger.open().use { reservation ->
            reservation.reserve(500)
            reservation.charge(200)
            reservation.charge(300)
            assertEquals(500L, ledger.inFlightBytes)
            assertEquals(500L, reservation.charged)
            assertEquals(0L, reservation.overflow)
        }
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `a decode charge beyond the prepaid estimate reserves only the excess`() {
        val ledger = ReadLedger(capacity = 1000)
        ledger.open().use { reservation ->
            reservation.reserve(100)
            reservation.charge(60)
            reservation.charge(70) // 40 prepaid left, 30 over
            assertEquals(130L, ledger.inFlightBytes)
            assertEquals(130L, reservation.charged)
            assertEquals(30L, reservation.overflow)
            // The excess is refused like any other charge once it no longer fits.
            val error = assertFailsWith<ReadBudgetExceeded> { reservation.charge(900) }
            assertTrue(error.ownRequest)
            assertEquals(130L, ledger.inFlightBytes)
        }
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `when estimates sum beyond capacity exactly the reads that fit are admitted and the rest are refused up front`() {
        val ledger = ReadLedger(capacity = 100)
        val reservations = List(7) { ledger.open() }
        try {
            val refusals = reservations.mapNotNull { reservation ->
                try {
                    reservation.reserve(30)
                    null
                } catch (error: ReadBudgetExceeded) {
                    error
                }
            }
            assertEquals(4, refusals.size)
            assertTrue(refusals.none { it.ownRequest })
            assertEquals(90L, ledger.inFlightBytes)
            // The admitted reads decode within their prepaid estimate: the total does not move.
            reservations.filter { it.reserved > 0 }.forEach { it.charge(30) }
            assertEquals(90L, ledger.inFlightBytes)
            // Closing one admitted read makes room for exactly one more.
            reservations.first { it.reserved > 0 }.close()
            reservations.first { it.reserved == 0L }.reserve(30)
            assertEquals(90L, ledger.inFlightBytes)
        } finally {
            reservations.forEach { it.close() }
        }
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `concurrent admissions never starve each other`() = runBlocking {
        val ledger = ReadLedger(capacity = 100)
        val attempts = 16
        val attempted = AtomicInteger(0)
        val allAttempted = CompletableDeferred<Unit>()
        val admitted = (1..attempts).map {
            async(Dispatchers.Default) {
                val reservation = ledger.open()
                val ok = try {
                    reservation.reserve(25)
                    true
                } catch (_: ReadBudgetExceeded) {
                    false
                }
                // Hold every admitted reservation until each attempt has returned: no timing.
                if (attempted.incrementAndGet() == attempts) allAttempted.complete(Unit)
                allAttempted.await()
                reservation.close()
                ok
            }
        }.awaitAll()
        assertEquals(4, admitted.count { it })
        assertEquals(0L, ledger.inFlightBytes)
    }
}
