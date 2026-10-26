package ch.nokillswit.infra.concurrency

import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

/**
 * A point-in-time reading of one bounded worker pool (the jq evaluator's `entity-jq`, the outbound fetch's `url-fetch`): tasks
 * running on a worker ([active]), tasks waiting in the queue ([queued]) and the pool's fixed bounds. Read by the telemetry sampler
 * (`.claude/docs/observability.md` "Telemetry"); observing it never alters the pool.
 */
class ExecutorStats(val active: Int, val queued: Int, val workers: Int, val queueCapacity: Int)

/**
 * The reading of this executor when it is a [ThreadPoolExecutor] (both production pools are); any other executor - a test's inline
 * or lambda executor - has no bounds to report and answers zeros.
 */
fun Executor.stats(): ExecutorStats {
    val pool = this as? ThreadPoolExecutor ?: return ExecutorStats(active = 0, queued = 0, workers = 0, queueCapacity = 0)
    val queued = pool.queue.size
    return ExecutorStats(pool.activeCount, queued, pool.maximumPoolSize, queued + pool.queue.remainingCapacity())
}
