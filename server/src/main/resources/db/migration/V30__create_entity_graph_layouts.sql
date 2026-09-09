-- Per-user Entity-graph layout (Port migration phase 3): the /entity-graph page's own layout
-- document, independent of the Backstage Graph's `graph_layouts` (V19+V24) — a byte-copy of
-- that table's columns in a SECOND table, so the two pages' saved layouts for the same user
-- never collide. Hard-delete table (the same user_disabled_features exception): a pure
-- per-user settings row whose PUT is a wholesale replace — no history worth keeping.
CREATE TABLE entity_graph_layouts (
    user_id    BIGINT      PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    mode       VARCHAR(10) NOT NULL DEFAULT 'auto',
    positions  TEXT        NOT NULL DEFAULT '{}',
    collapsed  TEXT        NOT NULL DEFAULT '[]',
    updated_at BIGINT      NOT NULL
);
