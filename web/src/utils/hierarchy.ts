import type { CatalogGraph, GraphNode } from "../api/catalogFiles";

/**
 * The Hierarchy page's pure shaping: the graph endpoint's nodes/edges become a forest of
 * containment trees. Containment relations only — ownership, dependencies, and API
 * provide/consume edges never nest anything here (the Graph page draws those).
 *
 * Placement rules (user-chosen):
 * - MOST SPECIFIC single parent: an entity nests in ONE place, picked by field priority
 *   (subcomponentOf beats system — a subcomponent renders under its parent component,
 *   not beside it under the system). The exception is group membership: memberOf/members
 *   are multi-valued, so a User (or a missing member) appears under EVERY containing Group.
 * - Virtual (MISSING/EXTERNAL) nodes render as placeholders ONLY when they participate in
 *   a containment relation — a deleted System keeps its ex-children nested under a dashed
 *   placeholder, while a virtual node reachable only through owner/dependsOn edges stays
 *   out of the tree entirely.
 * - Cycles (storable: A subcomponentOf B, B subcomponentOf A) are broken by promoting the
 *   first node of the unreached island to a root; the in-path repeat edge is dropped.
 */
export interface HierarchyNode {
  node: GraphNode;
  children: HierarchyNode[];
}

type GraphEdge = CatalogGraph["edges"][number];

/** Child→parent fields, most specific first — index order IS the placement priority. */
const SINGLE_PARENT_PRIORITY = [
  "spec.subcomponentOf",
  "spec.system",
  "spec.subdomainOf",
  "spec.domain",
  "spec.parent",
] as const;

const KIND_ORDER = ["domain", "system", "component", "api", "resource", "group", "user"];

function kindRank(kind: string): number {
  const rank = KIND_ORDER.indexOf(kind);
  return rank === -1 ? KIND_ORDER.length : rank;
}

function compareNodes(a: GraphNode, b: GraphNode): number {
  return kindRank(a.kind) - kindRank(b.kind) || a.name.localeCompare(b.name) || a.id.localeCompare(b.id);
}

/** child id → (field → parent id) for the single-parent fields (`spec.children` inverted onto `spec.parent`). */
function singleParentCandidates(edges: GraphEdge[]): Map<string, Map<string, string>> {
  const byChild = new Map<string, Map<string, string>>();
  const record = (childId: string, field: string, parentId: string) => {
    if (childId === parentId) return;
    const fields = byChild.get(childId) ?? new Map<string, string>();
    fields.set(field, parentId);
    byChild.set(childId, fields);
  };
  for (const edge of edges) {
    if ((SINGLE_PARENT_PRIORITY as readonly string[]).includes(edge.field)) {
      record(edge.sourceId, edge.field, edge.targetId);
    } else if (edge.field === "spec.children") {
      // The parent-declared side of the parent/children pair — same relation, inverted.
      record(edge.targetId, "spec.parent", edge.sourceId);
    }
  }
  return byChild;
}

/** child id → parent ids for the multi-valued membership pair (memberOf ∪ inverted members, deduped). */
function membershipParents(edges: GraphEdge[]): Map<string, Set<string>> {
  const byChild = new Map<string, Set<string>>();
  const record = (childId: string, parentId: string) => {
    if (childId === parentId) return;
    const parents = byChild.get(childId) ?? new Set<string>();
    parents.add(parentId);
    byChild.set(childId, parents);
  };
  for (const edge of edges) {
    if (edge.field === "spec.memberOf") record(edge.sourceId, edge.targetId);
    else if (edge.field === "spec.members") record(edge.targetId, edge.sourceId);
  }
  return byChild;
}

export function buildHierarchy(graph: CatalogGraph): HierarchyNode[] {
  const nodesById = new Map(graph.nodes.map((node) => [node.id, node]));

  // Resolve each node's parent set: membership wins (multi), else the priority pick.
  const singles = singleParentCandidates(graph.edges);
  const memberships = membershipParents(graph.edges);
  const parentsOf = new Map<string, string[]>();
  const childrenOf = new Map<string, GraphNode[]>();
  const inContainment = new Set<string>();
  for (const node of graph.nodes) {
    let parents: string[] = [];
    const membership = memberships.get(node.id);
    if (membership && membership.size > 0) {
      parents = [...membership];
    } else {
      const fields = singles.get(node.id);
      const pick = fields && SINGLE_PARENT_PRIORITY.find((field) => fields.has(field));
      if (fields && pick) parents = [fields.get(pick) as string];
    }
    const known = parents.filter((parentId) => nodesById.has(parentId));
    if (known.length > 0) {
      parentsOf.set(node.id, known);
      inContainment.add(node.id);
      for (const parentId of known) {
        inContainment.add(parentId);
        const siblings = childrenOf.get(parentId) ?? [];
        siblings.push(node);
        childrenOf.set(parentId, siblings);
      }
    }
  }

  // A virtual node earns a place only through containment; stored nodes always render.
  const included = graph.nodes.filter((node) => node.status === "STORED" || inContainment.has(node.id));
  const includedIds = new Set(included.map((node) => node.id));

  const build = (node: GraphNode, path: Set<string>): HierarchyNode => {
    const nested = new Set(path).add(node.id);
    const children = (childrenOf.get(node.id) ?? [])
      .filter((child) => includedIds.has(child.id) && !nested.has(child.id))
      .sort(compareNodes)
      .map((child) => build(child, nested));
    return { node, children };
  };
  const placed = new Set<string>();
  const collectIds = (tree: HierarchyNode) => {
    placed.add(tree.node.id);
    tree.children.forEach(collectIds);
  };

  const roots = included
    .filter((node) => !parentsOf.has(node.id))
    .sort(compareNodes)
    .map((node) => {
      const tree = build(node, new Set());
      collectIds(tree);
      return tree;
    });

  // Cycle islands: every parent chain loops, so nothing above reached them — promote the
  // sorted-first unplaced node to a root and keep going until everything renders once.
  let orphans = included.filter((node) => !placed.has(node.id)).sort(compareNodes);
  while (orphans.length > 0) {
    const tree = build(orphans[0], new Set());
    collectIds(tree);
    roots.push(tree);
    orphans = orphans.filter((node) => !placed.has(node.id));
  }

  return roots;
}
