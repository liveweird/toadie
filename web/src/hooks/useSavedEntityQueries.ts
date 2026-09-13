import { getSavedEntityQueries, type SavedEntityQuery } from "../api/entityQueries";
import { useRegistryQuery } from "./useRegistryQuery";

/**
 * One cached query for the saved entity queries visible to the caller (their own + everyone's
 * public ones) — the ["entityQueries"] key is shared by both canvases' `EntityQueryPicker`, so
 * one invalidation after a save/delete refreshes it everywhere (the `useLenses` shape).
 */
export function useSavedEntityQueries(): {
  entityQueries: SavedEntityQuery[];
  loading: boolean;
  error: boolean;
  /** The load failure itself (for loadErrorMessage); null while healthy. */
  loadError: unknown;
} {
  return useRegistryQuery(["entityQueries"], getSavedEntityQueries, "entityQueries");
}
