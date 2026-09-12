package ch.nokillswit

import ch.nokillswit.entities.JqEvaluator
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 5 (`.claude/docs/port-data-model.md` "Computed properties"): pure coverage of
 * `entities/JqCalculation.kt` — the jq bridge behind `calculationProperties`. No database.
 */
class JqCalculationTest {

    @Test
    fun `evaluate reads a plain value`() {
        val evaluator = JqEvaluator()
        assertEquals(JsonPrimitive(5), evaluator.evaluate(".a", buildJsonObject { put("a", 5) }))
    }

    @Test
    fun `evaluate interpolates strings`() {
        val evaluator = JqEvaluator()
        val input = buildJsonObject {
            put("a", "x")
            put("b", "y")
        }
        assertEquals(JsonPrimitive("x-y"), evaluator.evaluate("\"\\(.a)-\\(.b)\"", input))
    }

    @Test
    fun `a JSON null result is absent`() {
        val evaluator = JqEvaluator()
        assertNull(evaluator.evaluate(".missing", buildJsonObject { }))
        assertNull(evaluator.evaluate("null", buildJsonObject { }))
    }

    @Test
    fun `empty produces no output and is absent`() {
        assertNull(JqEvaluator().evaluate("empty", buildJsonObject { }))
    }

    @Test
    fun `the first value of a comma expression wins`() {
        val evaluator = JqEvaluator()
        val input = buildJsonObject {
            put("a", 1)
            put("b", 2)
        }
        assertEquals(JsonPrimitive(1), evaluator.evaluate(".a, .b", input))
    }

    @Test
    fun `range aborts at its first value instead of iterating a billion times`() {
        val result = JqEvaluator().evaluate("range(1e9)", buildJsonObject { })
        assertNotNull(result)
        assertEquals(0.0, (result as JsonPrimitive).double)
    }

    @Test
    fun `a compile error is absent`() {
        assertNull(JqEvaluator().evaluate("{{{", buildJsonObject { }))
    }

    @Test
    fun `a user error call is absent`() {
        assertNull(JqEvaluator().evaluate("error(\"boom\")", buildJsonObject { }))
    }

    @Test
    fun `a type error - dot-b on a number - is absent`() {
        assertNull(JqEvaluator().evaluate(".a.b", buildJsonObject { put("a", 5) }))
    }

    @Test
    fun `infinite recursion is absent, not a crash`() {
        assertNull(JqEvaluator().evaluate("def f: f; f", buildJsonObject { }))
    }

    @Test
    fun `env and ENV are shadowed even though the process environment is non-empty`() {
        assertNotNull(System.getenv("PATH"), "test sanity: the JVM process must carry a PATH")
        val evaluator = JqEvaluator()
        assertNull(evaluator.evaluate("env.PATH", buildJsonObject { }))
        assertNull(evaluator.evaluate("\$ENV.PATH", buildJsonObject { }))
    }

    @Test
    fun `include fails closed since no module loader is ever installed`() {
        assertNull(JqEvaluator().evaluate("include \"x\"; .", buildJsonObject { }))
    }

    @Test
    fun `the same expression compiles once regardless of how many times it is evaluated`() {
        val evaluator = JqEvaluator()
        evaluator.evaluate(".a", buildJsonObject { put("a", 1) })
        evaluator.evaluate(".a", buildJsonObject { put("a", 2) })
        assertEquals(1, evaluator.cacheSize)
        evaluator.evaluate(".b", buildJsonObject { put("b", 1) })
        assertEquals(2, evaluator.cacheSize)
    }

    @Test
    fun `an oversize result is absent, never truncated`() {
        assertNull(JqEvaluator().evaluate("\"x\" * 70000", buildJsonObject { }))
    }

    @Test
    fun `the jq builtins are loaded at construction, not on the first evaluation`() {
        // rootScope is a process-wide Lazy shared by every JqEvaluator instance, so this
        // reflects the JVM's overall state — but it must be true immediately after
        // construction, before this instance ever evaluates anything itself, which is the
        // property that matters: `configureDatabase` forces it at boot, long before the
        // first entity read could ever hand a fresh expression to evaluateBounded's deadline.
        val evaluator = JqEvaluator()
        assertTrue(evaluator.builtinsLoaded)
    }

    // ---------------------------------------------------------------------------------------
    // evaluateBounded: the bounded pool, the per-expression deadline, and quarantine
    // ---------------------------------------------------------------------------------------

    @Test
    fun `evaluateBounded reads a plain value through the default pool`() = runBlocking {
        val result = JqEvaluator().evaluateBounded(".a", buildJsonObject { put("a", 1) })
        assertEquals(JsonPrimitive(1), result)
    }

    @Test
    fun `a compile error never reaches the executor`() = runBlocking {
        val executor = Executor { fail("a compile failure must never be submitted to the executor") }
        val evaluator = JqEvaluator(executor = executor)
        val result = evaluator.evaluateBounded("not valid jq ((", buildJsonObject { }, "ctx")
        assertNull(result)
        assertEquals(0, evaluator.quarantinedCount)
    }

    @Test
    fun `a deadline miss is absent and quarantines the expression`() = runBlocking {
        val releaseWorker = CountDownLatch(1)
        // A private pool, so the held worker is never one of the production `entity-jq` four.
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
        val evaluator = JqEvaluator(
            deadline = Duration.ofMillis(50),
            executor = executor,
            beforeEvaluate = { awaitIgnoringInterrupts(releaseWorker) },
        )
        try {
            val result = evaluator.evaluateBounded(".a", buildJsonObject { put("a", 1) }, "ctx")
            assertNull(result)
            assertEquals(1, evaluator.quarantinedCount)
        } finally {
            releaseWorker.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a deadline miss while still queued is absent but not quarantined`() = runBlocking {
        val releaseWorker = CountDownLatch(1)
        val workerBusy = CountDownLatch(1)
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
        executor.execute {
            workerBusy.countDown()
            releaseWorker.await()
        }
        val evaluator = JqEvaluator(deadline = Duration.ofMillis(50), executor = executor)
        val capture = LogCapture("ch.nokillswit.entities.computed")
        try {
            assertTrue(workerBusy.await(2, TimeUnit.SECONDS))

            // The jq task sits queued behind the held worker and never starts before its deadline.
            assertNull(evaluator.evaluateBounded(".a", buildJsonObject { put("a", 1) }))
            assertEquals(0, evaluator.quarantinedCount)
            assertEquals(1, capture.events.count { it.level == ch.qos.logback.classic.Level.WARN })

            // Same episode, worker still held: absent again, still not quarantined, no NEW warning.
            assertNull(evaluator.evaluateBounded(".a", buildJsonObject { put("a", 1) }))
            assertEquals(0, evaluator.quarantinedCount)
            assertEquals(1, capture.events.count { it.level == ch.qos.logback.classic.Level.WARN })
        } finally {
            capture.detach()
            releaseWorker.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a quarantined expression is never resubmitted while another text still runs`() = runBlocking {
        val releaseWorker = CountDownLatch(1)
        val holdNext = AtomicBoolean(true)
        val starts = AtomicInteger(0)
        // Two private workers: ".a"'s held task strands one, ".b" must still find a free one.
        val executor = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
        val evaluator = JqEvaluator(
            deadline = Duration.ofMillis(50),
            executor = executor,
            beforeEvaluate = {
                starts.incrementAndGet()
                if (holdNext.get()) awaitIgnoringInterrupts(releaseWorker)
            },
        )
        try {
            assertNull(evaluator.evaluateBounded(".a", buildJsonObject { put("a", 1) }))
            assertEquals(1, evaluator.quarantinedCount)
            assertEquals(1, starts.get())

            // Same text again: short-circuits on the quarantine set, never reaches the executor.
            assertNull(evaluator.evaluateBounded(".a", buildJsonObject { put("a", 1) }))
            assertEquals(1, starts.get())

            // A different text is unaffected by ".a"'s quarantine.
            holdNext.set(false)
            assertEquals(JsonPrimitive(2), evaluator.evaluateBounded(".b", buildJsonObject { put("b", 2) }))
            assertEquals(2, starts.get())
        } finally {
            releaseWorker.countDown()
        }
    }

    @Test
    fun `the quarantine warning is logged once with the context and never the input`() = runBlocking {
        val releaseWorker = CountDownLatch(1)
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
        val evaluator = JqEvaluator(
            deadline = Duration.ofMillis(50),
            executor = executor,
            beforeEvaluate = { awaitIgnoringInterrupts(releaseWorker) },
        )
        val capture = LogCapture("ch.nokillswit.entities.computed")
        try {
            val input = buildJsonObject { put("secret", "SECRET-VALUE-7") }
            assertNull(evaluator.evaluateBounded(".secret", input, "bp.prop"))
            // A second miss of the same text: no new warning (still quarantined, never resubmitted).
            assertNull(evaluator.evaluateBounded(".secret", input, "bp.prop"))

            val warnings = capture.events.filter { it.level == ch.qos.logback.classic.Level.WARN }
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().formattedMessage.contains("bp.prop"))
            assertTrue(capture.events.none { it.formattedMessage.contains("SECRET-VALUE-7") })
        } finally {
            capture.detach()
            releaseWorker.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `pool saturation is absent, not quarantined, and warns once per episode`() = runBlocking {
        val executor = ToggleRejectingExecutor()
        val evaluator = JqEvaluator(executor = executor)
        val capture = LogCapture("ch.nokillswit.entities.computed")
        try {
            repeat(3) {
                assertNull(evaluator.evaluateBounded(".a", buildJsonObject { put("a", 1) }))
            }
            assertEquals(0, evaluator.quarantinedCount)
            assertEquals(1, capture.events.count { it.level == ch.qos.logback.classic.Level.WARN })

            executor.reject = false
            assertEquals(JsonPrimitive(1), evaluator.evaluateBounded(".a", buildJsonObject { put("a", 1) }))

            executor.reject = true
            assertNull(evaluator.evaluateBounded(".a", buildJsonObject { put("a", 1) }))
            assertEquals(2, capture.events.count { it.level == ch.qos.logback.classic.Level.WARN })
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `caller cancellation propagates without quarantining`() = runBlocking {
        val releaseWorker = CountDownLatch(1)
        val workerStarted = CountDownLatch(1)
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
        executor.execute {
            workerStarted.countDown()
            releaseWorker.await()
        }
        val evaluator = JqEvaluator(deadline = Duration.ofSeconds(5), executor = executor)
        try {
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS))
            val job = launch(Dispatchers.Default) {
                evaluator.evaluateBounded(".a", buildJsonObject { put("a", 1) })
            }
            withContext(Dispatchers.IO) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (executor.queue.size != 1 && System.nanoTime() < deadline) Thread.onSpinWait()
                assertEquals(1, executor.queue.size)
            }
            job.cancelAndJoin()
            assertEquals(0, evaluator.quarantinedCount)
            assertTrue(executor.queue.isEmpty())
        } finally {
            releaseWorker.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent evaluations share one compiled expression per text`() = runBlocking {
        // A private pool sized for the burst: 200 coroutines are in flight at once, so the
        // production pool's 4 + 64 capacity would REJECT some by design (absent), and a cold CI
        // JVM's first compiles could exceed a 500 ms deadline while queued. Neither is what this
        // case pins — only that one compiled expression per text serves every worker correctly.
        val executor = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256))
        val evaluator = JqEvaluator(deadline = Duration.ofSeconds(30), executor = executor)
        try {
            val expressions = (0 until 8).map { ".a + $it" }
            val results = (0 until 200).map { i ->
                async(Dispatchers.Default) {
                    evaluator.evaluateBounded(expressions[i % expressions.size], buildJsonObject { put("a", 1) })
                }
            }.awaitAll()
            results.forEachIndexed { i, result -> assertEquals(JsonPrimitive(1 + (i % expressions.size)), result) }
            assertEquals(8, evaluator.cacheSize)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `the compile cache clears when it overflows`() {
        val evaluator = JqEvaluator(maxCachedExpressions = 3)
        evaluator.evaluate(".a", buildJsonObject { put("a", 1) })
        evaluator.evaluate(".b", buildJsonObject { put("b", 1) })
        evaluator.evaluate(".c", buildJsonObject { put("c", 1) })
        assertEquals(3, evaluator.cacheSize)
        assertEquals(JsonPrimitive(4), evaluator.evaluate(".d", buildJsonObject { put("d", 4) }))
        assertEquals(1, evaluator.cacheSize)
    }
}

/**
 * Blocks on [latch] until it releases, swallowing interruption and retrying the wait — models
 * jackson-jq's own indifference to `Thread.interrupt()` (the same fixture idiom as
 * `UrlFetchTest`'s "native DNS ignores interruption"), so a cancelled [JqEvaluator.evaluateBounded]
 * caller's `task.cancel(true)` does not end the wait early.
 */
private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
    while (latch.count > 0) {
        try {
            latch.await(20, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            // Deliberately model an uninterruptible worker (jackson-jq ignores interruption too).
        }
    }
}

/** Rejects every submission while [reject] is true (saturation), runs inline otherwise. */
private class ToggleRejectingExecutor : Executor {
    var reject = true

    override fun execute(command: Runnable) {
        if (reject) throw RejectedExecutionException("full")
        command.run()
    }
}
