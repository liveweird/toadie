-- The ADMIN-curated annotation-key registry: one row = one allowed metadata.annotations
-- KEY with the entity kinds it may be applied to (a JSON array in TEXT — the labels/V10
-- precedent minus the value dimension: annotation VALUES stay free strings). Catalog-file
-- writes enforce the registry STRICTLY (unregistered keys are banned everywhere, no
-- grandfathering) — an EMPTY registry means no file may carry annotations until an admin
-- defines some (deliberate; there is no seed — the labels posture). Key uniqueness folds
-- case over ACTIVE rows only (the uq_labels_key_active pattern), so a soft-deleted key is
-- reusable.
CREATE TABLE annotation_keys (
    id                SERIAL       PRIMARY KEY,
    key               VARCHAR(317) NOT NULL, -- <=253 prefix + '/' + <=63 name (descriptor grammar)
    allowed_kinds     TEXT         NOT NULL, -- JSON array, canonical SUPPORTED_KINDS order
    marked_as_deleted BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_annotation_keys_key_active ON annotation_keys(LOWER(key)) WHERE NOT marked_as_deleted;
CREATE INDEX idx_annotation_keys_marked_as_deleted ON annotation_keys(marked_as_deleted);
