package ch.nokillswit.entities

import ch.nokillswit.blueprints.blueprintJson
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * Source references & HTTP re-sync (2.9.0, V37) — the `catalog/CatalogFileService.kt`
 * `syncFromRepo`/`syncState` twin, one level down. Declared as `EntityService` EXTENSION
 * functions (the `entities/EntityFilter.kt` `inheritedTeamMatches` idiom) purely to keep
 * `EntityService.kt` under the repo's `LargeClass` threshold (`config/detekt/detekt.yml`);
 * `writeTransaction`/`applyUpdate` widened to `internal` so this file can reach them.
 */

private typealias Entities = EntityService.Entities

/**
 * `POST …/entities/{id}/sync`: overwrites the stored document with the submitted [request] —
 * the remote copy the client fetched and parsed client-side — under the SAME strict rules as
 * `EntityService.update` (no waiver exists for entities, unlike the catalog's repo sync):
 * findings, the blueprint-must-equal-stored 400, and the rename cascade all apply unchanged via
 * `EntityService.applyUpdate`. The cheap row checks (existence, then a source reference) run
 * BEFORE that heavier blueprint/findings work.
 */
internal suspend fun EntityService.syncFromSource(id: UInt, request: EntityRequest): EntityUpdateResult = writeTransaction {
    val row = Entities.selectAll().where { (Entities.id eq id) and active() }.singleOrNull()
        ?: return@writeTransaction EntityUpdateResult(0, emptyList(), null)
    val sourceUrl = row[Entities.sourceUrl]
        ?: throw BadRequestException("This entity has no source reference — set one before syncing")
    applyUpdate(id, row, request, SourceWrite.Synced(sourceUrl))
}

/** The sync state of one active entity (null = no such entity — the route's 404). Plain read, not audited. */
internal suspend fun EntityService.syncState(id: UInt): EntitySyncStateResponse? = suspendTransaction(database) {
    Entities.select(Entities.sourceUrl, Entities.lastSyncedAt, Entities.syncedContent)
        .where { (Entities.id eq id) and active() }
        .toList()
        .singleOrNull()
        ?.let { row ->
            EntitySyncStateResponse(
                sourceUrl = row[Entities.sourceUrl],
                lastSyncedAt = row[Entities.lastSyncedAt],
                syncedDocument = row[Entities.syncedContent]?.let { blueprintJson.decodeFromString<EntityRequest>(it) },
            )
        }
}

/**
 * The write half of `EntityService.applyUpdate`, sharing ONE [now] with the caller so an
 * ordinary update's `updatedAt` and a sync's `lastSyncedAt` stamp identically (D2,
 * `.claude/docs/persistence.md` "V37"). [row] is the row already loaded by the caller — its
 * CURRENT source columns decide the [SourceWrite.FromRequest] reset rule. [request] carries the
 * FINAL values to persist, i.e. after any rename rewriting.
 */
internal suspend fun EntityService.replaceRow(id: UInt, row: ResultRow, request: EntityRequest, source: SourceWrite, now: Long): Int {
    val currentSourceUrl = row[Entities.sourceUrl]
    val sourceUrlValue: String?
    val lastSyncedAtValue: Long
    val syncedContentValue: String?
    when (source) {
        SourceWrite.FromRequest -> {
            sourceUrlValue = request.sourceUrl
            if (request.sourceUrl != currentSourceUrl) {
                lastSyncedAtValue = 0L
                syncedContentValue = null
            } else {
                lastSyncedAtValue = row[Entities.lastSyncedAt]
                syncedContentValue = row[Entities.syncedContent]
            }
        }
        SourceWrite.Keep -> {
            sourceUrlValue = currentSourceUrl
            lastSyncedAtValue = row[Entities.lastSyncedAt]
            syncedContentValue = row[Entities.syncedContent]
        }
        is SourceWrite.Synced -> {
            sourceUrlValue = source.sourceUrl
            lastSyncedAtValue = now
            syncedContentValue = baselineJson(request, EntityDocument(request.properties, request.relations))
        }
    }
    return Entities.update({ (Entities.id eq id) and active() }) {
        it[identifier] = request.identifier
        it[title] = request.title
        it[icon] = request.icon
        it[Entities.team] = request.team?.let { t -> blueprintJson.encodeToString(t) }
        it[Entities.document] = blueprintJson.encodeToString(EntityDocument(request.properties, request.relations))
        it[updatedAt] = now
        it[Entities.sourceUrl] = sourceUrlValue
        it[Entities.lastSyncedAt] = lastSyncedAtValue
        it[Entities.syncedContent] = syncedContentValue
    }
}

/**
 * The sync baseline: the request-shaped document (blueprint/identifier/title/icon/team plus
 * the ALREADY-FILTERED `document.properties`/`relations`) with `sourceUrl` absent — the
 * DB-vs-remote comparison the sync modal renders, never the envelope reference itself. `internal`
 * (not `private`) since `EntityService.insertRow` (`EntityService.kt`) uses it too.
 */
internal fun baselineJson(request: EntityRequest, document: EntityDocument): String = blueprintJson.encodeToString(
    EntityRequest(
        blueprint = request.blueprint,
        identifier = request.identifier,
        title = request.title,
        icon = request.icon,
        team = request.team,
        properties = document.properties,
        relations = document.relations,
    ),
)
