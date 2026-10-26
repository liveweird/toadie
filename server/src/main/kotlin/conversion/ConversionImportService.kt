package ch.nokillswit.conversion

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.catalog.CatalogFileService
import ch.nokillswit.entities.EntityImportSnapshot
import ch.nokillswit.entities.EntityService
import ch.nokillswit.entities.readStoredEntities
import ch.nokillswit.infra.concurrency.ReadBudgetExceeded
import ch.nokillswit.infra.concurrency.throwReadBudgetHttp
import io.ktor.util.AttributeKey

val ConversionImportServiceKey = AttributeKey<ConversionImportService>("ConversionImportService")

/**
 * The orchestration of `POST /api/v1/conversion-mappings/{id}/entity-documents` — a PURE
 * computation: no writes, no locks, no audit event (the writes happen later, through the ordinary
 * `POST /api/v1/entities/import`, which audits as usual). The reads, in sequence (so the peak is the
 * largest of them, never the sum):
 *  1. the mapping with the blueprints and registries it is judged against ([ConversionMappingService.exportBasis]);
 *  2. the stored catalog's IDENTITIES — kind, namespace, name and the selector fields a pair reads,
 *     never `content` ([CatalogFileService.readIdentities]; an identity-only read, outside the read
 *     ledger by its documented exception);
 *  3. the Port workspace's identities ([EntityService.importSnapshot], the entity import's own
 *     snapshot: identifiers and byte sizes, never documents);
 *  4. when [EntityDocumentsRequest.merge] is on, the stored documents of the at most
 *     `MAX_IMPORT_DOCUMENTS` entities the batch will overwrite ([readStoredEntities]) — admitted
 *     against the process-wide read ledger by their known stored byte sizes BEFORE any document is
 *     read, so a hostile workspace of maximum-size entities is a `400`/`429` like any other content read.
 * Step 4, the conversion ([ImportRun], a pure function of what was read) and the encoding of the
 * response share ONE reservation, released only when the finished body exists.
 */
class ConversionImportService(
    private val mappingService: ConversionMappingService,
    private val entityService: EntityService,
    private val catalogFileService: CatalogFileService,
) {
    /**
     * The encoded `200` body, or null when [mappingId] is not an active mapping; a `409` when the
     * mapping carries blocking findings. Encoded HERE, inside the one ledger reservation: the response
     * shares the decoded stored documents it merged, so the reservation is released only once nothing
     * but the finished text is left.
     */
    suspend fun entityDocumentsBody(mappingId: UInt, request: EntityDocumentsRequest): String? {
        val basis = mappingService.exportBasis(mappingId) ?: return null
        val document = basis.document ?: throw ConflictException(
            "The conversion mapping has ${basis.mapping.findings.size} blocking finding(s) — fix the mapping before converting",
        )
        val stored = catalogFileService.readIdentities(document.pairs.mapNotNull { it.select?.spec }.toSet())
        val snapshot = entityService.importSnapshot()
        val run = ImportRun(
            ImportInput(
                mapping = document,
                blueprints = basis.blueprints,
                registry = basis.registry,
                defaultNamespace = basis.defaultNamespace,
                files = request.files,
                stored = stored,
                directory = snapshot.directory(),
                existingId = snapshot::existingId,
                merge = request.merge,
            ),
        )
        entityService.readLedger.open().use { reservation ->
            try {
                val existing = if (request.merge) entityService.readStoredEntities(run.mergeIds(), snapshot, reservation) else null
                return blueprintJson.encodeToString(run.run(existing))
            } catch (cause: ReadBudgetExceeded) {
                throwReadBudgetHttp(cause, "entity")
            }
        }
    }
}

private fun EntityImportSnapshot.directory() = PortDirectory { blueprint, identifier -> canonicalIdentifier(blueprint, identifier) }
