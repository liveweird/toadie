import { useEffect } from "react";
import { isBoolean, isString } from "./useStoredState";
import { useEntityQueryStoredState } from "./useEntityQueryStoredState";

/**
 * The ONE shared entity-query state for BOTH the Entity graph and Entity hierarchy canvases
 * (phase 7, v2.0.0 — `.claude/docs/entity-query-language.md`) — deliberately NOT keyed per
 * view, so the draft/applied text survives switching between the two pages, unlike every other
 * per-view filter (`useEntityGraphFilterState`). `draft` is what the editor shows and what the
 * live `/query/check` diagnostics run against; `applied` is what actually narrows the graph
 * request (only `run`/`runText` change it) — running happens only on Run/Mod+Enter (both share
 * the blank guard), never on every keystroke.
 *
 * **Invariant (2.4.1): a blank draft never leaves an applied query in force.** `applied` is
 * DERIVED — `""` whenever the trimmed draft is blank, the stored value otherwise — so erasing
 * the editor text by hand always shows the full graph again, even without clicking Clear or Run.
 * An effect rewrites the stored `applied` pair to match on mount (normalizing a legacy
 * blank-draft/non-blank-applied pair a pre-2.4.1 session may have left behind), so the very
 * first request already omits `query`. `setDraft("")` clears the stored applied text outright
 * (rather than relying on the derivation alone) so a later non-blank `setDraft` never resurrects
 * it, and `run()` no-ops on a blank draft (Run/Mod+Enter's existing guard, restated here so
 * every caller gets it for free).
 *
 * **The collapsible Query section (2.4.1).** `open`/`setOpen` persist under the account-scoped `open` key
 * (default `false`, shared like the draft/applied pair — collapsed on a first visit, remembered
 * across the two canvases and reloads); `runText` — the saved-query pick and every canvas
 * context action — also opens the section, since landing a query nobody can see would be worse
 * than not collapsing at all. Plain `run()`/`clear()` leave `open` alone: the section is either
 * already open (a hand-typed query) or the caller separately forces it open on a REFUSED run
 * (`EntityGraph.tsx`/`EntityHierarchy.tsx`'s `queryForcedOpen`).
 */
export function useEntityQuery(): {
  draft: string;
  setDraft: (value: string) => void;
  applied: string;
  /** Copies the current draft into `applied`; no-ops on a blank draft. */
  run: () => void;
  /** Empties both the draft and the applied query. */
  clear: () => void;
  /** Sets both the draft and the applied query to `text` in one step, and opens the section
   *  (the saved-query picker and every canvas context action use this). */
  runText: (text: string) => void;
  open: boolean;
  setOpen: (open: boolean) => void;
} {
  const [draft, setDraftRaw] = useEntityQueryStoredState("text", "", isString);
  const [storedApplied, setStoredApplied] = useEntityQueryStoredState("applied", "", isString);
  const [open, setOpen] = useEntityQueryStoredState("open", false, isBoolean);

  const applied = draft.trim() === "" ? "" : storedApplied;

  // Normalize a stale pair (a blank draft with a leftover non-blank `applied`) on mount and
  // after an account partition changes. The render-time derivation above already keeps it out
  // of the first request; this also prevents a later edit from resurrecting the stale value.
  useEffect(() => {
    if (draft.trim() === "" && storedApplied !== "") setStoredApplied("");
  }, [draft, storedApplied, setStoredApplied]);

  function setDraft(value: string) {
    setDraftRaw(value);
    if (value.trim() === "") setStoredApplied("");
  }

  function run() {
    if (draft.trim() === "") return;
    setStoredApplied(draft);
  }

  function clear() {
    setDraftRaw("");
    setStoredApplied("");
  }

  function runText(text: string) {
    setDraftRaw(text);
    setStoredApplied(text);
    setOpen(true);
  }

  return { draft, setDraft, applied, run, clear, runText, open, setOpen };
}
