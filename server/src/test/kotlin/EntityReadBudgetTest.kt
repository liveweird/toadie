package ch.nokillswit

import ch.nokillswit.entities.ENTITY_READ_BUDGET_BYTES
import ch.nokillswit.entities.EntityReadLedger
import ch.nokillswit.entities.MAX_WORKSPACE_DOCUMENT_BYTES
import ch.nokillswit.entities.ReadBudgetExceeded
import ch.nokillswit.entities.estimatedHeapBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Pure tests of `entities/EntityReadBudget.kt` (2.4.0 — `.claude/docs/security.md` "Entity read
 * memory budget"): the [estimatedHeapBytes] formula per shape, and [EntityReadLedger]'s
 * own-vs-contention distinction plus release-on-close. No database. The write-side cap pin (`MAX_WORKSPACE_DOCUMENT_BYTES`) lives here too.
 */
class EntityReadBudgetTest {

    @Test
    fun `the workspace write budget leaves room for a whole workspace to fit a read beside it`() {
        // .claude/docs/persistence.md / entities/Entity.kt: MAX_WORKSPACE_DOCUMENT_BYTES is
        // deliberately ENTITY_READ_BUDGET_BYTES / 4, so a read charging the raw stored bytes
        // PLUS its decoded relations/team estimate never blows the read ledger just from a
        // workspace sitting right at its write-time cap.
        assertTrue(
            MAX_WORKSPACE_DOCUMENT_BYTES * 4 <= ENTITY_READ_BUDGET_BYTES,
            "MAX_WORKSPACE_DOCUMENT_BYTES ($MAX_WORKSPACE_DOCUMENT_BYTES) * 4 must fit " +
                "ENTITY_READ_BUDGET_BYTES ($ENTITY_READ_BUDGET_BYTES)",
        )
    }

    @Test
    fun `a JsonNull costs nothing`() {
        assertEquals(0L, estimatedHeapBytes(JsonNull))
    }

    @Test
    fun `a scalar costs its overhead plus content length`() {
        val short = estimatedHeapBytes(JsonPrimitive("ab"))
        val long = estimatedHeapBytes(JsonPrimitive("abcdefghij"))
        assertTrue(short > 0)
        assertEquals(8L, long - short) // the extra 8 content chars, overhead cancels
    }

    @Test
    fun `an array costs container overhead plus one slot and its own estimate per element`() {
        val empty = estimatedHeapBytes(buildJsonArray {})
        val oneElement = estimatedHeapBytes(buildJsonArray { add(JsonPrimitive("x")) })
        assertTrue(oneElement > empty)
    }

    @Test
    fun `an object costs container overhead plus one entry plus key length per member`() {
        val empty = estimatedHeapBytes(buildJsonObject {})
        val oneMember = estimatedHeapBytes(buildJsonObject { put("k", JsonPrimitive("v")) })
        assertTrue(oneMember > empty)
    }

    @Test
    fun `a nested object recurses into its members' own estimate`() {
        val flat = estimatedHeapBytes(buildJsonObject { put("k", JsonPrimitive("v")) })
        val nested = estimatedHeapBytes(
            buildJsonObject { put("k", buildJsonObject { put("inner", JsonPrimitive("v")) }) },
        )
        assertTrue(nested > flat)
    }

    @Test
    fun `a reservation charges against the shared ledger and releases on close`() {
        val ledger = EntityReadLedger(capacity = 1000)
        ledger.open().use { reservation ->
            reservation.charge(500)
            assertEquals(500L, ledger.inFlightBytes)
            assertEquals(500L, reservation.charged)
        }
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `a charge that alone exceeds capacity is the caller's own request`() {
        val ledger = EntityReadLedger(capacity = 100)
        ledger.open().use { reservation ->
            val error = assertFailsWith<ReadBudgetExceeded> { reservation.charge(200) }
            assertTrue(error.ownRequest)
        }
        assertEquals(0L, ledger.inFlightBytes)
    }

    @Test
    fun `a charge that fits alone but not alongside another reservation is contention, not the caller's own request`() {
        val ledger = EntityReadLedger(capacity = 100)
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
        val ledger = EntityReadLedger(capacity = 1000)
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
        val ledger = EntityReadLedger(capacity = 10)
        ledger.open().use { reservation ->
            reservation.charge(0)
            reservation.charge(-5)
            assertEquals(0L, ledger.inFlightBytes)
        }
    }
}
