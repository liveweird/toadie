import dagre from "@dagrejs/dagre";
import type { CSSProperties } from "react";
import type { Edge, Node } from "@xyflow/react";
import type { CatalogGraph, GraphNode } from "../api/catalogFiles";

/**
 * Pure graph-shaping shared by the Render page and the Entity graph page (v1.25.0): relation
 * filtering, the dagre left-to-right layout, and cluster frames — generalized over the node
 * payload type `N` via a `keyOf` accessor, so the Backstage catalog graph and the Port entity
 * graph share one implementation while each keeps its own clustering key (namespace vs.
 * blueprint) and node face component.
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
 * Drops edges of disabled relation families, then prunes MISSING nodes no remaining edge
 * touches — a missing entity is known ONLY through a reference, so with that reference hidden
 * there is nothing left to draw. Stored nodes always stay: the server already sent exactly the
 * entities the filters select, and a relation chip governs relations, not which entities exist.
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

/** The fold affordance a node with drawn descendants carries (see utils/graphFold.ts). */
export interface NodeFold {
  collapsed: boolean;
  /** Its drawn containment descendants — the hidden count the collapsed pill shows. */
  descendants: number;
  disabled?: boolean;
  onToggle: () => void;
}

/** The node data carried into React Flow (and the custom node component), generic over the
 *  API payload `N` (`GraphNode` for the catalog graph, `EntityGraphNode` for the entity graph). */
type LaidOutNodeData<N> = { apiNode: N; fold?: NodeFold } & Record<string, unknown>;
export type LaidOutNode<N = GraphNode> = Node<LaidOutNodeData<N>>;

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
};

/**
 * The stacked-cards look of a COLLAPSED face: a second card peeking out behind the node, in
 * the status's own border colour, so "several things" reads at any zoom before the count
 * pill is legible. Keyed by status like [STATUS_STYLE] and consumed by the legend too.
 */
export const COLLAPSED_FACE_STYLE: Record<string, CSSProperties> = {
  STORED: {
    boxShadow: "4px 4px 0 -1.5px var(--mantine-color-body), 4px 4px 0 0 var(--mantine-color-toadie-7)",
  },
  MISSING: {
    boxShadow: "4px 4px 0 -1.5px var(--mantine-color-body), 4px 4px 0 0 var(--mantine-color-red-6)",
  },
};

/** A FOLDED edge — one standing in for a hidden node's relation — draws dashed. */
export const FOLDED_EDGE_STYLE: CSSProperties = { strokeDasharray: "6 4" };

/** What `layoutGraph` needs of an edge: source/target/field, plus the fold counters when folded. */
export interface LayoutEdge {
  sourceId: string;
  targetId: string;
  field: string;
  relations?: number;
  folded?: number;
}
type LayoutInput<N> = { nodes: N[]; edges: LayoutEdge[] };

/** An edge's label: the field without `spec.` (a no-op on fields that never carry the prefix,
 *  e.g. the entity graph's relation ids), and `×n` when it merges several relations. */
export function edgeLabel(edge: LayoutEdge): string {
  const base = edge.field.replace("spec.", "");
  return (edge.relations ?? 1) > 1 ? `${base} ×${edge.relations}` : base;
}

/** How a graph clusters its nodes for layout and framing: the grouping key per node, and the
 *  React Flow node `type` the cluster's members render as. */
export interface ClusterAccessor<N> {
  keyOf: (node: N) => string;
  nodeType: string;
}

/** The Render page's clustering: one cluster per namespace, rendered as `catalog` nodes. */
export const NAMESPACE_CLUSTER: ClusterAccessor<GraphNode> = {
  keyOf: (node) => node.namespace,
  nodeType: "catalog",
};

function clusterKeysOf<N>(nodes: readonly N[], keyOf: (node: N) => string): string[] {
  return [...new Set(nodes.map(keyOf))].sort((a, b) => a.localeCompare(b));
}

/**
 * dagre left-to-right auto-layout → React Flow's nodes/edges, generic over the node payload
 * `N` via [cluster] (defaulting to [NAMESPACE_CLUSTER] so every existing catalog-graph call
 * site keeps working unchanged).
 *
 * With two or more cluster keys on the canvas the layout runs as a COMPOUND dagre graph, one
 * cluster per key, so a cluster's entities come out clumped instead of scattered through the
 * ranks (which is what makes [clusterFrames] worth drawing at all). A single cluster groups
 * nothing — clustering costs layout width for no information.
 *
 * Two dagre details are load-bearing. The graph must be built `{ compound: true }` (`setParent`
 * throws otherwise), and each cluster needs an explicit `setNode(id, {})`: `setParent` does
 * auto-create the node, but with the default `undefined` label, and dagre's `updateInputGraph`
 * skips label-less nodes. Never set `rankdir` on a cluster — that switches dagre onto its
 * `recursiveClusterLayout` path, which is a different and far less exercised algorithm.
 */
export function layoutGraph<N extends { id: string } = GraphNode>(
  graph: LayoutInput<N>,
  cluster: ClusterAccessor<N> = NAMESPACE_CLUSTER as unknown as ClusterAccessor<N>,
): { nodes: LaidOutNode<N>[]; edges: Edge[] } {
  const keys = clusterKeysOf(graph.nodes, cluster.keyOf);
  const grouped = keys.length > 1;
  const g = new dagre.graphlib.Graph({ compound: grouped });
  // Clustering needs room for the frame chrome between neighbouring clusters: dagre's
  // cluster gap comes out at nodesep + 40, and a frame costs FRAME_PADDING below plus
  // FRAME_PADDING + FRAME_HEADER above, so 36 leaves the boxes visibly apart without
  // stretching the within-cluster spacing much (24 would leave them touching).
  g.setGraph({ rankdir: "LR", nodesep: grouped ? 36 : 24, ranksep: 90 });
  g.setDefaultEdgeLabel(() => ({}));
  // `cluster:` cannot collide with either id grammar (`kind:namespace/name`, `blueprint|identifier`).
  if (grouped) for (const key of keys) g.setNode(`cluster:${key}`, {});
  for (const n of graph.nodes) {
    g.setNode(n.id, { width: GRAPH_NODE_WIDTH, height: GRAPH_NODE_HEIGHT });
    if (grouped) g.setParent(n.id, `cluster:${cluster.keyOf(n)}`);
  }
  for (const e of graph.edges) g.setEdge(e.sourceId, e.targetId);
  dagre.layout(g);

  const nodes: LaidOutNode<N>[] = graph.nodes.map((n) => {
    const pos = g.node(n.id);
    return {
      id: n.id,
      type: cluster.nodeType,
      position: { x: pos.x - GRAPH_NODE_WIDTH / 2, y: pos.y - GRAPH_NODE_HEIGHT / 2 },
      data: { apiNode: n },
    };
  });
  // Ids stay `source->target:field` — unique, since the fold merges on exactly that key.
  const edges: Edge[] = graph.edges.map((e) => ({
    id: `${e.sourceId}->${e.targetId}:${e.field}`,
    source: e.sourceId,
    target: e.targetId,
    label: edgeLabel(e),
    ...((e.folded ?? 0) > 0 ? { style: FOLDED_EDGE_STYLE } : {}),
  }));
  return { nodes, edges };
}

/** The manual-mode position overlay: dragged positions by node id. */
export type GraphPositions = Record<string, { x: number; y: number }>;

/**
 * Manual mode's overlay over the dagre output: nodes the user dragged take their stored
 * position, everything else keeps its dagre spot (so new entities appear laid out), and
 * stored positions for ids not in the graph are simply not consulted — never pruned.
 */
export function applyManualPositions<N>(nodes: LaidOutNode<N>[], positions: GraphPositions): LaidOutNode<N>[] {
  return nodes.map((n) => {
    const p = positions[n.id];
    return p ? { ...n, position: { x: p.x, y: p.y } } : n;
  });
}

/** One cluster's frame: a plain rectangle in canvas coordinates, plus its display label. */
export interface ClusterFrame {
  key: string;
  label: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

/** @deprecated shape kept only for [namespaceFrames]'s existing callers/tests. */
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
/** The strip above the members that carries the cluster's label. */
const FRAME_HEADER = 20;

/**
 * The frames to draw behind [nodes] — one per cluster key, sized to the members' CURRENT
 * positions. Deriving them from live positions rather than from dagre's own cluster bounds is
 * what makes Manual mode work: drag a node and its frame stretches to keep containing it,
 * because a node's cluster key is DATA and no amount of dragging can change it (so frames may
 * legitimately end up overlapping). The frame's `label` defaults to the grouping key itself —
 * a caller wanting a different display label (e.g. a blueprint's title rather than its
 * identifier) maps over the result.
 *
 * Fewer than two clusters means no frames at all: a lone frame around the whole canvas states
 * nothing. Node status/kind is irrelevant to clustering.
 */
export function clusterFrames<N>(
  nodes: readonly LaidOutNode<N>[],
  keyOf: (node: N) => string,
): ClusterFrame[] {
  const byKey = new Map<string, LaidOutNode<N>[]>();
  for (const n of nodes) {
    const key = keyOf(n.data.apiNode);
    const members = byKey.get(key);
    if (members) members.push(n);
    else byKey.set(key, [n]);
  }
  if (byKey.size < 2) return [];

  return [...byKey.entries()]
    .map(([key, members]) => {
      const left = Math.min(...members.map((n) => n.position.x));
      const top = Math.min(...members.map((n) => n.position.y));
      const right = Math.max(...members.map((n) => n.position.x)) + GRAPH_NODE_WIDTH;
      const bottom = Math.max(...members.map((n) => n.position.y)) + GRAPH_NODE_HEIGHT;
      return {
        key,
        label: key,
        x: left - FRAME_PADDING,
        y: top - FRAME_PADDING - FRAME_HEADER,
        width: right - left + FRAME_PADDING * 2,
        height: bottom - top + FRAME_PADDING * 2 + FRAME_HEADER,
      };
    })
    .sort((a, b) => a.label.localeCompare(b.label));
}

/** The Render page's original namespace-frame shape, kept as a thin wrapper over
 *  [clusterFrames] for its existing unit-test coverage; the page itself now renders
 *  [clusterFrames] directly through `components/ClusterFrames.tsx`. */
export function namespaceFrames(nodes: readonly LaidOutNode[]): NamespaceFrame[] {
  return clusterFrames(nodes, NAMESPACE_CLUSTER.keyOf).map((f) => ({
    namespace: f.label,
    x: f.x,
    y: f.y,
    width: f.width,
    height: f.height,
  }));
}
