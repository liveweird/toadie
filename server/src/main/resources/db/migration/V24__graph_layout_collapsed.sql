-- The Graph page's collapsed nodes join the per-user layout document (V19): the node ids the
-- user folded, as ONE JSON array in TEXT — the `positions` column's own idiom, keyed by the
-- same `kind:namespace/name` ids. Like positions, the list is replaced wholesale on every
-- save and never pruned server-side (an id of a node outside the user's current filter simply
-- waits). Existing rows fold nothing.
ALTER TABLE graph_layouts ADD COLUMN collapsed TEXT NOT NULL DEFAULT '[]';
