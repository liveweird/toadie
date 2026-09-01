import type { CatalogGraph, GraphNode } from "../api/catalogFiles";
import type { HierarchyNode } from "./hierarchy";

/**
 * The Graph page's FOLD: a client-side rewrite of the graph the server sent, driven by the
 * nodes the user COLLAPSED. Containment is the Hierarchy's — the forest comes from
 * `buildHierarchy` over the FULL payload, never from the relation chips, so a System is
 * collapsible with the "Part of system" chip off and the two views can never disagree about
 * what belongs to what.
 *
 * Collapsing a node HIDES every containment descendant, and the collapsed node STANDS IN for
 * them: a relation that touched a hidden node is redrawn from (or to) its collapsed ancestor
 * — a FOLDED edge — so nothing the hidden nodes said about the rest of the world is lost.
 * Several hidden relations of one field between the same pair merge into one edge that
 * counts them.
 */

type GraphEdge = CatalogGraph["edges"][number];

interface FoldedEdge extends GraphEdge {
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

export interface FoldedGraph {
  nodes: GraphNode[];
  edges: FoldedEdge[];
  /** Keyed by node id; only nodes with at least one drawn descendant have an entry. */
  info: Map<string, FoldInfo>;
}

/**
 * Every placement of every forest node, as the TOPMOST collapsed ancestor on that path (null
 * = the path is open). A collapsed node under a collapsed node is hidden by the OUTER one and
 * keeps its own flag, so re-expanding the outer brings it back still collapsed.
 */
function placements(forest: HierarchyNode[], collapsed: ReadonlySet<string>): Map<string, (string | null)[]> {
  const byId = new Map<string, (string | null)[]>();
  const walk = (items: HierarchyNode[], rep: string | null) => {
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
function descendantCounts(forest: HierarchyNode[], drawn: ReadonlySet<string>): Map<string, number> {
  const counts = new Map<string, number>();
  const visit = (item: HierarchyNode): Set<string> => {
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
 * [forest] (`buildHierarchy` of the UNFILTERED payload; chips first, fold second, so a MISSING
 * child a chip pruned is neither counted nor hidden).
 *
 * A node outside the forest (a virtual node reachable only through owner/dependsOn) is always
 * visible. A forest node is visible while ANY of its placements is open — a User under two
 * Groups stays while one Group is expanded — and, once hidden, is represented by the topmost
 * collapsed ancestor of EVERY closed placement (both Groups collapsed → both stand in).
 * An edge into one of its own end's stand-ins is INTERNAL and dropped: the containment edge
 * itself, any relation entirely inside one collapsed subtree, and a hidden node's relation to
 * one of the several ancestors representing it (that User's membership in each Group — never
 * redrawn as one Group being a member of the other).
 */
export function foldGraph(
  graph: CatalogGraph,
  forest: HierarchyNode[],
  collapsed: ReadonlySet<string>,
): FoldedGraph {
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
