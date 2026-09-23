package ch.nokillswit.blueprints

import ch.nokillswit.infra.fetch.SourceColumns
import ch.nokillswit.infra.fetch.SourceWrite
import ch.nokillswit.infra.fetch.requireExpectedSourceUrl
import ch.nokillswit.infra.fetch.resolveSourceColumns
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * Source references & HTTP re-sync (2.10.0, V38) — the `entities/EntitySync.kt` twin, one level
 * up. Declared as `BlueprintService` EXTENSION functions (the `entities/EntityFilter.kt`
 * `inheritedTeamMatches` idiom) purely to keep `BlueprintService.kt` under the repo's
 * `LargeClass` threshold (`config/detekt/detekt.yml`); `writeTransaction`/`applyUpdate`/
 * `activeRows`/`active` widened to `internal` so this file can reach them.
 */

private typealias BlueprintRows = BlueprintService.Blueprints

/**
 * `POST …/blueprints/{id}/sync`: overwrites the stored definition with the submitted [request] —
 * the remote copy the client fetched and parsed client-side — under the SAME strict rules as
 * `BlueprintService.update` (no waiver exists for blueprints, the entity posture): identifier/
 * base-shape protection for `_team`/`_user`, target-existence, and the rename cascade all apply
 * unchanged via [BlueprintService.applyUpdate].
 *
 * `hierarchyRelations` keep-when-absent (the user decision this feature ships with): when
 * [request] omits the map (`null`), the STORED map is copied onto it BEFORE validation, so a
 * remote export that never carries this Toadie-only extension can never accidentally clear it —
 * clearing is an editor-only action. An empty map (`{}}`) folds to "keep" too, matching
 * [sanitizedHierarchyRelations]'s own "empty means absent" normalization. Merging before
 * validation means a remote that dropped the very relation the stored map still names is refused
 * `400` naming that key, exactly as an ordinary PUT would be.
 */
internal suspend fun BlueprintService.syncFromSource(
    id: UInt,
    request: BlueprintRequest,
    expectedSourceUrl: String? = null,
): BlueprintUpdateResult = writeTransaction {
    val rows = activeRows()
    val current = rows.firstOrNull { it.id == id } ?: return@writeTransaction BlueprintUpdateResult(0, emptyList(), null, false)
    requireExpectedSourceUrl(expectedSourceUrl, current.sourceUrl)
    val sourceUrl = current.sourceUrl ?: throw BadRequestException("This blueprint has no source reference — set one before syncing")
    val merged = if (request.hierarchyRelations == null) {
        request.copy(hierarchyRelations = current.hierarchyRelations.ifEmpty { null })
    } else {
        request
    }
    applyUpdate(id, rows, current, merged, SourceWrite.Synced(sourceUrl))
}

/** The sync state of one active blueprint (null = no such blueprint — the route's 404). Plain read, not audited. */
internal suspend fun BlueprintService.syncState(id: UInt): BlueprintSyncStateResponse? = suspendTransaction(database) {
    BlueprintRows.select(BlueprintRows.sourceUrl, BlueprintRows.lastSyncedAt, BlueprintRows.syncedContent)
        .where { (BlueprintRows.id eq id) and active() }
        .toList()
        .singleOrNull()
        ?.let { row ->
            BlueprintSyncStateResponse(
                sourceUrl = row[BlueprintRows.sourceUrl],
                lastSyncedAt = row[BlueprintRows.lastSyncedAt],
                syncedDocument = row[BlueprintRows.syncedContent]?.let { blueprintJson.decodeFromString<BlueprintRequest>(it) },
            )
        }
}

/**
 * The write half of `BlueprintService.applyUpdate`, sharing ONE [now] with the caller so an
 * ordinary update's `updatedAt` and a sync's `lastSyncedAt` stamp identically (D2,
 * `.claude/docs/persistence.md` "V38"). [current] is the row already loaded by the caller — its
 * CURRENT source columns decide the [SourceWrite.FromRequest] reset rule. [request]/[definition]
 * carry the FINAL values to persist, i.e. after any rename rewriting.
 */
internal suspend fun BlueprintService.replaceRow(
    id: UInt,
    current: BlueprintService.ActiveRow,
    request: BlueprintRequest,
    definition: BlueprintDefinition,
    source: SourceWrite,
    now: Long,
): Int {
    val currentColumns = SourceColumns(current.sourceUrl, current.lastSyncedAt, current.syncedContent)
    val resolved = resolveSourceColumns(source, request.sourceUrl, currentColumns, now) { baselineJson(request, definition) }
    return BlueprintRows.update({ (BlueprintRows.id eq id) and active() }) {
        it[identifier] = request.identifier
        it[title] = request.title
        it[description] = request.description
        it[icon] = request.icon
        it[BlueprintRows.definition] = blueprintJson.encodeToString(definition)
        it[hierarchyRelations] = encodeHierarchyRelations(request.hierarchyRelations)
        it[updatedAt] = now
        it[BlueprintRows.sourceUrl] = resolved.sourceUrl
        it[BlueprintRows.lastSyncedAt] = resolved.lastSyncedAt
        it[BlueprintRows.syncedContent] = resolved.syncedContent
    }
}

/**
 * The sync baseline: [request] (the FINAL request to persist — identity, `hierarchyRelations`
 * already merged/rewritten) with its Port-document fields overwritten by the FINAL stored
 * [definition] (a rename's self-target rewrite included) and `sourceUrl` absent — the DB-vs-
 * remote comparison the sync modal renders, never the envelope reference itself. `internal` (not
 * `private`) since `BlueprintService.insertRow` uses it too.
 */
internal fun baselineJson(request: BlueprintRequest, definition: BlueprintDefinition): String = blueprintJson.encodeToString(
    request.copy(
        schema = definition.schema,
        relations = definition.relations,
        mirrorProperties = definition.mirrorProperties,
        calculationProperties = definition.calculationProperties,
        aggregationProperties = definition.aggregationProperties,
        ownership = definition.ownership,
        sourceUrl = null,
    ),
)
