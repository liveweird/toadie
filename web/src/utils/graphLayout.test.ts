import { describe, expect, test } from "vitest";
import type { CatalogGraph } from "../api/catalogFiles";
import {
  applyManualPositions,
  clusterFrames,
  edgeLabel,
  filterGraph,
  FOLDED_EDGE_STYLE,
  GRAPH_NODE_HEIGHT,
  GRAPH_NODE_WIDTH,
  layoutGraph,
  namespaceFrames,
  RELATION_FAMILIES,
  type ClusterAccessor,
} from "./graphLayout";

const GRAPH: CatalogGraph = {
  nodes: [
    { id: "component:default/a", kind: "component", namespace: "default", name: "a", title: null, fileId: 1, status: "STORED" },
    { id: "component:default/b", kind: "component", namespace: "default", name: "b", title: "B", fileId: 2, status: "STORED" },
    { id: "component:default/ghost", kind: "component", namespace: "default", name: "ghost", title: null, fileId: null, status: "MISSING" },
    { id: "group:default/team-x", kind: "group", namespace: "default", name: "team-x", title: null, fileId: null, status: "MISSING" },
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
    // ghost and team-x (both MISSING) lost their only edge → pruned; stored nodes stay.
    expect(filtered.nodes.map((n) => n.name)).toEqual(["a", "b"]);
  });

  test("the new families map their fields: domain and membership", () => {
    const graph = {
      nodes: [
        { id: "system:default/pay", kind: "system", namespace: "default", name: "pay", title: null, fileId: 1, status: "STORED" },
        { id: "domain:default/commerce", kind: "domain", namespace: "default", name: "commerce", title: null, fileId: null, status: "MISSING" },
        { id: "group:default/team", kind: "group", namespace: "default", name: "team", title: null, fileId: 2, status: "STORED" },
        { id: "user:default/jdoe", kind: "user", namespace: "default", name: "jdoe", title: null, fileId: null, status: "MISSING" },
      ],
      edges: [
        { sourceId: "system:default/pay", targetId: "domain:default/commerce", field: "spec.domain" },
        { sourceId: "group:default/team", targetId: "user:default/jdoe", field: "spec.members" },
      ],
    } as CatalogGraph;
    const domainOnly = filterGraph(graph, ["domain"]);
    expect(domainOnly.edges).toHaveLength(1);
    expect(domainOnly.nodes.map((n) => n.name)).toEqual(["pay", "commerce", "team"]);
    const membershipOnly = filterGraph(graph, ["membership"]);
    expect(membershipOnly.edges[0].field).toBe("spec.members");
    expect(membershipOnly.nodes.map((n) => n.name)).toEqual(["pay", "team", "jdoe"]);
  });

  test("stored nodes survive even with every family disabled", () => {
    // The server already sent exactly the entities the filters select, so a relation chip
    // must never remove one — it governs relations, not which entities are shown.
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

describe("applyManualPositions", () => {
  test("overrides stored ids, keeps dagre spots for the rest, ignores unknown ids", () => {
    const { nodes } = layoutGraph(GRAPH);
    const dagreB = nodes.find((n) => n.id === "component:default/b")!.position;
    const overlaid = applyManualPositions(nodes, {
      "component:default/a": { x: 42, y: -7 },
      // An id not in the graph (renamed/filtered entity) is simply not consulted.
      "component:default/gone": { x: 1, y: 1 },
    });
    expect(overlaid).toHaveLength(nodes.length);
    expect(overlaid.find((n) => n.id === "component:default/a")!.position).toEqual({ x: 42, y: -7 });
    expect(overlaid.find((n) => n.id === "component:default/b")!.position).toEqual(dagreB);
  });

  test("an empty map returns every node at its dagre position", () => {
    const { nodes } = layoutGraph(GRAPH);
    expect(applyManualPositions(nodes, {}).map((n) => n.position)).toEqual(nodes.map((n) => n.position));
  });
});

describe("namespaceFrames", () => {
  const laidOut = (
    id: string,
    namespace: string,
    x: number,
    y: number,
    status: "STORED" | "MISSING" = "STORED",
  ) => ({
    id,
    type: "catalog" as const,
    position: { x, y },
    data: {
      apiNode: {
        id,
        kind: "component",
        namespace,
        name: id,
        title: null,
        fileId: status === "STORED" ? 1 : null,
        status,
      },
    },
  });

  test("a single namespace gets no frame — a box round everything states nothing", () => {
    expect(namespaceFrames([laidOut("a", "default", 0, 0), laidOut("b", "default", 300, 0)])).toEqual([]);
  });

  test("one frame per namespace, each enclosing its members with padding", () => {
    const frames = namespaceFrames([
      laidOut("a", "default", 0, 0),
      laidOut("b", "default", 300, 200),
      laidOut("x", "external", 900, 0),
    ]);
    expect(frames.map((f) => f.namespace)).toEqual(["default", "external"]);

    const [def, ext] = frames;
    // The box contains both `default` members and neither of them touches its edge.
    expect(def.x).toBeLessThan(0);
    expect(def.y).toBeLessThan(0);
    expect(def.x + def.width).toBeGreaterThan(300 + GRAPH_NODE_WIDTH);
    expect(def.y + def.height).toBeGreaterThan(200 + GRAPH_NODE_HEIGHT);
    // A one-member namespace still gets a real box.
    expect(ext.width).toBeGreaterThan(GRAPH_NODE_WIDTH);
    expect(ext.height).toBeGreaterThan(GRAPH_NODE_HEIGHT);
  });

  test("virtual nodes are framed by namespace like any other", () => {
    const ghost = laidOut("ghost", "external", 900, 0, "MISSING");
    const frames = namespaceFrames([laidOut("a", "default", 0, 0), ghost]);
    expect(frames.map((f) => f.namespace)).toEqual(["default", "external"]);
  });

  test("a member dragged away stretches its frame to keep containing it", () => {
    const tight = namespaceFrames([laidOut("a", "default", 0, 0), laidOut("x", "external", 900, 0)]);
    const dragged = namespaceFrames([laidOut("a", "default", 0, 0), laidOut("x", "external", 900, 4000)]);
    // `external`'s single member moved far down — the box follows it, it does not clip.
    const before = tight.find((f) => f.namespace === "external")!;
    const after = dragged.find((f) => f.namespace === "external")!;
    expect(after.y).toBeGreaterThan(before.y);
    expect(after.height).toBe(before.height);
  });
});

describe("layoutGraph namespace clustering", () => {
  const twoNamespaces: CatalogGraph = {
    nodes: [
      ...GRAPH.nodes,
      { id: "system:external/acq", kind: "system", namespace: "external", name: "acq", title: null, fileId: 9, status: "STORED" },
      { id: "api:external/acq-rest", kind: "api", namespace: "external", name: "acq-rest", title: null, fileId: 10, status: "STORED" },
    ],
    edges: [
      ...GRAPH.edges,
      { sourceId: "api:external/acq-rest", targetId: "system:external/acq", field: "spec.system" },
      { sourceId: "component:default/a", targetId: "api:external/acq-rest", field: "spec.consumesApis" },
    ],
  };

  test("nodes of one namespace are clumped, so the frames do not overlap", () => {
    const { nodes } = layoutGraph(twoNamespaces);
    const frames = namespaceFrames(nodes);
    expect(frames).toHaveLength(2);
    const [a, b] = frames;
    // Separated on at least one axis — that is what makes a frame readable.
    const apart = a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y;
    expect(apart).toBe(true);
  });

  test("a single-namespace graph is laid out exactly as before — no clustering cost", () => {
    const { nodes } = layoutGraph(GRAPH);
    expect(nodes).toHaveLength(4);
    expect(namespaceFrames(nodes)).toEqual([]);
  });
});

describe("layoutGraph folded edges", () => {
  const folded = {
    nodes: GRAPH.nodes.slice(0, 2),
    edges: [
      { sourceId: "component:default/a", targetId: "component:default/b", field: "spec.dependsOn", relations: 3, folded: 3 },
      { sourceId: "component:default/b", targetId: "component:default/a", field: "spec.owner", relations: 2, folded: 1 },
      { sourceId: "component:default/a", targetId: "component:default/b", field: "spec.system", relations: 1, folded: 0 },
    ],
  };

  test("a plain edge keeps its field label; a merged one counts its relations", () => {
    expect(edgeLabel({ sourceId: "x", targetId: "y", field: "spec.dependsOn" })).toBe("dependsOn");
    expect(edgeLabel(folded.edges[0])).toBe("dependsOn ×3");
    expect(edgeLabel(folded.edges[2])).toBe("system");
  });

  test("an edge standing in for ANY hidden relation draws dashed; a direct one does not", () => {
    const { edges } = layoutGraph(folded);
    expect(edges.map((e) => e.label)).toEqual(["dependsOn ×3", "owner ×2", "system"]);
    expect(edges[0].style).toEqual(FOLDED_EDGE_STYLE);
    // Two relations, one of them folded: the edge is partly a stand-in, so it reads dashed.
    expect(edges[1].style).toEqual(FOLDED_EDGE_STYLE);
    expect(edges[2].style).toBeUndefined();
    // Ids stay the fold's own merge key, so React Flow sees one edge per merged pair+field.
    expect(edges[0].id).toBe("component:default/a->component:default/b:spec.dependsOn");
  });
});

// The Entity graph page's clustering key (blueprint, not namespace) — a minimal stand-in
// payload proving [layoutGraph]/[clusterFrames] generalize over ANY `ClusterAccessor<N>`,
// not just [NAMESPACE_CLUSTER].
interface FakeNode {
  id: string;
  group: string;
}
const CUSTOM_CLUSTER: ClusterAccessor<FakeNode> = { keyOf: (n) => n.group, nodeType: "fake" };

describe("layoutGraph with a custom cluster accessor", () => {
  test("two cluster keys compound-layout the nodes, clumped apart per key", () => {
    const graph = {
      nodes: [
        { id: "a", group: "alpha" },
        { id: "b", group: "alpha" },
        { id: "x", group: "beta" },
      ],
      edges: [],
    };
    const { nodes } = layoutGraph(graph, CUSTOM_CLUSTER);
    expect(nodes.every((n) => n.type === "fake")).toBe(true);
    const frames = clusterFrames(nodes, CUSTOM_CLUSTER.keyOf);
    expect(frames.map((f) => f.key)).toEqual(["alpha", "beta"]);
    const [alpha, beta] = frames;
    const apart =
      alpha.x + alpha.width <= beta.x ||
      beta.x + beta.width <= alpha.x ||
      alpha.y + alpha.height <= beta.y ||
      beta.y + beta.height <= alpha.y;
    expect(apart).toBe(true);
  });

  test("a single cluster key never clusters — no frame is drawn", () => {
    const graph = {
      nodes: [
        { id: "a", group: "only" },
        { id: "b", group: "only" },
      ],
      edges: [],
    };
    const { nodes } = layoutGraph(graph, CUSTOM_CLUSTER);
    expect(clusterFrames(nodes, CUSTOM_CLUSTER.keyOf)).toEqual([]);
  });

  test("dagre's internal cluster:<key> pseudo-nodes never leak into the output", () => {
    const graph = {
      nodes: [
        { id: "a", group: "alpha" },
        { id: "b", group: "alpha" },
        { id: "x", group: "beta" },
      ],
      edges: [],
    };
    const { nodes } = layoutGraph(graph, CUSTOM_CLUSTER);
    // Exactly the three real nodes come back — `cluster:alpha`/`cluster:beta` (dagre's own
    // grouping nodes, per the "never set rankdir on a cluster" note in graphLayout.ts) are
    // an internal layout detail, never returned as if they were graph entities.
    expect(nodes.map((n) => n.id)).toEqual(["a", "b", "x"]);
    expect(nodes.some((n) => n.id.startsWith("cluster:"))).toBe(false);
  });
});

describe("clusterFrames with a custom keyOf", () => {
  const laidOutFake = (id: string, group: string, x: number, y: number) => ({
    id,
    type: "fake" as const,
    position: { x, y },
    data: { apiNode: { id, group } },
  });

  test("labels each frame by the accessor's own key (not a hardcoded 'namespace' field)", () => {
    const frames = clusterFrames(
      [laidOutFake("a", "alpha", 0, 0), laidOutFake("b", "beta", 900, 0)],
      (n: FakeNode) => n.group,
    );
    expect(frames.map((f) => ({ key: f.key, label: f.label }))).toEqual([
      { key: "alpha", label: "alpha" },
      { key: "beta", label: "beta" },
    ]);
  });

  test("fewer than two keys draws no frame", () => {
    const frames = clusterFrames(
      [laidOutFake("a", "alpha", 0, 0), laidOutFake("b", "alpha", 300, 0)],
      (n: FakeNode) => n.group,
    );
    expect(frames).toEqual([]);
  });
});
