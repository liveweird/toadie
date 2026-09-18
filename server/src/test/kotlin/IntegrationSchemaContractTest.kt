package ch.nokillswit

import ch.nokillswit.integration.parseIntegrationSchema
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLNamedType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegrationSchemaContractTest {
    private val sdl = checkNotNull(javaClass.getResource("/graphql/schema.graphqls")).readText()

    @Test
    fun `schema is documented and read-only with the exact Port roots`() {
        val schema = parseIntegrationSchema(sdl)
        assertNull(schema.mutationType)
        assertNull(schema.subscriptionType)
        assertEquals(
            listOf("blueprint", "blueprints", "entities", "entity", "errors").sorted(),
            schema.queryType.fieldDefinitions.map { it.name }.sorted(),
        )

        val undocumented = mutableListOf<String>()
        schema.typeMap.values.filterIsInstance<GraphQLNamedType>()
            .filterNot { it.name.startsWith("__") || it.name in BUILT_INS }
            .forEach { type ->
                if (type.description.isNullOrBlank()) undocumented += type.name
                if (type is GraphQLFieldsContainer) {
                    type.fieldDefinitions.forEach { field ->
                        if (field.description.isNullOrBlank()) undocumented += "${type.name}.${field.name}"
                        field.arguments.filter { it.description.isNullOrBlank() }
                            .forEach { undocumented += "${type.name}.${field.name}(${it.name})" }
                    }
                }
            }
        assertTrue(undocumented.isEmpty(), "undocumented schema members: $undocumented")
    }

    @Test
    fun `contract has no account secret query execution or saved-query surface`() {
        val schema = parseIntegrationSchema(sdl)
        val fields = schema.typeMap.values.filterIsInstance<GraphQLFieldsContainer>()
            .filterNot { it.name.startsWith("__") }
            .flatMap { type -> type.fieldDefinitions.map { type.name to it.name } }
        val forbidden = setOf("password", "passwordHash", "token", "apiKey", "keyHash", "savedQueries", "queryText")
        assertTrue(fields.none { (_, field) -> field in forbidden }, fields.toString())
    }

    private companion object {
        val BUILT_INS = setOf("Int", "Float", "String", "Boolean", "ID")
    }
}
