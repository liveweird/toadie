import { useCallback, useMemo, useState } from "react";
import { foldGraph, type FoldableGraph, type FoldedGraph } from "../utils/graphFold";
import { graphGate, topLevelFold, type GraphGate } from "../utils/graphGate";
import { LARGE_GRAPH_NODES } from "../utils/graphLayout";
import type { HierarchyNode } from "../utils/hierarchy";

function sameIds(overlay: ReadonlySet<string>, ids: readonly string[]): boolean {
  return overlay.size === ids.length && ids.every((id) => overlay.has(id));
}

/**
 * The large-graph gate's state, shared by the Graph and Entity graph pages: the fold the
 * canvas draws, the gate verdict for it, and the TRANSIENT choices a user can make at the
 * gate. All are plain `useState` — never persisted, never written to the per-user layout
 * document (whose `collapsed` list is capped at 1,000 ids, far under a large workspace):
 *
 * - **Show top level** — an OVERLAY fold collapsing every forest root that has drawn
 *   descendants. While it is active the page's fold toggles edit the overlay locally
 *   ([toggle]); the saved `collapsed` list is left alone. A graph over the limit OPENS on this
 *   overlay by itself (2.22.0) whenever it fits the limit: the top-level fold must draw
 *   strictly fewer nodes AND at most `LARGE_GRAPH_NODES` ([topLevel.fits]) — otherwise the
 *   canvas would still be gated and the panel (which then offers the choice) is the better
 *   first screen. Leaving the overlay (the badge's clear button, Expand all) lands on the
 *   panel and stays there: the default is not re-applied until the graph is back under the
 *   limit.
 * - **Show everything** — bypasses the gate (`showAll`); the layout then drops dagre's optimal
 *   crossing-order heuristic above the limit (`layoutOptions`).
 *
 * The choices (and the "left the overlay" flag) reset as soon as the graph under the SAVED fold
 * is back under the limit, so narrowing the filters and widening them again starts from the
 * default again (the top-level overlay, or the panel when it does not fit). That mid-session
 * flip is deliberate: "everything" was a choice about THAT selection, and silently remembering
 * it would start a long layout the user never asked for on the next widening (pinned by the
 * "both choices reset" case in `useLargeGraphGate.test.ts`).
 */
export function useLargeGraphGate<N extends { id: string }>(
  filtered: FoldableGraph<N> | null,
  forest: HierarchyNode<N>[],
  collapsed: ReadonlySet<string>,
  /** The saved fold (`collapsed`) is final: the layout document has loaded or failed to. Until
   *  then the default overlay is not opened — an empty `collapsed` would open it for a graph the
   *  saved fold is about to bring under the limit (a flash and a wasted layout). Defaults to true. */
  ready = true,
): {
  /** The fold to draw (the overlay's while it is active, else the saved one). */
  folded: FoldedGraph<N> | null;
  gate: GraphGate;
  /** The effective "Show everything" — false whenever the graph is under the limit. */
  showAll: boolean;
  /** The overlay fold while "Show top level" is active. */
  overlay: ReadonlySet<string> | null;
  canShowTopLevel: boolean;
  showTopLevel: () => void;
  showEverything: () => void;
  clearOverlay: () => void;
  /** Toggle one node in the overlay (only while it is active). */
  toggle: (id: string) => void;
} {
  // The user's EXPLICIT overlay (Show top level / a toggle); null = none chosen. The default
  // top-level overlay is derived below, never stored, so it follows every filter change.
  const [overlayState, setOverlayState] = useState<ReadonlySet<string> | null>(null);
  const [showAllState, setShowAllState] = useState(false);
  // The user left the default overlay: show the panel instead of re-opening it.
  const [dismissedState, setDismissedState] = useState(false);

  const saved = useMemo(
    () => (filtered ? foldGraph(filtered, forest, collapsed) : null),
    [filtered, forest, collapsed],
  );
  const overLimit = saved !== null && saved.nodes.length > LARGE_GRAPH_NODES;
  if (!overLimit && (overlayState !== null || showAllState || dismissedState)) {
    // Derived state, reset during render: the graph is small again.
    setOverlayState(null);
    setShowAllState(false);
    setDismissedState(false);
  }
  // What "Show top level" would draw: offered only if it actually brings the drawn count down,
  // and opened by default only if the result is also within the limit.
  const topLevel = useMemo(() => {
    if (!overLimit || !saved || !filtered) {
      return { ids: [] as string[], helps: false, fits: false, folded: null };
    }
    const ids = topLevelFold(forest, saved.info);
    const folded = ids.length > 0 ? foldGraph(filtered, forest, new Set(ids)) : null;
    const helps = folded !== null && folded.nodes.length < saved.nodes.length;
    return { ids, helps, fits: helps && folded.nodes.length <= LARGE_GRAPH_NODES, folded };
  }, [overLimit, saved, filtered, forest]);
  const autoOverlay = useMemo(
    () => (topLevel.fits ? new Set(topLevel.ids) : null),
    [topLevel],
  );
  const autoOpen = ready && overLimit && !showAllState && !dismissedState && overlayState === null && autoOverlay !== null;
  const overlay = overLimit && !showAllState ? (overlayState ?? (autoOpen ? autoOverlay : null)) : null;
  const showAll = overLimit && showAllState;

  const folded = useMemo(() => {
    if (!overlay || !filtered) return saved;
    // The default overlay is exactly the fold [topLevel] already computed.
    return overlay === autoOverlay && topLevel.folded ? topLevel.folded : foldGraph(filtered, forest, overlay);
  }, [overlay, autoOverlay, topLevel, filtered, forest, saved]);

  const showTopLevel = useCallback(() => {
    setShowAllState(false);
    setOverlayState(new Set(topLevel.ids));
  }, [topLevel]);
  const showEverything = useCallback(() => {
    setOverlayState(null);
    setShowAllState(true);
  }, []);
  const clearOverlay = useCallback(() => {
    setOverlayState(null);
    setDismissedState(true);
  }, []);
  const toggle = useCallback(
    (id: string) => {
      setOverlayState((previous) => {
        // The first toggle on the default overlay turns it into an explicit one.
        const next = new Set(previous ?? autoOverlay ?? []);
        if (!next.delete(id)) next.add(id);
        return next;
      });
    },
    [autoOverlay],
  );

  return {
    folded,
    gate: folded ? graphGate({ drawnNodes: folded.nodes.length, showAll }) : "layout",
    showAll,
    overlay,
    // Offered whenever the overlay in force is not already the top level: no overlay at all (the
    // graph is still over the limit after the saved fold), or an explicit one the user edited
    // (an expand pushed it over the limit) — then only if the top level fits, since the button
    // would otherwise lead straight back to a gated panel.
    canShowTopLevel:
      topLevel.helps && (overlay === null || (topLevel.fits && !sameIds(overlay, topLevel.ids))),
    showTopLevel,
    showEverything,
    clearOverlay,
    toggle,
  };
}
