import { describe, expect, test } from "vitest";
import type { EntityGraph, EntityGraphEdge, EntityGraphNode } from "../api/entities";
import { buildEntityHierarchy, filterEntityGraph, relationsOf, toFoldable } from "./entityGraph";

let nextEntityId = 1;
function node(blueprint: string, identifier: string, title = identifier, findings = 0): EntityGraphNode {
  return {
    id: `${blueprint}|${identifier}`,
    entityId: nextEntityId++,
    blueprint,
    blueprintTitle: blueprint,
    identifier,
    title,
    findings,
  } as EntityGraphNode;
}

function edge(sourceId: string, targetId: string, relation: string, hierarchy = false): EntityGraphEdge {
  return { sourceId, targetId, relation, hierarchy };
}

describe("filterEntityGraph", () => {
  test("drops only the disabled relation's edges — nodes always stay", () => {
    const graph: EntityGraph = {
      nodes: [node("team", "a"), node("team", "b")],
      edges: [edge("team|a", "team|b", "peer"), edge("team|a", "team|b", "parent")],
    };
    const out = filterEntityGraph(graph, new Set(["peer"]));
    expect(out.nodes).toBe(graph.nodes);
    expect(out.edges).toEqual([edge("team|a", "team|b", "parent")]);
  });

  test("an empty disabled set keeps everything", () => {
    const graph: EntityGraph = {
      nodes: [node("team", "a")],
      edges: [edge("team|a", "team|a", "self")],
    };
    expect(filterEntityGraph(graph, new Set()).edges).toEqual(graph.edges);
  });
});

describe("relationsOf", () => {
  test("distinct relation identifiers, sorted", () => {
    const graph: EntityGraph = {
      nodes: [],
      edges: [edge("a", "b", "owns"), edge("b", "c", "parent"), edge("a", "c", "owns")],
    };
    expect(relationsOf(graph)).toEqual(["owns", "parent"]);
  });

  test("no edges means no chips", () => {
    expect(relationsOf({ nodes: [], edges: [] })).toEqual([]);
  });
});

describe("toFoldable", () => {
  test("maps relation onto field, keeping nodes untouched", () => {
    const graph: EntityGraph = {
      nodes: [node("team", "a")],
      edges: [edge("team|a", "team|b", "parent", true)],
    };
    const out = toFoldable(graph);
    expect(out.nodes).toBe(graph.nodes);
    expect(out.edges).toEqual([{ sourceId: "team|a", targetId: "team|b", field: "parent" }]);
  });
});

describe("buildEntityHierarchy", () => {
  test("a hierarchy edge places the child under its target; a non-hierarchy edge is ignored", () => {
    const parent = node("service", "checkout");
    const child = node("workload", "checkout-api");
    const graph: EntityGraph = {
      nodes: [parent, child],
      edges: [
        edge(child.id, parent.id, "service", true),
        edge(child.id, parent.id, "peer", false),
      ],
    };
    const roots = buildEntityHierarchy(graph);
    expect(roots.map((r) => r.node.id)).toEqual([parent.id]);
    expect(roots[0].children.map((c) => c.node.id)).toEqual([child.id]);
  });

  test("no shown hierarchy edge means every node is a root, sorted blueprint then title then id", () => {
    const a = node("b-kind", "z");
    const b = node("a-kind", "z");
    const c = node("a-kind", "a");
    const graph: EntityGraph = { nodes: [a, b, c], edges: [] };
    expect(buildEntityHierarchy(graph).map((r) => r.node.id)).toEqual([c.id, b.id, a.id]);
  });

  test("a self hierarchy edge is ignored — the node still roots", () => {
    const self = node("team", "solo");
    const graph: EntityGraph = { nodes: [self], edges: [edge(self.id, self.id, "parent", true)] };
    const roots = buildEntityHierarchy(graph);
    expect(roots).toHaveLength(1);
    expect(roots[0].children).toHaveLength(0);
  });

  test("only the FIRST shown hierarchy edge in payload order wins (a stale multi-value relation)", () => {
    const child = node("workload", "svc");
    const first = node("service", "first");
    const second = node("service", "second");
    const graph: EntityGraph = {
      nodes: [child, first, second],
      edges: [
        edge(child.id, first.id, "service", true),
        edge(child.id, second.id, "service", true),
      ],
    };
    const roots = buildEntityHierarchy(graph);
    const firstRoot = roots.find((r) => r.node.id === first.id)!;
    expect(firstRoot.children.map((c) => c.node.id)).toEqual([child.id]);
    const secondRoot = roots.find((r) => r.node.id === second.id)!;
    expect(secondRoot.children).toHaveLength(0);
  });

  test("a hierarchy edge targeting a node not in the graph is dropped — the source still roots", () => {
    const child = node("workload", "svc");
    const graph: EntityGraph = {
      nodes: [child],
      edges: [edge(child.id, "service|gone", "service", true)],
    };
    expect(buildEntityHierarchy(graph).map((r) => r.node.id)).toEqual([child.id]);
  });

  test("a cycle breaks by promoting the sorted-first island node to a root", () => {
    const a = node("team", "a");
    const b = node("team", "b");
    const graph: EntityGraph = {
      nodes: [a, b],
      edges: [edge(a.id, b.id, "parent", true), edge(b.id, a.id, "parent", true)],
    };
    const roots = buildEntityHierarchy(graph);
    // Every node renders exactly once; the sorted-first (a) is promoted to root.
    expect(roots.map((r) => r.node.id)).toEqual([a.id]);
    expect(roots[0].children.map((c) => c.node.id)).toEqual([b.id]);
  });
});
