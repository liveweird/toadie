package ch.nokillswit.entities

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.importing.ImportMutation
import ch.nokillswit.infra.importing.ImportMutationKind

/**
 * `entity.created`/`entity.updated`, `import: true` — emitted from the committed pass-1
 * mutation. Extracted from `EntityRoutes.kt` (MCP groundwork, 2.15.0) so the future MCP
 * entity-import endpoint can reuse it unchanged, passing `"clientId" to <id>` as [extra] instead
 * of duplicating this per-mutation-kind audit shape.
 */
internal fun auditImportedEntityMutation(callerId: UInt, mutation: ImportMutation, vararg extra: Pair<String, Any?>) {
    when (mutation.kind) {
        ImportMutationKind.CREATED -> audit(
            "entity.created",
            "byUserId" to callerId.toLong(),
            "entityId" to mutation.id.toLong(),
            "blueprint" to mutation.blueprint,
            "identifier" to mutation.identifier,
            "import" to true,
            *extra,
        )
        ImportMutationKind.UPDATED -> audit(
            "entity.updated",
            "byUserId" to callerId.toLong(),
            "entityId" to mutation.id.toLong(),
            "blueprint" to mutation.blueprint,
            "identifier" to mutation.identifier,
            "import" to true,
            *extra,
        )
    }
}

/**
 * `entity.deleted` — the one field set shared by `DELETE /api/v1/entities/{id}` and the MCP
 * `delete_entity` tool (which appends `"clientId" to <id>` as [extra]). Call it only after the
 * delete's [EntityDeleteResult.affected] was verified, exactly as both callers do.
 */
internal fun auditEntityDeleted(callerId: UInt, entityId: UInt, result: EntityDeleteResult, vararg extra: Pair<String, Any?>) {
    audit(
        "entity.deleted",
        "byUserId" to callerId.toLong(),
        "entityId" to entityId.toLong(),
        "blueprint" to result.blueprint,
        "identifier" to result.identifier,
        *extra,
    )
}

/**
 * `entity.updated`, `bulk: true` — one per row the entity bulk change committed (`entities/EntityBulkService.kt`), emitted from the
 * per-row `onCommitted` callback. Carries the run's `bulkId` and the `field` path, NEVER the value (the audit rule: no document
 * contents). The dry-run (`/check`) and the rows that were not written audit nothing.
 */
internal fun auditBulkChangedEntity(callerId: UInt, commit: EntityBulkCommit) {
    audit(
        "entity.updated",
        "byUserId" to callerId.toLong(),
        "entityId" to commit.id.toLong(),
        "blueprint" to commit.blueprint,
        "identifier" to commit.identifier,
        "bulk" to true,
        "bulkId" to commit.bulkId,
        "field" to commit.field,
    )
}
