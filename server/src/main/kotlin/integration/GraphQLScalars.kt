package ch.nokillswit.integration

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import kotlinx.serialization.json.JsonElement

internal val longScalar: GraphQLScalarType = GraphQLScalarType.newScalar()
    .name("Long")
    .description("A signed 64-bit integer.")
    .coercing(LongCoercing)
    .build()

internal val jsonScalar: GraphQLScalarType = GraphQLScalarType.newScalar()
    .name("JSON")
    .description("An output-only JSON value preserved without numeric coercion.")
    .coercing(JsonCoercing)
    .build()

private object LongCoercing : Coercing<Long, Long> {
    override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): Long {
        val number = dataFetcherResult as? Number
            ?: throw CoercingSerializeException("Expected a numeric Long value")
        val exact = when (number) {
            is BigDecimal -> runCatching { number.longValueExact() }.getOrNull()
            is BigInteger -> runCatching { number.longValueExact() }.getOrNull()
            is Double, is Float -> runCatching { BigDecimal.valueOf(number.toDouble()).longValueExact() }.getOrNull()
            else -> number.toLong()
        }
        return exact ?: throw CoercingSerializeException("Expected an integral Long value")
    }

    override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): Long =
        parseLong(input) ?: throw CoercingParseValueException("Expected a Long value")

    override fun parseLiteral(
        input: Value<*>,
        variables: CoercedVariables,
        graphQLContext: GraphQLContext,
        locale: Locale,
    ): Long = when (input) {
        is IntValue -> runCatching { input.value.longValueExact() }.getOrNull()
        is StringValue -> input.value?.toLongOrNull()
        else -> null
    } ?: throw CoercingParseLiteralException("Expected a Long literal")

    private fun parseLong(input: Any): Long? = when (input) {
        is BigDecimal -> runCatching { input.longValueExact() }.getOrNull()
        is BigInteger -> runCatching { input.longValueExact() }.getOrNull()
        is Double, is Float -> runCatching { BigDecimal.valueOf(input.toDouble()).longValueExact() }.getOrNull()
        is Number -> input.toLong()
        is String -> input.toLongOrNull()
        else -> null
    }
}

/** The API has no JSON input arguments. Returning JsonElement lets the bounded response writer
 * preserve the exact lexical form of every number instead of routing it through Double. */
private object JsonCoercing : Coercing<JsonElement, JsonElement> {
    override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): JsonElement =
        dataFetcherResult as? JsonElement
            ?: throw CoercingSerializeException("Expected a JSON value")

    override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): JsonElement =
        throw CoercingParseValueException("JSON is output-only")

    override fun parseLiteral(
        input: Value<*>,
        variables: CoercedVariables,
        graphQLContext: GraphQLContext,
        locale: Locale,
    ): JsonElement = throw CoercingParseLiteralException("JSON is output-only")
}
