-- Saved entity queries (phase 7, v2.1.0): named, saveable entity-query texts for the Entity
-- graph / Entity hierarchy query bar — the V20 lenses shape verbatim with the nine filter
-- slots replaced by ONE query text. Owned by their creator with a PRIVATE/PUBLIC visibility:
-- PRIVATE queries are visible only to their creator; PUBLIC queries are visible to every
-- authenticated user but stay creator-only mutable.
CREATE TABLE entity_queries (
    id                SERIAL        PRIMARY KEY,
    name              VARCHAR(100)  NOT NULL,
    -- PRIVATE | PUBLIC; no CHECK — the Kotlin SavedEntityQueryVisibility enum is the
    -- whitelist (the V12/V18/V20 idiom).
    visibility        VARCHAR(10)   NOT NULL,
    -- The query text as typed (multi-line allowed; <= 2000 characters, MAX_QUERY_LENGTH —
    -- enforced by the service, not a CHECK). Syntax-valid at save time; schema validity is
    -- NOT required (blueprints change — a stale query shows its diagnostics when applied).
    query             TEXT          NOT NULL,
    created_by        INTEGER       NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at        BIGINT        NOT NULL,
    updated_at        BIGINT        NOT NULL,
    marked_as_deleted BOOLEAN       NOT NULL DEFAULT FALSE
);

-- Name unique PER OWNER among active rows (case-insensitive); a soft-deleted row frees its
-- name. Public queries from different creators may share a name — the UI disambiguates
-- with the creator's name.
CREATE UNIQUE INDEX uq_entity_queries_owner_name_active ON entity_queries(created_by, LOWER(name)) WHERE NOT marked_as_deleted;
CREATE INDEX idx_entity_queries_created_by ON entity_queries(created_by);
CREATE INDEX idx_entity_queries_marked_as_deleted ON entity_queries(marked_as_deleted);
