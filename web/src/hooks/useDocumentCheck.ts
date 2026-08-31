import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { checkCatalogFile, type CatalogFileRequest, type DocumentCheckFinding } from "../api/catalogFiles";

/**
 * The editor's live check: the current form document, debounced, against the stored files AND
 * the registries (`POST /api/v1/files/check` — references plus label/annotation/tag/type/
 * lifecycle findings). Every finding here makes a strict save ask for the Save-anyway
 * confirmation; a failed check request simply reports nothing.
 *
 * One query for two consumers — the Findings panel lists them, the field block puts each on
 * its own control — so the editor issues ONE check per document, not one per consumer.
 */
export function useDocumentCheck(document: CatalogFileRequest): {
  findings: DocumentCheckFinding[];
  /** True once a check has answered — the panel's all-clear line waits for it. */
  checked: boolean;
} {
  const json = JSON.stringify(document);
  const [debounced] = useDebouncedValue(json, 500);

  const { data } = useQuery({
    // Under the "catalogFiles" prefix so catalog mutations refresh a live check; keyed on
    // the debounced document with gcTime 0 — superseded documents' entries are dropped as
    // soon as the key moves on, so typing never accumulates cache entries.
    queryKey: ["catalogFiles", "check", debounced],
    queryFn: () => checkCatalogFile(JSON.parse(debounced) as CatalogFileRequest),
    placeholderData: keepPreviousData,
    gcTime: 0,
  });

  return { findings: data?.findings ?? [], checked: data != null };
}
