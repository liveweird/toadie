import { useQuery } from "@tanstack/react-query";
import { getTagCategories, type TagCategory } from "../api/tagCategories";

/**
 * One cached query for the tag categories (the ["tagCategories"] key is shared with
 * pages/Tags.tsx) — the catalog editor's grouped tag picker filters it by the document's kind.
 */
export function useTagCategories(): {
  categories: TagCategory[];
  loading: boolean;
  error: boolean;
  /** The load failure itself (for loadErrorMessage); null while healthy. */
  loadError: unknown;
} {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["tagCategories"],
    queryFn: getTagCategories,
    staleTime: 5 * 60 * 1000,
  });
  return { categories: data ?? [], loading: isLoading, error: isError, loadError: error };
}
