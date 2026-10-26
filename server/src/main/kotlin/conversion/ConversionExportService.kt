package ch.nokillswit.conversion

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.catalog.CatalogFileService
import ch.nokillswit.entities.EntityGraphFilter
import ch.nokillswit.entities.EntityService
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.ExportScope
import ch.nokillswit.entities.exportEntities
import ch.nokillswit.infra.concurrency.ReadLedger
import io.ktor.util.AttributeKey

val ConversionExportServiceKey = AttributeKey<ConversionExportService>("ConversionExportService")

/**
 * The orchestration of `GET /api/v1/conversion-mappings/{id}/catalog-documents` — a PURE read:
 * no writes, no locks, no audit event. It takes ONE reservation on the process-wide [readLedger] and
 * holds it across everything that keeps the decoded snapshot alive: the entity read ([exportEntities],
 * the Entity graph's filter set), the stored-catalog read when [merge] is on
 * ([CatalogFileService.readActiveSources], narrowed to the identities the export will emit), the pure
 * conversion ([exportCatalogDocuments]) and — for [catalogDocumentsJson], which the route uses — the
 * response encoding. Both reads charge that one reservation, so their costs ADD (a second read cannot
 * be admitted on top of room the first already holds), and it is released only when the answer is built.
 * The mapping and its blueprints/registries are read first, without the ledger (a registry-sized read).
 */
class ConversionExportService(
    private val mappingService: ConversionMappingService,
    private val entityService: EntityService,
    private val catalogFileService: CatalogFileService,
    private val readLedger: ReadLedger,
) {
    /** The documents and the per-entity report; null when [mappingId] is not an active mapping, a `409` when it has blocking findings. */
    suspend fun catalogDocuments(mappingId: UInt, filter: EntityGraphFilter, entityId: UInt?, merge: Boolean): CatalogDocumentsResponse? =
        convert(mappingId, filter, entityId, merge) { it }

    /** [catalogDocuments], encoded with `blueprintJson` (no `null` member) while the reservation is still held. */
    suspend fun catalogDocumentsJson(mappingId: UInt, filter: EntityGraphFilter, entityId: UInt?, merge: Boolean): String? =
        convert(mappingId, filter, entityId, merge) { blueprintJson.encodeToString(it) }

    private suspend fun <T> convert(
        mappingId: UInt,
        filter: EntityGraphFilter,
        entityId: UInt?,
        merge: Boolean,
        finish: (CatalogDocumentsResponse) -> T,
    ): T? {
        val basis = mappingService.exportBasis(mappingId) ?: return null
        val document = basis.document ?: throw ConflictException(
            "The conversion mapping has ${basis.mapping.findings.size} blocking finding(s) — fix the mapping before converting",
        )
        readLedger.open().use { reservation ->
            val entities = entityService.exportEntities(filter, entityId, exportScopeOf(document), reservation)
            val input = ExportInput(
                mapping = document,
                blueprints = basis.blueprints,
                registry = basis.registry,
                defaultNamespace = basis.defaultNamespace,
                entities = entities,
                existing = null,
            )
            // Merge only over the stored files the export can write to: a narrow export reads (and reserves) little.
            val existing = if (merge) catalogFileService.readActiveSources(reservation, exportedIdentities(input)) else null
            val response = exportCatalogDocuments(input.copy(existing = existing))
            return finish(response)
        }
    }
}

/** What the entity read needs from a mapping: its blueprints, and per blueprint the properties its namespace rules read. */
internal fun exportScopeOf(document: MappingDocument): ExportScope = ExportScope(
    contextBlueprints = document.pairs.map { it.blueprint }.toSet(),
    identityProperties = document.pairs.associate { pair ->
        pair.blueprint to pair.rules
            .filter { it.writesOnExport && it.backstage == BackstageAddress.Metadata(MetadataField.NAMESPACE) }
            .mapNotNull { (it.port as? PortAddress.Property)?.id }
            .toSet()
    },
)
