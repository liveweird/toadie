import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { getDictionary } from "../api/dictionaries";

/**
 * One cached query for the lifecycles dictionary (the ["dictionary", "lifecycles"] key is
 * shared with pages/Lifecycles.tsx) mapped to Select options — the useNamespaceOptions
 * sibling, minus the default-entry concept (the LIFECYCLE dictionary has none). `current`
 * is appended when it is no longer among the active entries (removed after the file was
 * saved) so the stored value still displays — the strict server then explains the 400 on
 * save.
 */
export function useLifecycleOptions(current?: string): {
  options: string[];
  loading: boolean;
  error: boolean;
} {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["dictionary", "lifecycles"],
    queryFn: () => getDictionary("lifecycles"),
    staleTime: 5 * 60 * 1000,
  });

  const options = useMemo(() => {
    const active = (data ?? []).map((entry) => entry.value);
    const trimmed = current?.trim();
    return trimmed && !active.includes(trimmed) ? [...active, trimmed] : active;
  }, [data, current]);

  return { options, loading: isLoading, error: isError };
}
