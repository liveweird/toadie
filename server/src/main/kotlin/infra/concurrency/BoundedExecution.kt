package ch.nokillswit.infra.concurrency

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Runs [block] on [this] executor and suspends until it completes, with full cancellation
 * plumbing: cancelling the calling coroutine cancels the task (interrupting it if running),
 * removes it from a ThreadPoolExecutor's queue if still waiting, and invokes [onCancel] (a
 * hook for the caller's own in-flight resources). A queue/worker saturation rejection
 * surfaces as [RejectedExecutionException] to the caller — each consumer maps it to its own
 * failure type. Extracted from the outbound URL fetch (catalog/UrlFetch.kt, see
 * .claude/docs/security.md "Native DNS limitation and bounded containment"); the jq
 * calculation evaluator is its second consumer.
 */
suspend fun <T> Executor.awaitBounded(onCancel: () -> Unit = {}, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val task = object : FutureTask<T>(block) {
            override fun done() {
                if (!continuation.isActive) return
                try {
                    continuation.resume(get())
                } catch (_: java.util.concurrent.CancellationException) {
                    // The coroutine cancellation handler owns this outcome.
                } catch (cause: ExecutionException) {
                    continuation.resumeWithException(cause.cause ?: cause)
                } catch (cause: InterruptedException) {
                    Thread.currentThread().interrupt()
                    continuation.resumeWithException(cause)
                }
            }
        }
        continuation.invokeOnCancellation {
            onCancel()
            task.cancel(true)
            (this as? ThreadPoolExecutor)?.remove(task)
        }
        try {
            if (continuation.isActive) {
                execute(task)
                // Cancellation can win between the pre-submit check and queue insertion.
                if (task.isCancelled) (this as? ThreadPoolExecutor)?.remove(task)
            }
        } catch (e: RejectedExecutionException) {
            onCancel()
            task.cancel(true)
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }
