package ch.nokillswit.entities

import ch.nokillswit.infra.concurrency.ReadBudgetExceeded
import ch.nokillswit.infra.concurrency.ReadLedger
import ch.nokillswit.infra.concurrency.throwReadBudgetHttp
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * One stored entity as the Port -> Backstage conversion reads it (`conversion/BackstageExport.kt`):
 * the stored `properties`/`relations` (computed values are never mapped, so none are merged), the
 * EFFECTIVE [team] (Inherited ownership resolved, exactly as the Entity graph shows it) and [shown]
 * — whether the caller's filter selected the row, as opposed to it being loaded only as CONTEXT
 * (a reference target or the far end of a reverse/derive rule, whose converted identity the shown
 * documents need). [properties] is complete for a shown row of a mapped blueprint, holds only the
 * properties the pair's namespace rules read for a context row, and is empty for a row of an unmapped
 * blueprint (nothing is converted from it).
 */
internal data class ExportEntity(
    val id: UInt,
    val blueprint: String,
    val identifier: String,
    val title: String,
    val icon: String?,
    val team: List<String>,
    val properties: JsonObject,
    val relations: JsonObject,
    val shown: Boolean,
)

/**
 * What the export needs to know about the mapping to read entities: [contextBlueprints] are the mapped
 * blueprints (every entity of them is loaded, shown or not), [identityProperties] the properties per
 * blueprint that its pair's `metadata.namespace` rules read — the only properties a CONTEXT row needs.
 */
internal data class ExportScope(val contextBlueprints: Set<String>, val identityProperties: Map<String, Set<String>>)

/**
 * The conversion export's entity read (`GET …/conversion-mappings/{id}/catalog-documents`): the
 * Entity graph's read path (`EntityGraphRead.kt`) with a different payload. The SHOWN set is the
 * graph's own filter set — `blueprint` IN, `q`, `team` ([shownScope], one filter parser), the
 * optional [entityId], and the optional entity `query` ([withParsedQuery]: parsed/validated before any
 * row is read, one `entity-query` permit, evaluated OUTSIDE the transaction over the whole workspace and
 * narrowing the shown rows only) — and the CONTEXT set is every active entity of [ExportScope.contextBlueprints]
 * regardless of the filter (a document's references and the derive pass must see the whole workspace of the
 * mapped blueprints, or filtering would silently drop edges).
 *
 * The read is charged to the CALLER's [reservation], which the caller keeps open for as long as the
 * returned entities are alive (`ConversionExportService`): the decoded snapshot outlives this function, so
 * releasing the reservation here would let concurrent exports hold far more heap than the ledger sees.
 * Admission follows [loadReadSet]: shown rows (and the context rows of a blueprint whose namespace rule
 * reads a property, which are decoded for it) at the shown ratio, other context rows at the target ratio.
 * Only a shown row of a mapped blueprint is decoded in full; every other row is decoded to its relations
 * and team (plus the identity properties above), and a shown row of an unmapped blueprint — a SKIPPED
 * row — never has its properties decoded. No write lock, no audit; rows come back in id order.
 */
internal suspend fun EntityService.exportEntities(
    filter: EntityGraphFilter,
    entityId: UInt?,
    scope: ExportScope,
    reservation: ReadLedger.Reservation,
): List<ExportEntity> = withParsedQuery(filter.query) { parsed ->
    exportWithPlan(filter, entityId, scope, parsed, reservation)
}

private class ExportLoad(val entities: List<ExportEntity>, val plan: GraphQueryPlan?)

private suspend fun EntityService.exportWithPlan(
    filter: EntityGraphFilter,
    entityId: UInt?,
    scope: ExportScope,
    parsed: ParsedQuery?,
    reservation: ReadLedger.Reservation,
): List<ExportEntity> {
    try {
        val loaded = suspendTransaction(database) { materializeExport(filter, entityId, scope, parsed, reservation) }
            ?: return emptyList()
        val matched = loaded.plan?.let { plan -> evaluateEntityQuery(plan).rows.map { it.key }.toSet() }
            ?: return loaded.entities
        return loaded.entities.map { it.copy(shown = it.shown && (it.blueprint to it.identifier) in matched) }
    } catch (cause: ReadBudgetExceeded) {
        throwReadBudgetHttp(cause, "entity")
    }
}

private suspend fun EntityService.materializeExport(
    filter: EntityGraphFilter,
    entityId: UInt?,
    scope: ExportScope,
    parsed: ParsedQuery?,
    reservation: ReadLedger.Reservation,
): ExportLoad? {
    val activeBlueprints = loadActiveBlueprints()
    val blueprintsById = activeBlueprints.associateBy { it.id }
    val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
    val definitionsByIdentifier = activeBlueprints.associate { it.identifier to it.definition }

    val shownScope = shownScope(filter, activeBlueprints, reservation) ?: return null
    val shownPredicate = entityId?.let { shownScope.predicate and (EntityService.Entities.id eq it) } ?: shownScope.predicate

    val context = scope.contextBlueprints.filter { it in definitionsByIdentifier }.toSet()
    // A query may traverse through any blueprint; otherwise only the context blueprints and the
    // blueprints an Inherited ownership path walks through (for the effective team) are needed.
    val targets = if (parsed != null) {
        blueprintsByIdentifier.keys
    } else {
        context + context.flatMap { ownershipPathBlueprints(definitionsByIdentifier.getValue(it), definitionsByIdentifier) }
    }
    // Context rows whose namespace rule reads a property are decoded, so they are admitted like shown rows.
    val needsProperties = context.filter { scope.identityProperties[it].orEmpty().isNotEmpty() }
    val widened: Op<Boolean> = if (needsProperties.isEmpty()) {
        shownPredicate
    } else {
        shownPredicate or (EntityService.Entities.blueprintId inList needsProperties.map { blueprintsByIdentifier.getValue(it).id })
    }
    val readSet = loadReadSet(widened, targets, blueprintsByIdentifier, reservation)
    val shownIds: Set<UInt> = if (needsProperties.isEmpty()) {
        readSet.rows.filter { it.shown }.map { it.id }.toSet()
    } else {
        val id = EntityService.Entities.id
        EntityService.Entities.select(id).where { shownPredicate }.map { it[id].value }.toList().toSet()
    }

    val entities = readSet.rows.filter { it.id in shownIds || it.blueprint in context }.map { row ->
        val definition = blueprintsById.getValue(row.blueprintId).definition
        val shown = row.id in shownIds
        val skeleton = row.skeleton()
        val properties = when {
            shown && row.blueprint in context -> row.full().document.properties
            row.blueprint in context && scope.identityProperties[row.blueprint].orEmpty().isNotEmpty() -> {
                val needed = scope.identityProperties.getValue(row.blueprint)
                JsonObject(row.full().document.properties.filterKeys { it in needed })
            }
            else -> JsonObject(emptyMap())
        }
        ExportEntity(
            id = row.id,
            blueprint = row.blueprint,
            identifier = row.identifier,
            title = row.title,
            icon = row.icon,
            team = teamValues(
                effectiveTeam(skeleton.team, skeleton.document, definition, definitionsByIdentifier, readSet.snapshot.rowLookup),
            ),
            properties = properties,
            relations = skeleton.document.relations,
            shown = shown,
        )
    }
    val plan = parsed?.let {
        val graphBlueprints = activeBlueprints.associate {
            it.identifier to GraphBlueprint(it.identifier, it.title, it.definition, it.hierarchyRelations, it.tiers)
        }
        GraphQueryPlan(it.query, it.slots, readSet.rows, graphBlueprints, it.hierarchies, it.textLength)
    }
    return ExportLoad(entities, plan)
}
