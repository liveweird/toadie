import { describe, expect, test } from "vitest";
import { refSuggestions } from "./refSuggestions";

const id = (kind: string, namespace: string, name: string) => ({ kind, namespace, name });

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

  test("filters by the field's target kinds and offers the full identity form", () => {
    expect(refSuggestions(pool, "owner")).toEqual(["group:default/team-a", "user:default/jdoe"]);
    expect(refSuggestions(pool, "providesApis")).toEqual(["api:default/billing-api"]);
    expect(refSuggestions(pool, "dependsOn")).toEqual([
      "component:default/svc-a",
      "component:team-x/svc-b",
      "resource:default/orders-db",
    ]);
    expect(refSuggestions(pool, "members")).toEqual(["user:default/jdoe"]);
    expect(refSuggestions(pool, "system")).toEqual(["system:default/payments"]);
  });

  test("emits lowercase kinds and dedupes, tolerating an absent pool", () => {
    expect(refSuggestions(undefined, "owner")).toEqual([]);
    // A kind's stored casing folds to the canonical lowercase ref form → exact dupes collapse.
    const dupes = [...pool, id("group", "default", "team-a")];
    expect(refSuggestions(dupes, "parent")).toEqual(["group:default/team-a"]);
  });

  test("never offers the entity being edited (case-insensitively)", () => {
    expect(refSuggestions(pool, "dependsOn", id("Component", "DEFAULT", "SVC-A"))).toEqual([
      "component:team-x/svc-b",
      "resource:default/orders-db",
    ]);
    // A different identity (other namespace) excludes nothing.
    expect(refSuggestions(pool, "dependsOn", id("Component", "team-y", "svc-a"))).toHaveLength(3);
    expect(refSuggestions(pool, "dependsOn", null)).toHaveLength(3);
  });
});
