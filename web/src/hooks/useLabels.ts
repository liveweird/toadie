import { useQuery } from "@tanstack/react-query";
import { getLabels, type Label } from "../api/labels";

/**
 * One cached query for the label registry (the ["labels"] key is shared with
 * pages/Labels.tsx) — the catalog editor's label pickers filter it by the document's kind.
 */
export function useLabels(): {
  labels: Label[];
  loading: boolean;
  error: boolean;
  /** The load failure itself (for loadErrorMessage); null while healthy. */
  loadError: unknown;
} {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["labels"],
    queryFn: getLabels,
    staleTime: 5 * 60 * 1000,
  });
  return { labels: data ?? [], loading: isLoading, error: isError, loadError: error };
}
