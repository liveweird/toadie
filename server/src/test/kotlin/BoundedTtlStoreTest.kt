package ch.nokillswit

import ch.nokillswit.infra.concurrency.BoundedTtlStore
import ch.nokillswit.infra.concurrency.BoundedTtlStore.Admission
import ch.nokillswit.infra.concurrency.WhenFull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure unit tests for the bounded TTL store primitive (injected clock, no database). */
class BoundedTtlStoreTest {

    private var now = 1_000L

    /** A value is its own expiry instant; [Counter] below carries state for the [BoundedTtlStore.update] cases. */
    private fun expiring(capacity: Int, whenFull: WhenFull) =
        BoundedTtlStore<String, Long>(capacity, whenFull, { now }) { it }

    private data class Counter(val expiresAt: Long, val count: Int)

    private fun counters() = BoundedTtlStore<String, Counter>(10, WhenFull.REFUSE, { now }) { it.expiresAt }

    @Test
    fun `admit stores once, reports a duplicate, and admits the key again after expiry`() {
        val s = expiring(10, WhenFull.REFUSE)
        assertEquals(Admission.ADMITTED, s.admit("k", now + 100))
        assertEquals(Admission.DUPLICATE, s.admit("k", now + 100))
        now += 100
        assertEquals(Admission.ADMITTED, s.admit("k", now + 100))
    }

    @Test
    fun `REFUSE at capacity refuses admit and issue, keeps live entries, and expiry reopens admission`() {
        val s = expiring(2, WhenFull.REFUSE)
        assertEquals(Admission.ADMITTED, s.admit("a", now + 100))
        assertEquals(Admission.ADMITTED, s.admit("b", now + 200))
        assertEquals(Admission.FULL, s.admit("c", now + 300))
        assertNull(s.issue(now + 300) { "c" })
        assertEquals(2, s.size)
        assertEquals(Admission.DUPLICATE, s.admit("a", now + 100))
        assertEquals(now + 200, s.remove("b"))

        assertEquals(Admission.ADMITTED, s.admit("b", now + 200))
        now += 100 // "a" expires; the sweep runs before the capacity decision
        assertEquals(Admission.ADMITTED, s.admit("c", now + 300))
        assertEquals(2, s.size)
    }

    @Test
    fun `EVICT_OLDEST never refuses, drops the eldest live entry, and a duplicate evicts nothing`() {
        val s = expiring(2, WhenFull.EVICT_OLDEST)
        assertEquals(Admission.ADMITTED, s.admit("a", now + 1_000))
        assertEquals(Admission.ADMITTED, s.admit("b", now + 1_000))
        assertEquals(Admission.DUPLICATE, s.admit("b", now + 1_000))
        assertEquals(2, s.size)
        assertNotNull(s.issue(now + 1_000) { "z" }) // evicts "a"
        assertEquals(2, s.size)
        assertEquals(Admission.ADMITTED, s.admit("c", now + 1_000)) // evicts "b"
        assertNull(s.remove("a"))
        assertNull(s.remove("b"))
        assertNotNull(s.remove("z"))
        assertNotNull(s.remove("c"))
        // the evicted key admits again
        assertEquals(Admission.ADMITTED, s.admit("a", now + 1_000))
    }

    @Test
    fun `the sweep drops an expired entry queued behind a live one, so a full EVICT_OLDEST store keeps the live one`() {
        // The SsoSeenStates delta: a head-only purge would stop at the live "a" and evict it, letting its state replay.
        val s = expiring(2, WhenFull.EVICT_OLDEST)
        assertEquals(Admission.ADMITTED, s.admit("a", now + 1_000))
        assertEquals(Admission.ADMITTED, s.admit("b", now + 100))
        now += 200
        assertEquals(Admission.ADMITTED, s.admit("c", now + 1_000))
        assertEquals(Admission.DUPLICATE, s.admit("a", now + 1_000))
        assertNull(s.remove("b"))
        assertEquals(2, s.size)
    }

    @Test
    fun `issue under EVICT_OLDEST evicts nothing when the key generator throws`() {
        val s = expiring(1, WhenFull.EVICT_OLDEST)
        assertEquals(Admission.ADMITTED, s.admit("a", now + 1_000))
        assertFailsWith<IllegalStateException> { s.issue(now + 1_000) { error("no key") } }
        assertEquals(now + 1_000, s.remove("a"))
    }

    @Test
    fun `a non-positive capacity is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { expiring(0, WhenFull.EVICT_OLDEST) }
        assertFailsWith<IllegalArgumentException> { expiring(-1, WhenFull.REFUSE) }
    }

    @Test
    fun `issue regenerates a key that collides with a live entry`() {
        val s = expiring(10, WhenFull.REFUSE)
        assertEquals(Admission.ADMITTED, s.admit("a", now + 100))
        val candidates = ArrayDeque(listOf("a", "a", "b"))
        val generated = AtomicInteger()
        val key = s.issue(now + 100) {
            generated.incrementAndGet()
            candidates.removeFirst()
        }
        assertEquals("b", key)
        assertEquals(3, generated.get())
        assertEquals(2, s.size)
    }

    @Test
    fun `update propagates the result, replaces, removes on null, and refuses to insert`() {
        val s = counters()
        // absent: the block sees null, the result propagates, nothing is inserted
        assertEquals(
            "absent",
            s.update("k") { current ->
                assertNull(current)
                null to "absent"
            },
        )
        assertEquals(0, s.size)

        assertEquals(Admission.ADMITTED, s.admit("k", Counter(now + 100, 0)))
        assertEquals(1, s.update("k") { c -> c!!.copy(count = c.count + 1) to c.count + 1 })
        assertEquals(1, s.remove("k")!!.count)

        assertEquals(Admission.ADMITTED, s.admit("k", Counter(now + 100, 5)))
        assertEquals("gone", s.update("k") { null to "gone" })
        assertEquals(0, s.size)

        assertFailsWith<IllegalStateException> { s.update("missing") { Counter(now + 100, 0) to Unit } }
        assertEquals(0, s.size)
    }

    @Test
    fun `update and remove see an expired entry - the policy judges expiry`() {
        val s = counters()
        s.admit("k", Counter(now + 100, 0))
        now += 100
        assertEquals(true, s.update("k") { c -> null to (c != null && c.expiresAt <= now) })
        s.admit("j", Counter(now + 100, 0))
        now += 100
        assertNotNull(s.remove("j"))
    }

    @Test
    fun `remove hands the value out once, expired or not`() {
        val s = expiring(10, WhenFull.REFUSE)
        s.admit("k", now + 100)
        assertEquals(now + 100, s.remove("k"))
        assertNull(s.remove("k"))

        val expiry = now + 100
        s.admit("e", expiry)
        now += 500
        assertEquals(expiry, s.remove("e"))
        assertNull(s.remove("e"))
    }

    private fun <T> contend(contenders: Int, task: (Int) -> T): List<T> {
        val ready = CountDownLatch(contenders)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(contenders)
        try {
            val futures = (1..contenders).map { n ->
                executor.submit<T> {
                    ready.countDown()
                    start.await()
                    task(n)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "all contenders should reach the gate")
            start.countDown()
            return futures.map { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent issues into a REFUSE store cannot overshoot capacity`() {
        val capacity = 7
        val s = expiring(capacity, WhenFull.REFUSE)
        val counter = AtomicInteger()
        val keys = contend(32) { s.issue(now + 1_000) { "k${counter.incrementAndGet()}" } }
        assertEquals(capacity, keys.count { it != null })
        assertEquals(capacity, keys.filterNotNull().toSet().size)
        assertEquals(capacity, s.size)
    }

    @Test
    fun `concurrent removes of one key hand it to exactly one caller`() {
        val s = expiring(10, WhenFull.REFUSE)
        s.admit("k", now + 1_000)
        assertEquals(1, contend(32) { s.remove("k") }.count { it != null })
    }

    @Test
    fun `concurrent updates lose no increment`() {
        val s = counters()
        s.admit("k", Counter(now + 1_000, 0))
        contend(64) { s.update("k") { c -> c!!.copy(count = c.count + 1) to Unit } }
        assertEquals(64, s.remove("k")!!.count)
    }

    @Test
    fun `concurrent EVICT_OLDEST admissions settle at exactly capacity`() {
        val capacity = 5
        val s = expiring(capacity, WhenFull.EVICT_OLDEST)
        val admissions = contend(2 * capacity) { n -> s.admit("k$n", now + 1_000) }
        assertTrue(admissions.all { it == Admission.ADMITTED })
        assertEquals(capacity, s.size)
    }
}
