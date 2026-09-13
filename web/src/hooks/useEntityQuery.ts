import { isString, useStoredState } from "./useStoredState";

/**
 * The ONE shared entity-query state for BOTH the Entity graph and Entity hierarchy canvases
 * (phase 7, v2.0.0 — `.claude/docs/entity-query-language.md`) — deliberately NOT keyed per
 * view, so the draft/applied text survives switching between the two pages, unlike every other
 * per-view filter (`useEntityGraphFilterState`). `draft` is what the editor shows and what the
 * live `/query/check` diagnostics run against; `applied` is what actually narrows the graph
 * request (only `run`/`runText` change it) — running happens only on Run/Mod+Enter, never on
 * every keystroke.
 */
export function useEntityQuery(): {
  draft: string;
  setDraft: (value: string) => void;
  applied: string;
  /** Copies the current draft into `applied`. */
  run: () => void;
  /** Empties both the draft and the applied query. */
  clear: () => void;
  /** Sets both the draft and the applied query to `text` in one step (a future picked-query
   *  action — e.g. an Errors-report or Lens-driven query — reuses this rather than run(). */
  runText: (text: string) => void;
} {
  const [draft, setDraft] = useStoredState("entityQuery.text", "", isString);
  const [applied, setApplied] = useStoredState("entityQuery.applied", "", isString);

  function run() {
    setApplied(draft);
  }

  function clear() {
    setDraft("");
    setApplied("");
  }

  function runText(text: string) {
    setDraft(text);
    setApplied(text);
  }

  return { draft, setDraft, applied, run, clear, runText };
}
