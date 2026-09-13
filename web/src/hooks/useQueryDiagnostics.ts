import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { checkEntityQuery, type EntityQueryDiagnostic } from "../api/entities";

/**
 * The entity query bar's live diagnostics (phase 7, v2.0.0): the current DRAFT text, debounced,
 * against `POST /api/v1/entities/query/check` — the same parse+validate the graph request runs
 * before it evaluates a query, without evaluating it (so the two budget codes never appear
 * here). A blank/whitespace-only draft never checks (there is nothing to validate) and answers
 * no diagnostics; a failed check request simply reports nothing, the same posture as
 * `useDocumentCheck`.
 */
export function useQueryDiagnostics(text: string): {
  diagnostics: EntityQueryDiagnostic[];
  isChecking: boolean;
} {
  const [debounced] = useDebouncedValue(text, 300);
  const trimmed = debounced.trim();

  const { data, isFetching } = useQuery({
    queryKey: ["entities", "query", "check", debounced],
    queryFn: () => checkEntityQuery(debounced),
    enabled: trimmed !== "",
    retry: false,
    staleTime: 30_000,
  });

  return { diagnostics: trimmed === "" ? [] : (data?.diagnostics ?? []), isChecking: trimmed !== "" && isFetching };
}
