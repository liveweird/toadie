package ch.nokillswit.auth.sso

import ch.nokillswit.infra.catchingFailures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException

/** How long past its TTL a last-good discovery document or key set keeps being served while the issuer is unreachable. */
internal const val SSO_MAX_STALE_MILLIS = 24 * 60 * 60 * 1000L

/** The least time between two refresh ATTEMPTS once a value exists (an unknown `kid`, or the retries during an outage). */
internal const val SSO_REFRESH_INTERVAL_MILLIS = 60 * 1000L

/**
 * A value an upstream issuer publishes (the discovery document, the JWKS), cached with a TTL and refreshed WITHOUT holding
 * any lock across the network call (`.claude/docs/security.md` "SSO login (OIDC)"):
 *
 * - **Single flight**: overlapping callers that need a refresh share ONE load; the others suspend on it. A leader that is
 *   cancelled abandons the flight and a waiter simply takes over.
 * - **Fetch, then swap**: the cached value is replaced only after a load succeeded. A failure is never cached as good.
 * - **Bounded staleness**: when a refresh fails and a previous value is younger than [ttlMillis] + [maxStaleMillis], that
 *   last good value is served instead; refresh attempts are then spaced [refreshIntervalMillis] apart, so a down issuer
 *   neither blocks sign-ins that need no fresh data nor receives a request per sign-in. With no usable value the load's
 *   failure propagates, and every call retries.
 * - **A deadline is an attempt**: a leader that a caller's own `withTimeout` cancelled DID reach (and wait on) the issuer, so
 *   it counts toward the retry spacing — a hung issuer is asked once per interval, not once per caller. Any other
 *   cancellation (a disconnect) abandons the flight without counting, so a waiter takes over at once.
 */
internal class StaleServingCache<T : Any>(
    private val ttlMillis: Long,
    private val maxStaleMillis: Long = SSO_MAX_STALE_MILLIS,
    private val refreshIntervalMillis: Long = SSO_REFRESH_INTERVAL_MILLIS,
    private val clock: () -> Long,
    private val loader: suspend () -> T,
) {
    private class Entry<T>(val value: T, val loadedAt: Long)

    private sealed interface Outcome<out T> {
        class Loaded<T>(val value: T) : Outcome<T>
        class Failed(val cause: Exception) : Outcome<Nothing>
        data object Abandoned : Outcome<Nothing>
    }

    private sealed interface Step<out T> {
        class Use<T>(val value: T) : Step<T>
        class Wait<T>(val flight: CompletableDeferred<Outcome<T>>, val fallback: T?) : Step<T>
        class Lead<T>(val flight: CompletableDeferred<Outcome<T>>, val fallback: T?, val previousAttempt: Long) : Step<T>
    }

    private val monitor = Any()
    private var entry: Entry<T>? = null
    private var lastAttempt = Long.MIN_VALUE / 2
    private var flight: CompletableDeferred<Outcome<T>>? = null

    /** The cached value while it is still servable (within the TTL plus the staleness bound), without ever loading. */
    fun peekUsable(): T? = synchronized(monitor) {
        val current = entry ?: return null
        if (clock() - current.loadedAt < ttlMillis + maxStaleMillis) current.value else null
    }

    /**
     * The cached value, refreshed when it is older than the TTL or — for a value younger than that — when [wantFresh]
     * says it is not good enough (an unknown `kid`) and no attempt happened in the last [refreshIntervalMillis].
     */
    suspend fun get(wantFresh: (T) -> Boolean = { false }): T {
        while (true) {
            when (val step = decide(wantFresh)) {
                is Step.Use -> return step.value
                is Step.Lead -> return lead(step)
                is Step.Wait -> when (val outcome = step.flight.await()) {
                    is Outcome.Loaded -> return outcome.value
                    is Outcome.Failed -> return step.fallback ?: throw outcome.cause
                    Outcome.Abandoned -> Unit // the leader was cancelled: decide again
                }
            }
        }
    }

    private fun decide(wantFresh: (T) -> Boolean): Step<T> = synchronized(monitor) {
        val now = clock()
        val current = entry
        val age = current?.let { now - it.loadedAt }
        if (current != null && age != null && age < ttlMillis && !wantFresh(current.value)) return Step.Use(current.value)
        val usable = if (current != null && age != null && age < ttlMillis + maxStaleMillis) current.value else null
        flight?.let { return Step.Wait(it, usable) }
        if (usable != null && now - lastAttempt < refreshIntervalMillis) return Step.Use(usable)
        val mine = CompletableDeferred<Outcome<T>>()
        flight = mine
        val previousAttempt = lastAttempt
        lastAttempt = now
        Step.Lead(mine, usable, previousAttempt)
    }

    private suspend fun lead(step: Step.Lead<T>): T {
        val mine = step.flight
        var loaded: T? = null
        var failure: Exception? = null
        var timedOut = false
        try {
            loaded = catchingFailures<T?>({ loader() }) { cause ->
                failure = cause
                null
            }
        } catch (deadline: TimeoutCancellationException) {
            timedOut = true
            throw deadline
        } finally {
            // Also on cancellation (which skips the lines below): the flight must never stay registered with nobody to finish it.
            val problem = failure
            synchronized(monitor) {
                loaded?.let { entry = Entry(it, clock()) }
                // An abandoned flight never reached the issuer's answer, so it must not count as an attempt: otherwise the
                // waiters re-deciding below would serve the old value for a whole interval (a rotated key refused for 60 s).
                // A caller's own deadline is different: the issuer was asked and did not answer, so the spacing holds.
                if (loaded == null && problem == null && !timedOut) lastAttempt = step.previousAttempt
                flight = null
            }
            mine.complete(
                when {
                    loaded != null -> Outcome.Loaded(loaded)
                    problem != null -> Outcome.Failed(problem)
                    else -> Outcome.Abandoned
                },
            )
        }
        return loaded ?: step.fallback ?: throw checkNotNull(failure)
    }
}
