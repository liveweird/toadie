-- Widens the Toadie-only entity-hierarchy pointer (V29) from ONE `hierarchy_relation` column
-- to a MAP of hierarchy identifier -> relation key, so a workspace can define several PARALLEL
-- entity hierarchies (composition, deployment, cost-center...) instead of exactly one. The new
-- column is JSON-object-in-TEXT (the `catalog_files.content`/`blueprints.definition` precedent),
-- keyed by an ACTIVE value of the `hierarchies` dictionary (V33) and valued by one of the SAME
-- row's `relations` keys (`many == false`) — enforced service-side, not by a CHECK, the
-- Kotlin-enum/registry-is-the-whitelist posture every other column in this schema already uses.
--
-- Every pre-existing `hierarchy_relation` pointer is backfilled under the seeded `composition`
-- entry: that is the ONE entity hierarchy the pre-1.32 single pointer ever described (the org
-- tree via `_team`, the architecture tree via domain/system/service/...), so `_team`'s
-- `{"composition":"parent"}` is exactly what V31 seeded and `SystemBlueprintTest` already pins.
-- Soft-deleted rows are backfilled too (harmless — their whole row is inert either way, and
-- skipping them would just leave a stale column value with no observable difference).
ALTER TABLE blueprints ADD COLUMN hierarchy_relations TEXT NOT NULL DEFAULT '{}';
UPDATE blueprints SET hierarchy_relations = json_build_object('composition', hierarchy_relation)::text WHERE hierarchy_relation IS NOT NULL;
ALTER TABLE blueprints DROP COLUMN hierarchy_relation;
