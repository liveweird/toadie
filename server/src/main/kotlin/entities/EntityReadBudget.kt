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

// Admission weights (2.19.0): `loadReadSet` reserves `raw x ratio` per row up front, where raw is
// the row's stored `octet_length(document) + octet_length(team)`. Calibrated on the Port sample
// (`SampleData.numberedFiles("entities")`, pinned by `EntityReadBudgetTest`): the decoded heap is
// ~1.52x raw for relations+team and ~4.51x raw for properties. A SHOWN row is decoded in full
// (raw string held 1 + relations/team 1.52 + properties 4.51 = 7.03), so it is prepaid at 8. A
// TARGET-only row (a lookup/validation target, mostly never decoded) is admitted at raw x
// ENTITY_TARGET_ADMISSION_RATIO; the rows that ARE decoded are charged as overflow, up to
// ENTITY_TARGET_ROW_RATIO x raw each (raw 1 + relations/team 1.52 = 2.52, rounded up to 3 — the
// worst case a paged read pins against the write cap). A dense-JSON workspace can decode past its
// estimate; that overrun is charged on top (the ledger's overflow) rather than admitted for free.
const val ENTITY_SHOWN_ROW_RATIO = 8L
const val ENTITY_TARGET_ADMISSION_RATIO = 1L
const val ENTITY_TARGET_ROW_RATIO = 3L
