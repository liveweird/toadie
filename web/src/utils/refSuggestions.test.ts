import { describe, expect, test } from "vitest";
import { refSuggestions, shortestRef } from "./refSuggestions";

const id = (kind: string, namespace: string, name: string) => ({ kind, namespace, name });

describe("shortestRef", () => {
  test("shrinks to the shortest form that still resolves", () => {
    // Default kind + same namespace → bare name.
    expect(shortestRef(id("group", "default", "team-a"), "group", "default")).toBe("team-a");
    // Default kind, other namespace → namespace/name.
    expect(shortestRef(id("group", "team-x", "team-a"), "group", "default")).toBe("team-x/team-a");
    // Non-default kind, same namespace → kind:name.
    expect(shortestRef(id("user", "default", "jdoe"), "group", "default")).toBe("user:jdoe");
    // Non-default kind, other namespace → the full form.
    expect(shortestRef(id("user", "team-x", "jdoe"), "group", "default")).toBe("user:team-x/jdoe");
    // No default kind (dependsOn/dependencyOf) → always kind-prefixed.
    expect(shortestRef(id("component", "default", "svc"), null, "default")).toBe("component:svc");
  });

  test("matches case-insensitively and emits lowercase kinds", () => {
    expect(shortestRef(id("Group", "Default", "team-a"), "group", "DEFAULT")).toBe("team-a");
    expect(shortestRef(id("API", "team-x", "billing"), null, "default")).toBe("api:team-x/billing");
  });
});

describe("refSuggestions", () => {
  const pool = [
    id("Component", "default", "svc-a"),
    id("Component", "team-x", "svc-b"),
    id("Resource", "default", "orders-db"),
    id("API", "default", "billing-api"),
    id("Group", "default", "team-a"),
    id("User", "default", "jdoe"),
    id("System", "default", "payments"),
  ];

  test("filters by the field's target kinds and shortens per the current namespace", () => {
    expect(refSuggestions(pool, "owner", "default")).toEqual(["team-a", "user:jdoe"]);
    expect(refSuggestions(pool, "providesApis", "default")).toEqual(["billing-api"]);
    expect(refSuggestions(pool, "dependsOn", "default")).toEqual([
      "component:svc-a",
      "component:team-x/svc-b",
      "resource:orders-db",
    ]);
    expect(refSuggestions(pool, "members", "default")).toEqual(["jdoe"]);
    expect(refSuggestions(pool, "system", "default")).toEqual(["payments"]);
  });

  test("a blank current namespace means default; another namespace lengthens same-kind refs", () => {
    expect(refSuggestions(pool, "owner", "  ")).toEqual(["team-a", "user:jdoe"]);
    expect(refSuggestions(pool, "owner", "team-x")).toEqual(["default/team-a", "user:default/jdoe"]);
  });

  test("dedupes and tolerates an absent pool", () => {
    expect(refSuggestions(undefined, "owner", "default")).toEqual([]);
    const dupes = [...pool, id("group", "DEFAULT", "team-a")];
    expect(refSuggestions(dupes, "parent", "default")).toEqual(["team-a"]);
  });
});
