import { getLenses, type Lens } from "../api/lenses";
import { useRegistryQuery } from "./useRegistryQuery";

/**
 * One cached query for the lenses visible to the caller (their own + everyone's public
 * ones) — the ["lenses"] key is shared by every view's LensPicker, so one invalidation
 * after a save/delete refreshes them all.
 */
export function useLenses(): {
  lenses: Lens[];
  loading: boolean;
  error: boolean;
  /** The load failure itself (for loadErrorMessage); null while healthy. */
  loadError: unknown;
} {
  return useRegistryQuery(["lenses"], getLenses, "lenses");
}
