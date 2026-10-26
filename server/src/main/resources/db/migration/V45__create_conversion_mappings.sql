-- Conversion mappings (Backstage <-> Port conversion, phase 1): the stored DATA that says how a
-- Port blueprint's entities correspond to a Backstage kind's catalog files — an ADMIN-curated
-- registry, readable by everyone, the labels/blueprints registry shape. One row = one named
-- mapping document (schemaVersion 1, `conversion/MappingDocument.kt`), kept in `document`
-- (the blueprints.definition / catalog_files.content precedent: replaced whole on every save, no
-- child tables). The column holds the document RE-SERIALISED as compact JSON, not the bytes the
-- admin sent: whitespace is dropped, string escapes are normalised and, because the JSON reader
-- keeps the LAST of a duplicated key, a repeated key collapses to its final value. From the first
-- save on it round-trips byte-identically (GET, then PUT of the same document, stores the same
-- text). A document is validated against the CURRENT blueprints and registries when written
-- (blocking findings -> 400) and re-checked on every read; a stored row may therefore carry
-- findings after a later blueprint or registry edit — it is never rewritten behind the admin's back.
--
-- The caps (at most 20 active rows, document at most 256 KiB, name at most 100 characters) are
-- enforced by the service, not by CHECK constraints. No row is seeded: the default mapping for
-- the commerce-payments sample is loaded from
-- sample-data/port/commerce-payments/mapping/backstage.json, never by a migration.
CREATE TABLE conversion_mappings (
    id                SERIAL        PRIMARY KEY,
    name              VARCHAR(100)  NOT NULL,
    document          TEXT          NOT NULL,
    created_by        INTEGER       NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at        BIGINT        NOT NULL,
    updated_at        BIGINT        NOT NULL,
    marked_as_deleted BOOLEAN       NOT NULL DEFAULT FALSE
);

-- Name unique case-insensitively among ACTIVE mappings; a soft-deleted mapping frees its name.
CREATE UNIQUE INDEX uq_conversion_mappings_name_active ON conversion_mappings(LOWER(name)) WHERE NOT marked_as_deleted;
CREATE INDEX idx_conversion_mappings_created_by ON conversion_mappings(created_by);
CREATE INDEX idx_conversion_mappings_marked_as_deleted ON conversion_mappings(marked_as_deleted);
