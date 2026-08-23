import { describe, expect, test } from "vitest";
import type { CatalogGraph } from "../api/catalogFiles";
import { filterGraph, layoutGraph, RELATION_FAMILIES } from "./graphLayout";

const GRAPH: CatalogGraph = {
  nodes: [
    { id: "component:default/a", kind: "component", namespace: "default", name: "a", title: null, fileId: 1, status: "STORED" },
    { id: "component:default/b", kind: "component", namespace: "default", name: "b", title: "B", fileId: 2, status: "STORED" },
    { id: "component:default/ghost", kind: "component", namespace: "default", name: "ghost", title: null, fileId: null, status: "MISSING" },
    { id: "group:default/team-x", kind: "group", namespace: "default", name: "team-x", title: null, fileId: null, status: "EXTERNAL" },
  ],
  edges: [
    { sourceId: "component:default/a", targetId: "component:default/b", field: "spec.dependsOn" },
    { sourceId: "component:default/a", targetId: "component:default/ghost", field: "spec.subcomponentOf" },
    { sourceId: "component:default/a", targetId: "group:default/team-x", field: "spec.owner" },
  ],
};

describe("filterGraph", () => {
  test("keeps everything when all families are enabled", () => {
    const filtered = filterGraph(GRAPH, RELATION_FAMILIES);
    expect(filtered.nodes).toHaveLength(4);
    expect(filtered.edges).toHaveLength(3);
  });

  test("drops disabled families' edges and prunes orphaned virtual nodes", () => {
    const filtered = filterGraph(GRAPH, ["dependsOn"]);
    expect(filtered.edges).toEqual([GRAPH.edges[0]]);
    // ghost (MISSING) and team-x (EXTERNAL) lost their only edge → pruned; stored nodes stay.
    expect(filtered.nodes.map((n) => n.name)).toEqual(["a", "b"]);
  });

  test("stored nodes survive even with every family disabled", () => {
    const filtered = filterGraph(GRAPH, []);
    expect(filtered.edges).toHaveLength(0);
    expect(filtered.nodes.map((n) => n.status)).toEqual(["STORED", "STORED"]);
  });
});

describe("layoutGraph", () => {
  test("produces positioned React Flow nodes and labeled edges", () => {
    const { nodes, edges } = layoutGraph(GRAPH);
    expect(nodes).toHaveLength(4);
    expect(nodes.every((n) => n.type === "catalog")).toBe(true);
    expect(nodes.every((n) => Number.isFinite(n.position.x) && Number.isFinite(n.position.y))).toBe(true);
    // The layout separates nodes — no two share a position.
    const positions = new Set(nodes.map((n) => `${n.position.x},${n.position.y}`));
    expect(positions.size).toBe(nodes.length);
    // Left-to-right: a source sits left of its target.
    const byId = new Map(nodes.map((n) => [n.id, n]));
    expect(byId.get("component:default/a")!.position.x).toBeLessThan(
      byId.get("component:default/b")!.position.x,
    );

    expect(edges).toHaveLength(3);
    expect(edges[0]).toMatchObject({
      source: "component:default/a",
      target: "component:default/b",
      label: "dependsOn",
    });
    // Edge ids are unique even for parallel edges over different fields.
    expect(new Set(edges.map((e) => e.id)).size).toBe(3);
  });

  test("carries the API node through as data for the custom node", () => {
    const { nodes } = layoutGraph(GRAPH);
    expect(nodes.find((n) => n.id === "component:default/ghost")!.data.apiNode.status).toBe("MISSING");
  });
});
