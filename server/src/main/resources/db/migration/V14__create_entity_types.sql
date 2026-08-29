-- The ADMIN-curated per-kind type dictionaries: an INTERNAL Toadie constraint on the open
-- `spec.type` field (Backstage leaves it a free string) — one row = ONE kind's list of
-- allowed types (JSON array in TEXT — the labels/V10 precedent). The dictionaries are
-- INDEPENDENT: the same value may be allowed for several kinds (no cross-row uniqueness).
-- Catalog-file writes enforce the registry STRICTLY (an unregistered spec.type is banned,
-- no grandfathering) — a kind with NO active row allows NO types, which for required-type
-- kinds means no file of that kind can be saved until an admin defines its list (V15 seeds
-- the well-known values so a fresh database works out of the box). Kind uniqueness over
-- ACTIVE rows only (the uq_labels_key_active pattern; kinds are stored canonical, so the
-- index needs no LOWER()).
CREATE TABLE entity_types (
    id                SERIAL      PRIMARY KEY,
    kind              VARCHAR(63) NOT NULL,  -- canonical kind casing (the Kotlin whitelist, no CHECK)
    types             TEXT        NOT NULL,  -- JSON array of type values (spec.type grammar)
    marked_as_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_entity_types_kind_active ON entity_types(kind) WHERE NOT marked_as_deleted;
CREATE INDEX idx_entity_types_marked_as_deleted ON entity_types(marked_as_deleted);
