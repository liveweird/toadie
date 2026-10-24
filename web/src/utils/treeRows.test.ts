import { describe, expect, test } from "vitest";
import type { HierarchyNode } from "./hierarchy";
import { branchKeys, flattenTree, pathKey } from "./treeRows";

interface N {
  id: string;
}

function tree(id: string, ...children: HierarchyNode<N>[]): HierarchyNode<N> {
  return { node: { id }, children };
}

// a ⊃ (b ⊃ (d, e), c) ; f
const forest = [tree("a", tree("b", tree("d"), tree("e")), tree("c")), tree("f")];

describe("flattenTree", () => {
  test("lists rows depth-first with their depth and branch flag", () => {
    const rows = flattenTree(forest, new Set(), "");
    expect(rows.map((r) => [r.item.node.id, r.depth, r.hasChildren])).toEqual([
      ["a", 0, true],
      ["b", 1, true],
      ["d", 2, false],
      ["e", 2, false],
      ["c", 1, false],
      ["f", 0, false],
    ]);
  });

  test("keys are the nested renderer's pathKey chain", () => {
    const rows = flattenTree(forest, new Set(), "");
    expect(rows.map((r) => r.key)).toEqual(["/a", "/a/b", "/a/b/d", "/a/b/e", "/a/c", "/f"]);
    expect(rows[2].path).toBe("/a/b");
    expect(rows[2].key).toBe(pathKey(rows[2].path, rows[2].item.node));
  });

  test("a collapsed branch hides its descendants but stays a row", () => {
    const rows = flattenTree(forest, new Set(["/a/b"]), "");
    expect(rows.map((r) => r.item.node.id)).toEqual(["a", "b", "c", "f"]);
    expect(rows[1].collapsed).toBe(true);
    expect(flattenTree(forest, new Set(["/a"]), "").map((r) => r.item.node.id)).toEqual(["a", "f"]);
  });

  test("a pinned subtree keeps the keys it has unpinned (basePath)", () => {
    const pinned = forest[0].children[0];
    const rows = flattenTree([pinned], new Set(), "/a");
    expect(rows.map((r) => r.key)).toEqual(["/a/b", "/a/b/d", "/a/b/e"]);
    expect(flattenTree([pinned], new Set(["/a/b"]), "/a").map((r) => r.key)).toEqual(["/a/b"]);
    expect(rows[0].depth).toBe(0);
  });

  test("a node under two groups yields two rows with distinct keys", () => {
    const shared = tree("u");
    const rows = flattenTree([tree("g1", shared), tree("g2", shared)], new Set(["/g1/u"]), "");
    expect(rows.map((r) => r.key)).toEqual(["/g1", "/g1/u", "/g2", "/g2/u"]);
  });

  test("empty forest gives no rows", () => {
    expect(flattenTree<N>([], new Set(), "")).toEqual([]);
  });
});

describe("branchKeys", () => {
  test("lists every node with children under the base path", () => {
    expect(branchKeys(forest, "")).toEqual(["/a", "/a/b"]);
    expect(branchKeys([forest[0].children[0]], "/a")).toEqual(["/a/b"]);
  });
});
