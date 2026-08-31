-- The ADMIN-curated tag categories: an INTERNAL Toadie concept (not in the Backstage
-- schema) — one row = one category holding >=1 tag values (descriptor tag grammar) and the
-- entity kinds its tags may be applied to (both JSON arrays in TEXT — the labels/V10
-- precedent). Catalog-file writes enforce the registry STRICTLY (unregistered tags are
-- banned everywhere, no grandfathering) — an EMPTY registry means no file may carry tags
-- (deliberate; this migration ships none — V22 later seeds the reference set). Category
-- names fold case for uniqueness over ACTIVE rows
-- only (the uq_labels_key_active pattern). Each tag belongs to exactly ONE category —
-- enforced SERVICE-side in-transaction (tags live inside the JSON array, so no index can
-- back it; accepted for a single-ADMIN-curated registry).
CREATE TABLE tag_categories (
    id                SERIAL      PRIMARY KEY,
    name              VARCHAR(63) NOT NULL,  -- internal display name (not Backstage grammar)
    allowed_kinds     TEXT        NOT NULL,  -- JSON array, canonical SUPPORTED_KINDS order
    tags              TEXT        NOT NULL,  -- JSON array of tag values (descriptor tag grammar)
    marked_as_deleted BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_tag_categories_name_active ON tag_categories(LOWER(name)) WHERE NOT marked_as_deleted;
CREATE INDEX idx_tag_categories_marked_as_deleted ON tag_categories(marked_as_deleted);
