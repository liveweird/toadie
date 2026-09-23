import { useQuery } from "@tanstack/react-query";
import { listBlueprints, type Blueprint } from "../api/blueprints";

/**
 * One cached query for the blueprint registry (the ["blueprints"] key is shared with
 * pages/Blueprints.tsx) — the relation/aggregation target Selects consume it too.
 */
export function useBlueprints(options: { freshOnMount?: boolean } = {}): {
  blueprints: Blueprint[];
  loading: boolean;
  fetching: boolean;
  loaded: boolean;
  error: boolean;
  /** The load failure itself (for loadErrorMessage); null while healthy. */
  loadError: unknown;
} {
  const { data, isLoading, isFetching, isSuccess, isError, error } = useQuery({
    queryKey: ["blueprints"],
    queryFn: listBlueprints,
    staleTime: options.freshOnMount ? 0 : 5 * 60 * 1000,
    refetchOnMount: options.freshOnMount ? "always" : true,
  });
  return {
    blueprints: data ?? [],
    loading: isLoading,
    fetching: isFetching,
    loaded: isSuccess,
    error: isError,
    loadError: error,
  };
}
