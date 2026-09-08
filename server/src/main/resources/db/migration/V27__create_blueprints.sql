-- Blueprints (Port compatibility, phase 1): user-definable entity-kind definitions per
-- Port.io's ontology (docs.port.io/context-lake/data-model) — identifier/title/description/
-- icon denormalized as columns (the catalog_files.kind/name/namespace precedent), everything
-- else (schema, relations, mirrorProperties, calculationProperties, aggregationProperties,
-- ownership) as one JSON document in `definition` (the catalog_files.content/lenses.filters
-- precedent). Relation/aggregation targets are byte-exact blueprint identifiers, checked for
-- existence and cascaded on rename under the service's table lock (see
-- .claude/docs/persistence.md, "cooperating writer protocol").
CREATE TABLE blueprints (
    id                SERIAL        PRIMARY KEY,
    identifier        VARCHAR(100)  NOT NULL,
    title             VARCHAR(100)  NOT NULL,
    description       VARCHAR(2000) NULL,
    icon              VARCHAR(100)  NULL,
    definition        TEXT          NOT NULL,
    created_by        INTEGER       NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at        BIGINT        NOT NULL,
    updated_at        BIGINT        NOT NULL,
    marked_as_deleted BOOLEAN       NOT NULL DEFAULT FALSE
);

-- Identifier unique case-insensitively among active blueprints; a soft-deleted blueprint
-- frees its identifier for reuse by a NEW blueprint.
CREATE UNIQUE INDEX uq_blueprints_identifier_active ON blueprints(LOWER(identifier)) WHERE NOT marked_as_deleted;
CREATE INDEX idx_blueprints_created_by ON blueprints(created_by);
CREATE INDEX idx_blueprints_marked_as_deleted ON blueprints(marked_as_deleted);
