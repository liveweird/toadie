-- Immutable audit trail of entity changes — the user-facing History of one Port entity, the
-- twin of catalog_file_events (V23) one level up the ontology (the security trail stays the
-- AUDIT-marked structured logs; these rows are the product feature). Rows are minted as a
-- side-effect of every write to `entities` — create, update, the HTTP sync, the rename
-- cascade's rewrite of a referrer, the import loop (including its pass-two restoration) and
-- delete — inside the SAME transaction as the write; there is no update or delete of an event.
--
-- Events are stored STRUCTURALLY (event_type + a JSON params map) so the SPA renders each one
-- in the viewer's language — no rendered string is ever stored. The params of an UPDATED /
-- SYNCED event carry the field-level diff: `changed` (a comma-joined field-path list) plus the
-- per-field `<path>.from`/`.to`/`.added`/`.removed` companions. Properties whose blueprint
-- format is yaml / markdown / proto are free text and never ride along — they are recorded as
-- the bare fact that they changed.
--
-- A hard-delete table (the catalog_file_events / revoked_tokens class): the CASCADE below is
-- vestigial, since entities SOFT-delete, so an entity's events outlive it (a DELETED event
-- lands in a history the UI can no longer reach — kept for the record). There is no retention
-- or pruning: the history is immutable and grows with use.
CREATE TABLE entity_events (
    id              SERIAL      PRIMARY KEY,
    entity_id       INTEGER     NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
    -- ON DELETE RESTRICT: an actor can never be hard-removed out from under the history
    -- (users soft-delete anyway).
    user_id         INTEGER     NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at      BIGINT      NOT NULL,
    -- No CHECK — the Kotlin EntityEventType enum is the whitelist (the V12/V18/V20/V23 idiom).
    event_type      VARCHAR(40) NOT NULL,
    -- JSON object of string params, e.g. {"changed":"title","title.from":"A","title.to":"B"};
    -- "{}" when the event kind needs none.
    params          TEXT        NOT NULL
);

CREATE INDEX idx_entity_events_entity ON entity_events(entity_id);
