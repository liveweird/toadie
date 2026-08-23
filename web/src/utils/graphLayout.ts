import dagre from "@dagrejs/dagre";
import type { Edge, Node } from "@xyflow/react";
import type { CatalogGraph, GraphNode } from "../api/catalogFiles";

/**
 * Pure graph-shaping for the Render page: relation-family filtering and the dagre
 * left-to-right layout that turns the API's CatalogGraph into React Flow nodes/edges.
 */

export const RELATION_FAMILIES = ["dependsOn", "subcomponentOf", "apis", "owner", "system"] as const;
export type RelationFamily = (typeof RELATION_FAMILIES)[number];

const FIELD_FAMILY: Record<string, RelationFamily> = {
  "spec.dependsOn": "dependsOn",
  "spec.dependencyOf": "dependsOn",
  "spec.subcomponentOf": "subcomponentOf",
  "spec.providesApis": "apis",
  "spec.consumesApis": "apis",
  "spec.owner": "owner",
  "spec.system": "system",
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

/** dagre left-to-right auto-layout → React Flow's nodes/edges. */
export function layoutGraph(graph: CatalogGraph): { nodes: LaidOutNode[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: "LR", nodesep: 24, ranksep: 90 });
  g.setDefaultEdgeLabel(() => ({}));
  for (const n of graph.nodes) g.setNode(n.id, { width: GRAPH_NODE_WIDTH, height: GRAPH_NODE_HEIGHT });
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
