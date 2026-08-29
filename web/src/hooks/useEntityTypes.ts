import { useQuery } from "@tanstack/react-query";
import { getEntityTypes, type EntityTypes } from "../api/entityTypes";

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
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["entityTypes"],
    queryFn: getEntityTypes,
    staleTime: 5 * 60 * 1000,
  });
  return { dictionaries: data ?? [], loading: isLoading, error: isError, loadError: error };
}
