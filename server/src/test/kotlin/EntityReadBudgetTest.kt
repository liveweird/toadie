package ch.nokillswit

import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.ENTITY_SHOWN_ROW_RATIO
import ch.nokillswit.entities.ENTITY_TARGET_ADMISSION_RATIO
import ch.nokillswit.entities.ENTITY_TARGET_ROW_RATIO
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.MAX_WORKSPACE_DOCUMENT_BYTES
import ch.nokillswit.entities.documentByteSize
import ch.nokillswit.entities.estimatedHeapBytes
import ch.nokillswit.entities.toDocument
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
 * memory budget"): the [estimatedHeapBytes] formula per shape, the write-side cap pin
 * (`MAX_WORKSPACE_DOCUMENT_BYTES`) and the admission-ratio calibration against the Port sample.
 * No database. The ledger itself is `ReadLedgerTest`.
 */
class EntityReadBudgetTest {

    @Test
    fun `a paged read over a cap-sized workspace always fits alone`() {
        // .claude/docs/persistence.md / entities/Entity.kt: a PAGED read (list/read) admits the
        // workspace's target rows at raw x ENTITY_TARGET_ADMISSION_RATIO and then charges the ones it
        // actually decodes (relations/team only, properties are decoded on demand) as overflow, at
        // worst ENTITY_TARGET_ROW_RATIO x raw in total. A workspace sitting right at its write-time
        // cap therefore never blows the read ledger just by being read, even if every row were
        // decoded. The UNPAGED graph/errors reads prepay every shown row at ENTITY_SHOWN_ROW_RATIO, so
        // their ceiling is READ_BUDGET_BYTES / ENTITY_SHOWN_ROW_RATIO = 8 MiB of stored workspace —
        // half the write cap — beyond which they are refused with the named 400
        // (`.claude/docs/security.md` "Entity read memory budget").
        assertTrue(
            MAX_WORKSPACE_DOCUMENT_BYTES * ENTITY_TARGET_ADMISSION_RATIO <= READ_BUDGET_BYTES,
            "admission of a cap-sized workspace must fit READ_BUDGET_BYTES ($READ_BUDGET_BYTES)",
        )
        assertTrue(
            MAX_WORKSPACE_DOCUMENT_BYTES * ENTITY_TARGET_ROW_RATIO <= READ_BUDGET_BYTES,
            "MAX_WORKSPACE_DOCUMENT_BYTES ($MAX_WORKSPACE_DOCUMENT_BYTES) * ENTITY_TARGET_ROW_RATIO " +
                "($ENTITY_TARGET_ROW_RATIO) must fit READ_BUDGET_BYTES ($READ_BUDGET_BYTES)",
        )
    }

    @Test
    fun `the baseline ontology's documents decode within the shown- and target-row ratios`() {
        // The calibration pin behind ENTITY_SHOWN_ROW_RATIO / ENTITY_TARGET_ROW_RATIO (the worst-case
        // decode of a target row): workspace-level
        // sums over the Port sample, raw = the stored encoding the service writes (document + team).
        var raw = 0L
        var allEstimate = 0L
        var skeletonEstimate = 0L
        SampleData.numberedFiles("entities")
            .flatMap { blueprintJson.decodeFromString<List<EntityRequest>>(it.readText()) }
            .forEach { request ->
                val document = request.toDocument()
                raw += documentByteSize(document, request.team)
                val skeleton = estimatedHeapBytes(document.relations) + (request.team?.let(::estimatedHeapBytes) ?: 0L)
                skeletonEstimate += skeleton
                allEstimate += skeleton + estimatedHeapBytes(document.properties)
            }
        assertTrue(raw > 0)
        assertTrue(
            raw + allEstimate <= ENTITY_SHOWN_ROW_RATIO * raw,
            "shown rows: raw $raw + decoded $allEstimate must stay within $ENTITY_SHOWN_ROW_RATIO x raw",
        )
        assertTrue(
            raw + skeletonEstimate <= ENTITY_TARGET_ROW_RATIO * raw,
            "target rows: raw $raw + relations/team $skeletonEstimate must stay within $ENTITY_TARGET_ROW_RATIO x raw",
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
