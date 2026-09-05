package ch.nokillswit.plugins

import ch.nokillswit.dictionaries.Dictionary
import ch.nokillswit.dictionaries.DictionaryServiceKey
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val healthLog = LoggerFactory.getLogger("ch.nokillswit.health")

/**
 * Ceiling on the readiness answer. The R2DBC connection has no connect timeout of its own, and a
 * cancellation reaches the Exposed transaction only at its next suspension point — measured
 * 2–7 s per probe against a database whose Service has no endpoints (the v1.22.0 acceptance
 * test). The probe therefore runs DETACHED (single-flight, below) and the handler awaits it with
 * a cancellable timeout, so the 503 always goes out at this deadline, under the probe's
 * `timeoutSeconds: 3` in k8s/app-deployment.yaml. A deliberate addition over Lettuce's copy.
 */
private const val READYZ_TIMEOUT_MS = 2_000L

/**
 * Kubernetes health endpoints (Lettuce's `plugins/Health.kt`, ported) — unauthenticated and
 * OUTSIDE `/api/`, so the OpenAPI conformance layer (which validates `/api/` traffic only) ignores
 * them and no spec entry is needed. Registered after the infrastructure group in
 * `application.yaml` (readiness reads a service from `attributes`) and before the SPA catch-all
 * (`configureRouting` is last), so a real route answers ahead of the `index.html` fallback.
 *
 *  - `GET /healthz` — liveness: the process is up. It never touches the database, so a DB outage
 *    does NOT turn into a liveness restart storm (only readiness reacts to the DB).
 *  - `GET /readyz` — readiness: a cheap DB round-trip (the active NAMESPACE dictionary entries —
 *    a tiny table every environment has), bounded by [READYZ_TIMEOUT_MS]. `200` when the database
 *    answers in time, `503` problem+json when it does not — so the pod is pulled from the Service
 *    until it can serve API traffic. Concurrent probes share ONE in-flight round-trip (a probe every
 *    5 s must not pile up hung connection attempts while the database is away).
 *
 * In production mode the HTTPS redirect fires on a plain-HTTP request, so the k8s probes send
 * `X-Forwarded-Proto: https` (see k8s/app-deployment.yaml). In development mode (tests, the compose
 * demo) there is no redirect and the endpoints answer directly.
 */
fun Application.configureHealth() {
    val app = this
    val inFlightLock = Mutex()
    var inFlight: Deferred<Result<Unit>>? = null
    // The round-trip is a child of the Application scope, never of the request: a probe that
    // gave up on it must not cancel it mid-connect, and the next probe joins the same attempt.
    // The body never throws (Result), so a failed attempt cannot fail the application's job.
    suspend fun probe(): Deferred<Result<Unit>> = inFlightLock.withLock {
        inFlight?.takeIf { it.isActive } ?: app.async {
            runCatching { app.attributes[DictionaryServiceKey].read(Dictionary.NAMESPACE) }.map { }
        }.also { inFlight = it }
    }
    routing {
        get("/healthz") {
            call.respondText("OK")
        }
        get("/readyz") {
            val outcome = withTimeoutOrNull(READYZ_TIMEOUT_MS) { probe().await() }
            when {
                outcome == null -> {
                    healthLog.warn("readiness probe failed: database did not answer within ${READYZ_TIMEOUT_MS} ms")
                    call.respondProblem(HttpStatusCode.ServiceUnavailable, "Database is not reachable")
                }
                outcome.isFailure -> {
                    healthLog.warn("readiness probe failed: database unreachable", outcome.exceptionOrNull())
                    call.respondProblem(HttpStatusCode.ServiceUnavailable, "Database is not reachable")
                }
                else -> call.respondText("OK")
            }
        }
    }
}
