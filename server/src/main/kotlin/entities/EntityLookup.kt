package ch.nokillswit.entities

import ch.nokillswit.blueprints.BlueprintService
import ch.nokillswit.infra.db.currentOntologyRevision
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

// Identity lookups the MCP endpoint needs (2.15.0), kept as extension functions in a sibling
// file — the `EntitySync.kt` idiom — so `EntityService` stays under detekt's LargeClass threshold.

/**
 * Resolve `(blueprint, identifier)` to an entity id — the once-lowercased lookup the V28 partial
 * unique index enforces (`LOWER(identifier)` scoped to `blueprint_id`), joined against the owning
 * blueprint's OWN identifier the same way. A soft-deleted entity, a soft-deleted blueprint, or the
 * same identifier under a DIFFERENT blueprint all answer `null`.
 */
suspend fun EntityService.findByIdentity(blueprint: String, identifier: String): UInt? =
    suspendTransaction(database) {
        EntityService.Entities
            .join(
                BlueprintService.Blueprints,
                JoinType.INNER,
                onColumn = EntityService.Entities.blueprintId,
                otherColumn = BlueprintService.Blueprints.id,
            )
            .selectAll()
            .where {
                (LowerCase(BlueprintService.Blueprints.identifier) eq stringParam(blueprint.lowercase())) and
                    (LowerCase(EntityService.Entities.identifier) eq stringParam(identifier.lowercase())) and
                    active() and
                    activeBlueprints()
            }
            .map { it[EntityService.Entities.id].value }
            .singleOrNull()
    }

/** The V39 ontology-revision counter, read outside any lock (`.claude/docs/persistence.md` "Ontology revision (V39)"). */
suspend fun EntityService.ontologyRevision(): Long = suspendTransaction(database) { currentOntologyRevision() }
