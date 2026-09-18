package ch.nokillswit.integration

import ch.nokillswit.authz.TooManyRequestsException
import graphql.ErrorClassification
import graphql.ErrorType
import graphql.GraphQL
import graphql.GraphQLError
import graphql.analysis.FieldComplexityCalculator
import graphql.analysis.MaxQueryComplexityInstrumentation
import graphql.analysis.MaxQueryDepthInstrumentation
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.language.IntValue
import graphql.language.SourceLocation
import graphql.parser.InvalidSyntaxException
import graphql.parser.Parser
import graphql.parser.ParserEnvironment
import graphql.parser.ParserOptions
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.ktor.server.plugins.BadRequestException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.slf4j.LoggerFactory

private const val PAGE_FACTOR_UNIT = 20
private const val MAX_PAGE_FACTOR = 5
private const val FINDING_SELECTION_WEIGHT = 200
internal const val MAX_QUERY_TOKENS = 12_000

private val logger = LoggerFactory.getLogger("ch.nokillswit.integration")

/** List selection cost scales with pageSize; database-backed roots also carry fixed costs so
 * aliases cannot hide expensive service calls behind a shallow selection set. */
private val weightedComplexity = FieldComplexityCalculator { environment, childComplexity ->
    if (environment.field.name == "findings") {
        return@FieldComplexityCalculator FINDING_SELECTION_WEIGHT * childComplexity
    }
    val rootCost = when (environment.field.name) {
        "errors" -> 250
        "blueprints", "entities" -> 50
        "blueprint", "entity" -> 25
        else -> 1
    }
    val pageSize = environment.field.arguments.firstOrNull { it.name == "pageSize" }?.value
    val factor = when (pageSize) {
        null -> 1
        is IntValue -> {
            val size = runCatching { pageSize.value.intValueExact() }.getOrDefault(Int.MAX_VALUE)
            ((size.toLong() + PAGE_FACTOR_UNIT - 1) / PAGE_FACTOR_UNIT).coerceIn(1, MAX_PAGE_FACTOR.toLong()).toInt()
        }
        else -> MAX_PAGE_FACTOR
    }
    factor * (childComplexity + rootCost)
}

fun parseIntegrationSchema(sdl: String): graphql.schema.GraphQLSchema =
    SchemaGenerator().makeExecutableSchema(
        SchemaParser().parse(sdl),
        RuntimeWiring.newRuntimeWiring().scalar(longScalar).scalar(jsonScalar).build(),
    )

internal fun buildIntegrationGraphQL(sdl: String, services: IntegrationServices): GraphQL {
    val registry = SchemaParser().parse(sdl)
    val wiring = RuntimeWiring.newRuntimeWiring()
        .scalar(longScalar)
        .scalar(jsonScalar)
        .type("Query") { it.queryFetchers(services) }
        .type("OntologyErrors") { it.errorReportFetchers() }
        .build()
    val schema = SchemaGenerator().makeExecutableSchema(registry, wiring)
    return guardedIntegrationGraphQL(schema)
        .defaultDataFetcherExceptionHandler(SanitizingExceptionHandler())
        .build()
}

internal fun guardedIntegrationGraphQL(schema: graphql.schema.GraphQLSchema): GraphQL.Builder =
    GraphQL.newGraphQL(schema)
        .instrumentation(
            ChainedInstrumentation(
                listOf(
                    MaxQueryDepthInstrumentation(MAX_QUERY_DEPTH),
                    MaxQueryComplexityInstrumentation(MAX_QUERY_COMPLEXITY, weightedComplexity),
                ),
            ),
        )

/** A bounded, redacted preflight on the integration worker. graphql-java parses again during
 * execution, but only after this stricter source/token/rule-depth gate has accepted the input. */
internal fun hasValidBoundedSyntax(query: String): Boolean = try {
    val options = ParserOptions.newParserOptions()
        .maxCharacters(MAX_QUERY_SOURCE_BYTES)
        .maxTokens(MAX_QUERY_TOKENS)
        .maxWhitespaceTokens(MAX_QUERY_TOKENS)
        .maxNumericLiteralCharacters(1_024)
        .maxRuleDepth(1_024)
        .redactTokenParserErrorMessages(true)
        .build()
    Parser.parse(
        ParserEnvironment.newParserEnvironment()
            .document(query)
            .parserOptions(options)
            .build(),
    )
    true
} catch (_: InvalidSyntaxException) {
    false
}

private class SanitizedError(
    private val safeMessage: String,
    private val errorPath: List<Any>?,
    private val location: SourceLocation?,
) : GraphQLError {
    override fun getMessage(): String = safeMessage
    override fun getLocations(): List<SourceLocation> = listOfNotNull(location)
    override fun getErrorType(): ErrorClassification = ErrorType.DataFetchingException
    override fun getPath(): List<Any>? = errorPath
}

internal class SanitizingExceptionHandler : DataFetcherExceptionHandler {
    override fun handleException(
        handlerParameters: DataFetcherExceptionHandlerParameters,
    ): CompletableFuture<DataFetcherExceptionHandlerResult> {
        val cause = unwrap(handlerParameters.exception)
        val message = when (cause) {
            is BadRequestException -> cause.message ?: "Bad request"
            is TooManyRequestsException -> "Temporarily unavailable"
            else -> {
                logger.error("Unhandled integration resolver failure; errorType={}", cause.javaClass.name)
                "Internal error"
            }
        }
        val error = SanitizedError(message, handlerParameters.path.toList(), handlerParameters.sourceLocation)
        return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
    }

    private fun unwrap(exception: Throwable): Throwable =
        (exception as? CompletionException)?.cause ?: exception
}
