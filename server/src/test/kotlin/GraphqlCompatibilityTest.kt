package ch.nokillswit

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphqlCompatibilityTest {
    private val baseline = """
        type Query {
          thing(filter: Filter, limit: Int = 20): Thing
          configured(options: Options = {first: 1, second: 2}): Thing
        }
        type Thing {
          id: ID!
          name: String
          state: State
        }
        input Filter {
          q: String
        }
        input Options { first: Int second: Int }
        enum State { ACTIVE RETIRED }
    """.trimIndent()

    @Test
    fun `breaking schema changes are rejected`() {
        val changes = mapOf(
            "field removal" to baseline.replace("  name: String\n", ""),
            "field rename" to baseline.replace("name: String", "title: String"),
            "field type change" to baseline.replace("name: String", "name: ID"),
            "output nullability strengthening" to baseline.replace("name: String", "name: String!"),
            "output nullability weakening" to baseline.replace("id: ID!", "id: ID"),
            "required argument addition" to baseline.replace("limit: Int = 20", "limit: Int = 20, locale: String!"),
            "required input addition" to baseline.replace("q: String", "q: String\n  owner: String!"),
            "default value change" to baseline.replace("limit: Int = 20", "limit: Int = 10"),
            "enum value removal" to baseline.replace(" ACTIVE RETIRED", " ACTIVE"),
        )

        changes.forEach { (name, current) ->
            val result = compareGraphqlSchemas(baseline, current)
            assertTrue(!result.compatible, "$name unexpectedly passed")
        }
    }

    @Test
    fun `optional additions descriptions comments and ordering are compatible`() {
        val current = """
            # Order and documentation changes do not alter compatibility.
            "An existing result."
            type Thing {
              name: String
              id: ID!
              state: State
              note: String
            }
            enum State { RETIRED ACTIVE }
            input Filter {
              owner: String
              q: String
            }
            type Query {
              thing(locale: String, limit: Int = 20, filter: Filter): Thing
              configured(options: Options = {second: 2, first: 1}): Thing
              other: Extra
            }
            input Options { second: Int first: Int }
            type Extra { id: ID! }
        """.trimIndent()

        val result = compareGraphqlSchemas(baseline, current)
        assertTrue(result.compatible, result.problems.joinToString("\n"))
    }

    @Test
    fun `baseline ref precedence ignores empty and all-zero revisions`() {
        assertTrue(selectGraphqlBaselineRef("release", "environment") == "release")
        assertTrue(selectGraphqlBaselineRef(" ", "environment") == "environment")
        assertTrue(selectGraphqlBaselineRef(null, "0000000000000000000000000000000000000000") == "origin/master")
    }

    @Test
    fun `members declared in type extensions retain strict nullability`() {
        val oldSchema = "type Query { thing: Thing } type Thing { id: ID! } extend type Thing { name: String }"
        val newSchema = "type Query { thing: Thing } type Thing { id: ID! } extend type Thing { name: String! }"

        val result = compareGraphqlSchemas(oldSchema, newSchema)
        assertTrue(!result.compatible, "extension field nullability change unexpectedly passed")
    }

    @Test
    fun `unused types and enum values cannot be removed`() {
        val oldSchema = """
            type Query { ok: Boolean }
            type Dormant { state: DormantState }
            enum DormantState { ON OFF }
        """.trimIndent()
        val changes = listOf(
            oldSchema.replace(" ON OFF", " ON"),
            "type Query { ok: Boolean }",
        )

        changes.forEach { current ->
            val result = compareGraphqlSchemas(oldSchema, current)
            assertTrue(!result.compatible, "unused contract removal unexpectedly passed")
        }
    }

    @Test
    fun `unused directive definitions retain their contract`() {
        val oldSchema = """
            directive @trace(flag: Boolean = true) repeatable on FIELD | QUERY
            type Query { ok: Boolean }
        """.trimIndent()
        val changes = listOf(
            "type Query { ok: Boolean }",
            oldSchema.replace(" | QUERY", ""),
            oldSchema.replace(" repeatable", ""),
            oldSchema.replace("flag: Boolean = true", "flag: String = \"true\""),
            oldSchema.replace("= true", "= false"),
            oldSchema.replace("(flag: Boolean = true)", ""),
            oldSchema.replace("flag: Boolean = true", "flag: Boolean = true, required: ID!"),
        )

        changes.forEach { current ->
            val result = compareGraphqlSchemas(oldSchema, current)
            assertTrue(!result.compatible, "unused directive contract change unexpectedly passed")
        }

        val additive = """
            "Updated documentation."
            directive @trace(optional: String, flag: Boolean = true) repeatable on QUERY | FIELD | OBJECT
            directive @newDirective on FIELD
            type Query { ok: Boolean }
        """.trimIndent()
        val result = compareGraphqlSchemas(oldSchema, additive)
        assertTrue(result.compatible, result.problems.joinToString("\n"))
    }

    @Test
    fun `missing baseline history fails clearly`() {
        val repository = Files.createTempDirectory("graphql-compatibility-test")
        try {
            repository.resolve("server/src/main/resources/graphql").createDirectories()
            repository.resolve("server/src/main/resources/graphql/schema.graphqls")
                .writeText("type Query { ok: Boolean }")
            ProcessBuilder("git", "-C", repository.toString(), "init", "--quiet").start().also {
                assertTrue(it.waitFor() == 0)
            }

            val error = assertFailsWith<IllegalStateException> {
                checkGraphqlCompatibility(repository, "missing-ref", emptyMap())
            }
            assertContains(error.message.orEmpty(), "Cannot resolve GraphQL baseline ref 'missing-ref'")
        } finally {
            repository.toFile().deleteRecursively()
        }
    }
}
