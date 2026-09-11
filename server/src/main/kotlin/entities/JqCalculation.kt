package ch.nokillswit.entities

import ch.nokillswit.blueprints.blueprintJson
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.JsonElement
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
 * overflows, which is why every entity read runs jq OUTSIDE the database transaction
 * (`.claude/docs/persistence.md`) — a bounded executor with a per-expression deadline (the
 * `UrlFetch` pool idiom) is the named follow-up, not shipped here.
 */

private val log = LoggerFactory.getLogger("ch.nokillswit.entities.computed")

/** Cap on the serialized calculation OUTPUT (never the input) — an oversized result is absent, never truncated. */
const val MAX_CALCULATION_OUTPUT_CHARS = 65_536

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
 */
private val rootScope: Scope by lazy {
    val scope = Scope.newEmptyScope()
    BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, scope)
    scope.addFunction("env", 0) { _, _, _, _, _, _ -> }
    scope.setValue("ENV", jqMapper.createObjectNode())
    scope
}

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

/**
 * Evaluates one jq [expression] over [input] (bridged through [ch.nokillswit.blueprints.blueprintJson]
 * <-> Jackson, never a second persisted JSON model), returning the FIRST emitted value, or `null`
 * (absent) for anything else: no output at all, a JSON `null`, an oversized serialized result
 * ([MAX_CALCULATION_OUTPUT_CHARS]), a compile error, a jq runtime error, or a runaway recursion
 * (`StackOverflowError`, e.g. `def f: f; f`). This function NEVER throws. [context] is a
 * caller-supplied label (e.g. `"<blueprint>.<propertyId>"`) for the DEBUG failure log — [input]
 * itself is never logged (`.claude/docs/security.md`).
 */
class JqEvaluator {
    private val cache = HashMap<String, JsonQuery?>()

    /** One cache entry per distinct expression TEXT — proves compile-once in tests; never used for control flow. */
    internal val cacheSize: Int get() = cache.size

    fun evaluate(expression: String, input: JsonElement, context: String = ""): JsonElement? = try {
        val query = compileCached(expression, context)
        if (query == null) {
            null
        } else {
            val node = jqMapper.readTree(blueprintJson.encodeToString(input))
            outputOrAbsent(firstOutputOf(query, node))
        }
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
        if (cache.containsKey(expression)) return cache[expression]
        val compiled = try {
            JsonQuery.compile(expression, Versions.JQ_1_6)
        } catch (e: JsonProcessingException) {
            logFailure(context, e)
            null
        }
        cache[expression] = compiled
        return compiled
    }

    private fun logFailure(context: String, e: Throwable) {
        log.debug("jq evaluation failed ({}): {}", context, e.toString())
    }
}
