import { LARGE_GRAPH_NODES } from "./graphLayout";
import type { HierarchyNode } from "./hierarchy";

/** `layout` = lay the graph out and draw it; `large` = stop and ask first (the gate panel). */
export type GraphGate = "layout" | "large";

/**
 * The large-graph gate: a canvas that would DRAW more than [LARGE_GRAPH_NODES] nodes does not
 * lay itself out unasked — past that size the layout takes long (tens of seconds to minutes
 * for the biggest workspaces) and the picture is unreadable anyway. "Show everything"
 * (`showAll`) is the user's explicit bypass. Counts are DRAWN nodes (after relation chips and
 * the fold), so folding a large graph is itself a way under the limit.
 */
export function graphGate({ drawnNodes, showAll }: { drawnNodes: number; showAll: boolean }): GraphGate {
  return !showAll && drawnNodes > LARGE_GRAPH_NODES ? "large" : "layout";
}

/**
 * The ids "Show top level" collapses: the TOPMOST DRAWN node of every tree — the first node on
 * each root-to-leaf path that has at least one drawn descendant (`info` is the fold's per-node
 * info, which holds an entry exactly for those). Usually that is the forest root; when a filter
 * hides the roots it is the first drawn level underneath, so the overlay still folds the tree.
 * Folding exactly these leaves them plus every node outside the forest. Each id is listed once
 * (membership can place one node under several parents).
 */
export function topLevelFold<N extends { id: string }>(
  forest: readonly HierarchyNode<N>[],
  info: ReadonlyMap<string, unknown>,
): string[] {
  const found = new Set<string>();
  const visited = new Set<string>();
  const visit = (item: HierarchyNode<N>) => {
    const id = item.node.id;
    if (info.has(id)) {
      found.add(id);
      return;
    }
    if (visited.has(id)) return;
    visited.add(id);
    item.children.forEach(visit);
  };
  forest.forEach(visit);
  return [...found];
}
