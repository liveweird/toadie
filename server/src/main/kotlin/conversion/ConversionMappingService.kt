package ch.nokillswit.conversion

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintService
import ch.nokillswit.catalog.CatalogRegistryReader
import ch.nokillswit.catalog.DEFAULT_NAMESPACE
import ch.nokillswit.catalog.RegistrySnapshot
import ch.nokillswit.infra.db.lockingTransaction
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val ConversionMappingServiceKey = AttributeKey<ConversionMappingService>("ConversionMappingService")

/** What [ConversionMappingService.exportBasis] hands the export; [document] is null exactly when [mapping] has blocking findings. */
internal class ExportBasis(
    val mapping: ConversionMapping,
    val document: MappingDocument?,
    val blueprints: Map<String, BlueprintDefinition>,
    val registry: RegistrySnapshot,
    val defaultNamespace: String,
)

/**
 * The stored conversion-mapping registry (V45). ADMIN-curated, readable by everyone, capped at
 * [MAX_CONVERSION_MAPPINGS] rows of at most [MAX_CONVERSION_MAPPING_DOCUMENT_BYTES] each — a
 * registry capped at <= 200 rows, so its reads (and the blueprint/registry snapshots the
 * validator needs) are outside the whole-workspace read ledger's scope
 * (`.claude/docs/security.md`).
 *
 * **Validation reads happen INSIDE the write's own transaction, with no lock**: the blueprint
 * rows (`BlueprintService.activeRows`) and the registry snapshot
 * (`CatalogRegistryReader.loadSnapshot`) are read plain, READ COMMITTED. A mapping write does not
 * serialize against blueprint or registry writers on purpose — the mapping stores references BY
 * NAME that the validator re-checks on every read (`findings`/`drift` are recomputed), so a
 * concurrent edit simply shows up as a finding afterwards, exactly like a stale entity. The one
 * lock is the table-level `SHARE ROW EXCLUSIVE` on `conversion_mappings` taken by `create` for
 * the cap check (the `labels` precedent, `.claude/docs/persistence.md`).
 */
class ConversionMappingService(
    private val database: R2dbcDatabase,
    private val blueprintService: BlueprintService,
) {
    object ConversionMappings : UIntIdTable("conversion_mappings") {
        // Case-folded name uniqueness is enforced by the partial unique index
        // uq_conversion_mappings_name_active (active rows only; V45), so a soft-deleted mapping
        // frees its name. Exposed table defs are query-only (not DDL).
        val name = varchar("name", length = MAX_CONVERSION_MAPPING_NAME_LENGTH)
        val document = text("document")
        val createdBy = reference("created_by", UserService.Users)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private val registryReader = CatalogRegistryReader(Json)

    private fun active(): Op<Boolean> = ConversionMappings.markedAsDeleted eq false

    // The sanctioned cross-feature table read (persistence.md): the creator's display fields come
    // from the same transaction as the mapping rows.
    private fun joined() = ConversionMappings.join(
        UserService.Users,
        JoinType.INNER,
        onColumn = ConversionMappings.createdBy,
        otherColumn = UserService.Users.id,
    )

    /** What documents are validated against, read inside the calling transaction. */
    private class ValidationSnapshot(val blueprints: Map<String, BlueprintDefinition>, val registry: RegistrySnapshot)

    private suspend fun loadSnapshot(): ValidationSnapshot = ValidationSnapshot(
        blueprintService.activeRows().associate { it.identifier to it.definition },
        registryReader.loadSnapshot(),
    )

    private fun ResultRow.toResponse(snapshot: ValidationSnapshot): ConversionMapping {
        val document = Json.parseToJsonElement(this[ConversionMappings.document])
        val verdict = validateMapping(document, snapshot.blueprints, snapshot.registry)
        return ConversionMapping(
            id = this[ConversionMappings.id].value,
            name = this[ConversionMappings.name],
            document = document as JsonObject,
            findings = verdict.findings,
            drift = verdict.drift,
            createdBy = this[ConversionMappings.createdBy].value,
            creatorName = this[UserService.Users.name],
            creatorDeleted = this[UserService.Users.markedAsDeleted],
            createdAt = this[ConversionMappings.createdAt],
            updatedAt = this[ConversionMappings.updatedAt],
        )
    }

    /** Every active mapping, name-ordered case-insensitively (id as tiebreaker), findings and drift recomputed. */
    suspend fun list(): List<ConversionMapping> = suspendTransaction(database) {
        val snapshot = loadSnapshot()
        joined().selectAll()
            .where { active() }
            .orderBy(ConversionMappings.name.lowerCase() to SortOrder.ASC, ConversionMappings.id to SortOrder.ASC)
            .map { it.toResponse(snapshot) }
            .toList()
    }

    suspend fun read(id: UInt): ConversionMapping? = suspendTransaction(database) {
        readIn(id)
    }

    private suspend fun readIn(id: UInt): ConversionMapping? {
        val snapshot = loadSnapshot()
        return joined().selectAll()
            .where { (ConversionMappings.id eq id) and active() }
            .map { it.toResponse(snapshot) }
            .singleOrNull()
    }

    /**
     * Everything the Port -> Backstage export needs about one mapping, read in ONE plain transaction
     * (the validation reads of [read], no lock): the mapping with its recomputed findings, the typed
     * document (null while the mapping has blocking findings — the export refuses those with a `409`),
     * the active blueprints, one registry snapshot and the workspace's default namespace.
     */
    internal suspend fun exportBasis(id: UInt): ExportBasis? = suspendTransaction(database) {
        val snapshot = loadSnapshot()
        val mapping = joined().selectAll()
            .where { (ConversionMappings.id eq id) and active() }
            .map { it.toResponse(snapshot) }
            .singleOrNull()
            ?: return@suspendTransaction null
        ExportBasis(
            mapping = mapping,
            document = if (mapping.findings.isEmpty()) parseMappingDocument(mapping.document).document else null,
            blueprints = snapshot.blueprints,
            registry = snapshot.registry,
            defaultNamespace = registryReader.flaggedDefaultNamespace() ?: DEFAULT_NAMESPACE,
        )
    }

    /** The stored text of a validated document — compact JSON, never rewritten, no `null` members (the parser refuses them). */
    private fun storedText(document: JsonElement): String {
        val text = document.toString()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_CONVERSION_MAPPING_DOCUMENT_BYTES) {
            throw BadRequestException("document must be at most ${MAX_CONVERSION_MAPPING_DOCUMENT_BYTES / 1024} KiB")
        }
        return text
    }

    /** Throws the findings-bearing 400 when [document] has blocking findings; otherwise returns its stored text. */
    private suspend fun validated(request: ConversionMappingRequest): String {
        validateConversionMappingName(request.name)
        val text = storedText(request.document)
        val snapshot = loadSnapshot()
        val verdict = validateMapping(request.document, snapshot.blueprints, snapshot.registry)
        if (verdict.findings.isNotEmpty()) throw ConversionMappingInvalidException(verdict.findings)
        return text
    }

    suspend fun create(request: ConversionMappingRequest, callerId: UInt): ConversionMapping = lockingTransaction(
        database,
        "LOCK TABLE conversion_mappings IN SHARE ROW EXCLUSIVE MODE",
    ) {
        // Re-checked service-side so direct callers stay guarded.
        val text = validated(request)
        if (ConversionMappings.selectAll().where { active() }.count() >= MAX_CONVERSION_MAPPINGS) {
            throw BadRequestException("The conversion mapping registry is full ($MAX_CONVERSION_MAPPINGS mappings)")
        }
        val now = System.currentTimeMillis()
        val id = ConversionMappings.insert {
            it[name] = request.name
            it[document] = text
            it[createdBy] = callerId
            it[createdAt] = now
            it[updatedAt] = now
        }[ConversionMappings.id].value
        readIn(id) ?: error("conversion mapping $id vanished between insert and read-back")
    }

    /**
     * Whole-row replace. The existence check runs FIRST — an unknown or deleted id is `null` (the
     * route's 404) before the name is sanitized or the document judged, so 404 always beats 400
     * (the blueprint PUT rule; sanitizing could itself 400, so it happens here, after the check).
     * Returns the stored result so the route can audit the values actually written.
     */
    suspend fun update(id: UInt, request: ConversionMappingRequest): ConversionMapping? = suspendTransaction(database) {
        val exists = ConversionMappings.selectAll().where { (ConversionMappings.id eq id) and active() }.count() > 0
        if (!exists) return@suspendTransaction null
        val sanitized = sanitizedConversionMappingRequest(request)
        val text = validated(sanitized)
        ConversionMappings.update({ (ConversionMappings.id eq id) and active() }) {
            it[name] = sanitized.name
            it[document] = text
            it[updatedAt] = System.currentTimeMillis()
        }
        readIn(id)
    }

    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        ConversionMappings.update({ (ConversionMappings.id eq id) and active() }) {
            it[markedAsDeleted] = true
        }
    }
}
