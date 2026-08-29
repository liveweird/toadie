import { useQuery } from "@tanstack/react-query";
import { getAnnotationKeys, type AnnotationKey } from "../api/annotationKeys";

/**
 * One cached query for the annotation-key registry (the ["annotationKeys"] key is shared
 * with pages/Annotations.tsx) — the catalog editor's annotation key picker filters it by
 * the document's kind.
 */
export function useAnnotationKeys(): {
  annotationKeys: AnnotationKey[];
  loading: boolean;
  error: boolean;
  /** The load failure itself (for loadErrorMessage); null while healthy. */
  loadError: unknown;
} {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["annotationKeys"],
    queryFn: getAnnotationKeys,
    staleTime: 5 * 60 * 1000,
  });
  return { annotationKeys: data ?? [], loading: isLoading, error: isError, loadError: error };
}
