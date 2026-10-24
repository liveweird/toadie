import { describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import type { HierarchyNode } from "../utils/hierarchy";
import { LARGE_GRAPH_NODES } from "../utils/graphLayout";
import { useLargeGraphGate } from "./useLargeGraphGate";

type N = { id: string };
const NO_COLLAPSED: ReadonlySet<string> = new Set();

/** One root with `leaves` children — `leaves + 1` drawn nodes, all of them in the fold. */
function family(leaves: number) {
  const children = Array.from({ length: leaves }, (_, i) => ({ id: `c${i}` }));
  const nodes: N[] = [{ id: "root" }, ...children];
  const forest: HierarchyNode<N>[] = [{ node: { id: "root" }, children: children.map((node) => ({ node, children: [] })) }];
  const graph = { nodes, edges: children.map((c) => ({ sourceId: c.id, targetId: "root", field: "parent" })) };
  return { graph, forest };
}

function mount(initial: ReturnType<typeof family> | null) {
  return renderHook(({ fixture }) => useLargeGraphGate(fixture?.graph ?? null, fixture?.forest ?? [], NO_COLLAPSED), {
    initialProps: { fixture: initial },
  });
}

describe("useLargeGraphGate", () => {
  const BIG = family(LARGE_GRAPH_NODES); // LARGE_GRAPH_NODES + 1 nodes
  const SMALL = family(10);

  test("no graph is not gated", () => {
    expect(mount(null).result.current).toMatchObject({ folded: null, gate: "layout", showAll: false });
  });

  test("a graph under the limit lays out; one over it is held back", () => {
    expect(mount(SMALL).result.current.gate).toBe("layout");
    const { result } = mount(BIG);
    expect(result.current.gate).toBe("large");
    expect(result.current.canShowTopLevel).toBe(true);
    expect(result.current.folded?.nodes).toHaveLength(LARGE_GRAPH_NODES + 1);
  });

  test("Show top level folds the roots and drops under the limit; clearing it restores the gate", () => {
    const { result } = mount(BIG);
    act(() => result.current.showTopLevel());

    expect(result.current.gate).toBe("layout");
    expect(result.current.folded?.nodes.map((n) => n.id)).toEqual(["root"]);
    expect(result.current.overlay).toEqual(new Set(["root"]));
    expect(result.current.canShowTopLevel).toBe(false);

    act(() => result.current.toggle("root"));
    expect(result.current.folded?.nodes).toHaveLength(LARGE_GRAPH_NODES + 1);
    expect(result.current.gate).toBe("large");

    act(() => result.current.clearOverlay());
    expect(result.current.overlay).toBeNull();
    expect(result.current.gate).toBe("large");
  });

  test("Show everything bypasses the gate and drops any overlay", () => {
    const { result } = mount(BIG);
    act(() => result.current.showTopLevel());
    act(() => result.current.showEverything());

    expect(result.current).toMatchObject({ gate: "layout", showAll: true, overlay: null });
    expect(result.current.folded?.nodes).toHaveLength(LARGE_GRAPH_NODES + 1);
  });

  test("both choices reset once the graph is back under the limit", () => {
    const { result, rerender } = mount(BIG);
    act(() => result.current.showEverything());
    expect(result.current.showAll).toBe(true);

    rerender({ fixture: SMALL });
    expect(result.current).toMatchObject({ gate: "layout", showAll: false, overlay: null });

    // Widened again: the gate asks again instead of silently remembering "everything".
    rerender({ fixture: BIG });
    expect(result.current.gate).toBe("large");
  });

  test("with the roots filtered out, Show top level folds the first drawn level", () => {
    // 160 mids with one child each (320 drawn nodes); their root is hidden by a filter.
    const mids = Array.from({ length: 160 }, (_, i) => ({ id: `m${i}` }));
    const kids = mids.map((_m, i) => ({ id: `k${i}` }));
    const forest: HierarchyNode<N>[] = [
      {
        node: { id: "hidden-root" },
        children: mids.map((node, i) => ({ node, children: [{ node: kids[i], children: [] }] })),
      },
    ];
    const graph = { nodes: [...mids, ...kids], edges: [] };
    const { result } = mount({ graph, forest });

    expect(result.current.gate).toBe("large");
    expect(result.current.canShowTopLevel).toBe(true);
    act(() => result.current.showTopLevel());
    expect(result.current.folded?.nodes).toHaveLength(160);
    expect(result.current.gate).toBe("layout");
  });

  test("Show top level is not offered when folding would not draw fewer nodes", () => {
    // Roots exist but none has a drawn descendant: the overlay would change nothing.
    const nodes: N[] = Array.from({ length: LARGE_GRAPH_NODES + 1 }, (_, i) => ({ id: `n${i}` }));
    const forest: HierarchyNode<N>[] = nodes.map((node) => ({ node, children: [] }));
    const { result } = mount({ graph: { nodes, edges: [] }, forest });

    expect(result.current.gate).toBe("large");
    expect(result.current.canShowTopLevel).toBe(false);
  });

  test("a flat graph has nothing to fold at the top level", () => {
    const flat = { graph: { nodes: BIG.graph.nodes, edges: [] }, forest: [] };
    const { result } = mount(flat);
    expect(result.current.gate).toBe("large");
    expect(result.current.canShowTopLevel).toBe(false);
  });
});
