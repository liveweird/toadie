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
 * The ids "Show top level" collapses: every forest ROOT that has at least one DRAWN descendant
 * (`info` is the fold's per-node info, which holds an entry only for nodes with a drawn
 * descendant). Folding exactly these leaves the roots plus every node outside the forest.
 * Each root is listed once.
 */
export function topLevelFold<N extends { id: string }>(
  forest: readonly HierarchyNode<N>[],
  info: ReadonlyMap<string, unknown>,
): string[] {
  return [...new Set(forest.map((root) => root.node.id))].filter((id) => info.has(id));
}
