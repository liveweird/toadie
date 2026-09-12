import { getLabels, type Label } from "../api/labels";
import { useRegistryQuery } from "./useRegistryQuery";

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
  return useRegistryQuery(["labels"], getLabels, "labels");
}
