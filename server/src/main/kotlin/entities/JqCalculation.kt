package ch.nokillswit.entities

import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.infra.concurrency.awaitBounded
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import net.thisptr.jackson.jq.BuiltinFunctionLoader
import net.thisptr.jackson.jq.JsonQuery
import net.thisptr.jackson.jq.Output
import net.thisptr.jackson.jq.Scope
import net.thisptr.jackson.jq.Versions
import net.thisptr.jackson.jq.exception.JsonQueryException
import org.slf4j.LoggerFactory

/**
 * Phase 5 (`.claude/docs/port-data-model.md` "Computed properties"): the jq bridge behind
 * `calculationProperties` — real jq 1.6 semantics via `net.thisptr:jackson-jq`
 * (`.claude/docs/security.md` "Computed-property evaluation (jq)"). Admin-trusted, NOT a
 * sandbox: an evaluation that never emits (`def f: f; f`) blocks its worker until the JVM stack
 * overflows.
 *
 * The SHIPPED bound: every evaluation runs on a dedicated daemon pool ([JQ_EXECUTOR], 4 workers /
 * 64 queued, thread name `entity-jq`) — separate from `catalog/UrlFetch.kt`'s own bounded pool,
 * so a stranded jq worker never costs a URL fetch, and vice versa — under a per-expression
 * [DEFAULT_JQ_DEADLINE_MILLIS] deadline (`ch.nokillswit.infra.concurrency.awaitBounded`, the same
 * cancellation/rejection bridge `UrlFetch` uses). jackson-jq does not observe interruption, so a
 * task that misses its deadline is NOT reclaimed: the underlying worker stays busy — running the
 * SAME hostile expression — until it eventually finishes or the JVM stack overflows; only the
 * calling coroutine gives up. To keep a single hostile expression from being resubmitted onto an
 * already-clogged pool on every subsequent read, a deadline miss QUARANTINES the expression TEXT
 * for the life of the process (`quarantined` — cleared only by a server restart or the admin
 * editing the expression, since the cache key IS the expression text) — but ONLY when the miss
 * happens AFTER the task actually began evaluating. The deadline covers queue wait plus
 * EVALUATION of the already-compiled expression: compiling runs on the CALLER, before the
 * clock starts (a compile failure answers absent immediately, logged at DEBUG, and never
 * touches the executor or the quarantine set), and the jq 1.6 builtins are loaded once at
 * [JqEvaluator] construction (`configureDatabase`, well before any request) rather than lazily
 * inside the deadline window. Both exclusions matter for the same reason: on 2026-09-12 a cold
 * CI JVM's first-ever calculation quarantined a trivially cheap expression because loading the
 * builtins (class loading + parsing jq's own builtin definitions) alone exceeded the 500 ms
 * default on a slow runner — the deadline was meant to bound the EXPRESSION, not JVM warm-up.
 * If the pool's workers are already stranded by unrelated hostile expressions, every unrelated
 * GOOD expression queued behind them would otherwise miss its own deadline too and get wrongly
 * quarantined — a single stuck expression could empty the whole workspace's computed properties
 * before the next restart. A miss while still queued is treated as a pool-saturation symptom
 * instead: absent, WITHOUT quarantining, exactly like a [RejectedExecutionException] rejection —
 * the expression itself may be perfectly fine, the pool is just busy — and caller cancellation
 * (an enclosing read's own deadline/cancellation) propagates unchanged and never quarantines
 * anything.
 */

private val log = LoggerFactory.getLogger("ch.nokillswit.entities.computed")

/** Cap on the serialized calculation OUTPUT (never the input) — an oversized result is absent, never truncated. */
const val MAX_CALCULATION_OUTPUT_CHARS = 65_536

/**
 * Per-expression deadline default (ms) — covers queue wait + evaluation of the ALREADY-COMPILED
 * expression on the bounded pool. Compiling (and the one-time jq-builtin load) runs on the
 * caller before this clock starts — see the [JqEvaluator] class doc.
 */
const val DEFAULT_JQ_DEADLINE_MILLIS = 500L

/** Upper bound accepted for `computed.jq.deadlineMillis` (config validation, `infra/db/Database.kt`). */
const val MAX_JQ_DEADLINE_MILLIS = 60_000L

private const val JQ_WORKERS = 4
private const val JQ_QUEUE_CAPACITY = 64

/** Compile-cache ceiling per [JqEvaluator] instance — cleared wholesale on overflow, never LRU-evicted one at a time. */
internal const val MAX_CACHED_EXPRESSIONS = 4096

/**
 * The shared bounded pool EVERY [JqEvaluator] uses by default. Deliberately its OWN pool, not
 * `catalog/UrlFetch.kt`'s `URL_FETCH_EXECUTOR`: an outbound HTTP fetch and an admin-authored jq
 * expression are unrelated failure domains, and a jq worker parked forever by an uninterruptible
 * infinite loop must never starve URL fetches (or vice versa).
 */
private val JQ_EXECUTOR: Executor = ThreadPoolExecutor(
    JQ_WORKERS,
    JQ_WORKERS,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(JQ_QUEUE_CAPACITY),
    ThreadFactory { runnable -> Thread(runnable, "entity-jq").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
)

// A private Jackson bridge — kotlinx.serialization stays the ONE persisted JSON model everywhere
// else in the codebase; only this file ever touches com.fasterxml.jackson.databind.
private val jqMapper = ObjectMapper()

/**
 * The root jq scope, built ONCE for the process: jq 1.6 builtins, THEN `env`/`$ENV` shadowed to
 * emit nothing / an empty object. `env/0` is a loaded builtin (`EnvFunction`, backed by
 * `System.getenv`) that would otherwise let an admin-authored calculation leak `JWT_SECRET`/the
 * database password to every authenticated reader — shadowing it AFTER [BuiltinFunctionLoader]
 * runs is mandatory, not incidental. No [Scope.setModuleLoader] is ever installed, so `import`/
 * `include` fail rather than reading the filesystem. Read-only after this initializer runs;
 * every evaluation gets its OWN [Scope.newChildScope] so a mid-expression `def`/`as` binding
 * never leaks across entities or requests.
 * [Lazy] rather than plain `by lazy` so [JqEvaluator.builtinsLoaded] can observe whether the
 * (potentially costly on a cold JVM — class loading + parsing jq's own builtin definitions)
 * load already ran, without forcing it itself.
 */
private val rootScopeLazy: Lazy<Scope> = lazy {
    val scope = Scope.newEmptyScope()
    BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, scope)
    scope.addFunction("env", 0) { _, _, _, _, _, _ -> }
    scope.setValue("ENV", jqMapper.createObjectNode())
    scope
}
private val rootScope: Scope by rootScopeLazy

/**
 * Thrown the instant the first value is emitted — the abort itself IS the output-count cap
 * (`range(1e9)` stops at its first value, `0`, instead of iterating a billion times).
 */
private class FirstOutputTaken(val node: JsonNode) : JsonQueryException("first output taken")

private class FirstOutputCollector : Output {
    var result: JsonNode? = null
        private set

    override fun emit(node: JsonNode) {
        result = node
        throw FirstOutputTaken(node)
    }
}

/** One compiled expression, or a cached compile FAILURE (`query == null`) — [ConcurrentHashMap] rejects null values directly. */
private class CompiledExpression(val query: JsonQuery?)

/** The [evaluateBounded] worker's result, wrapped so a legitimate `null` (absent) is never confused with a timeout's `null`. */
private class Outcome(val value: JsonElement?)

/**
 * Evaluates jq expressions over entity documents. [evaluate] is the synchronous, unbounded
 * primitive (still used directly by tests and, via [compileCached]/[evaluateCompiled], by
 * [evaluateBounded]); [evaluateBounded] is what production code calls — see the file header for
 * the pool/deadline/quarantine design. [deadline]/[executor]/[maxCachedExpressions] are test
 * seams; production always uses the public constructors' defaults.
 *
 * Construction eagerly forces the shared [rootScope] (loading the jq 1.6 builtins) and runs one
 * throwaway compile+evaluation on the CONSTRUCTING thread — `configureDatabase`, at boot, well
 * before any request — so [evaluateBounded]'s per-expression deadline never has to absorb a cold
 * JVM's class-loading/builtin-parsing cost (see the class's deadline paragraph in the file header
 * and `.claude/docs/security.md` "Bounded executor and per-expression deadline").
 */
class JqEvaluator internal constructor(
    private val deadline: Duration = Duration.ofMillis(DEFAULT_JQ_DEADLINE_MILLIS),
    private val executor: Executor = JQ_EXECUTOR,
    private val maxCachedExpressions: Int = MAX_CACHED_EXPRESSIONS,
    /** Test seam: invoked on the worker, right after a task is confirmed STARTED, before evaluation itself runs. */
    private val beforeEvaluate: () -> Unit = {},
) {
    constructor() : this(Duration.ofMillis(DEFAULT_JQ_DEADLINE_MILLIS))

    /** The `computed.jq.deadlineMillis`-configured constructor (`infra/db/Database.kt`). */
    constructor(deadlineMillis: Long) : this(Duration.ofMillis(deadlineMillis))

    private val cache = ConcurrentHashMap<String, CompiledExpression>()

    /** One cache entry per distinct expression TEXT — proves compile-once in tests; never used for control flow. */
    internal val cacheSize: Int get() = cache.size

    /** Expression TEXTs that missed their deadline at least once — quarantined for the life of this evaluator instance. */
    private val quarantined: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Test/observability seam: how many distinct expressions are currently quarantined. */
    internal val quarantinedCount: Int get() = quarantined.size

    /** De-dupes the saturation warning to one per episode (busy → idle → busy again re-warns). */
    private val saturated = AtomicBoolean(false)

    /**
     * Test/observability seam: whether the shared jq-1.6-builtins [rootScope] has already been
     * loaded — [rootScopeLazy] is a process-wide [Lazy] (shared by every [JqEvaluator] instance
     * in the JVM), so this reflects the PROCESS's state, not just this instance's own warm-up.
     */
    internal val builtinsLoaded: Boolean get() = rootScopeLazy.isInitialized()

    init {
        // Force the builtin load + one compile on THIS (constructing) thread, never inside
        // evaluateBounded's deadline window — the 2026-09-12 CI incident quarantined a trivially
        // cheap expression solely because a cold JVM's builtin load exceeded 500ms. Remove the
        // warm-up expression from the cache afterward so cacheSize stays a meaningful "how many
        // REAL expressions has this instance compiled" seam for tests.
        evaluate(".", JsonPrimitive(1), "warm-up")
        cache.remove(".")
    }

    /**
     * Evaluates one jq [expression] over [input] (bridged through [ch.nokillswit.blueprints.blueprintJson]
     * <-> Jackson, never a second persisted JSON model), returning the FIRST emitted value, or `null`
     * (absent) for anything else: no output at all, a JSON `null`, an oversized serialized result
     * ([MAX_CALCULATION_OUTPUT_CHARS]), a compile error, a jq runtime error, or a runaway recursion
     * (`StackOverflowError`, e.g. `def f: f; f`). This function NEVER throws and never times out —
     * [evaluateBounded] is the bounded entry point production code uses. [context] is a
     * caller-supplied label (e.g. `"<blueprint>.<propertyId>"`) for the DEBUG failure log — [input]
     * itself is never logged (`.claude/docs/security.md`).
     */
    fun evaluate(expression: String, input: JsonElement, context: String = ""): JsonElement? {
        val query = compileCached(expression, context) ?: return null
        return evaluateCompiled(query, input, context)
    }

    /** [evaluate]'s post-compile half, reused by [evaluateBounded] once it already holds a compiled [JsonQuery]. */
    // jq evaluation boundary: runtime/type failures answer absent, logged without input document.
    @Suppress("TooGenericExceptionCaught")
    private fun evaluateCompiled(query: JsonQuery, input: JsonElement, context: String): JsonElement? = try {
        val node = jqMapper.readTree(blueprintJson.encodeToString(input))
        outputOrAbsent(firstOutputOf(query, node))
    } catch (e: JsonProcessingException) {
        logFailure(context, e)
        null
    } catch (e: RuntimeException) {
        logFailure(context, e)
        null
    } catch (e: StackOverflowError) {
        logFailure(context, e)
        null
    }

    /**
     * The bounded entry point: compiles [expression] on the CALLER (never touching [executor] —
     * a compile failure answers absent immediately, already logged at DEBUG by [compileCached],
     * and is never quarantined), then runs the compiled query on [executor] under [deadline]. A
     * quarantined [expression] short-circuits to absent before even compiling — an already-clogged
     * pool is never handed more work for a text known to hang it.
     *
     * [deadline] covers QUEUE WAIT plus EVALUATION of the already-compiled expression — never
     * compiling or the one-time jq-builtin load, both of which run on the caller/at construction
     * (see the class doc) — but a task can still miss it while sitting behind other stranded
     * workers, never having run at all. Only a miss AFTER the task actually started evaluating
     * quarantines [expression] (tracked by [started], set the instant the worker begins — before
     * evaluation itself runs): the worker is NOT reclaimed (jackson-jq ignores interruption) and
     * keeps running the SAME expression until it finishes or overflows, so resubmitting it would
     * only pile more work behind an already-hung worker. A miss while STILL QUEUED is a
     * pool-saturation symptom, not evidence against the expression itself — treated exactly like
     * [RejectedExecutionException]: absent, WITHOUT quarantining, warned once per saturated
     * episode. Caller cancellation (`CancellationException`, e.g. an enclosing request deadline)
     * is never caught here and propagates unchanged, quarantining nothing.
     */
    suspend fun evaluateBounded(expression: String, input: JsonElement, context: String = ""): JsonElement? {
        if (expression in quarantined) return null
        val query = compileCached(expression, context) ?: return null
        val started = AtomicBoolean(false)
        val outcome = try {
            withTimeoutOrNull(deadline.toMillis()) {
                executor.awaitBounded {
                    started.set(true)
                    beforeEvaluate()
                    Outcome(evaluateCompiled(query, input, context))
                }
            }
        } catch (e: RejectedExecutionException) {
            warnSaturated(context, e.toString())
            return null
        } catch (e: InterruptedException) {
            logFailure(context, e)
            return null
        }
        if (outcome == null) {
            if (started.get()) {
                if (quarantined.add(expression)) {
                    log.warn("jq calculation exceeded its {}ms deadline and is quarantined ({})", deadline.toMillis(), context)
                }
            } else {
                warnQueueTimeout(context)
            }
            return null
        }
        saturated.set(false)
        return outcome.value
    }

    private fun warnSaturated(context: String, detail: String) {
        if (saturated.compareAndSet(false, true)) {
            log.warn("jq worker pool saturated, calculation absent ({})", context)
        } else {
            log.debug("jq worker pool saturated, calculation absent ({}): {}", context, detail)
        }
    }

    /** A deadline miss while the task never left the queue — the same once-per-episode saturation warning, just untriggered by a reject. */
    private fun warnQueueTimeout(context: String) {
        if (saturated.compareAndSet(false, true)) {
            log.warn("jq calculation timed out while queued, calculation absent ({})", context)
        } else {
            log.debug("jq calculation timed out while queued, calculation absent ({})", context)
        }
    }

    private fun firstOutputOf(query: JsonQuery, node: JsonNode): JsonNode? {
        val collector = FirstOutputCollector()
        return try {
            query.apply(Scope.newChildScope(rootScope), node, collector)
            collector.result
        } catch (e: FirstOutputTaken) {
            e.node
        }
    }

    private fun outputOrAbsent(node: JsonNode?): JsonElement? {
        if (node == null || node.isNull) return null
        val serialized = jqMapper.writeValueAsString(node)
        if (serialized.length > MAX_CALCULATION_OUTPUT_CHARS) return null
        return blueprintJson.parseToJsonElement(serialized)
    }

    private fun compileCached(expression: String, context: String): JsonQuery? {
        cache[expression]?.let { return it.query }
        val compiled = try {
            JsonQuery.compile(expression, Versions.JQ_1_6)
        } catch (e: JsonProcessingException) {
            logFailure(context, e)
            null
        }
        // Clear-wholesale-on-overflow, not LRU: a bounded admin-authored registry rarely churns
        // past this ceiling, and per-entry eviction bookkeeping would outweigh the benefit.
        if (cache.size >= maxCachedExpressions) cache.clear()
        cache.putIfAbsent(expression, CompiledExpression(compiled))
        return compiled
    }

    private fun logFailure(context: String, e: Throwable) {
        log.debug("jq evaluation failed ({}): {}", context, e.toString())
    }
}
