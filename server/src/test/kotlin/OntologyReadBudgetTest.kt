package ch.nokillswit

import ch.nokillswit.entities.OntologyReadBudget
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OntologyReadBudgetTest {
    @Test
    fun `raw page budget refuses cumulative large values and resets for the next page`() {
        val document = """{"schema":{"properties":{"value":{"type":"string","default":"${"a".repeat(250_000)}"}}}}"""
        val budget = OntologyReadBudget()
        repeat(4) { budget.parse(document) }
        assertFailsWith<BadRequestException> { budget.parse(document) }
        val property = OntologyReadBudget().parse(document).jsonObject.getValue("schema")
            .jsonObject.getValue("properties").jsonObject.getValue("value").jsonObject
        assertEquals("string", property.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `compact arrays cannot evade the decoded heap ceiling`() {
        val document = """{"schema":{"properties":{"value":{"type":"array","default":[${List(60_000) { "0" }.joinToString(",")}]}}}}"""
        val budget = OntologyReadBudget()
        budget.parse(document)
        budget.parse(document)
        assertFailsWith<BadRequestException> { budget.parse(document) }
    }
}
