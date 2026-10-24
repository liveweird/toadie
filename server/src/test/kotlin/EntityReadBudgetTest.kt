package ch.nokillswit

import ch.nokillswit.entities.MAX_WORKSPACE_DOCUMENT_BYTES
import ch.nokillswit.entities.estimatedHeapBytes
import ch.nokillswit.infra.concurrency.READ_BUDGET_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Pure tests of `entities/EntityReadBudget.kt` (2.4.0 — `.claude/docs/security.md` "Entity read
 * memory budget"): the [estimatedHeapBytes] formula per shape and the write-side cap pin
 * (`MAX_WORKSPACE_DOCUMENT_BYTES`). No database. The ledger itself is `ReadLedgerTest`.
 */
class EntityReadBudgetTest {

    @Test
    fun `the workspace write budget leaves room for a whole workspace to fit a read beside it`() {
        // .claude/docs/persistence.md / entities/Entity.kt: MAX_WORKSPACE_DOCUMENT_BYTES is
        // deliberately READ_BUDGET_BYTES / 4, so a read charging the raw stored bytes
        // PLUS its decoded relations/team estimate never blows the read ledger just from a
        // workspace sitting right at its write-time cap.
        assertTrue(
            MAX_WORKSPACE_DOCUMENT_BYTES * 4 <= READ_BUDGET_BYTES,
            "MAX_WORKSPACE_DOCUMENT_BYTES ($MAX_WORKSPACE_DOCUMENT_BYTES) * 4 must fit " +
                "READ_BUDGET_BYTES ($READ_BUDGET_BYTES)",
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
}
