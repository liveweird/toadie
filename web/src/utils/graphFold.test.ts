import { describe, expect, test } from "vitest";
import type { CatalogGraph, GraphNode } from "../api/catalogFiles";
import { foldGraph } from "./graphFold";
import { buildHierarchy } from "./hierarchy";

function node(id: string, status: GraphNode["status"] = "STORED"): GraphNode {
  const [kind, rest] = id.split(":");
  const [namespace, name] = rest.split("/");
  return { id, kind, namespace, name, title: null, fileId: status === "STORED" ? 1 : null, status };
}

function edge(sourceId: string, targetId: string, field: string) {
  return { sourceId, targetId, field };
}

// One Domain ⊃ one System ⊃ three Components (one with a subcomponent), a Resource outside
// the System, a Group that owns things and a User in it. Kinds/fields are the server's.
const SYS = "system:default/shop";
const DOM = "domain:default/commerce";
const C1 = "component:default/cart";
const C2 = "component:default/checkout";
const C3 = "component:default/search";
const SUB = "component:default/cart-ui";
const RES = "resource:default/db";
const GRP = "group:default/team";
const USR = "user:default/ann";

const LANDSCAPE: CatalogGraph = {
  nodes: [DOM, SYS, C1, C2, C3, SUB, RES, GRP, USR].map((id) => node(id)),
  edges: [
    edge(SYS, DOM, "spec.domain"),
    edge(C1, SYS, "spec.system"),
    edge(C2, SYS, "spec.system"),
    edge(C3, SYS, "spec.system"),
    edge(SUB, C1, "spec.subcomponentOf"),
    edge(C1, RES, "spec.dependsOn"),
    edge(C2, RES, "spec.dependsOn"),
    edge(C3, RES, "spec.dependsOn"),
    edge(C1, C2, "spec.dependsOn"),
    edge(C1, GRP, "spec.owner"),
    edge(SYS, GRP, "spec.owner"),
    edge(USR, GRP, "spec.memberOf"),
  ],
};

function fold(graph: CatalogGraph, collapsed: string[], forestOf: CatalogGraph = graph) {
  return foldGraph(graph, buildHierarchy(forestOf), new Set(collapsed));
}

function ids(nodes: GraphNode[]) {
  return nodes.map((n) => n.id);
}

describe("foldGraph", () => {
  test("nothing collapsed folds nothing — nodes and edges pass through, every edge plain", () => {
    const out = fold(LANDSCAPE, []);
    expect(ids(out.nodes)).toEqual(ids(LANDSCAPE.nodes));
    expect(out.edges).toHaveLength(LANDSCAPE.edges.length);
    expect(out.edges.every((e) => e.relations === 1 && e.folded === 0)).toBe(true);
  });

  test("a collapsed System hides its components (and their subcomponents) and the containment edges with them", () => {
    const out = fold(LANDSCAPE, [SYS]);
    expect(ids(out.nodes)).toEqual([DOM, SYS, RES, GRP, USR]);
    // No edge touches a hidden node, and the Component→System edges became self-loops → gone.
    const touched = out.edges.flatMap((e) => [e.sourceId, e.targetId]);
    expect(touched.every((id) => ids(out.nodes).includes(id))).toBe(true);
    expect(out.edges.some((e) => e.field === "spec.system")).toBe(false);
    expect(out.edges.some((e) => e.field === "spec.subcomponentOf")).toBe(false);
  });

  test("a hidden node's relations are redrawn from the collapsed ancestor, merged per field with a count", () => {
    const out = fold(LANDSCAPE, [SYS]);
    // Three hidden components each depend on the Resource → ONE folded edge counting three.
    expect(out.edges).toContainEqual({ sourceId: SYS, targetId: RES, field: "spec.dependsOn", relations: 3, folded: 3 });
    // The System's own owner edge is direct, and the hidden cart's owner edge folds INTO it.
    expect(out.edges).toContainEqual({ sourceId: SYS, targetId: GRP, field: "spec.owner", relations: 2, folded: 1 });
    // cart → checkout lived entirely inside the collapsed subtree: dropped, not a self-loop.
    expect(out.edges.filter((e) => e.sourceId === e.targetId)).toHaveLength(0);
    expect(out.edges.filter((e) => e.field === "spec.dependsOn")).toHaveLength(1);
  });

  test("a collapsed node under a collapsed node resolves to the topmost — and keeps its own flag", () => {
    const out = fold(LANDSCAPE, [DOM, SYS]);
    expect(ids(out.nodes)).toEqual([DOM, RES, GRP, USR]);
    expect(out.edges).toContainEqual({ sourceId: DOM, targetId: RES, field: "spec.dependsOn", relations: 3, folded: 3 });
    expect(out.edges).toContainEqual({ sourceId: DOM, targetId: GRP, field: "spec.owner", relations: 2, folded: 2 });
    // The hidden System is not on the canvas, so it has no face to describe — no info entry
    // is needed for it; the Domain's info counts everything drawn beneath it.
    expect(out.info.get(DOM)).toEqual({ collapsed: true, descendants: 5 });
  });

  test("info lists every node with a DRAWN descendant, with the flag and the count", () => {
    const out = fold(LANDSCAPE, [C1]);
    expect(out.info.get(DOM)).toEqual({ collapsed: false, descendants: 5 });
    expect(out.info.get(SYS)).toEqual({ collapsed: false, descendants: 4 });
    expect(out.info.get(C1)).toEqual({ collapsed: true, descendants: 1 });
    expect(out.info.get(GRP)).toEqual({ collapsed: false, descendants: 1 });
    // Leaves have nothing to fold — no entry, so the face shows no toggle.
    expect(out.info.has(C2)).toBe(false);
    expect(out.info.has(RES)).toBe(false);
    expect(out.info.has(USR)).toBe(false);
  });

  test("a descendant the relation chips pruned is neither counted nor hidden", () => {
    // The forest comes from the FULL payload; the graph is what the chips left — here the
    // subcomponent's node is gone (as if chip-pruned), so cart has nothing drawn beneath it.
    const chipped: CatalogGraph = {
      nodes: LANDSCAPE.nodes.filter((n) => n.id !== SUB),
      edges: LANDSCAPE.edges.filter((e) => e.sourceId !== SUB),
    };
    const out = fold(chipped, [C1], LANDSCAPE);
    expect(out.info.has(C1)).toBe(false);
    expect(out.info.get(SYS)).toEqual({ collapsed: false, descendants: 3 });
  });

  test("containment comes from the forest, not from the drawn edges — a System is collapsible with its chip off", () => {
    const noSystemEdges: CatalogGraph = {
      nodes: LANDSCAPE.nodes,
      edges: LANDSCAPE.edges.filter((e) => e.field !== "spec.system"),
    };
    const out = fold(noSystemEdges, [SYS], LANDSCAPE);
    expect(ids(out.nodes)).toEqual([DOM, SYS, RES, GRP, USR]);
    expect(out.edges).toContainEqual({ sourceId: SYS, targetId: RES, field: "spec.dependsOn", relations: 3, folded: 3 });
  });

  test("a User under two Groups stays while one Group is open, and is doubly represented once both close", () => {
    const G2 = "group:default/guild";
    const graph: CatalogGraph = {
      nodes: [GRP, G2, USR, RES].map((id) => node(id)),
      edges: [edge(USR, GRP, "spec.memberOf"), edge(USR, G2, "spec.memberOf"), edge(USR, RES, "spec.dependsOn")],
    };
    const oneOpen = fold(graph, [GRP]);
    expect(ids(oneOpen.nodes)).toEqual([GRP, G2, USR, RES]);
    expect(oneOpen.edges.every((e) => e.folded === 0)).toBe(true);

    const bothClosed = fold(graph, [GRP, G2]);
    expect(ids(bothClosed.nodes)).toEqual([GRP, G2, RES]);
    expect(bothClosed.edges).toContainEqual({ sourceId: GRP, targetId: RES, field: "spec.dependsOn", relations: 1, folded: 1 });
    expect(bothClosed.edges).toContainEqual({ sourceId: G2, targetId: RES, field: "spec.dependsOn", relations: 1, folded: 1 });
    // The User's membership in each Group is a relation INTO one of its own stand-ins —
    // internal, so it must never surface as one Group being a member of the other.
    expect(bothClosed.edges.filter((e) => e.field === "spec.memberOf")).toHaveLength(0);
    expect(bothClosed.edges).toHaveLength(2);
  });

  test("a virtual node outside the forest is always visible, and a MISSING containment parent folds like any other", () => {
    const ghostOwner = "group:default/ghost";
    const ghostSystem = "system:default/legacy";
    const graph: CatalogGraph = {
      nodes: [node(C1), node(ghostOwner, "MISSING"), node(ghostSystem, "MISSING"), node(RES)],
      edges: [edge(C1, ghostOwner, "spec.owner"), edge(C1, ghostSystem, "spec.system"), edge(C1, RES, "spec.dependsOn")],
    };
    const out = fold(graph, [ghostSystem]);
    expect(ids(out.nodes)).toEqual([ghostOwner, ghostSystem, RES]);
    expect(out.edges).toContainEqual({ sourceId: ghostSystem, targetId: ghostOwner, field: "spec.owner", relations: 1, folded: 1 });
    expect(out.edges).toContainEqual({ sourceId: ghostSystem, targetId: RES, field: "spec.dependsOn", relations: 1, folded: 1 });
  });

  test("collapsed ids that match nothing are ignored — the list is never pruned, only not consulted", () => {
    const out = fold(LANDSCAPE, ["system:default/gone", "component:default/nope"]);
    expect(ids(out.nodes)).toEqual(ids(LANDSCAPE.nodes));
    expect(out.edges).toHaveLength(LANDSCAPE.edges.length);
    expect([...out.info.values()].every((i) => !i.collapsed)).toBe(true);
  });
});
