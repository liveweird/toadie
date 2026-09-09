-- Entities (Port migration phase 2): instances of a blueprint. Every entity belongs to one
-- blueprint (by ID, not identifier — a blueprint rename never touches entities), carries
-- `properties` typed by that blueprint's schema and `relations` naming other entities of the
-- target blueprints. Identity columns (identifier/title/icon) denormalized as columns (the
-- blueprints.identifier/title/icon precedent); `team` stored exactly as sent (a free string or
-- string array, unvalidated — teams arrive with the users/teams phase); everything else
-- (properties, relations) as one blueprintJson-encoded document in `document` (the
-- blueprints.definition/catalog_files.content precedent). Relation targets are byte-exact
-- entity identifiers scoped to their target blueprint, checked for existence and cascaded on
-- rename under the service's two-table lock protocol (see .claude/docs/persistence.md).
CREATE TABLE entities (
    id                SERIAL        PRIMARY KEY,
    blueprint_id      INTEGER       NOT NULL REFERENCES blueprints(id) ON DELETE RESTRICT,
    identifier        VARCHAR(200)  NOT NULL,
    title             VARCHAR(200)  NOT NULL,
    icon              VARCHAR(100)  NULL,
    team              TEXT          NULL,
    document          TEXT          NOT NULL,
    created_by        INTEGER       NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at        BIGINT        NOT NULL,
    updated_at        BIGINT        NOT NULL,
    marked_as_deleted BOOLEAN       NOT NULL DEFAULT FALSE
);

-- Identifier unique case-insensitively PER BLUEPRINT among active entities; a soft-deleted
-- entity frees its identifier for reuse within the same blueprint (and it was always reusable
-- across DIFFERENT blueprints, since the index is scoped by blueprint_id).
CREATE UNIQUE INDEX uq_entities_blueprint_identifier_active
    ON entities(blueprint_id, LOWER(identifier)) WHERE NOT marked_as_deleted;
CREATE INDEX idx_entities_blueprint_id ON entities(blueprint_id);
CREATE INDEX idx_entities_created_by ON entities(created_by);
CREATE INDEX idx_entities_marked_as_deleted ON entities(marked_as_deleted);
