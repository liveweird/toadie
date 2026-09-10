import { useDebouncedValue } from "@mantine/hooks";
import type { GetEntityGraphQuery } from "../api/entities";
import { isString, isStringArray, useStoredState } from "./useStoredState";

/** The raw control values + setters `EntityGraphFilterControls` renders. */
export type EntityGraphFilterControlsState = {
  blueprints: string[];
  setBlueprints: (v: string[]) => void;
  q: string;
  setQ: (v: string) => void;
  team: string;
  setTeam: (v: string) => void;
};

/**
 * The Entity graph/hierarchy pages' shared filter state (v1.25.0, + the Phase 4 `team` slot,
 * v1.26.0) — a blueprint multi-select, free-text search, and a Team select, the mirror of
 * `useCatalogFileFilterState` scaled down to the params `GET /api/v1/entities/graph` declares.
 * Persisted per view under `toadie.viewSettings.<viewKey>.filter.*` (NOT in the URL — the
 * catalog views' posture; the Entities LIST's own `?blueprint=` param is unrelated). Only `q`
 * debounces (300 ms); the blueprint MultiSelect and the Team Select change discretely.
 */
export function useEntityGraphFilterState(viewKey: string): {
  values: GetEntityGraphQuery;
  activeFilterCount: number;
  controls: EntityGraphFilterControlsState;
} {
  const [blueprints, setBlueprints] = useStoredState<string[]>(
    `${viewKey}.filter.blueprints`,
    [],
    isStringArray,
  );
  const [q, setQ] = useStoredState(`${viewKey}.filter.q`, "", isString);
  const [debouncedQ] = useDebouncedValue(q, 300);
  const [team, setTeam] = useStoredState(`${viewKey}.filter.team`, "", isString);

  const values: GetEntityGraphQuery = {
    blueprints: blueprints.length > 0 ? blueprints : undefined,
    q: debouncedQ || undefined,
    team: team || undefined,
  };

  const activeFilterCount =
    (blueprints.length > 0 ? 1 : 0) + (q.trim() ? 1 : 0) + (team ? 1 : 0);

  return { values, activeFilterCount, controls: { blueprints, setBlueprints, q, setQ, team, setTeam } };
}
