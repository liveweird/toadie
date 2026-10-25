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

type Props = { fixture: ReturnType<typeof family> | null; collapsed?: ReadonlySet<string>; ready?: boolean };

function mount(initial: Props["fixture"], extra: Omit<Props, "fixture"> = {}) {
  return renderHook(
    ({ fixture, collapsed = NO_COLLAPSED, ready = true }: Props) =>
      useLargeGraphGate(fixture?.graph ?? null, fixture?.forest ?? [], collapsed, ready),
    { initialProps: { fixture: initial, ...extra } as Props },
  );
}

describe("useLargeGraphGate", () => {
  const BIG = family(LARGE_GRAPH_NODES); // LARGE_GRAPH_NODES + 1 nodes
  const SMALL = family(10);

  test("no graph is not gated", () => {
    expect(mount(null).result.current).toMatchObject({ folded: null, gate: "layout", showAll: false });
  });

  test("the 300 / 301 boundary: 300 nodes lay out as drawn, 301 open on the top-level overlay", () => {
    const edge = mount(family(LARGE_GRAPH_NODES - 1)); // exactly LARGE_GRAPH_NODES nodes
    expect(edge.result.current).toMatchObject({ gate: "layout", overlay: null, showAll: false });
    expect(edge.result.current.folded?.nodes).toHaveLength(LARGE_GRAPH_NODES);

    const { result } = mount(BIG);
    expect(result.current.gate).toBe("layout");
    expect(result.current.overlay).toEqual(new Set(["root"]));
    expect(result.current.folded?.nodes.map((n) => n.id)).toEqual(["root"]);
    // The overlay is already on: the panel's "Show top level" is not offered.
    expect(result.current.canShowTopLevel).toBe(false);
    expect(mount(SMALL).result.current).toMatchObject({ gate: "layout", overlay: null });
  });

  test("leaving the default overlay brings the panel, and it stays; Show top level restores the overlay", () => {
    const { result, rerender } = mount(BIG);
    act(() => result.current.clearOverlay());
    expect(result.current).toMatchObject({ gate: "large", overlay: null, canShowTopLevel: true });
    expect(result.current.folded?.nodes).toHaveLength(LARGE_GRAPH_NODES + 1);

    // Not re-applied by a re-render with the same (or an equal) graph.
    rerender({ fixture: BIG });
    expect(result.current.gate).toBe("large");

    act(() => result.current.showTopLevel());
    expect(result.current).toMatchObject({ gate: "layout", canShowTopLevel: false });
    expect(result.current.overlay).toEqual(new Set(["root"]));
  });

  test("a fold toggle on the default overlay makes it explicit; an expand over the limit re-gates and offers the top level again", () => {
    const { result } = mount(BIG);
    expect(result.current.canShowTopLevel).toBe(false);
    act(() => result.current.toggle("root"));
    expect(result.current.overlay).toEqual(new Set());
    expect(result.current.folded?.nodes).toHaveLength(LARGE_GRAPH_NODES + 1);
    expect(result.current.gate).toBe("large");
    // The overlay in force is no longer the top level, which still fits: the panel offers it.
    expect(result.current.canShowTopLevel).toBe(true);

    act(() => result.current.showTopLevel());
    expect(result.current.overlay).toEqual(new Set(["root"]));
    expect(result.current).toMatchObject({ gate: "layout", canShowTopLevel: false });

    // Folding it back by hand lands on the same overlay.
    act(() => result.current.toggle("root"));
    expect(result.current.gate).toBe("large");
    act(() => result.current.toggle("root"));
    expect(result.current.overlay).toEqual(new Set(["root"]));
    expect(result.current.gate).toBe("layout");
  });

  test("an expand over the limit does not offer a top level that is itself over it", () => {
    // 301 roots with one child each: Show top level (301 nodes) is explicit but still gated.
    const roots = Array.from({ length: LARGE_GRAPH_NODES + 1 }, (_, i) => ({ id: `r${i}` }));
    const kids = roots.map((_r, i) => ({ id: `k${i}` }));
    const forest: HierarchyNode<N>[] = roots.map((node, i) => ({ node, children: [{ node: kids[i], children: [] }] }));
    const { result } = mount({ graph: { nodes: [...roots, ...kids], edges: [] }, forest });
    act(() => result.current.showTopLevel());
    act(() => result.current.toggle("r0"));
    expect(result.current.gate).toBe("large");
    expect(result.current.canShowTopLevel).toBe(false);
  });

  test("a top level of exactly 300 nodes fits and opens by default; 301 keeps the panel", () => {
    const wide = (roots: number) => {
      const rs = Array.from({ length: roots }, (_, i) => ({ id: `r${i}` }));
      const ks = rs.map((_r, i) => ({ id: `k${i}` }));
      const forest: HierarchyNode<N>[] = rs.map((node, i) => ({ node, children: [{ node: ks[i], children: [] }] }));
      return { graph: { nodes: [...rs, ...ks], edges: [] }, forest };
    };
    const exact = mount(wide(LARGE_GRAPH_NODES));
    expect(exact.result.current.gate).toBe("layout");
    expect(exact.result.current.folded?.nodes).toHaveLength(LARGE_GRAPH_NODES);
    expect(exact.result.current.overlay?.size).toBe(LARGE_GRAPH_NODES);

    const over = mount(wide(LARGE_GRAPH_NODES + 1));
    expect(over.result.current).toMatchObject({ gate: "large", overlay: null, canShowTopLevel: true });
  });

  test("a saved fold that already brings the graph under the limit is not gated at all", () => {
    const { result } = mount(BIG, { collapsed: new Set(["root"]) });
    expect(result.current).toMatchObject({ gate: "layout", overlay: null, showAll: false, canShowTopLevel: false });
    expect(result.current.folded?.nodes.map((n) => n.id)).toEqual(["root"]);
  });

  test("the default overlay waits for the saved fold: a collapsed set arriving late never flashes it", () => {
    const { result, rerender } = mount(BIG, { ready: false });
    // Not ready: the graph is gated as before (the panel), nothing is folded or laid out.
    expect(result.current).toMatchObject({ gate: "large", overlay: null, canShowTopLevel: true });
    expect(result.current.folded?.nodes).toHaveLength(LARGE_GRAPH_NODES + 1);

    // The layout document arrives with a fold that brings the graph under the limit.
    rerender({ fixture: BIG, collapsed: new Set(["root"]), ready: true });
    expect(result.current).toMatchObject({ gate: "layout", overlay: null });
    expect(result.current.folded?.nodes.map((n) => n.id)).toEqual(["root"]);
  });

  test("once ready with nothing saved, the default overlay opens (a failed load counts as ready too)", () => {
    const { result, rerender } = mount(BIG, { ready: false });
    expect(result.current.overlay).toBeNull();
    rerender({ fixture: BIG, ready: true });
    expect(result.current.overlay).toEqual(new Set(["root"]));
    expect(result.current.gate).toBe("layout");
  });

  test("leaving the default overlay survives a filter change that stays over the limit", () => {
    const { result, rerender } = mount(BIG);
    act(() => result.current.clearOverlay());
    expect(result.current.gate).toBe("large");

    // The selection changes (a wider, then a narrower one) but stays over the limit: still dismissed.
    const wider = family(LARGE_GRAPH_NODES + 20);
    rerender({ fixture: wider });
    expect(result.current).toMatchObject({ gate: "large", overlay: null, canShowTopLevel: true });
    const narrower = family(LARGE_GRAPH_NODES + 5);
    rerender({ fixture: narrower });
    expect(result.current).toMatchObject({ gate: "large", overlay: null });
  });

  test("Show everything bypasses the gate and drops the overlay", () => {
    const { result } = mount(BIG);
    expect(result.current.overlay).not.toBeNull();
    act(() => result.current.showEverything());

    expect(result.current).toMatchObject({ gate: "layout", showAll: true, overlay: null });
    expect(result.current.folded?.nodes).toHaveLength(LARGE_GRAPH_NODES + 1);
  });

  test("every choice resets once the graph is back under the limit", () => {
    const { result, rerender } = mount(BIG);
    act(() => result.current.showEverything());
    expect(result.current.showAll).toBe(true);

    rerender({ fixture: SMALL });
    expect(result.current).toMatchObject({ gate: "layout", showAll: false, overlay: null });

    // Widened again: the default (the top-level overlay) applies again instead of "everything".
    rerender({ fixture: BIG });
    expect(result.current).toMatchObject({ gate: "layout", showAll: false });
    expect(result.current.overlay).toEqual(new Set(["root"]));

    // Leaving the overlay is forgotten the same way.
    act(() => result.current.clearOverlay());
    expect(result.current.gate).toBe("large");
    rerender({ fixture: SMALL });
    rerender({ fixture: BIG });
    expect(result.current.overlay).toEqual(new Set(["root"]));
  });

  test("with the roots filtered out, the default overlay folds the first drawn level", () => {
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

    expect(result.current.gate).toBe("layout");
    expect(result.current.folded?.nodes).toHaveLength(160);
  });

  test("a top level that is still over the limit keeps the panel (and still offers itself)", () => {
    // 301 roots with one child each: the top level draws 301 nodes, fewer than 602 but not within the limit.
    const roots = Array.from({ length: LARGE_GRAPH_NODES + 1 }, (_, i) => ({ id: `r${i}` }));
    const kids = roots.map((_r, i) => ({ id: `k${i}` }));
    const forest: HierarchyNode<N>[] = roots.map((node, i) => ({ node, children: [{ node: kids[i], children: [] }] }));
    const { result } = mount({ graph: { nodes: [...roots, ...kids], edges: [] }, forest });

    expect(result.current).toMatchObject({ gate: "large", overlay: null, canShowTopLevel: true });
    act(() => result.current.showTopLevel());
    expect(result.current.folded?.nodes).toHaveLength(LARGE_GRAPH_NODES + 1);
    expect(result.current.gate).toBe("large");
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
