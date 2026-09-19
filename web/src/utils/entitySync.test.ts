import { describe, expect, test } from "vitest";
import { blueprintResponse } from "../test/fixtures";
import { canonicalEntityDocumentJson, pickSourceEntityDocument } from "./entitySync";

describe("canonicalEntityDocumentJson", () => {
  test("orders top-level keys in the fixed identity order, only those present", () => {
    const json = canonicalEntityDocumentJson({
      relations: {},
      properties: { a: 1 },
      identifier: "svc-a",
      blueprint: "service",
    });
    expect(Object.keys(JSON.parse(json))).toEqual(["blueprint", "identifier", "properties", "relations"]);
  });

  test("orders unrecognized top-level keys alphabetically after the fixed ones", () => {
    const json = canonicalEntityDocumentJson({
      zeta: 1,
      blueprint: "service",
      alpha: 2,
      identifier: "svc-a",
    });
    expect(Object.keys(JSON.parse(json))).toEqual(["blueprint", "identifier", "alpha", "zeta"]);
  });

  test("sorts nested object keys recursively, but keeps array element order", () => {
    const json = canonicalEntityDocumentJson({
      blueprint: "service",
      identifier: "svc-a",
      properties: { zeta: 1, alpha: { delta: 1, bravo: 2 } },
      relations: { owner: ["z", "a", "m"] },
    });
    const parsed = JSON.parse(json) as Record<string, unknown>;
    expect(Object.keys(parsed.properties as Record<string, unknown>)).toEqual(["alpha", "zeta"]);
    expect(Object.keys((parsed.properties as { alpha: object }).alpha)).toEqual(["bravo", "delta"]);
    expect((parsed.relations as { owner: string[] }).owner).toEqual(["z", "a", "m"]);
  });

  test("renders via JSON.stringify(..., null, 2) — two-space indentation", () => {
    const json = canonicalEntityDocumentJson({ blueprint: "service", identifier: "svc-a" });
    expect(json).toBe('{\n  "blueprint": "service",\n  "identifier": "svc-a"\n}');
  });
});

const TARGET = { blueprint: "service", identifier: "svc-a" };

describe("pickSourceEntityDocument", () => {
  test("a bare document injects the target blueprint when absent", () => {
    const result = pickSourceEntityDocument(
      JSON.stringify({ identifier: "svc-a", title: "Service A" }),
      TARGET,
      [],
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "svc-a", title: "Service A", blueprint: "service" });
    expect(result.strippedComputed).toEqual([]);
  });

  test("a bare document permits a source-side identifier rename", () => {
    const result = pickSourceEntityDocument(
      JSON.stringify({ identifier: "svc-a-renamed", blueprint: "service" }),
      TARGET,
      [],
    );
    expect(result.error).toBeNull();
    expect(result.document?.identifier).toBe("svc-a-renamed");
  });

  test("a single document naming a DIFFERENT blueprint is a blueprintMismatch", () => {
    const result = pickSourceEntityDocument(
      JSON.stringify({ identifier: "svc-a", blueprint: "other" }),
      TARGET,
      [],
    );
    expect(result).toEqual({ document: null, error: "blueprintMismatch", strippedComputed: [] });
  });

  test("an {ok, entity} envelope is unwrapped to its single document", () => {
    const result = pickSourceEntityDocument(
      JSON.stringify({ ok: true, entity: { identifier: "svc-a", blueprint: "service" } }),
      TARGET,
      [],
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "svc-a", blueprint: "service" });
  });

  test("several documents ({entities:[...]}) are picked by identifier, case-insensitively", () => {
    const result = pickSourceEntityDocument(
      JSON.stringify({
        entities: [
          { identifier: "svc-b", blueprint: "service" },
          { identifier: "SVC-A", blueprint: "service" },
        ],
      }),
      TARGET,
      [],
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "SVC-A", blueprint: "service" });
  });

  test("several documents: a blueprint-less candidate is still eligible", () => {
    const result = pickSourceEntityDocument(
      JSON.stringify({
        entities: [
          { identifier: "svc-b", blueprint: "other" },
          { identifier: "svc-a" },
        ],
      }),
      TARGET,
      [],
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "svc-a", blueprint: "service" });
  });

  test("several documents: a candidate missing an identifier never matches", () => {
    const result = pickSourceEntityDocument(
      JSON.stringify({
        entities: [{ title: "No identifier" }, { identifier: "svc-a", blueprint: "service" }],
      }),
      TARGET,
      [],
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "svc-a", blueprint: "service" });
  });

  test("several documents with no matching identifier is a noMatch", () => {
    const result = pickSourceEntityDocument(
      JSON.stringify({
        entities: [
          { identifier: "svc-b", blueprint: "service" },
          { identifier: "svc-c", blueprint: "service" },
        ],
      }),
      TARGET,
      [],
    );
    expect(result).toEqual({ document: null, error: "noMatch", strippedComputed: [] });
  });

  test("several documents matching by identifier but a different blueprint is a noMatch", () => {
    const result = pickSourceEntityDocument(
      JSON.stringify({
        entities: [
          { identifier: "svc-a", blueprint: "other" },
          { identifier: "svc-b", blueprint: "service" },
        ],
      }),
      TARGET,
      [],
    );
    expect(result).toEqual({ document: null, error: "noMatch", strippedComputed: [] });
  });

  test("zero candidate documents is a noMatch", () => {
    const result = pickSourceEntityDocument(JSON.stringify({ entities: [] }), TARGET, []);
    expect(result).toEqual({ document: null, error: "noMatch", strippedComputed: [] });
  });

  test("invalid JSON is a parse error", () => {
    const result = pickSourceEntityDocument("{not json", TARGET, []);
    expect(result).toEqual({ document: null, error: "parse", strippedComputed: [] });
  });

  test("a computed property id is stripped and reported", () => {
    const blueprint = blueprintResponse({
      identifier: "service",
      mirrorProperties: { region: { title: "Region", path: "owner.region" } },
    });
    const result = pickSourceEntityDocument(
      JSON.stringify({
        identifier: "svc-a",
        blueprint: "service",
        properties: { region: "eu-west", plan: "pro" },
      }),
      TARGET,
      [blueprint],
    );
    expect(result.error).toBeNull();
    expect(result.strippedComputed).toEqual(["region"]);
    expect(result.document).toEqual({
      identifier: "svc-a",
      blueprint: "service",
      properties: { plan: "pro" },
    });
  });

  test("id/createdAt and other read-only keys are sanitized away", () => {
    const result = pickSourceEntityDocument(
      JSON.stringify({
        id: 42,
        identifier: "svc-a",
        blueprint: "service",
        createdAt: 1_700_000_000_000,
        createdBy: 7,
        findings: [{ field: "team", code: "TEAM_TARGET_MISSING" }],
      }),
      TARGET,
      [],
    );
    expect(result.error).toBeNull();
    expect(result.document).toEqual({ identifier: "svc-a", blueprint: "service" });
  });
});
