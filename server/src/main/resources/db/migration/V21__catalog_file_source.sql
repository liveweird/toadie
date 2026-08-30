-- Source references & repo sync: each catalog file may point at the canonical copy of
-- itself in a GitLab/GitHub repo. The reference and sync state are ROW state — the content
-- JSON stays a pure Backstage document (export/import round-trip purity).
ALTER TABLE catalog_files
    -- The https URL of the repo copy; NULL = no reference (reported on the Errors page).
    ADD COLUMN source_url VARCHAR(2048),
    -- Epoch millis of the last repo->DB sync; 0 = never (the password_changed_at idiom).
    -- A sync stamps updated_at to the SAME value, so updated_at > last_synced_at means
    -- "modified in the DB since the last sync".
    ADD COLUMN last_synced_at BIGINT NOT NULL DEFAULT 0,
    -- The document JSON snapshot taken at sync time — the baseline that attributes later
    -- changes to a side (DB vs repo). NULL = never synced.
    ADD COLUMN synced_content TEXT;
