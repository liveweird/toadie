package ch.nokillswit.entities

import ch.nokillswit.blueprints.BlueprintService
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.infra.concurrency.ReadLedger
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * One stored entity exactly as its row holds it — the STORED `properties`/`relations` document
 * (computed values are never part of it) and the STORED `team` value (the JSON the client sent, a
 * string or an array; never the Inherited-ownership computation) — which is what a footprint merge
 * must start from: merging over the computed view would write computed property ids back (`COMPUTED_PROPERTY`)
 * and an inherited team onto a blueprint that does not store one.
 */
internal data class StoredEntity(
    val id: UInt,
    val blueprint: String,
    val identifier: String,
    val title: String,
    val icon: String?,
    val team: JsonElement?,
    val properties: JsonObject,
    val relations: JsonObject,
)

/**
 * The conversion import's merge read (`conversion/ConversionImportService.kt`): the stored
 * documents of the [ids] the batch will overwrite (at most the 200-document batch cap), by id.
 * The caller owns the [reservation] — ONE ledger reservation held across this read, the whole
 * conversion and the encoding of its response, because the decoded documents stay alive until the
 * response is built (a reservation closed before that would under-count the heap). The rows'
 * stored byte sizes [snapshot] already holds are reserved BEFORE any document is read, at the
 * shown-row ratio ([ENTITY_SHOWN_ROW_RATIO] — rows decoded in full, which a merge needs: it must
 * carry every stored property and relation), so even 200 maximum-size documents are admitted like
 * any other content read (`400` too large on its own, `429` contention; no write lock, no audit).
 * An id that is no longer active is simply absent.
 */
internal suspend fun EntityService.readStoredEntities(
    ids: Collection<UInt>,
    snapshot: EntityImportSnapshot,
    reservation: ReadLedger.Reservation,
): Map<UInt, StoredEntity> {
    if (ids.isEmpty()) return emptyMap()
    return suspendTransaction(database) {
        reservation.reserve(ids.sumOf { snapshot.bytesFor(it) } * ENTITY_SHOWN_ROW_RATIO)
        val entities = EntityService.Entities
        val blueprints = BlueprintService.Blueprints
        // Only what a merge reads: no creator join, no source/sync columns.
        entities.join(blueprints, JoinType.INNER, onColumn = entities.blueprintId, otherColumn = blueprints.id)
            .select(
                entities.id, blueprints.identifier, entities.identifier, entities.title, entities.icon, entities.team,
                entities.document,
            )
            .where { (entities.id inList ids) and active() }
            .map { row ->
                val document = blueprintJson.decodeFromString<EntityDocument>(row[entities.document])
                StoredEntity(
                    id = row[entities.id].value,
                    blueprint = row[blueprints.identifier],
                    identifier = row[entities.identifier],
                    title = row[entities.title],
                    icon = row[entities.icon],
                    team = row[entities.team]?.let { blueprintJson.decodeFromString<JsonElement>(it) },
                    properties = document.properties,
                    relations = document.relations,
                )
            }
            .toList()
            .associateBy { it.id }
    }
}
