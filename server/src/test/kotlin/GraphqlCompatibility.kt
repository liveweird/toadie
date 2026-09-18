package ch.nokillswit

import graphql.language.ArrayValue
import graphql.language.AstPrinter
import graphql.language.ObjectValue
import graphql.language.Value
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.diff.SchemaDiff
import graphql.schema.diff.SchemaDiffSet
import graphql.schema.diff.reporting.CapturingReporter
import graphql.schema.idl.SchemaGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

private const val SCHEMA_PATH = "server/src/main/resources/graphql/schema.graphqls"
private const val DEFAULT_BASELINE_REF = "origin/master"
private val ALL_ZERO_REVISION = Regex("0+")

internal data class GraphqlCompatibilityResult(val problems: List<String>) {
    val compatible: Boolean get() = problems.isEmpty()
}

internal fun compareGraphqlSchemas(baseline: String, current: String): GraphqlCompatibilityResult {
    val baselineSchema = parseSchema("baseline", baseline)
    val currentSchema = parseSchema("current", current)
    val reporter = CapturingReporter()
    SchemaDiff(SchemaDiff.Options.defaultOptions().enforceDirectives())
        .diffSchema(SchemaDiffSet.diffSetFromSdl(baseline, current), reporter)

    val libraryProblems = (reporter.breakages + reporter.dangers).map { event ->
        val typeName = event.typeName.orEmpty()
        val fieldName = event.fieldName.orEmpty()
        buildString {
            append(event.level).append(": ")
            if (typeName.isNotBlank()) append(typeName)
            if (fieldName.isNotBlank()) append('.').append(fieldName)
            if (typeName.isNotBlank() || fieldName.isNotBlank()) append(": ")
            append(event.reasonMsg)
        }
    }
    return GraphqlCompatibilityResult(
        (libraryProblems + strictMemberProblems(baselineSchema, currentSchema)).distinct().sorted(),
    )
}

private fun parseSchema(label: String, sdl: String): GraphQLSchema =
    try {
        SchemaGenerator.createdMockedSchema(sdl)
    } catch (exception: RuntimeException) {
        throw IllegalArgumentException("Invalid $label GraphQL SDL: ${exception.message}", exception)
    }

/** graphql-java permits some variance changes that GQL-CON-003 deliberately forbids. */
private fun strictMemberProblems(
    baseline: GraphQLSchema,
    current: GraphQLSchema,
): List<String> {
    val problems = mutableListOf<String>()
    baseline.typeMap.filterKeys { !it.startsWith("__") }.forEach { (typeName, oldType) ->
        val newType = current.typeMap[typeName]
        when {
            newType == null -> problems += "type $typeName was removed"
            oldType::class != newType::class -> problems +=
                "type $typeName changed kind from ${oldType::class.simpleName} to ${newType::class.simpleName}"
        }
    }
    baseline.typeMap.values.forEach { oldType ->
        when (oldType) {
            is GraphQLFieldsContainer -> compareOutputType(oldType, current.typeMap[oldType.name], problems)
            is GraphQLInputObjectType -> compareInputType(oldType, current.typeMap[oldType.name], problems)
            is GraphQLEnumType -> compareEnum(oldType, current.typeMap[oldType.name], problems)
            is GraphQLUnionType -> compareUnion(oldType, current.typeMap[oldType.name], problems)
        }
        if (oldType is GraphQLImplementingType) compareInterfaces(oldType, current.typeMap[oldType.name], problems)
    }
    compareDirectives(baseline.directives, current.directives, problems)
    return problems
}

private fun compareOutputType(
    baseline: GraphQLFieldsContainer,
    currentType: GraphQLType?,
    problems: MutableList<String>,
) {
    val current = currentType as? GraphQLFieldsContainer ?: return
    val currentFields = current.fieldDefinitions.associateBy { it.name }
    baseline.fieldDefinitions.forEach { oldField ->
        val newField = currentFields[oldField.name]
        if (newField == null) {
            problems += "${baseline.name}.${oldField.name} was removed"
            return@forEach
        }
        compareType("${baseline.name}.${oldField.name}", oldField.type, newField.type, problems)
        compareInputs(
            "${baseline.name}.${oldField.name} argument",
            oldField.arguments.map(::contractInput),
            newField.arguments.map(::contractInput),
            problems,
        )
    }
}

private fun compareInputType(
    baseline: GraphQLInputObjectType,
    currentType: GraphQLType?,
    problems: MutableList<String>,
) {
    val current = currentType as? GraphQLInputObjectType ?: return
    compareInputs(
        "${baseline.name} input field",
        baseline.fieldDefinitions.map(::contractInput),
        current.fieldDefinitions.map(::contractInput),
        problems,
    )
}

private fun compareEnum(baseline: GraphQLEnumType, currentType: GraphQLType?, problems: MutableList<String>) {
    val currentValues = (currentType as? GraphQLEnumType)?.values?.mapTo(mutableSetOf()) { it.name } ?: return
    baseline.values.map { it.name }.filterNot { it in currentValues }.forEach { value ->
        problems += "${baseline.name}.$value enum value was removed"
    }
}

private fun compareUnion(baseline: GraphQLUnionType, currentType: GraphQLType?, problems: MutableList<String>) {
    val currentMembers = (currentType as? GraphQLUnionType)?.types?.mapTo(mutableSetOf()) { it.name } ?: return
    baseline.types.map { it.name }.filterNot { it in currentMembers }.forEach { member ->
        problems += "${baseline.name} union member $member was removed"
    }
}

private fun compareInterfaces(
    baseline: GraphQLImplementingType,
    currentType: GraphQLType?,
    problems: MutableList<String>,
) {
    val currentInterfaces = (currentType as? GraphQLImplementingType)?.interfaces?.mapTo(mutableSetOf()) { it.name }
        ?: return
    baseline.interfaces.map { it.name }.filterNot { it in currentInterfaces }.forEach { interfaceName ->
        problems += "${baseline.name} no longer implements $interfaceName"
    }
}

private fun compareDirectives(
    baseline: List<GraphQLDirective>,
    current: List<GraphQLDirective>,
    problems: MutableList<String>,
) {
    val currentByName = current.associateBy { it.name }
    baseline.forEach { oldDirective ->
        val newDirective = currentByName[oldDirective.name]
        if (newDirective == null) {
            problems += "directive @${oldDirective.name} was removed"
            return@forEach
        }
        oldDirective.validLocations().filterNot { it in newDirective.validLocations() }.forEach { location ->
            problems += "directive @${oldDirective.name} no longer allows location $location"
        }
        if (oldDirective.isRepeatable && newDirective.isNonRepeatable) {
            problems += "directive @${oldDirective.name} is no longer repeatable"
        }
        compareInputs(
            "directive @${oldDirective.name} argument",
            oldDirective.arguments.map(::contractInput),
            newDirective.arguments.map(::contractInput),
            problems,
        )
    }
}

private data class ContractInput(val name: String, val type: GraphQLType, val defaultValue: Value<*>?)

private fun contractInput(argument: GraphQLArgument) =
    ContractInput(argument.name, argument.type, argument.definition?.defaultValue)

private fun contractInput(field: GraphQLInputObjectField) =
    ContractInput(field.name, field.type, field.definition?.defaultValue)

private fun compareInputs(
    owner: String,
    oldInputs: List<ContractInput>,
    newInputs: List<ContractInput>,
    problems: MutableList<String>,
) {
    val oldByName = oldInputs.associateBy { it.name }
    val newByName = newInputs.associateBy { it.name }
    oldByName.forEach { (name, oldInput) ->
        val newInput = newByName[name]
        if (newInput == null) {
            problems += "$owner $name was removed"
            return@forEach
        }
        compareType("$owner $name", oldInput.type, newInput.type, problems)
        val oldDefault = oldInput.defaultValue?.let(::canonicalDefault)
        val newDefault = newInput.defaultValue?.let(::canonicalDefault)
        if (oldDefault != newDefault) {
            problems += "$owner $name changed default value from " +
                "${oldDefault ?: "<absent>"} to ${newDefault ?: "<absent>"}"
        }
    }
    newByName.filterKeys { it !in oldByName }.forEach { (name, input) ->
        if (GraphQLTypeUtil.isNonNull(input.type) && input.defaultValue == null) {
            problems += "$owner $name was added as required"
        }
    }
}

private fun canonicalDefault(value: Value<*>): String = when (value) {
    is ObjectValue -> value.objectFields.sortedBy { it.name }
        .joinToString(prefix = "{", postfix = "}", separator = ",") { "${it.name}:${canonicalDefault(it.value)}" }
    is ArrayValue -> value.values.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::canonicalDefault)
    else -> AstPrinter.printAstCompact(value)
}

private fun compareType(owner: String, oldType: GraphQLType, newType: GraphQLType, problems: MutableList<String>) {
    val oldRendered = GraphQLTypeUtil.simplePrint(oldType)
    val newRendered = GraphQLTypeUtil.simplePrint(newType)
    if (oldRendered != newRendered) {
        problems += "$owner changed type from $oldRendered to $newRendered"
    }
}

internal fun selectGraphqlBaselineRef(property: String?, environment: String?): String =
    sequenceOf(property, environment)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull { !ALL_ZERO_REVISION.matches(it) }
        ?: DEFAULT_BASELINE_REF

internal fun checkGraphqlCompatibility(
    repositoryRoot: Path,
    configuredRef: String?,
    environment: Map<String, String> = System.getenv(),
): String {
    val currentPath = repositoryRoot.resolve(SCHEMA_PATH)
    require(Files.isRegularFile(currentPath)) { "Current GraphQL SDL is missing: $currentPath" }
    val requestedRef = selectGraphqlBaselineRef(configuredRef, environment["GRAPHQL_BASELINE_REVISION"])
    val commit = runGit(
        repositoryRoot,
        listOf("rev-parse", "--verify", "--end-of-options", "$requestedRef^{commit}"),
        "Cannot resolve GraphQL baseline ref '$requestedRef'. Fetch its history or choose a valid " +
            "-PgraphqlBaselineRef/GRAPHQL_BASELINE_REVISION.",
    ).trim()
    require(Regex("[0-9a-fA-F]{40,64}").matches(commit)) {
        "Git resolved GraphQL baseline ref '$requestedRef' to an invalid commit id: $commit"
    }
    val baseline = runGit(
        repositoryRoot,
        listOf("show", "$commit:$SCHEMA_PATH"),
        "GraphQL baseline SDL '$SCHEMA_PATH' is missing from commit $commit (requested as '$requestedRef').",
    )
    val result = compareGraphqlSchemas(baseline, currentPath.readText())
    check(result.compatible) {
        "GraphQL schema is incompatible with baseline $commit (requested as '$requestedRef'):\n" +
            result.problems.joinToString("\n") { " - $it" }
    }
    return commit
}

private fun runGit(repositoryRoot: Path, arguments: List<String>, failure: String): String {
    val process = ProcessBuilder(listOf("git", "-C", repositoryRoot.toString()) + arguments)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) { "$failure\n${output.trim()}" }
    return output
}

object GraphqlCompatibilityCli {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size in 1..2) { "Usage: GraphqlCompatibilityCli <repository-root> [baseline-ref]" }
        val ref = arguments.getOrNull(1)
        val commit = checkGraphqlCompatibility(Path.of(arguments[0]), ref)
        println("GraphQL schema is compatible with baseline $commit")
    }
}
