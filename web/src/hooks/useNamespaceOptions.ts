import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { getDictionary } from "../api/dictionaries";

/**
 * One cached query for the namespaces dictionary (the ["dictionary", "namespaces"] key is
 * shared with pages/Namespaces.tsx) mapped to Select options. Unlike Lettuce's id-based
 * dictionary consumers, catalog files store the namespace TEXT (it IS the Backstage
 * identity), so the options are the values themselves. `current` is appended when it is no
 * longer among the active entries (removed from the dictionary after the file was saved) so
 * the stored value still displays instead of vanishing — the strict server then explains
 * the 400 on save.
 */
export function useNamespaceOptions(current?: string): {
  options: string[];
  loading: boolean;
  error: boolean;
} {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["dictionary", "namespaces"],
    queryFn: () => getDictionary("namespaces"),
    staleTime: 5 * 60 * 1000,
  });

  const options = useMemo(() => {
    const active = (data ?? []).map((entry) => entry.value);
    const folded = current?.trim().toLowerCase();
    return folded && !active.includes(folded) ? [...active, folded] : active;
  }, [data, current]);

  return { options, loading: isLoading, error: isError };
}
