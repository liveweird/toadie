import dagre from "@dagrejs/dagre";
import type { CSSProperties } from "react";
import type { Edge, Node } from "@xyflow/react";
import type { CatalogGraph, GraphNode } from "../api/catalogFiles";

/**
 * Pure graph-shaping for the Render page: relation-family filtering and the dagre
 * left-to-right layout that turns the API's CatalogGraph into React Flow nodes/edges.
 */

export const RELATION_FAMILIES = [
  "dependsOn",
  "subcomponentOf",
  "apis",
  "owner",
  "system",
  "domain",
  "membership",
] as const;
export type RelationFamily = (typeof RELATION_FAMILIES)[number];

const FIELD_FAMILY: Record<string, RelationFamily> = {
  "spec.dependsOn": "dependsOn",
  "spec.dependencyOf": "dependsOn",
  "spec.subcomponentOf": "subcomponentOf",
  "spec.providesApis": "apis",
  "spec.consumesApis": "apis",
  "spec.owner": "owner",
  "spec.system": "system",
  "spec.domain": "domain",
  "spec.subdomainOf": "domain",
  "spec.parent": "membership",
  "spec.children": "membership",
  "spec.members": "membership",
  "spec.memberOf": "membership",
};

/**
 * Drops edges of disabled relation families, then prunes virtual (MISSING/EXTERNAL) nodes no
 * remaining edge touches. Stored nodes always stay — the workspace itself is the render.
 */
export function filterGraph(graph: CatalogGraph, enabled: readonly RelationFamily[]): CatalogGraph {
  const enabledSet = new Set(enabled);
  const edges = graph.edges.filter((e) => {
    const family = FIELD_FAMILY[e.field];
    return family !== undefined && enabledSet.has(family);
  });
  const referenced = new Set(edges.flatMap((e) => [e.sourceId, e.targetId]));
  const nodes = graph.nodes.filter((n) => n.status === "STORED" || referenced.has(n.id));
  return { nodes, edges };
}

/** The node data carried into React Flow (and the custom node component). */
type CatalogNodeData = { apiNode: GraphNode } & Record<string, unknown>;
export type LaidOutNode = Node<CatalogNodeData>;

// Fixed footprint for layout; the custom node caps itself to the same box.
export const GRAPH_NODE_WIDTH = 200;
export const GRAPH_NODE_HEIGHT = 64;

// Status → border/background via Mantine CSS vars only, so light/dark both work untouched.
// Lives here (not in CatalogGraphNode) so the Graph page's legend can derive its swatches
// from the same borders the nodes draw — the legend cannot drift — without the component
// file exporting a non-component (the react-refresh rule).
export const STATUS_STYLE: Record<string, CSSProperties> = {
  STORED: {
    border: "1.5px solid var(--mantine-color-toadie-7)",
    background: "var(--mantine-color-body)",
  },
  MISSING: {
    border: "1.5px dashed var(--mantine-color-red-6)",
    background: "var(--mantine-color-body)",
  },
  EXTERNAL: {
    border: "1.5px dashed var(--mantine-color-gray-5)",
    background: "var(--mantine-color-default-hover)",
  },
};

/** The distinct namespaces a laid-out graph spans — the "should we group at all" input. */
function namespacesOf(nodes: readonly GraphNode[]): string[] {
  return [...new Set(nodes.map((n) => n.namespace))].sort((a, b) => a.localeCompare(b));
}

/**
 * dagre left-to-right auto-layout → React Flow's nodes/edges.
 *
 * With two or more namespaces on the canvas the layout runs as a COMPOUND dagre graph, one
 * cluster per namespace, so a namespace's entities come out clumped instead of scattered
 * through the ranks (which is what makes [namespaceFrames] worth drawing at all). A single
 * namespace groups nothing — clustering costs layout width for no information.
 *
 * Two dagre details are load-bearing. The graph must be built `{ compound: true }` (`setParent`
 * throws otherwise), and each cluster needs an explicit `setNode(id, {})`: `setParent` does
 * auto-create the node, but with the default `undefined` label, and dagre's `updateInputGraph`
 * skips label-less nodes. Never set `rankdir` on a cluster — that switches dagre onto its
 * `recursiveClusterLayout` path, which is a different and far less exercised algorithm.
 */
export function layoutGraph(graph: CatalogGraph): { nodes: LaidOutNode[]; edges: Edge[] } {
  const namespaces = namespacesOf(graph.nodes);
  const grouped = namespaces.length > 1;
  const g = new dagre.graphlib.Graph({ compound: grouped });
  // Clustering needs room for the frame chrome between neighbouring namespaces: dagre's
  // cluster gap comes out at nodesep + 40, and a frame costs FRAME_PADDING below plus
  // FRAME_PADDING + FRAME_HEADER above, so 36 leaves the boxes visibly apart without
  // stretching the within-cluster spacing much (24 would leave them touching).
  g.setGraph({ rankdir: "LR", nodesep: grouped ? 36 : 24, ranksep: 90 });
  g.setDefaultEdgeLabel(() => ({}));
  // `ns:` cannot collide with a node id, which is always `kind:namespace/name`.
  if (grouped) for (const ns of namespaces) g.setNode(`ns:${ns}`, {});
  for (const n of graph.nodes) {
    g.setNode(n.id, { width: GRAPH_NODE_WIDTH, height: GRAPH_NODE_HEIGHT });
    if (grouped) g.setParent(n.id, `ns:${n.namespace}`);
  }
  for (const e of graph.edges) g.setEdge(e.sourceId, e.targetId);
  dagre.layout(g);

  const nodes: LaidOutNode[] = graph.nodes.map((n) => {
    const pos = g.node(n.id);
    return {
      id: n.id,
      type: "catalog",
      position: { x: pos.x - GRAPH_NODE_WIDTH / 2, y: pos.y - GRAPH_NODE_HEIGHT / 2 },
      data: { apiNode: n },
    };
  });
  const edges: Edge[] = graph.edges.map((e) => ({
    id: `${e.sourceId}->${e.targetId}:${e.field}`,
    source: e.sourceId,
    target: e.targetId,
    label: e.field.replace("spec.", ""),
  }));
  return { nodes, edges };
}

/** The manual-mode position overlay: dragged positions by node id (`kind:namespace/name`). */
export type GraphPositions = Record<string, { x: number; y: number }>;

/**
 * Manual mode's overlay over the dagre output: nodes the user dragged take their stored
 * position, everything else keeps its dagre spot (so new entities appear laid out), and
 * stored positions for ids not in the graph are simply not consulted — never pruned.
 */
export function applyManualPositions(nodes: LaidOutNode[], positions: GraphPositions): LaidOutNode[] {
  return nodes.map((n) => {
    const p = positions[n.id];
    return p ? { ...n, position: { x: p.x, y: p.y } } : n;
  });
}

/** One namespace's frame: a plain rectangle in canvas coordinates. */
export interface NamespaceFrame {
  namespace: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

// Frame chrome. Kept tight on purpose: every pixel here has to be paid for by the layout's
// cluster gap above (see the nodesep note), or neighbouring frames overlap in Auto mode.
/** Breathing room between a frame's edge and its outermost members. */
const FRAME_PADDING = 16;
/** The strip above the members that carries the namespace name. */
const FRAME_HEADER = 20;

/**
 * The frames to draw behind [nodes] — one per namespace, sized to the members' CURRENT
 * positions. Deriving them from live positions rather than from dagre's own cluster bounds is
 * what makes Manual mode work: drag a node and its frame stretches to keep containing it,
 * because a node's namespace is DATA and no amount of dragging can change it (so frames may
 * legitimately end up overlapping).
 *
 * Fewer than two namespaces means no frames at all: a lone frame around the whole canvas
 * states nothing, and most workspaces use `default` and nothing else. Node status is
 * irrelevant — a MISSING or EXTERNAL node still names a namespace, and belongs in its box.
 */
export function namespaceFrames(nodes: readonly LaidOutNode[]): NamespaceFrame[] {
  const byNamespace = new Map<string, LaidOutNode[]>();
  for (const n of nodes) {
    const ns = n.data.apiNode.namespace;
    const members = byNamespace.get(ns);
    if (members) members.push(n);
    else byNamespace.set(ns, [n]);
  }
  if (byNamespace.size < 2) return [];

  return [...byNamespace.entries()]
    .map(([namespace, members]) => {
      const left = Math.min(...members.map((n) => n.position.x));
      const top = Math.min(...members.map((n) => n.position.y));
      const right = Math.max(...members.map((n) => n.position.x)) + GRAPH_NODE_WIDTH;
      const bottom = Math.max(...members.map((n) => n.position.y)) + GRAPH_NODE_HEIGHT;
      return {
        namespace,
        x: left - FRAME_PADDING,
        y: top - FRAME_PADDING - FRAME_HEADER,
        width: right - left + FRAME_PADDING * 2,
        height: bottom - top + FRAME_PADDING * 2 + FRAME_HEADER,
      };
    })
    .sort((a, b) => a.namespace.localeCompare(b.namespace));
}
