package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintList
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.blueprints.isSystemIdentifier
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.assertEquals

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

    /**
     * Loads one sample blueprint file through the real API: a `_`-prefixed identifier (v1.26.0's
     * system blueprints, `_team`/`_user`) is an EXTENSION of the row `V31__system_blueprints.sql`
     * already seeded, so this looks it up by identifier and `PUT`s the file's full desired
     * definition over it (204); every other identifier is `POST`ed fresh (201). Either way the
     * row is re-`GET`, so callers always see the stored, canonical [BlueprintResponse].
     */
    suspend fun loadBlueprint(client: HttpClient, text: String): BlueprintResponse {
        val request = blueprintJson.decodeFromString<BlueprintRequest>(text)
        val id = if (isSystemIdentifier(request.identifier)) {
            val existing = client.get("/api/v1/blueprints").body<BlueprintList>().items
                .first { it.identifier == request.identifier }
            val put = client.put("/api/v1/blueprints/${existing.id}") {
                contentType(ContentType.Application.Json)
                setBody(text)
            }
            assertEquals(HttpStatusCode.NoContent, put.status, "PUT ${request.identifier}: ${put.bodyAsText()}")
            existing.id
        } else {
            val post = client.post("/api/v1/blueprints") {
                contentType(ContentType.Application.Json)
                setBody(text)
            }
            assertEquals(HttpStatusCode.Created, post.status, "POST ${request.identifier}: ${post.bodyAsText()}")
            post.body<BlueprintResponse>().id
        }
        return client.get("/api/v1/blueprints/$id").body()
    }

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
