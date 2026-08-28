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

describe("refResolutionError", () => {
  const id = (kind: string, namespace: string, name: string) => ({ kind, namespace, name });
  const pool = [
    id("Component", "default", "svc-a"),
    id("Group", "default", "team-a"),
    id("User", "default", "jdoe"),
  ];

  test("resolves via default kind, explicit forms, and case-insensitively", () => {
    expect(refResolutionError("team-a", "owner", "default", null, pool)).toBeNull();
    expect(refResolutionError("user:default/jdoe", "owner", "default", null, pool)).toBeNull();
    expect(refResolutionError("Component:DEFAULT/SVC-A", "subcomponentOf", "default", null, pool)).toBeNull();
    // Namespaceless refs resolve in the CURRENT namespace.
    expect(refResolutionError("team-a", "owner", "team-x", null, pool)).toBe("unresolved");
  });

  test("kind rules apply even without a pool; membership needs one", () => {
    expect(refResolutionError("svc-a", "dependsOn", "default", null, pool)).toBe("kindRequired");
    expect(refResolutionError("component:default/svc-a", "owner", "default", null, pool)).toBe("wrongKind");
    expect(refResolutionError("template:default/x", "dependsOn", "default", null, [])).toBe("wrongKind");
    expect(refResolutionError("nobody", "owner", "default", null, pool)).toBe("unresolved");
    // An unavailable pool skips the membership half (server stays the gate).
    expect(refResolutionError("nobody", "owner", "default", null, undefined)).toBeNull();
    expect(refResolutionError("nobody", "owner", "default", null, [])).toBeNull();
    // Unparsable refs are the grammar rule's problem, never a verdict here.
    expect(refResolutionError("a:b:c", "owner", "default", null, pool)).toBeNull();
  });

  test("a reference to the entity itself is selfReference, pool or no pool", () => {
    const self = id("Component", "default", "svc-a");
    // Full form, short form (default kind + current namespace), and case-variant all match.
    expect(refResolutionError("component:default/svc-a", "subcomponentOf", "default", self, pool)).toBe(
      "selfReference",
    );
    expect(refResolutionError("svc-a", "subcomponentOf", "default", self, pool)).toBe("selfReference");
    expect(refResolutionError("Component:DEFAULT/SVC-A", "subcomponentOf", "default", self, pool)).toBe(
      "selfReference",
    );
    // The self check needs no pool — it fires even while the pool is unavailable.
    expect(refResolutionError("component:default/svc-a", "dependsOn", "default", self, undefined)).toBe(
      "selfReference",
    );
    // Another namespace is a different identity; wrong-kind still wins over self.
    expect(refResolutionError("component:team-x/svc-a", "subcomponentOf", "default", self, pool)).toBe(
      "unresolved",
    );
    expect(refResolutionError("component:default/svc-a", "owner", "default", self, pool)).toBe("wrongKind");
  });
});
