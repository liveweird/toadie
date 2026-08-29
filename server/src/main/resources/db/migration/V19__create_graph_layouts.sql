-- Per-user Graph-page layout (one row per user): the layout mode (auto|manual — no CHECK,
-- the application whitelist rules, the V12/V18 idiom) plus the manually dragged node
-- positions as one JSON object in TEXT keyed by node id `kind:namespace/name` (the
-- catalog_files.content precedent — no child table). Hard-delete table (the
-- user_disabled_features exception): a pure per-user settings row whose PUT is a wholesale
-- replace — no history worth keeping; rows follow their user via ON DELETE CASCADE.
CREATE TABLE graph_layouts (
    user_id    BIGINT      PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    mode       VARCHAR(10) NOT NULL DEFAULT 'auto',
    positions  TEXT        NOT NULL DEFAULT '{}',
    updated_at BIGINT      NOT NULL
);
