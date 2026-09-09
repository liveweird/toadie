package ch.nokillswit.entities

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintService
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import io.r2dbc.spi.IsolationLevel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val EntityServiceKey = AttributeKey<EntityService>("EntityService")

data class EntityFilter(val blueprint: String?, val q: String?)

data class EntityListResult(val items: List<EntityResponse>, val total: Long)

/** [EntityService.update]'s outcome: affected-row count plus what the rename cascaded (for the audit). */
data class EntityUpdateResult(val affected: Int, val cascaded: List<String>, val renamedFrom: String?)

/** [EntityService.delete]'s outcome: affected-row count plus the deleted identity (for the audit). */
data class EntityDeleteResult(val affected: Int, val blueprint: String?, val identifier: String?)

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to EntityService.Entities.id,
    "identifier" to EntityService.Entities.identifier,
    "title" to EntityService.Entities.title,
    "updatedAt" to EntityService.Entities.updatedAt,
)

/** The ONE sortable whitelist — mirrors `catalog/CatalogFileService.kt`'s `CATALOG_FILE_SORT_FIELDS`. */
val ENTITY_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

class EntityService(private val database: R2dbcDatabase) {
    object Entities : UIntIdTable("entities") {
        // Case-folded identifier uniqueness PER BLUEPRINT is enforced by the partial unique
        // index uq_entities_blueprint_identifier_active (active rows only; V28) — a
        // soft-deleted entity frees its identifier within the same blueprint. FK to
        // blueprints.id (not the identifier), so a blueprint rename never touches entities.
        val blueprintId = reference("blueprint_id", BlueprintService.Blueprints)
        val identifier = varchar("identifier", length = MAX_ENTITY_IDENTIFIER_LENGTH)
        val title = varchar("title", length = MAX_ENTITY_TITLE_LENGTH)
        val icon = varchar("icon", length = MAX_ENTITY_ICON_LENGTH).nullable()
        // The JSON value EXACTLY as sent ("x" or ["x","y"]); NULL = absent (unvalidated —
        // teams arrive with the users/teams phase).
        val team = text("team").nullable()
        // {properties, relations} as one blueprintJson document (the blueprints.definition
        // precedent).
        val document = text("document")
        val createdBy = reference("created_by", UserService.Users)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    /**
     * One global lock order — `blueprints` before `entities` (`.claude/docs/persistence.md`):
     * SHARE on `blueprints` (self-compatible; entity writers only ever serialize against EACH
     * OTHER, on `entities`) yet conflicting with a blueprint writer's SHARE ROW EXCLUSIVE, so an
     * entity is never validated against a definition mid-change nor attached to a blueprint
     * being deleted. READ COMMITTED so a writer that waited for either lock sees the prior
     * writer's committed rows before deciding.
     */
    private suspend fun <T> writeTransaction(block: suspend R2dbcTransaction.() -> T): T =
        suspendTransaction(database, transactionIsolation = IsolationLevel.READ_COMMITTED) {
            exec("LOCK TABLE blueprints IN SHARE MODE")
            exec("LOCK TABLE entities IN SHARE ROW EXCLUSIVE MODE")
            block()
        }

    private fun active(): Op<Boolean> = Entities.markedAsDeleted eq false

    private fun activeBlueprints(): Op<Boolean> = BlueprintService.Blueprints.markedAsDeleted eq false

    // The sanctioned cross-feature table reads (persistence.md): the creator's display fields
    // and the blueprint's identifier must come from the same transaction as the entity row.
    private fun joined() = Entities
        .join(UserService.Users, JoinType.INNER, onColumn = Entities.createdBy, otherColumn = UserService.Users.id)
        .join(BlueprintService.Blueprints, JoinType.INNER, onColumn = Entities.blueprintId, otherColumn = BlueprintService.Blueprints.id)

    private data class ActiveBlueprint(val id: UInt, val identifier: String, val definition: BlueprintDefinition)

    private suspend fun loadActiveBlueprints(): List<ActiveBlueprint> =
        BlueprintService.Blueprints.selectAll().where { activeBlueprints() }
            .map {
                ActiveBlueprint(
                    it[BlueprintService.Blueprints.id].value,
                    it[BlueprintService.Blueprints.identifier],
                    blueprintJson.decodeFromString<BlueprintDefinition>(it[BlueprintService.Blueprints.definition]),
                )
            }
            .toList()

    /**
     * A closure over ONE snapshot: the active entity identifiers of every blueprint any of
     * [definitions]' relations targets — loaded once per call, not once per relation (the
     * plan's "staleness cost" section).
     */
    private suspend fun buildTargetExists(
        definitions: Collection<BlueprintDefinition>,
        blueprintsByIdentifier: Map<String, ActiveBlueprint>,
    ): (String, String) -> Boolean {
        val targetIdentifiers = definitions.flatMap { it.relations.values.map { relation -> relation.target } }.toSet()
        val targetIds = targetIdentifiers.mapNotNull { blueprintsByIdentifier[it]?.id }
        val activeByBlueprintId: Map<UInt, Set<String>> = if (targetIds.isEmpty()) {
            emptyMap()
        } else {
            Entities.selectAll().where { (Entities.blueprintId inList targetIds) and active() }
                .map { it[Entities.blueprintId].value to it[Entities.identifier] }
                .toList()
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }
        }
        val idByIdentifier = blueprintsByIdentifier.mapValues { it.value.id }
        return { targetBlueprint, entityId ->
            val id = idByIdentifier[targetBlueprint]
            id != null && entityId in activeByBlueprintId.getOrDefault(id, emptySet())
        }
    }

    private fun ResultRow.toResponse(
        blueprintsById: Map<UInt, ActiveBlueprint>,
        targetExists: (String, String) -> Boolean,
    ): EntityResponse {
        val document = blueprintJson.decodeFromString<EntityDocument>(this[Entities.document])
        val blueprintId = this[Entities.blueprintId].value
        val entityId = this[Entities.id].value
        // A blueprint can only be deleted once its active entity count is 0 (BlueprintService.
        // delete), so any active entity's blueprint is guaranteed active here.
        val blueprint = blueprintsById[blueprintId] ?: error("entity $entityId references blueprint $blueprintId, which is not active")
        return EntityResponse(
            id = this[Entities.id].value,
            blueprint = blueprint.identifier,
            blueprintId = blueprintId,
            identifier = this[Entities.identifier],
            title = this[Entities.title],
            icon = this[Entities.icon],
            team = this[Entities.team]?.let { blueprintJson.decodeFromString<JsonElement>(it) },
            properties = document.properties,
            relations = document.relations,
            findings = entityFindings(document, blueprint.definition, targetExists),
            createdBy = this[Entities.createdBy].value,
            creatorName = this[UserService.Users.name],
            creatorDeleted = this[UserService.Users.markedAsDeleted],
            createdAt = this[Entities.createdAt],
            updatedAt = this[Entities.updatedAt],
        )
    }

    /** `q` substring-matches identifier OR title; an unknown `blueprint` identifier is empty (never a 404/400). */
    suspend fun list(filter: EntityFilter, paging: PageRequest): EntityListResult = suspendTransaction(database) {
        val activeBlueprints = loadActiveBlueprints()
        val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
        val blueprintsById = activeBlueprints.associateBy { it.id }
        // The list filter is the ONE case-insensitive lookup (blueprint identifiers are unique
        // case-insensitively); every other identifier-keyed map here (targetExists) stays
        // byte-exact.
        val blueprintsByIdentifierFolded = activeBlueprints.associateBy { it.identifier.lowercase() }
        val filterBlueprint = filter.blueprint?.let { blueprintsByIdentifierFolded[it.lowercase()] }
        if (filter.blueprint != null && filterBlueprint == null) {
            return@suspendTransaction EntityListResult(emptyList(), 0)
        }
        var predicate: Op<Boolean> = active()
        filterBlueprint?.let { predicate = predicate and (Entities.blueprintId eq it.id) }
        filter.q?.let { q -> predicate = predicate and (Entities.identifier.containsNormalized(q) or Entities.title.containsNormalized(q)) }

        val total = joined().selectAll().where { predicate }.count()
        val rows = joined().selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        val definitions = rows.mapNotNull { blueprintsById[it[Entities.blueprintId].value]?.definition }
        val targetExists = buildTargetExists(definitions, blueprintsByIdentifier)
        EntityListResult(rows.map { it.toResponse(blueprintsById, targetExists) }, total)
    }

    suspend fun read(id: UInt): EntityResponse? = suspendTransaction(database) {
        val row = joined().selectAll().where { (Entities.id eq id) and active() }.singleOrNull() ?: return@suspendTransaction null
        val activeBlueprints = loadActiveBlueprints()
        val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
        val blueprintsById = activeBlueprints.associateBy { it.id }
        val definition = blueprintsById[row[Entities.blueprintId].value]?.definition
        val targetExists = buildTargetExists(listOfNotNull(definition), blueprintsByIdentifier)
        row.toResponse(blueprintsById, targetExists)
    }

    suspend fun create(request: EntityRequest, callerId: UInt): EntityResponse {
        validateEntityRequest(request) // re-checked service-side so direct callers stay guarded
        return writeTransaction {
            val activeBlueprints = loadActiveBlueprints()
            val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
            val blueprint = blueprintsByIdentifier[request.blueprint] ?: throw BadRequestException("Unknown blueprint")
            checkCaps(blueprint.id)
            val document = request.toDocument()
            val targetExists = buildTargetExists(listOf(blueprint.definition), blueprintsByIdentifier)
            val findings = entityFindings(document, blueprint.definition, targetExists)
            requireNoFindings(findings)
            val now = System.currentTimeMillis()
            val id = insertRow(request, blueprint.id, document, callerId, now)
            val row = joined().selectAll().where { Entities.id eq id }.singleOrNull()
                ?: error("entity $id vanished between insert and read-back")
            val blueprintsById = activeBlueprints.associateBy { it.id } + (blueprint.id to blueprint)
            row.toResponse(blueprintsById, targetExists)
        }
    }

    private suspend fun checkCaps(blueprintId: UInt) {
        if (Entities.selectAll().where { active() }.count() >= MAX_ENTITIES_TOTAL) {
            throw BadRequestException("The entity registry is full ($MAX_ENTITIES_TOTAL entities)")
        }
        if (Entities.selectAll().where { (Entities.blueprintId eq blueprintId) and active() }.count() >= MAX_ENTITIES_PER_BLUEPRINT) {
            throw BadRequestException("This blueprint's entities are full ($MAX_ENTITIES_PER_BLUEPRINT entities)")
        }
    }

    private fun requireNoFindings(findings: List<EntityFinding>) {
        if (findings.isNotEmpty()) {
            throw BadRequestException(findings.joinToString("; ") { "${it.field}: ${it.message}" })
        }
    }

    private suspend fun insertRow(request: EntityRequest, blueprintId: UInt, document: EntityDocument, callerId: UInt, now: Long): UInt =
        Entities.insert {
            it[Entities.blueprintId] = blueprintId
            it[identifier] = request.identifier
            it[title] = request.title
            it[icon] = request.icon
            it[team] = request.team?.let { team -> blueprintJson.encodeToString(team) }
            it[Entities.document] = blueprintJson.encodeToString(document)
            it[createdBy] = callerId
            it[createdAt] = now
            it[updatedAt] = now
        }[Entities.id].value

    /**
     * Row missing → affected 0 (the route's 404), decided BEFORE validation — the `LensService`/
     * `BlueprintService` order. `request.blueprint` must equal the stored row's blueprint
     * (400 otherwise: an entity never moves blueprints). An identifier rename cascades into
     * every OTHER active entity whose blueprint has a relation targeting THIS blueprint and
     * whose relation value names the old identifier, in this same locked transaction.
     */
    suspend fun update(id: UInt, request: EntityRequest): EntityUpdateResult = writeTransaction {
        val row = Entities.selectAll().where { (Entities.id eq id) and active() }.singleOrNull()
            ?: return@writeTransaction EntityUpdateResult(0, emptyList(), null)
        validateEntityRequest(request) // re-checked service-side so direct callers stay guarded
        val activeBlueprints = loadActiveBlueprints()
        val blueprintsByIdentifier = activeBlueprints.associateBy { it.identifier }
        val blueprintsById = activeBlueprints.associateBy { it.id }
        val currentBlueprint = blueprintsById[row[Entities.blueprintId].value]
            ?: error("entity $id references a blueprint that is not active")
        if (request.blueprint != currentBlueprint.identifier) {
            throw BadRequestException("blueprint must be '${currentBlueprint.identifier}' and cannot be changed")
        }
        val document = request.toDocument()
        val baseTargetExists = buildTargetExists(listOf(currentBlueprint.definition), blueprintsByIdentifier)
        // The SELECT backing baseTargetExists runs before THIS row's identifier rename is
        // written, so a self-blueprint relation naming the row's own NEW identifier would be
        // wrongly rejected; treat it as a synthetic self-match.
        val targetExists = { targetBlueprint: String, entityId: String ->
            (targetBlueprint == currentBlueprint.identifier && entityId == request.identifier) ||
                baseTargetExists(targetBlueprint, entityId)
        }
        requireNoFindings(entityFindings(document, currentBlueprint.definition, targetExists))

        val currentIdentifier = row[Entities.identifier]
        val renamed = currentIdentifier != request.identifier
        val cascaded = if (renamed) {
            cascadeRename(activeBlueprints, currentBlueprint.identifier, currentIdentifier, request.identifier)
        } else {
            emptyList()
        }
        Entities.update({ (Entities.id eq id) and active() }) {
            it[identifier] = request.identifier
            it[title] = request.title
            it[icon] = request.icon
            it[team] = request.team?.let { team -> blueprintJson.encodeToString(team) }
            it[Entities.document] = blueprintJson.encodeToString(document)
            it[updatedAt] = System.currentTimeMillis()
        }
        EntityUpdateResult(1, cascaded, if (renamed) currentIdentifier else null)
    }

    /**
     * Rewrites every OTHER active entity referencing [oldIdentifier] within
     * [blueprintIdentifier]; returns "blueprint/identifier" strings.
     */
    private suspend fun cascadeRename(
        activeBlueprints: List<ActiveBlueprint>,
        blueprintIdentifier: String,
        oldIdentifier: String,
        newIdentifier: String,
    ): List<String> {
        val referrerBlueprintIds = referrerBlueprintIds(activeBlueprints, blueprintIdentifier)
        if (referrerBlueprintIds.isEmpty()) return emptyList()
        val blueprintsById = activeBlueprints.associateBy { it.id }
        val now = System.currentTimeMillis()
        val cascaded = mutableListOf<String>()
        candidateEntities(referrerBlueprintIds).forEach { candidate ->
            val candidateBlueprint = blueprintsById.getValue(candidate[Entities.blueprintId].value)
            val candidateDocument = blueprintJson.decodeFromString<EntityDocument>(candidate[Entities.document])
            if (blueprintIdentifier to oldIdentifier in entityTargets(candidateDocument, candidateBlueprint.definition)) {
                val rewritten = withEntityTargetRenamed(
                    candidateDocument,
                    candidateBlueprint.definition,
                    blueprintIdentifier,
                    oldIdentifier,
                    newIdentifier,
                )
                Entities.update({ Entities.id eq candidate[Entities.id] }) {
                    it[document] = blueprintJson.encodeToString(rewritten)
                    it[updatedAt] = now
                }
                cascaded += "${candidateBlueprint.identifier}/${candidate[Entities.identifier]}"
            }
        }
        return cascaded
    }

    private fun referrerBlueprintIds(activeBlueprints: List<ActiveBlueprint>, targetIdentifier: String): List<UInt> =
        activeBlueprints
            .filter { targetIdentifier in it.definition.relations.values.map { relation -> relation.target } }
            .map { it.id }

    private suspend fun candidateEntities(blueprintIds: List<UInt>): List<ResultRow> =
        Entities.selectAll().where { (Entities.blueprintId inList blueprintIds) and active() }.toList()

    /** Referrers = other active entities naming this one through a relation targeting this blueprint; a self-reference never blocks. */
    suspend fun delete(id: UInt): EntityDeleteResult = writeTransaction {
        val row = Entities.selectAll().where { (Entities.id eq id) and active() }.singleOrNull()
            ?: return@writeTransaction EntityDeleteResult(0, null, null)
        val activeBlueprints = loadActiveBlueprints()
        val blueprintsById = activeBlueprints.associateBy { it.id }
        val blueprintId = row[Entities.blueprintId].value
        val blueprint = blueprintsById[blueprintId] ?: error("entity $id references blueprint $blueprintId, which is not active")
        val identifier = row[Entities.identifier]
        val referrers = findReferrers(id, activeBlueprints, blueprintsById, blueprint.identifier, identifier)
        if (referrers.isNotEmpty()) {
            val target = "${blueprint.identifier}/$identifier"
            throw ConflictException("Entity '$target' is the target of relations in: ${referrers.joinToString()}")
        }
        val affected = Entities.update({ (Entities.id eq id) and active() }) { it[markedAsDeleted] = true }
        EntityDeleteResult(affected, blueprint.identifier, identifier)
    }

    private suspend fun findReferrers(
        id: UInt,
        activeBlueprints: List<ActiveBlueprint>,
        blueprintsById: Map<UInt, ActiveBlueprint>,
        blueprintIdentifier: String,
        identifier: String,
    ): List<String> {
        val referrerBlueprintIds = referrerBlueprintIds(activeBlueprints, blueprintIdentifier)
        if (referrerBlueprintIds.isEmpty()) return emptyList()
        return Entities.selectAll()
            .where { (Entities.blueprintId inList referrerBlueprintIds) and active() and (Entities.id neq id) }
            .toList()
            .mapNotNull { candidate -> referrerLabel(candidate, blueprintsById, blueprintIdentifier, identifier) }
    }

    private fun referrerLabel(
        candidate: ResultRow,
        blueprintsById: Map<UInt, ActiveBlueprint>,
        blueprintIdentifier: String,
        identifier: String,
    ): String? {
        val candidateBlueprint = blueprintsById.getValue(candidate[Entities.blueprintId].value)
        val candidateDocument = blueprintJson.decodeFromString<EntityDocument>(candidate[Entities.document])
        val targets = entityTargets(candidateDocument, candidateBlueprint.definition)
        val label = "${candidateBlueprint.identifier}/${candidate[Entities.identifier]}"
        return if (blueprintIdentifier to identifier in targets) label else null
    }
}
