import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import type { CatalogFileResponse } from "../api/catalogFiles";
import {
  catalogFileFormValidation,
  EMPTY_CATALOG_FILE_FORM,
  fromCatalogFileResponse,
  isValidEntityRef,
  isValidName,
  toCatalogFileRequest,
} from "./catalogFileForm";

const t = i18n.t;

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

  test("mirrors the server's field rules", () => {
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
    expect(rules.type("service")).toBeNull();
    expect(rules.type("  ")).not.toBeNull();
    expect(rules.lifecycle("two words")).not.toBeNull();
    expect(rules.owner("")).not.toBeNull();
    expect(rules.owner("group:default/platform")).toBeNull();
    expect(rules.system("")).toBeNull();
    expect(rules.system("a:b:c")).not.toBeNull();
    expect(rules.dependsOn(["resource:db", ""])).toBeNull(); // blanks are dropped, not errors
    expect(rules.dependsOn(["bad ref"])).not.toBeNull();
  });
});

describe("toCatalogFileRequest / fromCatalogFileResponse", () => {
  test("trims, folds the namespace, and drops empties", () => {
    const req = toCatalogFileRequest({
      ...EMPTY_CATALOG_FILE_FORM,
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
    });
    expect(req).toEqual({
      metadata: {
        name: "my-svc",
        namespace: "team-a",
        title: undefined,
        description: undefined,
        labels: { tier: "backend" },
        annotations: {},
        tags: ["java"],
        links: [{ url: "https://example.com", title: undefined, icon: undefined }],
      },
      spec: {
        type: "service",
        lifecycle: "production",
        owner: "group:platform",
        system: undefined,
        subcomponentOf: undefined,
        providesApis: ["svc-api"],
        consumesApis: [],
        dependsOn: [],
        dependencyOf: [],
      },
    });
  });

  test("a blank namespace becomes the default namespace", () => {
    const req = toCatalogFileRequest({
      ...EMPTY_CATALOG_FILE_FORM,
      name: "n",
      type: "t",
      lifecycle: "l",
      owner: "o",
    });
    expect(req.metadata.namespace).toBe("default");
  });

  test("fromCatalogFileResponse round-trips through toCatalogFileRequest", () => {
    const response: CatalogFileResponse = {
      id: 7,
      metadata: {
        name: "svc",
        namespace: "team-a",
        title: "Svc",
        description: "d",
        labels: { tier: "backend" },
        annotations: { "github.com/project-slug": "acme/svc" },
        tags: ["java"],
        links: [{ url: "https://example.com", title: "Home", icon: "dashboard" }],
      },
      spec: {
        type: "service",
        lifecycle: "production",
        owner: "group:platform",
        system: "payments",
        subcomponentOf: null,
        providesApis: ["svc-api"],
        consumesApis: [],
        dependsOn: [],
        dependencyOf: [],
      },
      createdBy: 1,
      creatorName: "Casey",
      creatorDeleted: false,
      createdAt: 1000,
      updatedAt: 2000,
    };
    const req = toCatalogFileRequest(fromCatalogFileResponse(response));
    expect(req.metadata).toEqual({ ...response.metadata, links: response.metadata.links });
    expect(req.spec).toEqual({ ...response.spec, subcomponentOf: undefined });
  });
});
