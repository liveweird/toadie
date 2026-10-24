import { useCallback, useMemo, useState } from "react";
import { foldGraph, type FoldableGraph, type FoldedGraph } from "../utils/graphFold";
import { graphGate, topLevelFold, type GraphGate } from "../utils/graphGate";
import { LARGE_GRAPH_NODES } from "../utils/graphLayout";
import type { HierarchyNode } from "../utils/hierarchy";

/**
 * The large-graph gate's state, shared by the Graph and Entity graph pages: the fold the
 * canvas draws, the gate verdict for it, and the two TRANSIENT choices a user can make at the
 * gate. Both are plain `useState` — never persisted, never written to the per-user layout
 * document (whose `collapsed` list is capped at 1,000 ids, far under a large workspace):
 *
 * - **Show top level** — an OVERLAY fold collapsing every forest root that has drawn
 *   descendants. While it is active the page's fold toggles edit the overlay locally
 *   ([toggle]); the saved `collapsed` list is left alone.
 * - **Show everything** — bypasses the gate (`showAll`); the layout then drops dagre's optimal
 *   crossing-order heuristic above the limit (`layoutOptions`).
 *
 * Both reset as soon as the graph under the SAVED fold is back under the limit, so narrowing
 * the filters and widening them again shows the gate again.
 */
export function useLargeGraphGate<N extends { id: string }>(
  filtered: FoldableGraph<N> | null,
  forest: HierarchyNode<N>[],
  collapsed: ReadonlySet<string>,
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
  const [overlayState, setOverlayState] = useState<ReadonlySet<string> | null>(null);
  const [showAllState, setShowAllState] = useState(false);

  const saved = useMemo(
    () => (filtered ? foldGraph(filtered, forest, collapsed) : null),
    [filtered, forest, collapsed],
  );
  const overLimit = saved !== null && saved.nodes.length > LARGE_GRAPH_NODES;
  if (!overLimit && (overlayState !== null || showAllState)) {
    // Derived state, reset during render: the graph is small again.
    setOverlayState(null);
    setShowAllState(false);
  }
  const overlay = overLimit ? overlayState : null;
  const showAll = overLimit && showAllState;

  const folded = useMemo(
    () => (overlay && filtered ? foldGraph(filtered, forest, overlay) : saved),
    [overlay, filtered, forest, saved],
  );
  const topLevel = useMemo(
    () => (overLimit && saved ? topLevelFold(forest, saved.info) : []),
    [overLimit, saved, forest],
  );

  const showTopLevel = useCallback(() => {
    setShowAllState(false);
    setOverlayState(new Set(topLevel));
  }, [topLevel]);
  const showEverything = useCallback(() => {
    setOverlayState(null);
    setShowAllState(true);
  }, []);
  const clearOverlay = useCallback(() => setOverlayState(null), []);
  const toggle = useCallback((id: string) => {
    setOverlayState((previous) => {
      const next = new Set(previous ?? []);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }, []);

  return {
    folded,
    gate: folded ? graphGate({ drawnNodes: folded.nodes.length, showAll }) : "layout",
    showAll,
    overlay,
    canShowTopLevel: overlay === null && topLevel.length > 0,
    showTopLevel,
    showEverything,
    clearOverlay,
    toggle,
  };
}
