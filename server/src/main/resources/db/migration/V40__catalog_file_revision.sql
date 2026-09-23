-- Monotonic token for optional optimistic concurrency on catalog file mutations.
-- Existing and newly inserted rows begin at revision 1; every state-changing update advances it.
ALTER TABLE catalog_files
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_catalog_files_revision_positive CHECK (revision > 0);
