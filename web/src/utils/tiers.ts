import type { Blueprint } from "../api/blueprints";

/**
 * Fill-in tiers (2.18.0) — a Toadie-only, purely VISUAL priority hint (1 = fill first … 4)
 * stored beside a blueprint's Port document at three levels: the blueprint, each schema
 * property, each relation. Nothing behavioural hangs off them: they change no validation,
 * finding or computed value. Untiered (`null`) means "no tier", and an active Focus excludes
 * untiered items. The server twin of `fillTier`/`isFilled*` is `entityquery/QueryTiers.kt`.
 */
export type Tier = 1 | 2 | 3 | 4;

export const TIERS: readonly Tier[] = [1, 2, 3, 4];

/** The tiered slice of a blueprint response — structural, so a fixture needs only `tiers`. */
type Tiered = Pick<Blueprint, "tiers">;

export function isTier(value: unknown): value is Tier {
  return value === 1 || value === 2 || value === 3 || value === 4;
}

/** The wire/form value as a [Tier], else `null` (unset, or out of 1-4). */
export function asTier(value: unknown): Tier | null {
  return isTier(value) ? value : null;
}

/** The blueprint's own tier; `null` when unset. */
export function blueprintTier(blueprint: Tiered | undefined): Tier | null {
  return asTier(blueprint?.tiers?.blueprint);
}

/** The tier of one schema property; `null` when unset. */
export function propertyTier(blueprint: Tiered | undefined, propertyId: string): Tier | null {
  return asTier(blueprint?.tiers?.properties?.[propertyId]);
}

/** The tier of one relation; `null` when unset. */
export function relationTier(blueprint: Tiered | undefined, relationId: string): Tier | null {
  return asTier(blueprint?.tiers?.relations?.[relationId]);
}

/**
 * Whether an item of [tier] is inside the Focus "up to tier N": no focus keeps everything,
 * an active focus keeps tier <= N and EXCLUDES untiered items.
 */
export function withinFocus(tier: Tier | null | undefined, focus: Tier | null): boolean {
  if (focus === null) return true;
  return tier != null && tier <= focus;
}

/** Sort comparator over tiers: tiered ascending first, untiered last; ties compare equal, so
 *  `Array.prototype.sort` (stable) keeps the original order within a tier. */
export function byTier(a: Tier | null | undefined, b: Tier | null | undefined): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  return a - b;
}

/** `identifier -> blueprint tier` lookup over the loaded registry (`null` for unknown/untiered). */
export function blueprintTierLookup(
  blueprints: readonly (Tiered & { identifier: string })[],
): (identifier: string) => Tier | null {
  const byIdentifier = new Map(blueprints.map((blueprint) => [blueprint.identifier, blueprintTier(blueprint)]));
  return (identifier) => byIdentifier.get(identifier) ?? null;
}

/**
 * A stored property counts as filled when present and not `null`, a blank string, `[]` or `{}`;
 * `false` and `0` ARE filled (the server's `isFilledProperty`).
 */
export function isFilledProperty(value: unknown): boolean {
  if (value === undefined || value === null) return false;
  if (typeof value === "string") return value.trim() !== "";
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === "object") return Object.keys(value).length > 0;
  return true;
}

/**
 * A relation value counts as filled when it is a non-blank string, or an array with at least
 * one non-blank string element (the server's `isFilledRelation`). Whether the target resolves
 * is the findings' job, not the tier's.
 */
export function isFilledRelation(value: unknown): boolean {
  if (typeof value === "string") return value.trim() !== "";
  if (Array.isArray(value)) return value.some((entry) => typeof entry === "string" && entry.trim() !== "");
  return false;
}

/**
 * The highest T in 0..4 such that every tiered property and relation of tier <= T is filled;
 * `null` when the blueprint names no tiered property or relation (untiered is not complete).
 * Tiers that carry no field count as satisfied, so the answer is one below the lowest tier of
 * an unfilled field, or 4 when everything is filled. Mirrors `QueryTiers.kt#computeFillTier`
 * exactly (the case table in `tiers.test.ts` is copied from `QueryTiersTest.kt`).
 */
export function fillTier(
  blueprint: Tiered | undefined,
  properties: Readonly<Record<string, unknown>> | undefined,
  relations: Readonly<Record<string, unknown>> | undefined,
): number | null {
  const tieredProperties = Object.entries(blueprint?.tiers?.properties ?? {});
  const tieredRelations = Object.entries(blueprint?.tiers?.relations ?? {});
  if (tieredProperties.length === 0 && tieredRelations.length === 0) return null;
  const unfilled = [
    ...tieredProperties.filter(([id]) => !isFilledProperty(properties?.[id])),
    ...tieredRelations.filter(([id]) => !isFilledRelation(relations?.[id])),
  ].map(([, tier]) => tier);
  return unfilled.length === 0 ? 4 : Math.min(...unfilled) - 1;
}
