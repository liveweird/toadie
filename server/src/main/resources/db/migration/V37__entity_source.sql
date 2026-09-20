-- Entity source references & HTTP re-sync (2.9.0) — the V21 `catalog_files` envelope, ported
-- one level down onto `entities`: an entity may point at the canonical copy of itself served
-- over HTTP (a catalog-info.yaml or a Port entity JSON document). The reference and sync state
-- are Toadie-only ROW state, stored beside `document` — never inside it, so the stored Port
-- document (and any future Port export) stays byte-identical.
ALTER TABLE entities
    -- The https URL of the entity's canonical remote copy; NULL = no reference.
    ADD COLUMN source_url VARCHAR(2048),
    -- Epoch millis of the last HTTP->DB sync; 0 = never (the catalog_files.last_synced_at
    -- idiom). A sync stamps updated_at to the SAME value, so updated_at > last_synced_at means
    -- "modified in the DB since the last sync".
    ADD COLUMN last_synced_at BIGINT NOT NULL DEFAULT 0,
    -- The request-shaped document JSON snapshot taken at sync time — the baseline the sync
    -- modal compares the current DB document and the freshly fetched remote copy against.
    -- NULL = never synced.
    ADD COLUMN synced_content TEXT;
