-- The ADMIN-curated label registry: one row = one allowed metadata.labels key, with its
-- closed value list and the entity kinds it may be applied to (both JSON arrays in TEXT —
-- the catalog_files.content precedent: whole-label updates replace them atomically, no
-- child-table reconcile). Catalog-file writes enforce the registry STRICTLY (unregistered
-- keys are banned everywhere, no grandfathering) — an EMPTY registry means no file may
-- carry labels until an admin defines some (deliberate; there is no seed).
-- Key uniqueness folds case (no confusing twins) over ACTIVE rows only (the
-- uq_users_email_active pattern), so a soft-deleted label frees its key.
CREATE TABLE labels (
    id                SERIAL       PRIMARY KEY,
    key               VARCHAR(317) NOT NULL, -- <=253 prefix + '/' + <=63 name (descriptor grammar)
    allowed_kinds     TEXT         NOT NULL, -- JSON array, canonical SUPPORTED_KINDS order
    allowed_values    TEXT         NOT NULL, -- JSON array, admin's order preserved
    marked_as_deleted BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_labels_key_active ON labels(LOWER(key)) WHERE NOT marked_as_deleted;
CREATE INDEX idx_labels_marked_as_deleted ON labels(marked_as_deleted);
