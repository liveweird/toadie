package ch.nokillswit

import ch.nokillswit.entityquery.levenshtein
import ch.nokillswit.entityquery.suggest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure coverage of `entityquery/Suggestions.kt` (PR1 P1.4): the validator's "did you mean"
 * lookup behind every `UNKNOWN_*` diagnostic. No database.
 */
class SuggestionsTest {

    @Test
    fun `levenshtein distance over a handful of known pairs`() {
        assertEquals(0, levenshtein("service", "service"))
        assertEquals(0, levenshtein("", ""))
        assertEquals(3, levenshtein("", "abc"))
        assertEquals(3, levenshtein("abc", ""))
        assertEquals(3, levenshtein("kitten", "sitting"))
        assertEquals(1, levenshtein("service", "servics"))
    }

    @Test
    fun `exact folded match wins outright regardless of distance`() {
        assertEquals("Service", suggest("  service  ", listOf("Service", "System")))
        assertEquals("SERVICE", suggest("service", listOf("SERVICE", "Service")))
    }

    @Test
    fun `a candidate within the distance threshold qualifies`() {
        // "servics" -> "service": distance 1, threshold max(2, 7/3=2) = 2.
        assertEquals("service", suggest("servics", listOf("service", "resource")))
    }

    @Test
    fun `a candidate outside the distance threshold but with input as a prefix qualifies`() {
        // "system" is a strict prefix of "systemxyz"; distance 3 exceeds threshold 2.
        assertEquals("systemxyz", suggest("system", listOf("systemxyz", "unrelated")))
    }

    @Test
    fun `a candidate containing input as a substring qualifies only for input of 3+ chars`() {
        assertEquals("prefix-service-suffix", suggest("service", listOf("prefix-service-suffix", "unrelated")))
        // "ab" is only 2 chars: substring alone must not qualify a distant candidate.
        assertNull(suggest("ab", listOf("prefix-ab-suffix")))
    }

    @Test
    fun `ties break by smaller distance then alphabetically`() {
        // "cat" and "dot" are both distance 1 from "cot"; "cat" sorts first.
        assertEquals("cat", suggest("cot", listOf("dot", "cat")))
        // "cot" and "bat" are both distance 1 from "cat" too; "bat" sorts first.
        assertEquals("bat", suggest("cat", listOf("cot", "bat")))
    }

    @Test
    fun `nothing qualifies answers null`() {
        assertNull(suggest("xyz", listOf("completelyDifferent", "alsoUnrelated")))
    }

    @Test
    fun `empty candidates answer null`() {
        assertNull(suggest("anything", emptyList()))
    }
}
