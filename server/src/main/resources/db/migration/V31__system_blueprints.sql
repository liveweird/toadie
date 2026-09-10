-- System blueprints (Phase 4 of the Port data-model move, v1.26.0 — see
-- .claude/docs/port-data-model.md): `_team` and `_user` are Port's own system blueprints,
-- protected against deletion and against removal of their base shape (see
-- blueprints/SystemBlueprints.kt). This is the ONE documented exception to "no migration
-- seeds blueprints/entities" (CLAUDE.md, sample-data/ section): these two rows are SCHEMA,
-- the same posture as V22's registry seeds, not sample content.
--
-- `is_system` marks a row a system blueprint; BlueprintService enforces the protections
-- (no delete, no rename, no removal of a base property/relation) service-side — nothing here
-- backs that with a CHECK, the same posture as every other Kotlin-enum-is-the-whitelist column
-- in this schema.
ALTER TABLE blueprints ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;

-- `definition` is written in blueprintJson's canonical encodeDefaults form (schema, relations,
-- mirrorProperties, calculationProperties, aggregationProperties, in that field order; unset
-- optionals — description/icon/ownership — ABSENT, never null) — byte-identical to what
-- BlueprintService.insertRow would produce for the same BlueprintRequest, pinned against
-- blueprints/SystemBlueprints.kt's SYSTEM_BLUEPRINT_BASES by SystemBlueprintTest.
--
-- `created_by` is the first users row ever created (the V3 seed admin; users never
-- hard-delete, so MIN(id) is always resolvable). `ON CONFLICT ... DO UPDATE SET is_system =
-- TRUE` adopts a pre-existing user-made `_team`/`_user` row (flags it system without touching
-- its definition) rather than failing the migration — the same idempotent-upsert idiom as V22,
-- targeting the V27 partial unique index over active rows by identifier.
INSERT INTO blueprints (identifier, title, definition, hierarchy_relation, is_system, created_by, created_at, updated_at)
VALUES (
    '_team',
    'Team',
    '{"schema":{"properties":{},"required":[]},"relations":{"parent":{"title":"Parent team","target":"_team","required":false,"many":false}},"mirrorProperties":{},"calculationProperties":{},"aggregationProperties":{}}',
    'parent',
    TRUE,
    (SELECT MIN(id) FROM users),
    (extract(epoch from now()) * 1000)::bigint,
    (extract(epoch from now()) * 1000)::bigint
)
ON CONFLICT (LOWER(identifier)) WHERE NOT marked_as_deleted DO UPDATE SET is_system = TRUE;

INSERT INTO blueprints (identifier, title, definition, is_system, created_by, created_at, updated_at)
VALUES (
    '_user',
    'User',
    '{"schema":{"properties":{"email":{"type":"string","title":"Email","format":"email"}},"required":["email"]},"relations":{"team":{"title":"Team","target":"_team","required":false,"many":true}},"mirrorProperties":{},"calculationProperties":{},"aggregationProperties":{}}',
    TRUE,
    (SELECT MIN(id) FROM users),
    (extract(epoch from now()) * 1000)::bigint,
    (extract(epoch from now()) * 1000)::bigint
)
ON CONFLICT (LOWER(identifier)) WHERE NOT marked_as_deleted DO UPDATE SET is_system = TRUE;
