import { describe, expect, test } from "vitest";
import { refResolutionError, refSuggestions } from "./refSuggestions";

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
});

describe("refResolutionError", () => {
  const id = (kind: string, namespace: string, name: string) => ({ kind, namespace, name });
  const pool = [
    id("Component", "default", "svc-a"),
    id("Group", "default", "team-a"),
    id("User", "default", "jdoe"),
  ];

  test("resolves via default kind, explicit forms, and case-insensitively", () => {
    expect(refResolutionError("team-a", "owner", "default", pool)).toBeNull();
    expect(refResolutionError("user:default/jdoe", "owner", "default", pool)).toBeNull();
    expect(refResolutionError("Component:DEFAULT/SVC-A", "subcomponentOf", "default", pool)).toBeNull();
    // Namespaceless refs resolve in the CURRENT namespace.
    expect(refResolutionError("team-a", "owner", "team-x", pool)).toBe("unresolved");
  });

  test("kind rules apply even without a pool; membership needs one", () => {
    expect(refResolutionError("svc-a", "dependsOn", "default", pool)).toBe("kindRequired");
    expect(refResolutionError("component:default/svc-a", "owner", "default", pool)).toBe("wrongKind");
    expect(refResolutionError("template:default/x", "dependsOn", "default", [])).toBe("wrongKind");
    expect(refResolutionError("nobody", "owner", "default", pool)).toBe("unresolved");
    // An unavailable pool skips the membership half (server stays the gate).
    expect(refResolutionError("nobody", "owner", "default", undefined)).toBeNull();
    expect(refResolutionError("nobody", "owner", "default", [])).toBeNull();
    // Unparsable refs are the grammar rule's problem, never a verdict here.
    expect(refResolutionError("a:b:c", "owner", "default", pool)).toBeNull();
  });
});
