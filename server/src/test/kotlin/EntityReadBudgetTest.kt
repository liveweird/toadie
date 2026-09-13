package ch.nokillswit

import ch.nokillswit.entities.ENTITY_READ_BUDGET_BYTES
import ch.nokillswit.entities.MAX_WORKSPACE_DOCUMENT_BYTES
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `entities/EntityReadBudget.kt` (2.4.0) — the entity READ memory budget. This file is shared
 * with the read-side ledger/estimator cases (`EntityReadLedger`, `estimatedHeapBytes`); keep new
 * cases in their own `@Test` function so the two halves merge without touching each other's code.
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
}
