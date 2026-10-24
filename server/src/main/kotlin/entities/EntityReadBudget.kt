package ch.nokillswit.entities

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Measured against kotlinx-serialization-json 1.11.0 on JDK 21 (compact strings, SerialGC):
// a scalar is a JsonLiteral (24 B) + a String (40 B + content); an object is a LinkedHashMap
// (64 B) + 40 B per entry + ~5 B of table slot + the key String; an array is an ArrayList
// (64 B) + 4 B per slot. Reproduces seven measured shapes within ±5 %.
private const val SCALAR_OVERHEAD = 64L
private const val CONTAINER_OVERHEAD = 64L
private const val OBJECT_ENTRY_OVERHEAD = 45L
private const val STRING_OVERHEAD = 40L
private const val ARRAY_SLOT = 4L

/** Estimated retained heap bytes of a decoded [element] (a `JsonNull` costs nothing — it is a singleton). */
fun estimatedHeapBytes(element: JsonElement): Long = when (element) {
    is JsonNull -> 0L
    is JsonPrimitive -> SCALAR_OVERHEAD + element.content.length
    is JsonArray -> CONTAINER_OVERHEAD + element.sumOf { ARRAY_SLOT + estimatedHeapBytes(it) }
    is JsonObject -> CONTAINER_OVERHEAD +
        element.entries.sumOf { (k, v) -> OBJECT_ENTRY_OVERHEAD + STRING_OVERHEAD + k.length + estimatedHeapBytes(v) }
}
