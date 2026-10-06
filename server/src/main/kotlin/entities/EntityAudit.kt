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
