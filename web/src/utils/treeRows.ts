import type { HierarchyNode } from "./hierarchy";

/**
 * The two Hierarchy pages' shared tree shaping. A forest is flattened into one ordered row
 * list (what is currently VISIBLE, folds applied) so the page can window it: the nested
 * recursive renderer these pages used to have cannot be virtualized, a flat list can.
 *
 * Collapse state is keyed by PATH, not node id — a User under two Groups folds independently
 * — and the path chain here is the same one the recursive renderer built, so persisted pins,
 * `branchKeys` and the Collapse-all set mean exactly what they always did.
 */
export function pathKey(path: string, node: { id: string }): string {
  return `${path}/${node.id}`;
}

/** The keys of every branch (a node with children) under [items] — the Collapse-all set. */
export function branchKeys<N extends { id: string }>(items: HierarchyNode<N>[], path: string, into: string[] = []): string[] {
  for (const item of items) {
    const key = pathKey(path, item.node);
    if (item.children.length > 0) {
      into.push(key);
      branchKeys(item.children, key, into);
    }
  }
  return into;
}

export interface TreeRow<N extends { id: string }> {
  item: HierarchyNode<N>;
  /** The path of this row's PARENT — what the recursive renderer passed down. */
  path: string;
  /** This row's collapse key and React key: `<path>/<id>`. */
  key: string;
  depth: number;
  hasChildren: boolean;
  collapsed: boolean;
}

/**
 * Depth-first rows of [roots] hanging at [basePath] (the pinned subtree's parent path, or
 * ""), skipping the descendants of every collapsed row. A node placed under two parents
 * yields two rows with two different keys.
 */
export function flattenTree<N extends { id: string }>(
  roots: HierarchyNode<N>[],
  collapsed: ReadonlySet<string>,
  basePath: string,
): TreeRow<N>[] {
  const rows: TreeRow<N>[] = [];
  const walk = (items: HierarchyNode<N>[], path: string, depth: number) => {
    for (const item of items) {
      const key = pathKey(path, item.node);
      const isCollapsed = collapsed.has(key);
      const hasChildren = item.children.length > 0;
      rows.push({ item, path, key, depth, hasChildren, collapsed: isCollapsed });
      if (hasChildren && !isCollapsed) walk(item.children, key, depth + 1);
    }
  };
  walk(roots, basePath, 0);
  return rows;
}
