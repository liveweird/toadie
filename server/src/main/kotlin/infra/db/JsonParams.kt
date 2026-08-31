package ch.nokillswit.infra.db

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * (De)serialization of the string-to-string `params` maps stored as JSON TEXT columns (today:
 * `catalog_file_events.params`) — the interpolation values the SPA uses to render structured
 * events in the viewer's language. Ported from Lettuce verbatim; a future notifications table
 * shares it, which is why it lives beside [EventLog] rather than inside it.
 */
private val paramsSerializer = MapSerializer(String.serializer(), String.serializer())

internal fun encodeParams(params: Map<String, String>): String =
    Json.encodeToString(paramsSerializer, params)

internal fun decodeParams(json: String): Map<String, String> =
    Json.decodeFromString(paramsSerializer, json)
