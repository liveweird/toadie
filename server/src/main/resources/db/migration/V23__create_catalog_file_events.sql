-- Immutable audit trail of catalog-file changes — the user-facing History of one document
-- (the security trail stays the AUDIT-marked structured logs; these rows are the product
-- feature). Rows are minted as a side-effect of create/update/sync/delete and the import loop
-- (there is no create endpoint); there is no update or delete.
--
-- Events are stored STRUCTURALLY (event_type + a JSON params map) so the SPA renders each one
-- in the viewer's language — no rendered string is ever stored. The params of an UPDATED /
-- SYNCED event carry the field-level diff: `changed` (a comma-joined field-path list) plus the
-- per-field `<path>.from`/`.to`/`.added`/`.removed` companions. Free text never rides along —
-- a description or an API definition is recorded as the bare fact that it changed.
--
-- A hard-delete table (the revoked_tokens / user_disabled_features / graph_layouts class): the
-- CASCADE below is vestigial, since catalog_files SOFT-deletes, so a file's events outlive it
-- (a DELETED event lands in a history the UI can no longer reach — kept for the record).
CREATE TABLE catalog_file_events (
    id              SERIAL      PRIMARY KEY,
    catalog_file_id INTEGER     NOT NULL REFERENCES catalog_files(id) ON DELETE CASCADE,
    -- ON DELETE RESTRICT: an actor can never be hard-removed out from under the history
    -- (users soft-delete anyway).
    user_id         INTEGER     NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at      BIGINT      NOT NULL,
    -- No CHECK — the Kotlin CatalogFileEventType enum is the whitelist (the V12/V18/V20 idiom).
    event_type      VARCHAR(40) NOT NULL,
    -- JSON object of string params, e.g. {"changed":"spec.owner","spec.owner.from":"group:default/a",
    -- "spec.owner.to":"group:default/b"}; "{}" when the event kind needs none.
    params          TEXT        NOT NULL
);

CREATE INDEX idx_catalog_file_events_file ON catalog_file_events(catalog_file_id);
