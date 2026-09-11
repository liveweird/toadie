import type { CSSProperties } from "react";
import type { EntityGraph, EntityGraphNode } from "../api/entities";
import type { HierarchyNode } from "./hierarchy";

/**
 * Pure shaping for the Entity graph/hierarchy pages (Port migration phase 3, v1.25.0; Phase 4
 * ownership edges, v1.26.0) — the entity-graph counterparts of `utils/graphLayout.ts`'s
 * `filterGraph` and `utils/hierarchy.ts`'s `buildHierarchy`. The wire graph
 * (`GET /api/v1/entities/graph`) already carries only shown nodes and edges whose both ends
 * are shown (the catalog graph's rule, enforced server-side — there is no MISSING-node concept
 * for entities), so nothing here prunes nodes.
 */

/** The Phase 4 ownership edge's dotted-gray style (drawn by `pages/EntityGraph.tsx` for the
 *  `$team` field) — never a `hierarchy` edge (the server never flags one both ways) and folds
 *  like any other relation (`toFoldable` below carries `ownership` edges through unchanged). */
export const OWNERSHIP_EDGE_STYLE: CSSProperties = {
  strokeDasharray: "2 3",
  stroke: "var(--mantine-color-gray-6)",
};

/** Drops edges of relation ids the caller disabled (a relation CHIP off) — nodes stay: the
 *  server already sent exactly the entities the blueprint/search filters select, and a
 *  relation chip governs relations, not which entities exist. */
export function filterEntityGraph(graph: EntityGraph, disabledRelations: ReadonlySet<string>): EntityGraph {
  return { nodes: graph.nodes, edges: graph.edges.filter((e) => !disabledRelations.has(e.relation)) };
}

/** The distinct relation identifiers present in the graph, sorted — the relation chips'
 *  source (assumption A3: the payload carries no relation titles, so chips key on the id). */
export function relationsOf(graph: EntityGraph): string[] {
  return [...new Set(graph.edges.map((e) => e.relation))].sort((a, b) => a.localeCompare(b));
}

/** The entity graph's edges as `utils/graphFold.ts#foldGraph`'s generic shape — `relation`
 *  mapped onto `field`, so the fold's merge key and info stay byte-identical to the catalog
 *  graph's. */
export function toFoldable(graph: EntityGraph): { nodes: EntityGraphNode[]; edges: { sourceId: string; targetId: string; field: string }[] } {
  return {
    nodes: graph.nodes,
    edges: graph.edges.map((e) => ({ sourceId: e.sourceId, targetId: e.targetId, field: e.relation })),
  };
}

function compareEntityNodes(a: EntityGraphNode, b: EntityGraphNode): number {
  return (
    a.blueprint.localeCompare(b.blueprint) ||
    a.title.localeCompare(b.title) ||
    a.id.localeCompare(b.id)
  );
}

/**
 * Builds the containment forest a blueprint's `hierarchyRelation` describes: an entity's
 * parent is the target of the FIRST shown `hierarchy` edge in payload order (a self-edge is
 * ignored, and assumption A2 — a stale array value on a now-single relation can still surface
 * several hierarchy edges — is resolved by taking that first one); an entity with no shown
 * hierarchy edge is a root. Cycles (storable, since the relation is just data) break exactly
 * like `utils/hierarchy.ts#buildHierarchy`: promote the sorted-first node of the unreached
 * island to a root. Sort order is blueprint → title → id, since entities carry no kind tier.
 */
export function buildEntityHierarchy(graph: EntityGraph): HierarchyNode<EntityGraphNode>[] {
  const nodesById = new Map(graph.nodes.map((n) => [n.id, n]));

  const parentOf = new Map<string, string>();
  for (const edge of graph.edges) {
    // An ownership edge is never a parent link, even if it were somehow also flagged
    // `hierarchy` (the server never sends both) — ownership never nests.
    if (edge.ownership) continue;
    if (!edge.hierarchy) continue;
    if (edge.sourceId === edge.targetId) continue;
    if (parentOf.has(edge.sourceId)) continue;
    if (!nodesById.has(edge.targetId)) continue;
    parentOf.set(edge.sourceId, edge.targetId);
  }

  const childrenOf = new Map<string, EntityGraphNode[]>();
  for (const [childId, parentId] of parentOf) {
    const child = nodesById.get(childId);
    if (!child) continue;
    const siblings = childrenOf.get(parentId) ?? [];
    siblings.push(child);
    childrenOf.set(parentId, siblings);
  }

  const build = (node: EntityGraphNode, path: Set<string>): HierarchyNode<EntityGraphNode> => {
    const nested = new Set(path).add(node.id);
    const children = (childrenOf.get(node.id) ?? [])
      .filter((child) => !nested.has(child.id))
      .sort(compareEntityNodes)
      .map((child) => build(child, nested));
    return { node, children };
  };

  const placed = new Set<string>();
  const collectIds = (tree: HierarchyNode<EntityGraphNode>) => {
    placed.add(tree.node.id);
    tree.children.forEach(collectIds);
  };

  const roots = graph.nodes
    .filter((node) => !parentOf.has(node.id))
    .sort(compareEntityNodes)
    .map((node) => {
      const tree = build(node, new Set());
      collectIds(tree);
      return tree;
    });

  // Cycle islands: every parent chain loops, so nothing above reached them — promote the
  // sorted-first unplaced node to a root and keep going until everything renders once.
  let orphans = graph.nodes.filter((node) => !placed.has(node.id)).sort(compareEntityNodes);
  while (orphans.length > 0) {
    const tree = build(orphans[0], new Set());
    collectIds(tree);
    roots.push(tree);
    orphans = orphans.filter((node) => !placed.has(node.id));
  }

  return roots;
}
