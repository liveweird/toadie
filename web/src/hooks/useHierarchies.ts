import { getDictionary, type DictionaryEntry } from "../api/dictionaries";
import { useRegistryQuery } from "./useRegistryQuery";

/**
 * One cached query for the hierarchies dictionary — the ["dictionary", "hierarchies"] key is
 * shared with pages/Hierarchies.tsx, so its save invalidates every picker at once. Order is
 * the admin's payload order; the FIRST entry is what the Entity hierarchy/graph pages open on.
 */
export function useHierarchies(): {
  hierarchies: DictionaryEntry[];
  loading: boolean;
  error: boolean;
  loadError: unknown;
} {
  return useRegistryQuery(["dictionary", "hierarchies"], () => getDictionary("hierarchies"), "hierarchies");
}
