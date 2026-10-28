package ch.nokillswit

import ch.nokillswit.auth.sso.StaleServingCache
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * The refresh rules shared by SSO discovery and the JWKS (`auth/sso/StaleServingCache.kt`), on a hand-moved clock:
 * single flight outside any lock, fetch-then-swap, bounded staleness, and a retry spacing that never caches a failure as good.
 */
class StaleServingCacheTest {

    private class Rig(var now: Long = 0L) {
        val loads = AtomicInteger()
        var failing = false
        val cache = StaleServingCache<Int>(ttlMillis = 1_000, maxStaleMillis = 10_000, refreshIntervalMillis = 100, clock = { now }) {
            val n = loads.incrementAndGet()
            check(!failing) { "upstream down" }
            n
        }
    }

    @Test
    fun `a fresh value is served without a load, an expired one is replaced`() = runBlocking<Unit> {
        val rig = Rig()
        assertEquals(1, rig.cache.get())
        assertEquals(1, rig.cache.get())
        rig.now = 1_001
        assertEquals(2, rig.cache.get())
        assertEquals(2, rig.loads.get())
    }

    @Test
    fun `with no previous value every call retries and the failure is never cached`() = runBlocking<Unit> {
        val rig = Rig()
        rig.failing = true
        assertFailsWith<IllegalStateException> { rig.cache.get() }
        assertFailsWith<IllegalStateException> { rig.cache.get() }
        assertEquals(2, rig.loads.get())
        rig.failing = false
        assertEquals(3, rig.cache.get())
    }

    @Test
    fun `after the TTL a failing upstream keeps serving the last good value, retried at most once per interval, up to the bound`() =
        runBlocking<Unit> {
            val rig = Rig()
            assertEquals(1, rig.cache.get())
            rig.failing = true
            rig.now = 2_000
            assertEquals(1, rig.cache.get(), "stale value served on a failed refresh")
            assertEquals(2, rig.loads.get())
            assertEquals(1, rig.cache.get())
            assertEquals(2, rig.loads.get(), "no second attempt inside the interval")
            rig.now = 2_101
            assertEquals(1, rig.cache.get())
            assertEquals(3, rig.loads.get())
            // Recovery swaps the value in.
            rig.failing = false
            rig.now = 2_300
            assertEquals(4, rig.cache.get())
            // Beyond ttl + maxStale since the last good load there is nothing left to serve.
            rig.failing = true
            rig.now = 2_300 + 1_000 + 10_000
            assertFailsWith<IllegalStateException> { rig.cache.get() }
        }

    @Test
    fun `a value that is wanted fresher is refreshed at most once per interval and a failed refresh keeps the value`() = runBlocking<Unit> {
        val rig = Rig()
        assertEquals(1, rig.cache.get())
        assertEquals(1, rig.cache.get { true }, "inside the interval since the load: no refresh")
        assertEquals(1, rig.loads.get())
        rig.now = 150
        assertEquals(2, rig.cache.get { it < 2 })
        assertEquals(2, rig.cache.get { it < 2 })
        assertEquals(2, rig.loads.get())
        rig.failing = true
        rig.now = 300
        assertEquals(2, rig.cache.get { true }, "a failing refresh of a fresh value still answers")
    }

    @Test
    fun `overlapping callers share one load and a cancelled leader hands over to a waiter`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val cache = StaleServingCache<Int>(ttlMillis = 1_000, clock = { 0L }) {
            val n = loads.incrementAndGet()
            if (n == 1) started.complete(Unit)
            release.await()
            n
        }
        val leader = launch(start = CoroutineStart.UNDISPATCHED) { cache.get() }
        started.await()
        val waiters = List(5) { async(start = CoroutineStart.UNDISPATCHED) { cache.get() } }
        yield()
        assertEquals(1, loads.get(), "one load for six callers")
        leader.cancelAndJoin()
        // The waiters take over: exactly one more load runs, and they all see its value.
        release.complete(Unit)
        val values = waiters.map { it.await() }
        assertEquals(List(5) { 2 }, values)
        assertEquals(2, loads.get())
        assertTrue(cache.get() == 2)
    }

    @Test
    fun `a cancelled leader does not count as an attempt, so a waiter wanting fresh data still loads`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        var now = 0L
        val cache = StaleServingCache<Int>(ttlMillis = 1_000, refreshIntervalMillis = 100, clock = { now }) {
            val n = loads.incrementAndGet()
            if (n == 2) {
                started.complete(Unit)
                release.await()
            }
            n
        }
        assertEquals(1, cache.get())
        // A rotated key past the retry interval: the cached value is not good enough, and the leader's refresh hangs.
        now = 200
        val leader = launch(start = CoroutineStart.UNDISPATCHED) { cache.get { it < 2 } }
        started.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { cache.get { it < 2 } }
        yield()
        leader.cancelAndJoin()
        release.complete(Unit)
        assertEquals(3, waiter.await(), "the waiter loads instead of being served the old value for an interval")
    }

    @Test
    fun `a leader cancelled by its own deadline counts as an attempt, and the value stays peekable only inside the stale bound`() =
        runBlocking<Unit> {
            val loads = AtomicInteger()
            var now = 0L
            val hang = CompletableDeferred<Unit>()
            val cache = StaleServingCache<Int>(
                ttlMillis = 1_000, maxStaleMillis = 10_000, refreshIntervalMillis = 100, clock = { now },
            ) {
                val n = loads.incrementAndGet()
                if (n > 1) hang.await()
                n
            }
            assertNull(cache.peekUsable(), "nothing cached yet")
            assertEquals(1, cache.get())
            now = 2_000
            assertEquals(1, cache.peekUsable())
            // The refresh hangs and the caller's deadline cancels it.
            assertNull(withTimeoutOrNull(50) { cache.get() })
            assertEquals(2, loads.get())
            // A deadline is an attempt: inside the interval the stale value is served with no second load.
            assertEquals(1, cache.get())
            assertEquals(2, loads.get())
            // A plain cancellation is not (the sibling test): past the interval the next caller loads again.
            now = 2_101
            assertNull(withTimeoutOrNull(50) { cache.get() })
            assertEquals(3, loads.get())
            now = 1_000 + 10_000
            assertNull(cache.peekUsable(), "past ttl + maxStale nothing is servable")
        }
}
