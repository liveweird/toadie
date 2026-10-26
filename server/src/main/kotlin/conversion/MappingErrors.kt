package ch.nokillswit.conversion

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.catalog.RegistrySnapshot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll

/**
 * One stored mapping that carries blocking findings and/or drift — a row of the Port-world Errors
 * report (`entities/EntityErrors.kt`). Clean mappings are omitted from the report, like
 * every other row kind; [findings] and [drift] are the same lists `GET /conversion-mappings`
 * answers for the mapping.
 */
@Serializable
data class MappingErrorRow(
    val id: UInt,
    val name: String,
    val findings: List<MappingFinding>,
    val drift: List<MappingDrift>,
)

/** A stored mapping as the report needs it: identity plus the parsed-JSON document. */
internal data class StoredMapping(val id: UInt, val name: String, val document: JsonElement)

/**
 * Every active mapping, read inside the caller's (Errors report) transaction — a sanctioned
 * cross-feature table read of [ConversionMappingService.ConversionMappings] (`persistence.md`),
 * the `loadVisibleSavedQueries` idiom: registries capped at <= 200 rows, so no read-ledger step.
 */
internal suspend fun loadStoredMappings(): List<StoredMapping> {
    val mappings = ConversionMappingService.ConversionMappings
    return mappings.selectAll()
        .where { mappings.markedAsDeleted eq false }
        .map { StoredMapping(it[mappings.id].value, it[mappings.name], Json.parseToJsonElement(it[mappings.document])) }
        .toList()
}

/**
 * The report rows over [mappings], validated against [blueprints] and [registry] OUTSIDE any
 * transaction (pure): name-ordered case-insensitively, mappings with neither findings nor drift
 * omitted.
 */
internal fun mappingErrorRows(
    mappings: List<StoredMapping>,
    blueprints: Map<String, BlueprintDefinition>,
    registry: RegistrySnapshot,
): List<MappingErrorRow> = mappings
    .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    .mapNotNull { stored ->
        val verdict = validateMapping(stored.document, blueprints, registry)
        if (verdict.findings.isEmpty() && verdict.drift.isEmpty()) {
            null
        } else {
            MappingErrorRow(stored.id, stored.name, verdict.findings, verdict.drift)
        }
    }
