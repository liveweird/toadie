// Port's own system blueprints (`_team`/`_user`, Phase 4 v1.26.0) — seeded by migration and
// protected server-side (`blueprints/SystemBlueprints.kt`): no delete, no identifier rename,
// no removal/retyping of the base shape.

/** The `_team` system blueprint's identifier — every entity's `team`/`format: team` values
 *  must resolve to one of its active rows (`entities/EntityOwnership.kt`). */
export const TEAM_BLUEPRINT = "_team";
/** The `_user` system blueprint's identifier — every `format: user` property value must
 *  resolve to one of its active rows. */
export const USER_BLUEPRINT = "_user";

/** The Port relation id carrying entity ownership on the wire — never a declared blueprint
 *  relation (`entities/EntityOwnership.kt`'s `OWNERSHIP_RELATION_ID`); `$` sits outside every
 *  identifier charset, so it can never collide with a real relation. Used only to recognize
 *  and style ownership edges on the Entity graph. */
export const OWNERSHIP_RELATION = "$team";

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

/**
 * `format: team` / `format: user` -> the system blueprint that format's values must resolve
 * to (`EntityValidation.kt`'s `checkStringFormat` rule) — `undefined` for every other format,
 * including no format at all.
 */
export function referenceTargetOf(format: string | undefined): string | undefined {
  if (format === "team") return TEAM_BLUEPRINT;
  if (format === "user") return USER_BLUEPRINT;
  return undefined;
}
