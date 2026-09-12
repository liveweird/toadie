import { getEntityTypes, type EntityTypes } from "../api/entityTypes";
import { useRegistryQuery } from "./useRegistryQuery";

/**
 * One cached query for the per-kind type dictionaries (the ["entityTypes"] key is shared
 * with pages/Types.tsx) — the catalog editor's Type picker filters it by the document's
 * kind.
 */
export function useEntityTypes(): {
  dictionaries: EntityTypes[];
  loading: boolean;
  error: boolean;
  /** The load failure itself (for loadErrorMessage); null while healthy. */
  loadError: unknown;
} {
  return useRegistryQuery(["entityTypes"], getEntityTypes, "dictionaries");
}
