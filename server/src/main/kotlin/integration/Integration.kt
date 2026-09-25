package ch.nokillswit.integration

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.blueprints.BlueprintServiceKey
import ch.nokillswit.entities.EntityServiceKey
import ch.nokillswit.plugins.respondProblem
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.GraphQLContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.function.Consumer
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.future.await
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import io.ktor.utils.io.readAvailable

/** Registers the separately authenticated, read-only Port integration API when explicitly
 * enabled. Its SDL is the contract and its resolvers only call domain services. */
fun Application.configureIntegration() {
    val enabled = environment.config.propertyOrNull("integration.enabled")?.getString()?.toBoolean() == true
    if (!enabled) {
        log.info("Integration API disabled (integration.enabled=false)")
        return
    }

    val clients = attributes[IntegrationClientServiceKey]
    val services = IntegrationServices(attributes[BlueprintServiceKey], attributes[EntityServiceKey])
    val sdl = checkNotNull(javaClass.getResource("/graphql/schema.graphqls")) {
        "graphql/schema.graphqls missing from the classpath"
    }.readText()
    val graphQL = buildIntegrationGraphQL(sdl, services)
    val limits = IntegrationLimits()
    val retainedLedger = IntegrationRetainedLedger()
    val executor = IntegrationExecutor()
    monitor.subscribe(ApplicationStopped) { executor.close() }

    routing {
        get("/integration/graphql/schema") {
            withAdmission(limits) {
                call.respondIntegrationSchema(clients, limits, sdl)
            }
        }
        post("/integration/graphql") {
            withAdmission(limits) {
                call.respondIntegrationGraphQL(clients, limits, retainedLedger, executor, graphQL)
            }
        }
        post("/integration/mcp") {
            withAdmission(limits) {
                call.respondIntegrationMcp(clients, limits, retainedLedger, services)
            }
        }
    }
}

private suspend fun ApplicationCall.respondIntegrationSchema(
    clients: IntegrationClientService,
    limits: IntegrationLimits,
    sdl: String,
) {
    val principal = withTimeoutOrNull(EXECUTION_TIMEOUT_MILLIS) {
        integrationCaller(clients).also { requireRateAllowance(limits, it) }
    }
    if (principal == null) {
        respondProblem(HttpStatusCode.RequestTimeout, "Integration request timed out")
    } else {
        respondText(sdl, ContentType.Text.Plain)
    }
}

private suspend fun ApplicationCall.respondIntegrationGraphQL(
    clients: IntegrationClientService,
    limits: IntegrationLimits,
    retainedLedger: IntegrationRetainedLedger,
    executor: IntegrationExecutor,
    graphQL: GraphQL,
) {
    val authenticated = receiveAuthenticatedRequest(clients, limits) ?: run {
        respondProblem(HttpStatusCode.RequestTimeout, "Integration request timed out")
        return
    }
    val (principal, body) = authenticated
    if (body.query.utf8Size() > MAX_QUERY_SOURCE_BYTES) {
        respondProblem(HttpStatusCode.PayloadTooLarge, "GraphQL query exceeds the size limit")
        return
    }
    retainedLedger.open().use { retained ->
        val specification = executeBounded(graphQL, body, retained, executor)
        auditIntegrationRequest(principal, body, specification)
        val encoded = boundedGraphQLJson(specification)
        if (encoded == null) {
            respondProblem(HttpStatusCode.PayloadTooLarge, "GraphQL response exceeds the size limit")
        } else {
            respondText(encoded, ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}

private suspend fun ApplicationCall.receiveAuthenticatedRequest(
    clients: IntegrationClientService,
    limits: IntegrationLimits,
): AuthenticatedRequest? = withTimeoutOrNull(EXECUTION_TIMEOUT_MILLIS) {
    val principal = integrationCaller(clients)
    requireRateAllowance(limits, principal)
    if (request.contentType().withoutParameters() != ContentType.Application.Json) {
        throw BadRequestException("Content-Type must be application/json")
    }
    val rawBody = receiveBoundedBody()
    validateGraphQLBodyStructure(rawBody)
    val body = try {
        integrationHttpJson.decodeFromString<GraphQLHttpRequest>(rawBody)
    } catch (_: SerializationException) {
        throw BadRequestException("Request body is missing or not valid JSON")
    }
    AuthenticatedRequest(principal, body)
}

private suspend fun executeBounded(
    graphQL: GraphQL,
    body: GraphQLHttpRequest,
    retained: IntegrationRetainedLedger.Reservation,
    executor: IntegrationExecutor,
): Map<String, Any?> = withTimeoutOrNull(EXECUTION_TIMEOUT_MILLIS) {
    withContext(executor.dispatcher) {
        executeInRequestScope(graphQL, body, retained)
    }
} ?: timeoutSpecification()

private suspend fun executeInRequestScope(
    graphQL: GraphQL,
    body: GraphQLHttpRequest,
    retained: IntegrationRetainedLedger.Reservation,
): Map<String, Any?> {
    if (!hasValidBoundedSyntax(body.query)) return invalidDocumentSpecification()
    return supervisorScope {
        val scope = this
        val context = Consumer<GraphQLContext.Builder> {
            it.put(SCOPE_CONTEXT_KEY, scope)
            it.put(MEMO_CONTEXT_KEY, RequestMemo(scope, retained))
        }
        graphQL.executeAsync(
            ExecutionInput.newExecutionInput()
                .query(body.query)
                .operationName(body.operationName)
                .variables(body.variables?.mapValues { (_, value) -> value.toAnyValue() } ?: emptyMap())
                .graphQLContext(context)
                .build(),
        ).await().toSpecification()
    }
}

private fun auditIntegrationRequest(
    principal: IntegrationClientPrincipal,
    body: GraphQLHttpRequest,
    specification: Map<String, Any?>,
) = audit(
    "integration.request",
    "clientId" to principal.clientId.toLong(),
    "clientName" to principal.name,
    "operationName" to body.operationName?.take(MAX_AUDITED_OPERATION_NAME),
    "rootFields" to responseRootKeys(specification),
)

private data class AuthenticatedRequest(
    val principal: IntegrationClientPrincipal,
    val body: GraphQLHttpRequest,
)

private val integrationHttpJson = Json

private suspend fun ApplicationCall.receiveBoundedBody(): String {
    val channel = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read == -1) break
        if (output.size().toLong() + read > MAX_INTEGRATION_BODY_BYTES) {
            throw PayloadTooLargeException(MAX_INTEGRATION_BODY_BYTES)
        }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8)
}

private fun invalidDocumentSpecification(): Map<String, Any?> = mapOf(
    "data" to null,
    "errors" to listOf(mapOf("message" to "Invalid GraphQL document")),
)

private fun timeoutSpecification(): Map<String, Any?> = mapOf(
    "data" to null,
    "errors" to listOf(mapOf("message" to "Execution timed out")),
)

internal suspend fun io.ktor.server.routing.RoutingContext.withAdmission(
    limits: IntegrationLimits,
    block: suspend () -> Unit,
) {
    val lease = limits.tryAcquire() ?: throw TooManyRequestsException("Too many integration requests — retry shortly")
    lease.use { block() }
}

private fun responseRootKeys(specification: Map<String, Any?>): String =
    ((specification["data"] as? Map<*, *>)?.keys.orEmpty())
        .map { it.toString() }
        .filter { it in AUDITABLE_ROOT_FIELDS }
        .joinToString(",")
        .take(500)

private val AUDITABLE_ROOT_FIELDS = setOf(
    "blueprints", "blueprint", "entities", "entity", "errors", "__schema", "__type", "__typename",
)

private fun integrationBearerToken(call: ApplicationCall): String? {
    val header = call.request.headers.getAll(HttpHeaders.Authorization)?.singleOrNull() ?: return null
    return BEARER_PATTERN.matchEntire(header.trim())?.groupValues?.get(1)
}

internal suspend fun ApplicationCall.integrationCaller(
    clients: IntegrationClientService,
): IntegrationClientPrincipal {
    val key = integrationBearerToken(this)
    if (key == null) {
        audit("integration.auth_failed", "reason" to "missing_or_malformed")
        throw UnauthorizedException("Missing or invalid integration API key")
    }
    return clients.authenticate(key) ?: run {
        audit("integration.auth_failed", "reason" to "unknown_or_revoked")
        throw UnauthorizedException("Missing or invalid integration API key")
    }
}

internal fun ApplicationCall.requireRateAllowance(limits: IntegrationLimits, principal: IntegrationClientPrincipal) {
    if (!limits.allow(principal.clientId)) {
        audit("integration.rate_limited", "clientId" to principal.clientId.toLong(), "clientName" to principal.name)
        throw TooManyRequestsException("Integration client rate limit exceeded — retry later")
    }
}

private val BEARER_PATTERN = Regex(
    "^(?i:Bearer)[\\t ]+(${Regex.escape(INTEGRATION_API_KEY_PREFIX)}[A-Za-z0-9_-]{43})[\\t ]*$",
)
