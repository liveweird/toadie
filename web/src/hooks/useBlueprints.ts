import { useQuery } from "@tanstack/react-query";
import { listBlueprints, type Blueprint } from "../api/blueprints";

/** A module-level empty list, so `blueprints` keeps ONE identity while the registry has not
 *  loaded — memo/effect dependencies on it (the Entity graph's layout) must not churn per render. */
const NO_BLUEPRINTS: Blueprint[] = [];

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
    blueprints: data ?? NO_BLUEPRINTS,
    loading: isLoading,
    fetching: isFetching,
    loaded: isSuccess,
    error: isError,
    loadError: error,
  };
}
