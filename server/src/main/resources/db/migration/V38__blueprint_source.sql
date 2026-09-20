-- Blueprint source references & HTTP re-sync (2.10.0) — the V37 `entities` envelope, ported one
-- level up onto `blueprints`: a blueprint may point at the canonical copy of itself served over
-- HTTP (a Port blueprint export or a Toadie export). The reference and sync state are
-- Toadie-only ROW state, stored beside `definition` — never inside it, so the stored Port
-- document (and any future Port export) stays byte-identical.
ALTER TABLE blueprints
    -- The https URL of the blueprint's canonical remote copy; NULL = no reference.
    ADD COLUMN source_url VARCHAR(2048),
    -- Epoch millis of the last HTTP->DB sync; 0 = never (the entities.last_synced_at idiom). A
    -- sync stamps updated_at to the SAME value, so updated_at > last_synced_at means "modified
    -- in the DB since the last sync".
    ADD COLUMN last_synced_at BIGINT NOT NULL DEFAULT 0,
    -- The request-shaped document JSON snapshot taken at sync time (including the merged
    -- hierarchyRelations) — the baseline the sync modal compares the current DB document and
    -- the freshly fetched remote copy against. NULL = never synced.
    ADD COLUMN synced_content TEXT;
