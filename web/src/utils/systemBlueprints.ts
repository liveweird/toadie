// Port's own system blueprints (`_team`/`_user`, Phase 4 v1.26.0) — seeded by migration and
// protected server-side (`blueprints/SystemBlueprints.kt`): no delete, no identifier rename,
// no removal/retyping of the base shape. This step (the Blueprints editor's row locks/badges)
// only needs `lockedRowIds`; the two identifiers, `OWNERSHIP_RELATION`, and
// `referenceTargetOf` are added and exported by the entity-side step that follows — keeping
// them unexported here (rather than exported-but-unconsumed) keeps knip clean in the meantime.

const TEAM_BLUEPRINT = "_team";
const USER_BLUEPRINT = "_user";

/**
 * The client mirror of the server's seeded base rows (`SYSTEM_BLUEPRINT_BASES` in
 * `blueprints/SystemBlueprints.kt`) — rules identical to the server's — keep in sync; the
 * server stays the gate.
 */
export const SYSTEM_BASE_ROWS: Record<string, { properties: readonly string[]; relations: readonly string[] }> = {
  [TEAM_BLUEPRINT]: { properties: [], relations: ["parent"] },
  [USER_BLUEPRINT]: { properties: ["email"], relations: ["team"] },
};

/**
 * The row ids of one family (`properties`/`relations`) a system blueprint's editor must lock —
 * id read-only, remove disabled. Empty for a non-system identifier.
 */
export function lockedRowIds(identifier: string, family: "properties" | "relations"): ReadonlySet<string> {
  const base = SYSTEM_BASE_ROWS[identifier];
  if (!base) return new Set();
  return new Set(base[family]);
}
