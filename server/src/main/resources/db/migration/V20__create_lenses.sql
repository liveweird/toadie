-- Lenses: named, saveable catalog-filter sets (the shared Hierarchy/Files/Graph/Errors
-- filter values as one JSON object), owned by their creator with a PRIVATE/PUBLIC
-- visibility. PRIVATE lenses are visible only to their creator; PUBLIC lenses are visible
-- to every authenticated user but stay creator-only mutable.
CREATE TABLE lenses (
    id                SERIAL       PRIMARY KEY,
    name              VARCHAR(100) NOT NULL,
    -- PRIVATE | PUBLIC; no CHECK — the Kotlin LensVisibility enum is the whitelist
    -- (the V12/V18 idiom).
    visibility        VARCHAR(10)  NOT NULL,
    -- The nine shared filter slots as one JSON object (the catalog_files.content precedent).
    filters           TEXT         NOT NULL,
    created_by        INTEGER      NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at        BIGINT       NOT NULL,
    updated_at        BIGINT       NOT NULL,
    marked_as_deleted BOOLEAN      NOT NULL DEFAULT FALSE
);

-- Name unique PER OWNER among active lenses (case-insensitive); a soft-deleted lens frees
-- its name. Public lenses from different creators may share a name — the UI disambiguates
-- with the creator's name.
CREATE UNIQUE INDEX uq_lenses_owner_name_active ON lenses(created_by, LOWER(name)) WHERE NOT marked_as_deleted;
CREATE INDEX idx_lenses_created_by ON lenses(created_by);
CREATE INDEX idx_lenses_marked_as_deleted ON lenses(marked_as_deleted);
