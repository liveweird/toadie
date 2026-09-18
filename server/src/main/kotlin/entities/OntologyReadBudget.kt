package ch.nokillswit.entities

import ch.nokillswit.blueprints.blueprintJson
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

private const val MAX_PAGE_RAW_BYTES = 1024 * 1024
private const val MAX_PAGE_DECODED_BYTES = 8L * 1024 * 1024

/** Bounds eager Port definition/page decoding before a GraphQL root can retain the result.
 * The GraphQL request budget separately accounts for values retained across multiple roots. */
class OntologyReadBudget {
    private var rawBytes = 0L
    private var decodedBytes = 0L

    fun parse(raw: String): JsonElement {
        rawBytes += raw.toByteArray(Charsets.UTF_8).size
        if (rawBytes > MAX_PAGE_RAW_BYTES) refuse()
        val element = blueprintJson.parseToJsonElement(raw)
        retain(element)
        return element
    }

    fun retain(element: JsonElement) {
        charge(estimatedHeapBytes(element))
    }

    fun retainFindings(findings: List<EntityFinding>) {
        charge(64L + findings.sumOf { 160L + 2L * (it.code.length + it.field.length + it.message.length) })
    }

    private fun charge(bytes: Long) {
        decodedBytes += bytes
        if (decodedBytes > MAX_PAGE_DECODED_BYTES) refuse()
    }

    private fun refuse(): Nothing = throw BadRequestException("Ontology read exceeds the document budget; reduce pageSize or document size")
}

/** Keep ordinary REST reads unchanged; integration callers opt into a cumulative document cap. */
internal inline fun <reified T> decodeForRead(raw: String, budget: OntologyReadBudget?): T =
    if (budget == null) blueprintJson.decodeFromString<T>(raw)
    else blueprintJson.decodeFromJsonElement<T>(budget.parse(raw))
