import { isTier, type Tier } from "../utils/tiers";
import { useStoredState } from "./useStoredState";

/**
 * The persisted "Focus: up to tier N" choice of one view (2.18.0) — `null` = all tiers.
 * `storageKey` is a `toadie.viewSettings.<key>` suffix (the Blueprints page uses
 * `blueprints.focusTier`; the Entity canvases get theirs through `useEntityGraphFilterState`);
 * a stored value outside 1-4 falls back to "all tiers".
 */
export function useTierFocus(storageKey: string): [Tier | null, (next: Tier | null) => void] {
  return useStoredState<Tier | null>(storageKey, null, (value) => value === null || isTier(value));
}
