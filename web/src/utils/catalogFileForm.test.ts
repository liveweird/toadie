import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import type { CatalogFileResponse } from "../api/catalogFiles";
import {
  catalogFileFormValidation,
  emptyCatalogFileForm,
  fromCatalogFileResponse,
  isValidEntityRef,
  isValidName,
  toCatalogFileRequest,
  type CatalogFileFormValues,
  type EntityKind,
} from "./catalogFileForm";

const t = i18n.t;

const values = (overrides: Partial<CatalogFileFormValues>): CatalogFileFormValues => ({
  ...emptyCatalogFileForm(),
  ...overrides,
});

describe("isValidName", () => {
  test.each(["svc", "my-svc", "a_b.c-d", "A1"])("accepts %s", (v) => {
    expect(isValidName(v)).toBe(true);
  });

  test.each(["", "has space", "-lead", "trail-", "double..dot", "x".repeat(64)])(
    "rejects %s",
    (v) => {
      expect(isValidName(v)).toBe(false);
    },
  );
});

describe("isValidEntityRef", () => {
  test.each(["name", "kind:name", "ns/name", "group:default/platform", "API:team-a/billing"])(
    "accepts %s",
    (v) => {
      expect(isValidEntityRef(v)).toBe(true);
    },
  );

  test.each(["", "a:b:c", "kind:ns/x/y", "group:default/", "1kind:name", "bad ref", ":name", "/name"])(
    "rejects %s",
    (v) => {
      expect(isValidEntityRef(v)).toBe(false);
    },
  );
});

describe("catalogFileFormValidation", () => {
  const rules = catalogFileFormValidation(t);
  const component = values({ kind: "Component" });

  test("mirrors the server's kind-independent field rules", () => {
    expect(rules.name("ok-name")).toBeNull();
    expect(rules.name("bad name")).not.toBeNull();
    expect(rules.namespace("")).toBeNull();
    expect(rules.namespace("TEAM-A")).toBeNull(); // folded before checking
    expect(rules.namespace("under_score")).not.toBeNull();
    expect(rules.tags(["java", "c++"])).toBeNull();
    expect(rules.tags(["Upper"])).not.toBeNull();
    expect(rules.labels.key("example.com/tier")).toBeNull();
    expect(rules.labels.key("a/b/c")).not.toBeNull();
    expect(rules.labels.value("backend")).toBeNull();
    expect(rules.labels.value("has space")).not.toBeNull();
    expect(rules.annotations.key("backstage.io/orphan")).not.toBeNull(); // server-written
    expect(rules.annotations.value("x".repeat(5001))).not.toBeNull();
    expect(rules.links.url("https://example.com")).toBeNull();
    expect(rules.links.url("/relative")).not.toBeNull();
    expect(rules.links.icon("")).toBeNull();
    expect(rules.links.icon("bad icon")).not.toBeNull();
  });

  test("mirrors the server's Component rules", () => {
    expect(rules.type("service", component)).toBeNull();
    expect(rules.type("  ", component)).not.toBeNull();
    expect(rules.lifecycle("two words", component)).not.toBeNull();
    expect(rules.owner("", component)).not.toBeNull();
    expect(rules.owner("group:default/platform", component)).toBeNull();
    expect(rules.system("", component)).toBeNull();
    expect(rules.system("a:b:c", component)).not.toBeNull();
    expect(rules.dependsOn(["resource:db", ""], component)).toBeNull(); // blanks are dropped
    expect(rules.dependsOn(["bad ref"], component)).not.toBeNull();
  });

  test("required-ness follows the kind", () => {
    const system = values({ kind: "System" });
    const user = values({ kind: "User" });
    const api = values({ kind: "API" });
    // type is required for Component but optional for System, absent for User.
    expect(rules.type("", component)).not.toBeNull();
    expect(rules.type("", system)).toBeNull();
    expect(rules.type("whatever", user)).toBeNull(); // not applicable → never an error
    // lifecycle applies only to Component/API.
    expect(rules.lifecycle("", system)).toBeNull();
    expect(rules.lifecycle("", api)).not.toBeNull();
    // owner is required for System but doesn't apply to User.
    expect(rules.owner("", system)).not.toBeNull();
    expect(rules.owner("", user)).toBeNull();
    // definition is required for API only.
    expect(rules.definition("", api)).not.toBeNull();
    expect(rules.definition("", component)).toBeNull();
    expect(rules.definition("openapi: 3.0.0", api)).toBeNull();
    // profile picture must be an absolute URI where profile applies.
    expect(rules.profilePicture("/rel.png", user)).not.toBeNull();
    expect(rules.profilePicture("https://x.example/a.png", user)).toBeNull();
    expect(rules.profilePicture("/rel.png", component)).toBeNull(); // not applicable
    // membership refs validate for their kinds.
    expect(rules.memberOf(["bad ref"], user)).not.toBeNull();
    expect(rules.children(["group:teams/a"], values({ kind: "Group" }))).toBeNull();
  });
});

describe("toCatalogFileRequest / fromCatalogFileResponse", () => {
  test("trims, folds the namespace, and drops empties (Component)", () => {
    const req = toCatalogFileRequest(
      values({
        name: "  my-svc  ",
        namespace: "  TEAM-A ",
        title: "  ",
        tags: [" java ", ""],
        labels: [
          { key: " tier ", value: " backend " },
          { key: "", value: "ignored" },
        ],
        links: [
          { url: " https://example.com ", title: "", icon: "" },
          { url: "", title: "dropped", icon: "" },
        ],
        type: " service ",
        lifecycle: "production",
        owner: " group:platform ",
        system: "  ",
        providesApis: [" svc-api "],
      }),
    );
    expect(req).toEqual({
      kind: "Component",
      metadata: {
        name: "my-svc",
        namespace: "team-a",
        labels: { tier: "backend" },
        annotations: {},
        tags: ["java"],
        links: [{ url: "https://example.com" }],
      },
      spec: {
        type: "service",
        lifecycle: "production",
        owner: "group:platform",
        providesApis: ["svc-api"],
        consumesApis: [],
        dependsOn: [],
        dependencyOf: [],
      },
    });
  });

  test("strips fields foreign to the kind (a kind switch leaves no residue)", () => {
    const req = toCatalogFileRequest(
      values({
        kind: "System",
        name: "payments",
        owner: "team-a",
        domain: "commerce",
        // Leftovers from a previous Component/API/Group phase of the form:
        lifecycle: "production",
        definition: "openapi: 3.0.0",
        dependsOn: ["resource:db"],
        children: ["group:x"],
        memberOf: ["group:y"],
      }),
    );
    expect(req.spec).toEqual({ owner: "team-a", domain: "commerce" });
  });

  test("Group children and User memberOf stay present even when empty", () => {
    const group = toCatalogFileRequest(values({ kind: "Group", name: "team-a", type: "team" }));
    expect(group.spec.children).toEqual([]);
    expect(group.spec.members).toEqual([]);
    const user = toCatalogFileRequest(values({ kind: "User", name: "jdoe" }));
    expect(user.spec.memberOf).toEqual([]);
    expect(user.spec.children).toBeUndefined();
  });

  test("profile is emitted only when a field is set, and round-trips", () => {
    const bare = toCatalogFileRequest(values({ kind: "User", name: "jdoe" }));
    expect(bare.spec.profile).toBeUndefined();
    const withProfile = toCatalogFileRequest(
      values({ kind: "User", name: "jdoe", profileDisplayName: " Jane Doe ", profileEmail: "j@x.dev" }),
    );
    expect(withProfile.spec.profile).toEqual({ displayName: "Jane Doe", email: "j@x.dev" });
  });

  test.each(["Component", "API", "System", "Domain", "Resource", "Group", "User"] as EntityKind[])(
    "fromCatalogFileResponse round-trips a %s through toCatalogFileRequest",
    (kind) => {
      const response: CatalogFileResponse = {
        id: 7,
        kind,
        metadata: { name: "thing", namespace: "team-a" },
        spec: {
          type: kind === "User" ? null : "one-word",
          lifecycle: kind === "Component" || kind === "API" ? "production" : null,
          owner: kind === "Group" || kind === "User" ? null : "team-a",
          definition: kind === "API" ? "openapi: 3.0.0" : null,
          children: kind === "Group" ? ["group:sub"] : null,
          members: kind === "Group" ? ["user:jdoe"] : [],
          memberOf: kind === "User" ? [] : null,
          domain: kind === "System" ? "commerce" : null,
          subdomainOf: kind === "Domain" ? "commerce" : null,
          providesApis: [],
          consumesApis: [],
          dependsOn: kind === "Component" || kind === "Resource" ? ["resource:db"] : [],
          dependencyOf: [],
          system: null,
          subcomponentOf: null,
          profile: null,
          parent: null,
        },
        createdBy: 1,
        creatorName: "Casey",
        creatorDeleted: false,
        createdAt: 1000,
        updatedAt: 2000,
        sourceUrl: null,
        lastSyncedAt: 0,
      };
      const req = toCatalogFileRequest(fromCatalogFileResponse(response));
      expect(req.kind).toBe(kind);
      // Every field the kind carries survives; nulls and foreign fields vanish.
      if (kind === "API") expect(req.spec.definition).toBe("openapi: 3.0.0");
      if (kind === "Group") expect(req.spec.children).toEqual(["group:sub"]);
      if (kind === "User") expect(req.spec.memberOf).toEqual([]);
      if (kind === "System") expect(req.spec.domain).toBe("commerce");
      if (kind === "Domain") expect(req.spec.subdomainOf).toBe("commerce");
      if (kind === "Component" || kind === "Resource") {
        expect(req.spec.dependsOn).toEqual(["resource:db"]);
      } else {
        expect(req.spec.dependsOn ?? []).toEqual([]);
      }
    },
  );
});

describe("reference validation is grammar-only (resolution is the server's soft check)", () => {
  const rules = catalogFileFormValidation(t);
  const component = values({ kind: "Component" });

  test("well-formed references pass regardless of resolution — the Save-anyway flow owns that", () => {
    expect(rules.owner("ghost-team", component)).toBeNull();
    expect(rules.owner("component:default/anything", component)).toBeNull();
    expect(rules.dependsOn(["svc-a", "component:default/svc-a"], component)).toBeNull();
    expect(rules.subcomponentOf("component:default/self-name", values({ kind: "Component", name: "self-name" }))).toBeNull();
  });

  test("malformed references still fail on grammar", () => {
    expect(rules.owner("a:b:c", component)).toMatch(/entity reference/);
    expect(rules.dependsOn(["bad ref"], component)).toMatch(/"bad ref"/);
  });
});
