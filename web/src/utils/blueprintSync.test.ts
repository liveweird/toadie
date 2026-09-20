import { describe, expect, test } from "vitest";
import { canonicalBlueprintDocumentJson, pickSourceBlueprintDocument } from "./blueprintSync";

describe("canonicalBlueprintDocumentJson", () => {
  test("orders top-level keys in the fixed identity order, only those present", () => {
    const json = canonicalBlueprintDocumentJson({
      relations: {},
      schema: { properties: {}, required: [] },
      identifier: "service",
      title: "Service",
    });
    expect(Object.keys(JSON.parse(json))).toEqual(["identifier", "title", "schema", "relations"]);
  });

  test("orders unrecognized top-level keys alphabetically after the fixed ones", () => {
    const json = canonicalBlueprintDocumentJson({
      zeta: 1,
      identifier: "service",
      alpha: 2,
      title: "Service",
    });
    expect(Object.keys(JSON.parse(json))).toEqual(["identifier", "title", "alpha", "zeta"]);
  });

  test("sorts nested object keys recursively, but keeps array element order", () => {
    const json = canonicalBlueprintDocumentJson({
      identifier: "service",
      title: "Service",
      schema: { properties: { zeta: { type: "string" }, alpha: { type: "string" } }, required: ["z", "a"] },
    });
    const parsed = JSON.parse(json) as { schema: { properties: Record<string, unknown>; required: string[] } };
    expect(Object.keys(parsed.schema.properties)).toEqual(["alpha", "zeta"]);
    expect(parsed.schema.required).toEqual(["z", "a"]);
  });

  test("renders via JSON.stringify(..., null, 2) — two-space indentation", () => {
    const json = canonicalBlueprintDocumentJson({ identifier: "service", title: "Service" });
    expect(json).toBe('{\n  "identifier": "service",\n  "title": "Service"\n}');
  });
});

const TARGET = { identifier: "service" };

describe("pickSourceBlueprintDocument", () => {
  test("a bare document is taken as-is", () => {
    const result = pickSourceBlueprintDocument(
      JSON.stringify({ identifier: "service", title: "Service" }),
      TARGET,
      {},
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "service", title: "Service" });
    expect(result.hierarchyKept).toBe(false);
  });

  test("a bare document permits a source-side identifier rename", () => {
    const result = pickSourceBlueprintDocument(
      JSON.stringify({ identifier: "service-renamed", title: "Service" }),
      TARGET,
      {},
    );
    expect(result.error).toBeNull();
    expect(result.document?.identifier).toBe("service-renamed");
  });

  test("an {ok, blueprint} envelope is unwrapped to its single document", () => {
    const result = pickSourceBlueprintDocument(
      JSON.stringify({ ok: true, blueprint: { identifier: "service", title: "Service" } }),
      TARGET,
      {},
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "service", title: "Service" });
  });

  test("several documents ({blueprints:[...]}) are picked by identifier, case-insensitively", () => {
    const result = pickSourceBlueprintDocument(
      JSON.stringify({
        blueprints: [
          { identifier: "team", title: "Team" },
          { identifier: "SERVICE", title: "Service" },
        ],
      }),
      TARGET,
      {},
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "SERVICE", title: "Service" });
  });

  test("several documents: a candidate missing an identifier never matches", () => {
    const result = pickSourceBlueprintDocument(
      JSON.stringify({
        blueprints: [{ title: "No identifier" }, { identifier: "service", title: "Service" }],
      }),
      TARGET,
      {},
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "service", title: "Service" });
  });

  test("several documents with no matching identifier is a noMatch", () => {
    const result = pickSourceBlueprintDocument(
      JSON.stringify({
        blueprints: [
          { identifier: "team", title: "Team" },
          { identifier: "domain", title: "Domain" },
        ],
      }),
      TARGET,
      {},
    );
    expect(result).toEqual({ document: null, error: "noMatch", hierarchyKept: false });
  });

  test("zero candidate documents is a noMatch", () => {
    const result = pickSourceBlueprintDocument(JSON.stringify({ blueprints: [] }), TARGET, {});
    expect(result).toEqual({ document: null, error: "noMatch", hierarchyKept: false });
  });

  test("invalid JSON is a parse error", () => {
    const result = pickSourceBlueprintDocument("{not json", TARGET, {});
    expect(result).toEqual({ document: null, error: "parse", hierarchyKept: false });
  });

  test("a null description/icon reads as unset, the same as an omitted key", () => {
    const result = pickSourceBlueprintDocument(
      JSON.stringify({ identifier: "service", title: "Service", description: null, icon: null }),
      TARGET,
      {},
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "service", title: "Service" });
  });

  test("id/createdAt and other read-only keys are sanitized away", () => {
    const result = pickSourceBlueprintDocument(
      JSON.stringify({
        id: 42,
        identifier: "service",
        title: "Service",
        createdAt: 1_700_000_000_000,
        createdBy: 7,
        system: false,
        sourceUrl: "https://example.com/service.json",
        lastSyncedAt: 1_700_000_000_000,
      }),
      TARGET,
      {},
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "service", title: "Service" });
  });

  describe("the hierarchyRelations keep-when-absent mirror", () => {
    test("a picked document with no hierarchyRelations keeps the stored map", () => {
      const result = pickSourceBlueprintDocument(
        JSON.stringify({ identifier: "service", title: "Service" }),
        TARGET,
        { hierarchyRelations: { composition: "parent" } },
      );
      expect(result.error).toBeNull();
      expect(result.hierarchyKept).toBe(true);
      expect(result.document).toEqual({
        identifier: "service",
        title: "Service",
        hierarchyRelations: { composition: "parent" },
      });
    });

    test("a picked document with its OWN non-empty hierarchyRelations is used as-is", () => {
      const result = pickSourceBlueprintDocument(
        JSON.stringify({
          identifier: "service",
          title: "Service",
          hierarchyRelations: { deployment: "cluster" },
        }),
        TARGET,
        { hierarchyRelations: { composition: "parent" } },
      );
      expect(result.error).toBeNull();
      expect(result.hierarchyKept).toBe(false);
      expect(result.document).toEqual({
        identifier: "service",
        title: "Service",
        hierarchyRelations: { deployment: "cluster" },
      });
    });

    test("no stored map and none picked leaves the document without one", () => {
      const result = pickSourceBlueprintDocument(
        JSON.stringify({ identifier: "service", title: "Service" }),
        TARGET,
        {},
      );
      expect(result.hierarchyKept).toBe(false);
      expect(result.document).toEqual({ identifier: "service", title: "Service" });
    });

    test("an empty hierarchyRelations object in the picked document still triggers the keep", () => {
      const result = pickSourceBlueprintDocument(
        JSON.stringify({ identifier: "service", title: "Service", hierarchyRelations: {} }),
        TARGET,
        { hierarchyRelations: { composition: "parent" } },
      );
      expect(result.hierarchyKept).toBe(true);
      expect(result.document).toEqual({
        identifier: "service",
        title: "Service",
        hierarchyRelations: { composition: "parent" },
      });
    });
  });
});
