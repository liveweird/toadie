import { describe, expect, test } from "vitest";
import type { EntityGraph, EntityGraphEdge, EntityGraphNode } from "../api/entities";
import { foldGraph } from "./graphFold";
import { buildEntityHierarchy, effectiveHierarchyId, filterEntityGraph, relationsOf, toFoldable } from "./entityGraph";

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

function edge(sourceId: string, targetId: string, relation: string, hierarchies: string[] = [], ownership = false): EntityGraphEdge {
  return { sourceId, targetId, relation, hierarchies, ownership };
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
      edges: [edge("team|a", "team|b", "parent", ["composition"])],
    };
    const out = toFoldable(graph);
    expect(out.nodes).toBe(graph.nodes);
    expect(out.edges).toEqual([{ sourceId: "team|a", targetId: "team|b", field: "parent" }]);
  });
});

describe.each(["composition", "cost-center"])("buildEntityHierarchy (hierarchy=%s)", (hierarchyId) => {
  test("a hierarchy edge places the child under its target; a non-hierarchy edge is ignored", () => {
    const parent = node("service", "checkout");
    const child = node("workload", "checkout-api");
    const graph: EntityGraph = {
      nodes: [parent, child],
      edges: [
        edge(child.id, parent.id, "service", [hierarchyId]),
        edge(child.id, parent.id, "peer", []),
      ],
    };
    const roots = buildEntityHierarchy(graph, hierarchyId);
    expect(roots.map((r) => r.node.id)).toEqual([parent.id]);
    expect(roots[0].children.map((c) => c.node.id)).toEqual([child.id]);
  });

  test("no shown hierarchy edge means every node is a root, sorted blueprint then title then id", () => {
    const a = node("b-kind", "z");
    const b = node("a-kind", "z");
    const c = node("a-kind", "a");
    const graph: EntityGraph = { nodes: [a, b, c], edges: [] };
    expect(buildEntityHierarchy(graph, hierarchyId).map((r) => r.node.id)).toEqual([c.id, b.id, a.id]);
  });

  test("a self hierarchy edge is ignored — the node still roots", () => {
    const self = node("team", "solo");
    const graph: EntityGraph = { nodes: [self], edges: [edge(self.id, self.id, "parent", [hierarchyId])] };
    const roots = buildEntityHierarchy(graph, hierarchyId);
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
        edge(child.id, first.id, "service", [hierarchyId]),
        edge(child.id, second.id, "service", [hierarchyId]),
      ],
    };
    const roots = buildEntityHierarchy(graph, hierarchyId);
    const firstRoot = roots.find((r) => r.node.id === first.id)!;
    expect(firstRoot.children.map((c) => c.node.id)).toEqual([child.id]);
    const secondRoot = roots.find((r) => r.node.id === second.id)!;
    expect(secondRoot.children).toHaveLength(0);
  });

  test("a hierarchy edge targeting a node not in the graph is dropped — the source still roots", () => {
    const child = node("workload", "svc");
    const graph: EntityGraph = {
      nodes: [child],
      edges: [edge(child.id, "service|gone", "service", [hierarchyId])],
    };
    expect(buildEntityHierarchy(graph, hierarchyId).map((r) => r.node.id)).toEqual([child.id]);
  });

  test("an ownership edge is never a parent link, even if it carries a hierarchy id", () => {
    const child = node("workload", "svc");
    const team = node("team", "platform");
    const graph: EntityGraph = {
      nodes: [child, team],
      // The server never actually sends a hierarchy id on an ownership edge, but ownership
      // must never nest even in that case.
      edges: [edge(child.id, team.id, "$team", [hierarchyId], true)],
    };
    const roots = buildEntityHierarchy(graph, hierarchyId);
    expect(roots.map((r) => r.node.id).sort()).toEqual([child.id, team.id].sort());
    expect(roots.flatMap((r) => r.children)).toHaveLength(0);
  });

  test("a cycle breaks by promoting the sorted-first island node to a root", () => {
    const a = node("team", "a");
    const b = node("team", "b");
    const graph: EntityGraph = {
      nodes: [a, b],
      edges: [edge(a.id, b.id, "parent", [hierarchyId]), edge(b.id, a.id, "parent", [hierarchyId])],
    };
    const roots = buildEntityHierarchy(graph, hierarchyId);
    // Every node renders exactly once; the sorted-first (a) is promoted to root.
    expect(roots.map((r) => r.node.id)).toEqual([a.id]);
    expect(roots[0].children.map((c) => c.node.id)).toEqual([b.id]);
  });
});

describe("buildEntityHierarchy — multi-hierarchy edges", () => {
  test("an edge listing two hierarchy ids is a parent link in BOTH", () => {
    const parent = node("service", "checkout");
    const child = node("workload", "checkout-api");
    const graph: EntityGraph = {
      nodes: [parent, child],
      edges: [edge(child.id, parent.id, "service", ["composition", "cost-center"])],
    };
    for (const hierarchyId of ["composition", "cost-center"]) {
      const roots = buildEntityHierarchy(graph, hierarchyId);
      expect(roots.map((r) => r.node.id)).toEqual([parent.id]);
      expect(roots[0].children.map((c) => c.node.id)).toEqual([child.id]);
    }
  });

  test("a hierarchy id no edge carries yields all roots", () => {
    const parent = node("service", "checkout");
    const child = node("workload", "checkout-api");
    const graph: EntityGraph = {
      nodes: [parent, child],
      edges: [edge(child.id, parent.id, "service", ["composition"])],
    };
    const roots = buildEntityHierarchy(graph, "unknown-hierarchy");
    expect(roots.map((r) => r.node.id).sort()).toEqual([child.id, parent.id].sort());
    expect(roots.flatMap((r) => r.children)).toHaveLength(0);
  });
});

describe("effectiveHierarchyId", () => {
  test("keeps the stored id when it is still a dictionary value", () => {
    expect(effectiveHierarchyId("cost-center", ["composition", "cost-center"])).toBe("cost-center");
  });

  test("falls back to the first dictionary value when the stored id is stale", () => {
    expect(effectiveHierarchyId("gone", ["composition", "cost-center"])).toBe("composition");
  });

  test("falls back to the first dictionary value when nothing was stored yet", () => {
    expect(effectiveHierarchyId("", ["composition", "cost-center"])).toBe("composition");
  });

  test("an empty dictionary resolves to the empty string", () => {
    expect(effectiveHierarchyId("composition", [])).toBe("");
  });
});

describe("ownership edges fold like other relations", () => {
  test("collapsing a hidden owner's ancestor redraws its $team edge from the collapsed node", () => {
    const parent = node("service", "checkout");
    const child = node("workload", "checkout-api");
    const team = node("team", "platform");
    const graph: EntityGraph = {
      nodes: [parent, child, team],
      edges: [
        edge(child.id, parent.id, "service", ["composition"]),
        edge(child.id, team.id, "$team", [], true),
      ],
    };
    const forest = buildEntityHierarchy(graph, "composition");
    const folded = foldGraph(toFoldable(graph), forest, new Set([parent.id]));
    // child is hidden under the collapsed parent; its ownership edge stands in as one FROM
    // the parent — never dropped just because it carries `ownership` rather than a plain
    // declared relation.
    expect(folded.edges).toContainEqual({
      sourceId: parent.id,
      targetId: team.id,
      field: "$team",
      relations: 1,
      folded: 1,
    });
  });
});
