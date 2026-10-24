import { describe, expect, test } from "vitest";
import { graphGate, topLevelFold } from "./graphGate";
import { LARGE_GRAPH_NODES } from "./graphLayout";
import type { HierarchyNode } from "./hierarchy";

describe("graphGate", () => {
  test("lays out up to the limit and asks above it", () => {
    expect(graphGate({ drawnNodes: 0, showAll: false })).toBe("layout");
    expect(graphGate({ drawnNodes: LARGE_GRAPH_NODES, showAll: false })).toBe("layout");
    expect(graphGate({ drawnNodes: LARGE_GRAPH_NODES + 1, showAll: false })).toBe("large");
    expect(graphGate({ drawnNodes: 5000, showAll: false })).toBe("large");
  });

  test("Show everything bypasses the gate at any size", () => {
    expect(graphGate({ drawnNodes: LARGE_GRAPH_NODES + 1, showAll: true })).toBe("layout");
    expect(graphGate({ drawnNodes: 5000, showAll: true })).toBe("layout");
  });
});

describe("topLevelFold", () => {
  const leaf = (id: string): HierarchyNode<{ id: string }> => ({ node: { id }, children: [] });
  const tree = (id: string, ...children: HierarchyNode<{ id: string }>[]): HierarchyNode<{ id: string }> => ({
    node: { id },
    children,
  });

  test("picks only the roots that have a drawn descendant", () => {
    const forest = [tree("sys-a", leaf("a1"), leaf("a2")), leaf("loner"), tree("sys-b", tree("sub", leaf("deep")))];
    // The fold's info holds an entry for every node with at least one drawn descendant.
    const info = new Map([["sys-a", {}], ["sys-b", {}], ["sub", {}]]);
    expect(topLevelFold(forest, info)).toEqual(["sys-a", "sys-b"]);
  });

  test("a root whose descendants are not drawn is left alone", () => {
    const forest = [tree("sys-a", leaf("a1")), tree("sys-b", leaf("b1"))];
    expect(topLevelFold(forest, new Map([["sys-b", {}]]))).toEqual(["sys-b"]);
  });

  test("with the roots filtered out, the topmost DRAWN level of each tree is folded instead", () => {
    // `root` is not drawn (no info entry of its own); its drawn children are the fold points,
    // and nothing below the first drawn node is listed.
    const forest = [tree("root", tree("mid-a", tree("deep", leaf("x"))), tree("mid-b", leaf("y")), leaf("lone"))];
    const info = new Map([["mid-a", {}], ["deep", {}], ["mid-b", {}]]);
    expect(topLevelFold(forest, info)).toEqual(["mid-a", "mid-b"]);
  });

  test("a node the forest places twice (group membership) is listed once", () => {
    const shared = tree("grp", leaf("u1"));
    expect(topLevelFold([shared, shared], new Map([["grp", {}]]))).toEqual(["grp"]);
  });

  test("an empty forest folds nothing", () => {
    expect(topLevelFold([], new Map())).toEqual([]);
  });
});
