import type { HierarchyNode } from "./hierarchy";

/**
 * The Graph/Entity-graph pages' shared FOLD: a client-side rewrite of the graph the server
 * sent, driven by the nodes the user COLLAPSED. Containment is the owning page's forest — the
 * catalog Graph page passes `buildHierarchy`'s result, the Entity graph page
 * (`utils/entityGraph.ts`, v1.25.0) passes `buildEntityHierarchy`'s — so a node is collapsible
 * with any relation chip off and the two views of one graph can never disagree about what
 * belongs to what.
 *
 * Collapsing a node HIDES every containment descendant, and the collapsed node STANDS IN for
 * them: a relation that touched a hidden node is redrawn from (or to) its collapsed ancestor
 * — a FOLDED edge — so nothing the hidden nodes said about the rest of the world is lost.
 * Several hidden relations of one field between the same pair merge into one edge that
 * counts them.
 *
 * Generic over the node payload `N` (default `GraphNode` via the catalog `CatalogGraph`
 * shape) and over the edge shape, which needs only `sourceId`/`targetId`/`field` — the entity
 * graph maps its wire `relation` onto `field` before calling this (`utils/entityGraph.ts`'s
 * `toFoldable`), so the merge key and the fold info stay byte-identical between both pages.
 */

interface FoldableEdge {
  sourceId: string;
  targetId: string;
  field: string;
}
interface FoldableGraph<N> {
  nodes: N[];
  edges: FoldableEdge[];
}

interface FoldedEdge extends FoldableEdge {
  /** Underlying relations this edge stands for (1 = an ordinary edge). */
  relations: number;
  /** How many of them had a hidden end — more than none draws the edge dashed. */
  folded: number;
}

/** What the node face needs for a node with something to fold. */
interface FoldInfo {
  collapsed: boolean;
  /** Its DRAWN containment descendants — the count the collapsed pill shows. */
  descendants: number;
}

export interface FoldedGraph<N> {
  nodes: N[];
  edges: FoldedEdge[];
  /** Keyed by node id; only nodes with at least one drawn descendant have an entry. */
  info: Map<string, FoldInfo>;
}

/**
 * Every placement of every forest node, as the TOPMOST collapsed ancestor on that path (null
 * = the path is open). A collapsed node under a collapsed node is hidden by the OUTER one and
 * keeps its own flag, so re-expanding the outer brings it back still collapsed.
 */
function placements<N extends { id: string }>(
  forest: HierarchyNode<N>[],
  collapsed: ReadonlySet<string>,
): Map<string, (string | null)[]> {
  const byId = new Map<string, (string | null)[]>();
  const walk = (items: HierarchyNode<N>[], rep: string | null) => {
    for (const item of items) {
      const id = item.node.id;
      const reps = byId.get(id);
      if (reps) reps.push(rep);
      else byId.set(id, [rep]);
      walk(item.children, rep ?? (collapsed.has(id) ? id : null));
    }
  };
  walk(forest, null);
  return byId;
}

/** id → the ids drawn in its subtree (the descendants the pill counts), over the whole forest. */
function descendantCounts<N extends { id: string }>(
  forest: HierarchyNode<N>[],
  drawn: ReadonlySet<string>,
): Map<string, number> {
  const counts = new Map<string, number>();
  const visit = (item: HierarchyNode<N>): Set<string> => {
    const below = new Set<string>();
    for (const child of item.children) {
      if (drawn.has(child.node.id)) below.add(child.node.id);
      for (const id of visit(child)) below.add(id);
    }
    // Membership can place one node under several Groups, so a subtree's ids are a SET —
    // a node is never counted twice under one ancestor. The max across placements is moot:
    // every placement of a node carries the same children, so the sets are equal.
    if (below.size > 0) counts.set(item.node.id, below.size);
    return below;
  };
  forest.forEach(visit);
  return counts;
}

/**
 * Folds [graph] — the relation-chip-filtered graph — by [collapsed], with containment from
 * [forest] (the UNFILTERED payload's forest; chips first, fold second, so a MISSING/hidden
 * child a chip pruned is neither counted nor hidden).
 *
 * A node outside the forest is always visible. A forest node is visible while ANY of its
 * placements is open — a node under two parents stays while one is expanded — and, once
 * hidden, is represented by the topmost collapsed ancestor of EVERY closed placement (both
 * parents collapsed → both stand in). An edge into one of its own end's stand-ins is INTERNAL
 * and dropped: the containment edge itself, any relation entirely inside one collapsed
 * subtree, and a hidden node's relation to one of the several ancestors representing it
 * (never redrawn as one ancestor being related to the other).
 */
export function foldGraph<N extends { id: string }>(
  graph: FoldableGraph<N>,
  forest: HierarchyNode<N>[],
  collapsed: ReadonlySet<string>,
): FoldedGraph<N> {
  const drawn = new Set(graph.nodes.map((n) => n.id));
  const placed = placements(forest, collapsed);

  const representatives = (id: string): string[] => {
    const reps = placed.get(id);
    if (!reps || reps.includes(null)) return [id];
    return [...new Set(reps as string[])];
  };
  const visible = (id: string) => representatives(id)[0] === id;

  const nodes = graph.nodes.filter((n) => visible(n.id));

  const info = new Map<string, FoldInfo>();
  for (const [id, descendants] of descendantCounts(forest, drawn)) {
    if (drawn.has(id)) info.set(id, { collapsed: collapsed.has(id), descendants });
  }

  const merged = new Map<string, FoldedEdge>();
  for (const edge of graph.edges) {
    const sourceReps = representatives(edge.sourceId);
    const targetReps = representatives(edge.targetId);
    for (const sourceId of sourceReps) {
      for (const targetId of targetReps) {
        // Internal: the chosen end IS (or stands in for) the other end — covers a plain
        // self-loop, since a visible node is its own only representative.
        if (sourceReps.includes(targetId) || targetReps.includes(sourceId)) continue;
        const key = `${sourceId}|${targetId}|${edge.field}`;
        const moved = sourceId !== edge.sourceId || targetId !== edge.targetId ? 1 : 0;
        const existing = merged.get(key);
        if (existing) {
          existing.relations += 1;
          existing.folded += moved;
        } else {
          merged.set(key, { sourceId, targetId, field: edge.field, relations: 1, folded: moved });
        }
      }
    }
  }

  return { nodes, edges: [...merged.values()], info };
}
