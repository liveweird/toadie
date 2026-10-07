import { useDebouncedValue } from "@mantine/hooks";
import type { GetEntityGraphQuery } from "../api/entities";
import { withinFocus, type Tier } from "../utils/tiers";
import { isString, isStringArray, useStoredState } from "./useStoredState";
import { useTierFocus } from "./useTierFocus";

/** The raw control values + setters `EntityGraphFilterControls` renders — Search and Team only
 *  since 2.4.1 (the blueprint slot moved to the always-visible `BlueprintPills` row), plus the
 *  tier Focus since 2.18.0. */
export type EntityGraphFilterControlsState = {
  q: string;
  setQ: (v: string) => void;
  team: string;
  setTeam: (v: string) => void;
  /** The persisted "up to tier N" Focus; `null` = all tiers. */
  focus: Tier | null;
  setFocus: (next: Tier | null) => void;
};

/** The blueprint-pills row's own state (2.4.1): every ACTIVE blueprint plus which of them the
 *  user hid, fed straight to `components/BlueprintPills.tsx`. */
export type EntityGraphBlueprintPillsState = {
  active: readonly string[];
  hidden: readonly string[];
  setHidden: (hidden: string[]) => void;
  /** The Focus and the blueprint-tier lookup, so the pills can dim what the Focus excludes. */
  focus: Tier | null;
  tierOf: (identifier: string) => Tier | null;
};

/**
 * The Entity graph/hierarchy pages' shared filter state (v1.25.0, + the Phase 4 `team` slot,
 * v1.26.0; blueprint pills replacing the Blueprints filter since 2.4.1) — free-text search, a
 * Team select, and the blueprint-visibility pill row, the mirror of `useCatalogFileFilterState`
 * scaled down to the params `GET /api/v1/entities/graph` declares. Persisted per view under
 * `toadie.viewSettings.<viewKey>.filter.*` (NOT in the URL — the catalog views' posture; the
 * Entities LIST's own `?blueprint=` param is unrelated). Only `q` debounces (300 ms); the Team
 * Select and the pills change discretely.
 *
 * **Blueprint pills (2.4.1).** `activeBlueprints` is the WHOLE blueprint registry (every kind
 * pill's rule, one level over) — all start SHOWN (`${viewKey}.filter.hiddenBlueprints`, default
 * `[]`, so a newly created blueprint is shown without any migration), and the caller passes
 * `registryLoading` (`useBlueprints().loading`) so the derived state can tell "the registry is
 * genuinely empty" from "it hasn't loaded yet". `values.blueprints` carries the VISIBLE set only
 * while something ACTIVE is hidden (an all-shown registry sends no `blueprint` param at all —
 * the all-on-kind-pills idiom); `noBlueprints` is true only once the registry is known to be
 * non-empty AND every one of its rows is hidden (an empty/unloaded registry never suppresses the
 * fetch — both page tests mock `/blueprints` as `[]`); `ready` gates the query only while a
 * STORED hidden list exists and the registry is still loading (recomputing `noBlueprints`/
 * `values.blueprints` against the not-yet-loaded `activeBlueprints` would be wrong in that one
 * case; an empty stored list needs no such wait). `setHidden` PRUNES the write to active ids, in
 * registry order — a hidden id whose blueprint is later deleted simply lingers in storage until
 * the next write, the kind-pills rule restated. `activeFilterCount` counts `q`, `team` and the tier Focus (never the pills):
 * pills never count into a toolbar badge, exactly like the catalog Kind pills.
 *
 * **Tier Focus (2.18.0).** A stored `${viewKey}.filter.focusTier` ("up to tier N", `null` = all)
 * folds into the SAME visible-blueprints value: the pill-visible blueprints whose tier
 * (`tierOf`, the loaded registry's lookup) is within the Focus — untiered blueprints are
 * excluded. `values.blueprints` is sent whenever the Focus (or a hidden pill) actually narrows
 * the set, `noBlueprints` covers an empty intersection (the Focus excluding everything that is
 * shown), `ready` also waits for the registry while a Focus is stored (a not-yet-loaded
 * registry has no tiers to resolve), and an active Focus counts +1 into `activeFilterCount`
 * (it is a Select in the filter panel, unlike the pills). The pills themselves stay
 * independent of the Focus: toggling one never changes it, and a dimmed pill is still togglable.
 */
export function useEntityGraphFilterState(
  viewKey: string,
  activeBlueprints: readonly string[],
  registryLoading = false,
  tierOf: (identifier: string) => Tier | null = () => null,
): {
  values: GetEntityGraphQuery;
  activeFilterCount: number;
  controls: EntityGraphFilterControlsState;
  blueprintPills: EntityGraphBlueprintPillsState;
  noBlueprints: boolean;
  ready: boolean;
} {
  const [storedHidden, setStoredHidden] = useStoredState<string[]>(
    `${viewKey}.filter.hiddenBlueprints`,
    [],
    isStringArray,
  );
  const [q, setQ] = useStoredState(`${viewKey}.filter.q`, "", isString);
  const [debouncedQ] = useDebouncedValue(q, 300);
  const [team, setTeam] = useStoredState(`${viewKey}.filter.team`, "", isString);
  const [focus, setFocus] = useTierFocus(`${viewKey}.filter.focusTier`);

  const activeSet = new Set(activeBlueprints);
  const hiddenActive = storedHidden.filter((id) => activeSet.has(id));
  const shown = activeBlueprints.filter((id) => !hiddenActive.includes(id));
  const visible = shown.filter((id) => withinFocus(tierOf(id), focus));
  const narrowed = visible.length < activeBlueprints.length;

  function setHidden(next: string[]) {
    const nextSet = new Set(next);
    setStoredHidden(activeBlueprints.filter((id) => nextSet.has(id)));
  }

  const values: GetEntityGraphQuery = {
    blueprints: narrowed ? visible : undefined,
    q: debouncedQ || undefined,
    team: team || undefined,
  };

  const noBlueprints = activeBlueprints.length > 0 && visible.length === 0;
  const ready = (storedHidden.length === 0 && focus === null) || !registryLoading;
  const activeFilterCount = (q.trim() ? 1 : 0) + (team ? 1 : 0) + (focus === null ? 0 : 1);

  return {
    values,
    activeFilterCount,
    controls: { q, setQ, team, setTeam, focus, setFocus },
    blueprintPills: { active: activeBlueprints, hidden: hiddenActive, setHidden, focus, tierOf },
    noBlueprints,
    ready,
  };
}
