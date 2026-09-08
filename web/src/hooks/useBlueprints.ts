import { useQuery } from "@tanstack/react-query";
import { listBlueprints, type Blueprint } from "../api/blueprints";

/**
 * One cached query for the blueprint registry (the ["blueprints"] key is shared with
 * pages/Blueprints.tsx) — the relation/aggregation target Selects consume it too.
 */
export function useBlueprints(): {
  blueprints: Blueprint[];
  loading: boolean;
  error: boolean;
  /** The load failure itself (for loadErrorMessage); null while healthy. */
  loadError: unknown;
} {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["blueprints"],
    queryFn: listBlueprints,
    staleTime: 5 * 60 * 1000,
  });
  return { blueprints: data ?? [], loading: isLoading, error: isError, loadError: error };
}
