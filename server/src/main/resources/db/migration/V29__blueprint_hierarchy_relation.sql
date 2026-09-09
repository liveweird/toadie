-- Port migration phase 3: the admin-marked "hierarchy relation" (.claude/docs/port-data-model.md)
-- is a Toadie-only view extension stored BESIDE the Port document (`definition`), never inside
-- it — so `toDefinition()` stays untouched and a future Port export strips nothing. NULL = no
-- hierarchy (the blueprint is a root). Must name a key of the SAME row's `relations` map whose
-- `many` is false; enforced by `validateBlueprintRequest`, not a database constraint (the
-- relations map lives in the JSON `definition` column, unreachable from SQL).
ALTER TABLE blueprints ADD COLUMN hierarchy_relation VARCHAR(100) NULL;
