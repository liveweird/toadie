import { describe, expect, test } from "vitest";
import type { CatalogGraph, GraphNode } from "../api/catalogFiles";
import { buildHierarchy, type HierarchyNode } from "./hierarchy";

function node(
  kind: string,
  name: string,
  status: GraphNode["status"] = "STORED",
  fileId: number | null = 1,
): GraphNode {
  return {
    id: `${kind}:default/${name}`,
    kind,
    namespace: "default",
    name,
    status,
    fileId: status === "STORED" ? fileId : null,
  };
}

function edge(source: GraphNode, field: string, target: GraphNode) {
  return { sourceId: source.id, targetId: target.id, field };
}

/** Flatten a tree into "indented path" strings for compact structural asserts. */
function shape(items: HierarchyNode[], prefix = ""): string[] {
  return items.flatMap((item) => [
    `${prefix}${item.node.kind}:${item.node.name}`,
    ...shape(item.children, `${prefix}${item.node.kind}:${item.node.name} > `),
  ]);
}

describe("buildHierarchy", () => {
  test("domain, system, component, and subcomponent nest through the containment chain", () => {
    const domain = node("domain", "payments");
    const system = node("system", "billing");
    const component = node("component", "billing-core");
    const sub = node("component", "billing-worker");
    const graph: CatalogGraph = {
      nodes: [sub, component, system, domain],
      edges: [
        edge(system, "spec.domain", domain),
        edge(component, "spec.system", system),
        edge(sub, "spec.system", system),
        edge(sub, "spec.subcomponentOf", component),
      ],
    };
    // Most-specific placement: the subcomponent sits under its parent component ONLY,
    // never a second time directly under the system.
    expect(shape(buildHierarchy(graph))).toEqual([
      "domain:payments",
      "domain:payments > system:billing",
      "domain:payments > system:billing > component:billing-core",
      "domain:payments > system:billing > component:billing-core > component:billing-worker",
    ]);
  });

  test("the redundant parent/children and members/memberOf pairs dedupe to one nesting", () => {
    const parent = node("group", "engineering");
    const child = node("group", "platform");
    const user = node("user", "jdoe");
    const graph: CatalogGraph = {
      nodes: [parent, child, user],
      edges: [
        edge(child, "spec.parent", parent),
        edge(parent, "spec.children", child),
        edge(user, "spec.memberOf", child),
        edge(child, "spec.members", user),
      ],
    };
    expect(shape(buildHierarchy(graph))).toEqual([
      "group:engineering",
      "group:engineering > group:platform",
      "group:engineering > group:platform > user:jdoe",
    ]);
  });

  test("a user in several groups appears under each (membership is multi-parent)", () => {
    const a = node("group", "alpha");
    const b = node("group", "beta");
    const user = node("user", "jdoe");
    const graph: CatalogGraph = {
      nodes: [a, b, user],
      edges: [edge(user, "spec.memberOf", a), edge(user, "spec.memberOf", b)],
    };
    expect(shape(buildHierarchy(graph))).toEqual([
      "group:alpha",
      "group:alpha > user:jdoe",
      "group:beta",
      "group:beta > user:jdoe",
    ]);
  });

  test("a MISSING containment parent renders as a placeholder root with its children nested", () => {
    const gone = node("system", "deleted-sys", "MISSING");
    const component = node("component", "orphaned");
    const graph: CatalogGraph = {
      nodes: [component, gone],
      edges: [edge(component, "spec.system", gone)],
    };
    expect(shape(buildHierarchy(graph))).toEqual([
      "system:deleted-sys",
      "system:deleted-sys > component:orphaned",
    ]);
  });

  test("virtual nodes reachable only through non-containment edges stay out of the tree", () => {
    const component = node("component", "svc");
    const ownerGroup = node("group", "ghost-owners", "MISSING");
    const dep = node("resource", "ghost-db", "MISSING");
    const graph: CatalogGraph = {
      nodes: [component, ownerGroup, dep],
      edges: [edge(component, "spec.owner", ownerGroup), edge(component, "spec.dependsOn", dep)],
    };
    expect(shape(buildHierarchy(graph))).toEqual(["component:svc"]);
  });

  test("a subcomponent cycle is broken by promoting one node to a root", () => {
    const a = node("component", "a");
    const b = node("component", "b");
    const graph: CatalogGraph = {
      nodes: [a, b],
      edges: [edge(a, "spec.subcomponentOf", b), edge(b, "spec.subcomponentOf", a)],
    };
    // Neither has a cycle-free parent chain: the sorted-first node is promoted, the loop
    // edge back up is dropped, and every node renders exactly once.
    expect(shape(buildHierarchy(graph))).toEqual(["component:a", "component:a > component:b"]);
  });

  test("roots and siblings sort by kind rank then name", () => {
    const graph: CatalogGraph = {
      nodes: [node("user", "zoe"), node("component", "b-svc"), node("component", "a-svc"), node("domain", "core")],
      edges: [],
    };
    expect(shape(buildHierarchy(graph))).toEqual([
      "domain:core",
      "component:a-svc",
      "component:b-svc",
      "user:zoe",
    ]);
  });
});
