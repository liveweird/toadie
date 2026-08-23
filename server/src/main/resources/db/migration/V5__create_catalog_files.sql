-- Stored catalog-info.yaml files: one row = one Backstage entity document (Component only for
-- now — the CHECK grows with each kind the visual editor learns). The structured document
-- travels as JSON in `content` (source of truth for everything but identity); `name`/`namespace`
-- are denormalized for filtering, sorting, and the uniqueness rule. Backstage identity is
-- case-insensitively unique per kind+namespace: namespace is stored lowercase (folded at
-- validation), name keeps its case and the partial unique index folds it — active rows only,
-- so a soft-deleted file frees its identity (the uq_users_email_active pattern).
-- created_at / updated_at are epoch millis.
CREATE TABLE catalog_files (
    id SERIAL PRIMARY KEY,
    kind VARCHAR(63) NOT NULL DEFAULT 'Component' CHECK (kind IN ('Component')),
    name VARCHAR(63) NOT NULL,
    namespace VARCHAR(63) NOT NULL DEFAULT 'default',
    content TEXT NOT NULL,
    created_by INTEGER NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_catalog_files_entity_active
    ON catalog_files (kind, namespace, LOWER(name)) WHERE NOT marked_as_deleted;
CREATE INDEX idx_catalog_files_marked_as_deleted ON catalog_files(marked_as_deleted);
CREATE INDEX idx_catalog_files_created_by ON catalog_files(created_by);
