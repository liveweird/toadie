-- Fill-in tiers (2.18.0): a Toadie-only 1-4 priority hint stored BESIDE the Port document, the
-- `hierarchy_relations` (V34) idiom — never inside `definition`, so the stored Port document (and
-- any Port export of it) stays byte-identical. One JSON object in TEXT:
--   {"blueprint": 1, "properties": {"<schema property key>": 2}, "relations": {"<relation key>": 3}}
-- Every member is optional and "{}" means "no tiers"; the 1-4 range and the key-existence rules
-- (property keys name schema.properties, relation keys name relations) are enforced
-- service-side by the request validation, not by a CHECK — the registry-is-the-whitelist
-- posture of every other column in this schema. Tiers are a visual hint of what to fill first
-- and change no validation, finding or computed value. Additive: no existing row changes.
ALTER TABLE blueprints ADD COLUMN tiers TEXT NOT NULL DEFAULT '{}';
