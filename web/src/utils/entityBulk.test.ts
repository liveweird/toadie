import { describe, expect, test } from "vitest";
import { blueprintResponse } from "../test/fixtures";
import type { EntityGraphNode } from "../api/entities";
import type { HierarchyNode } from "./hierarchy";
import {
  ancestorBlueprints,
  bulkCounts,
  bulkFields,
  bulkValueOf,
  checkState,
  formatBulkValue,
  hasBulkValue,
  operationsFor,
  pruneToBlueprint,
  singleVisibleBlueprint,
  targetsUnder,
  toBulkRequest,
  valuePaths,
  withSelection,
} from "./entityBulk";

const rel = (target: string, many = false) => ({ title: target, target, required: false, many });

const BLUEPRINTS = [
  blueprintResponse({ identifier: "service", relations: { team: rel("team"), peer: rel("service") }, hierarchyRelations: { composition: "team", cost: "peer" } }),
  blueprintResponse({ identifier: "team", relations: { dept: rel("dept") }, hierarchyRelations: { composition: "dept" } }),
  blueprintResponse({ identifier: "dept", relations: { boss: rel("service") }, hierarchyRelations: { composition: "boss" } }),
];

function node(blueprint: string, identifier: string, entityId: number): EntityGraphNode {
  return { id: `${blueprint}|${identifier}`, entityId, blueprint, blueprintTitle: blueprint, identifier, title: identifier, findings: 0 };
}
const tree = (n: EntityGraphNode, children: HierarchyNode<EntityGraphNode>[] = []): HierarchyNode<EntityGraphNode> => ({ node: n, children });

describe("ancestorBlueprints", () => {
  test("follows the selected hierarchy upward and stops at a loop back to the start", () => {
    expect(ancestorBlueprints("service", BLUEPRINTS, "composition")).toEqual(["team", "dept"]);
  });
  test("a self-referencing relation yields no ancestors", () => {
    expect(ancestorBlueprints("service", BLUEPRINTS, "cost")).toEqual([]);
  });
  test("no hierarchy, an unknown blueprint or an undeclared relation yields none", () => {
    expect(ancestorBlueprints("service", BLUEPRINTS, "")).toEqual([]);
    expect(ancestorBlueprints("ghost", BLUEPRINTS, "composition")).toEqual([]);
    const stale = [blueprintResponse({ identifier: "a", relations: {}, hierarchyRelations: { composition: "gone" } })];
    expect(ancestorBlueprints("a", stale, "composition")).toEqual([]);
  });
});

describe("tree pruning and selection", () => {
  const forest = [
    tree(node("dept", "d1", 1), [
      tree(node("team", "t1", 2), [tree(node("service", "s1", 3)), tree(node("service", "s2", 4))]),
      tree(node("team", "t-empty", 5)),
    ]),
    tree(node("dept", "d-empty", 6)),
    tree(node("service", "s-root", 7)),
  ];

  test("pruneToBlueprint keeps targets and the context rows above them only", () => {
    const pruned = pruneToBlueprint(forest, "service");
    expect(pruned.map((r) => r.node.identifier)).toEqual(["d1", "s-root"]);
    expect(pruned[0].children.map((c) => c.node.identifier)).toEqual(["t1"]);
    expect(pruned[0].children[0].children.map((c) => c.node.identifier)).toEqual(["s1", "s2"]);
  });

  test("targetsUnder lists targets depth-first, including the item itself", () => {
    const pruned = pruneToBlueprint(forest, "service");
    expect(targetsUnder(pruned, "service").map((n) => n.entityId)).toEqual([3, 4, 7]);
    expect(targetsUnder(pruned[0].children, "service").map((n) => n.entityId)).toEqual([3, 4]);
    expect(targetsUnder([tree(node("service", "p", 9), [tree(node("service", "c", 10))])], "service").map((n) => n.entityId)).toEqual([9, 10]);
  });

  test("checkState and withSelection do the tri-state arithmetic without mutating", () => {
    const selected = new Set([1]);
    expect(checkState([1, 2], selected)).toBe("some");
    expect(checkState([1], selected)).toBe("all");
    expect(checkState([2], selected)).toBe("none");
    expect(checkState([], selected)).toBe("none");
    const next = withSelection(selected, [2, 3], true);
    expect([...next]).toEqual([1, 2, 3]);
    expect([...selected]).toEqual([1]);
    expect([...withSelection(next, [1, 2], false)]).toEqual([3]);
  });
});

describe("fields and operations", () => {
  const blueprint = blueprintResponse({
    schema: {
      properties: { stage: { type: "string", title: "Stage" }, tags: { type: "array", items: { type: "string" } }, name: { type: "string" } },
      required: ["name"],
    },
    relations: { owner: { title: "Owner", target: "team", required: true, many: false }, deps: { title: "Deps", target: "service", required: false, many: true } },
  });

  test("bulkFields lists properties, relations and team with their list-ness and required flags", () => {
    const fields = bulkFields(blueprint, "Team");
    expect(fields.map((f) => [f.path, f.label, f.listLike, f.required, f.index])).toEqual([
      ["properties.stage", "Stage", false, false, 0],
      ["properties.tags", "tags", true, false, 1],
      ["properties.name", "name", false, true, 2],
      ["relations.owner", "Owner", false, true, 0],
      ["relations.deps", "Deps", true, false, 1],
      ["team", "Team", true, false, -1],
    ]);
  });

  test("an Inherited-ownership blueprint offers no team field", () => {
    const inherited = blueprintResponse({ ownership: { type: "Inherited", path: "owner" } });
    expect(bulkFields(inherited, "Team").map((f) => f.path)).not.toContain("team");
  });

  test("operationsFor disables Clear on a required field and offers Add/Remove on lists only", () => {
    const [stage, tags, name] = bulkFields(blueprint, "Team");
    expect(operationsFor(stage)).toEqual([{ operation: "SET", enabled: true }, { operation: "CLEAR", enabled: true }]);
    expect(operationsFor(tags).map((o) => o.operation)).toEqual(["SET", "CLEAR", "ADD", "REMOVE"]);
    expect(operationsFor(name).find((o) => o.operation === "CLEAR")?.enabled).toBe(false);
  });

  test("valuePaths names every draft slot of the picked field", () => {
    const fields = bulkFields(blueprint, "Team");
    expect(valuePaths(fields[1])).toEqual([
      "properties.1.text",
      "properties.1.bool",
      "properties.1.list",
      "properties.1.json",
    ]);
    expect(valuePaths(fields[3])).toEqual(["relations.0.single", "relations.0.many"]);
    expect(valuePaths(fields[5])).toEqual(["team"]);
  });

  test("bulkValueOf reads the picked field out of the request; hasBulkValue judges the contract", () => {
    const [stage, , , owner, , team] = bulkFields(blueprint, "Team");
    const request = { blueprint: "service", identifier: "", title: "", properties: { stage: "live" }, relations: { owner: "t1" }, team: ["a"] };
    expect(bulkValueOf(stage, request)).toBe("live");
    expect(bulkValueOf(owner, request)).toBe("t1");
    expect(bulkValueOf(team, request)).toEqual(["a"]);
    expect(hasBulkValue("CLEAR", undefined)).toBe(true);
    expect(hasBulkValue("SET", undefined)).toBe(false);
    expect(hasBulkValue("SET", false)).toBe(true);
    expect(hasBulkValue("ADD", [])).toBe(false);
    expect(hasBulkValue("REMOVE", ["x"])).toBe(true);
    expect(hasBulkValue("ADD", "x")).toBe(false);
  });
});

describe("requests and results", () => {
  const field = bulkFields(blueprintResponse({ schema: { properties: { stage: { type: "string" } }, required: [] } }), "Team")[0];

  test("toBulkRequest carries stamps only where known and never a value for CLEAR", () => {
    const set = toBulkRequest({ blueprint: "service", field, operation: "SET", value: "live", entities: [{ id: 1, updatedAt: 5 }, { id: 2 }] });
    expect(set).toEqual({ blueprint: "service", field: "properties.stage", operation: "SET", value: "live", entities: [{ id: 1, updatedAt: 5 }, { id: 2 }] });
    const clear = toBulkRequest({ blueprint: "service", field, operation: "CLEAR", value: "ignored", entities: [{ id: 1 }] });
    expect("value" in JSON.parse(JSON.stringify(clear))).toBe(false);
  });

  test("bulkCounts tallies statuses and formatBulkValue flattens values", () => {
    expect(bulkCounts([{ status: "UPDATED" }, { status: "UPDATED" }, { status: "STALE" }])).toEqual({
      UPDATED: 2, UNCHANGED: 0, INVALID: 0, STALE: 1, ERROR: 0,
    });
    expect(formatBulkValue(undefined)).toBeNull();
    expect(formatBulkValue("a")).toBe("a");
    expect(formatBulkValue(["a", 2])).toBe("a, 2");
    expect(formatBulkValue({ url: "x" })).toBe('{"url":"x"}');
    expect(formatBulkValue(true)).toBe("true");
  });

  test("singleVisibleBlueprint is the lone visible pill, else nothing", () => {
    expect(singleVisibleBlueprint(["a"], ["a", "b"])).toBe("a");
    expect(singleVisibleBlueprint(undefined, ["a"])).toBe("a");
    expect(singleVisibleBlueprint(undefined, ["a", "b"])).toBeUndefined();
    expect(singleVisibleBlueprint([], ["a", "b"])).toBeUndefined();
  });
});
