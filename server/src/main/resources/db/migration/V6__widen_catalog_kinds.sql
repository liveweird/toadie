-- The editor grows from Component-only to the six further landscape kinds (API, System,
-- Domain, Resource, Group, User) — widen the kind CHECK accordingly. Location and Template
-- stay out deliberately (a pointer mechanism and scaffolder config, not landscape content).
-- The identity uniqueness index (uq_catalog_files_entity_active) already spans kind.
ALTER TABLE catalog_files DROP CONSTRAINT catalog_files_kind_check;
ALTER TABLE catalog_files ADD CONSTRAINT catalog_files_kind_check
    CHECK (kind IN ('Component', 'API', 'System', 'Domain', 'Resource', 'Group', 'User'));
