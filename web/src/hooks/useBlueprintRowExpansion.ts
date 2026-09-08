import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormErrors, UseFormReturnType } from "@mantine/form";
import { rowDomId, type RowExpansionApi } from "../components/EditorRowList";
import { ROW_FAMILIES, rowsWithErrors, type BlueprintFormValues, type RowFamily } from "../utils/blueprintForm";

/** The five families' rows, uniformly typed to what `firstRowIds`/`revealErrors` need —
 *  sidesteps TypeScript's inability to index `BlueprintFormValues` by a widened `RowFamily`
 *  union directly (the `combinedPropertyIds` idiom in `utils/blueprintForm.ts`). */
function familyRows(values: BlueprintFormValues): Record<RowFamily, { key: string }[]> {
  return {
    properties: values.properties,
    relations: values.relations,
    mirrorProperties: values.mirrorProperties,
    calculationProperties: values.calculationProperties,
    aggregationProperties: values.aggregationProperties,
  };
}

/** The lazy initializer's seed: the first row's id of every non-empty family, family order. */
function firstRowIds(values: BlueprintFormValues): string[] {
  const families = familyRows(values);
  return ROW_FAMILIES.filter((family) => families[family].length > 0).map((family) =>
    rowDomId(family, families[family][0].key),
  );
}

export interface BlueprintRowExpansion extends RowExpansionApi {
  /** A blocked submit's handler: expands every row carrying an error and focuses the first
   *  (family order, then row position). */
  revealErrors: (errors: FormErrors) => void;
}

/**
 * Row-fold state for the whole Blueprint editor — one `ReadonlySet<string>` shared by every
 * `EditorRowList` instance on the page. The first row of each non-empty family starts
 * expanded (a fresh Create page seeds nothing to expand); `BlueprintEditor` wires
 * `revealErrors` into `form.onSubmit`'s validation-failure callback.
 */
export function useBlueprintRowExpansion(form: UseFormReturnType<BlueprintFormValues>): BlueprintRowExpansion {
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set(firstRowIds(form.values)));
  const [pendingFocus, setPendingFocus] = useState<string | null>(null);

  // EditBlueprint's current shape calls form.initialize during its OWN render, before this
  // hook (nested inside BlueprintEditor) ever runs — so the lazy initializer above already
  // sees the loaded rows on this hook's very first render. A page that instead initializes
  // AFTER this hook has already mounted still gets the correct seed once form.initialized
  // flips true, exactly once (the ref guard): an ordinary later edit — add/remove a row —
  // must never silently re-collapse the form. Reading/writing the ref from an effect (never
  // during render) keeps this outside react-hooks' "no ref access during render" rule.
  const seededRef = useRef(false);
  useEffect(() => {
    if (!form.initialized || seededRef.current) return;
    seededRef.current = true;
    setExpanded(new Set(firstRowIds(form.values)));
  }, [form, form.initialized]);

  const isExpanded = useCallback((rowId: string) => expanded.has(rowId), [expanded]);

  const toggle = useCallback((rowId: string) => {
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(rowId)) next.delete(rowId);
      else next.add(rowId);
      return next;
    });
  }, []);

  const expandAll = useCallback((ids: string[]) => {
    setExpanded((current) => new Set([...current, ...ids]));
  }, []);

  const collapseAll = useCallback((ids: string[]) => {
    setExpanded((current) => {
      const next = new Set(current);
      for (const id of ids) next.delete(id);
      return next;
    });
  }, []);

  const reveal = useCallback((rowId: string) => {
    setExpanded((current) => (current.has(rowId) ? current : new Set(current).add(rowId)));
    // A later reveal always supersedes an unhandled earlier one — EditorRowList's effect
    // only ever acts on the CURRENT pendingFocus, so a stale target can never fire after
    // the row it originally named.
    setPendingFocus(rowId);
  }, []);

  const focusHandled = useCallback(() => setPendingFocus(null), []);

  const revealErrors = useCallback(
    (errors: FormErrors) => {
      const entries = rowsWithErrors(errors);
      if (entries.length === 0) return;
      const families = familyRows(form.values);
      const ids = entries.map(({ family, index }) => rowDomId(family, families[family][index].key));
      setExpanded((current) => new Set([...current, ...ids]));
      setPendingFocus(ids[0]);
    },
    [form],
  );

  return useMemo(
    () => ({ isExpanded, toggle, expandAll, collapseAll, reveal, pendingFocus, focusHandled, revealErrors }),
    [isExpanded, toggle, expandAll, collapseAll, reveal, pendingFocus, focusHandled, revealErrors],
  );
}
