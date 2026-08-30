import { useQuery } from "@tanstack/react-query";
import { getLenses, type Lens } from "../api/lenses";

/**
 * One cached query for the lenses visible to the caller (their own + everyone's public
 * ones) — the ["lenses"] key is shared by every view's LensPicker, so one invalidation
 * after a save/delete refreshes them all.
 */
export function useLenses(): {
  lenses: Lens[];
  loading: boolean;
  error: boolean;
} {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["lenses"],
    queryFn: getLenses,
    staleTime: 5 * 60 * 1000,
  });
  return { lenses: data ?? [], loading: isLoading, error: isError };
}
