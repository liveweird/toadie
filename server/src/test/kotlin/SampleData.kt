package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.blueprintJson
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Shared helpers for the numbered JSON sample sets under `sample-data/` — the blueprint set
 * (the baseline ontology, [SampleBlueprintsTest]) and its entity set ([SampleEntitiesTest]) both
 * read their fixtures this way. Test cwd is `server/` (the Gradle test task's default working
 * directory), so files are read via `../sample-data/<dir>`.
 */
object SampleData {
    fun numberedFiles(dir: String): List<File> =
        File("../sample-data/$dir").listFiles { f -> f.name.matches(Regex("[0-9]{2}-.*\\.json")) }
            ?.sortedBy { it.name }
            ?: error("sample-data/$dir not found relative to the test working directory")

    /** [text] decoded as a request and re-encoded via [blueprintJson] — the canonical, defaults-expanded form. */
    fun canonicalBlueprint(text: String): JsonElement =
        Json.parseToJsonElement(blueprintJson.encodeToString(blueprintJson.decodeFromString<BlueprintRequest>(text)))

    /** The definitional fields of a stored [BlueprintResponse], reshaped back into a [BlueprintRequest]. */
    fun BlueprintResponse.asRequest() = BlueprintRequest(
        identifier = identifier,
        title = title,
        description = description,
        icon = icon,
        schema = schema,
        relations = relations,
        mirrorProperties = mirrorProperties,
        calculationProperties = calculationProperties,
        aggregationProperties = aggregationProperties,
        ownership = ownership,
        hierarchyRelation = hierarchyRelation,
    )
}
