import { useQuery, type QueryKey } from "@tanstack/react-query";

/** Every registry query shares this 5-minute staleness (they change rarely, admin-curated). */
const REGISTRY_STALE_TIME_MS = 5 * 60 * 1000;

/**
 * The shared shape of every registry hook: the fetched items (defaulting to an empty array
 * while loading/erroring), a loading flag, an error flag, and the raw load failure for
 * `loadErrorMessage` (null while healthy).
 */
type RegistryQueryResult<T, K extends string> = { [P in K]: T[] } & {
  loading: boolean;
  error: boolean;
  loadError: unknown;
};

/**
 * Factory behind `useLabels`/`useTagCategories`/`useAnnotationKeys`/`useEntityTypes`/
 * `useLenses` — one `useQuery` over a fixed key and fetcher, returning the common
 * `{<itemsKey>, loading, error, loadError}` shape every registry page/picker consumes.
 * `itemsKey` picks the property name (each hook exposes its own noun, e.g. `labels` vs
 * `categories`) so callers keep their exact existing destructuring.
 */
export function useRegistryQuery<T, K extends string>(
  queryKey: QueryKey,
  fetcher: () => Promise<T[]>,
  itemsKey: K,
): RegistryQueryResult<T, K> {
  const { data, isLoading, isError, error } = useQuery({
    queryKey,
    queryFn: fetcher,
    staleTime: REGISTRY_STALE_TIME_MS,
  });
  return {
    [itemsKey]: data ?? [],
    loading: isLoading,
    error: isError,
    loadError: error,
  } as RegistryQueryResult<T, K>;
}
