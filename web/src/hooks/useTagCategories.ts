import { getTagCategories, type TagCategory } from "../api/tagCategories";
import { useRegistryQuery } from "./useRegistryQuery";

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
  return useRegistryQuery(["tagCategories"], getTagCategories, "categories");
}
