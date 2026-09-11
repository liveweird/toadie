import { describe, expect, test } from "vitest";
import type { Blueprint } from "../api/blueprints";
import type { Entity } from "../api/entities";
import { computedDefinitions, computedPropertyIds, computedValuesOf, previewComputedColumns } from "./computedProperties";

// A representative blueprint covering every computed-property family and every calculation
// output type — cast at the boundary like blueprintForm.test.ts's FULL_REQUEST fixture, since
// the wire shape carries fields this test doesn't need to populate.
const BLUEPRINT = {
  mirrorProperties: {
    domain_title: { title: "Domain title", path: "system.domain.$title" },
  },
  calculationProperties: {
    stack: { title: "Stack", type: "string", calculation: ".properties.languages" },
    risk: {
      title: "Risk",
      type: "string",
      calculation: ".properties.tier",
      colorized: true,
      colors: { high: "red" },
    },
    score: { title: "Score", type: "number", calculation: "1" },
    flagged: { title: "Flagged", type: "boolean", calculation: "true" },
    replicas_list: { title: "Replicas list", type: "array", calculation: "[]" },
    meta: { title: "Meta", type: "object", calculation: "{}" },
  },
  aggregationProperties: {
    service_count: {
      title: "Service count",
      target: "service",
      calculationSpec: { calculationBy: "entities", func: "count" },
    },
  },
} as unknown as Blueprint;

const NO_COMPUTED_BLUEPRINT = {
  mirrorProperties: {},
  calculationProperties: {},
  aggregationProperties: {},
} as unknown as Blueprint;

describe("computedDefinitions", () => {
  test("flattens mirror, calculation and aggregation properties in that fixed order", () => {
    const ids = computedDefinitions(BLUEPRINT).map((d) => d.id);
    expect(ids).toEqual(["domain_title", "stack", "risk", "score", "flagged", "replicas_list", "meta", "service_count"]);
  });

  test("carries kind/title for every family, plus type/colorized/colors for calculations only", () => {
    const defs = computedDefinitions(BLUEPRINT);
    expect(defs.find((d) => d.id === "domain_title")).toEqual({ id: "domain_title", kind: "mirror", title: "Domain title" });
    expect(defs.find((d) => d.id === "service_count")).toEqual({
      id: "service_count",
      kind: "aggregation",
      title: "Service count",
    });
    expect(defs.find((d) => d.id === "risk")).toEqual({
      id: "risk",
      kind: "calculation",
      title: "Risk",
      type: "string",
      colorized: true,
      colors: { high: "red" },
    });
  });

  test("an empty blueprint yields no definitions", () => {
    expect(computedDefinitions(NO_COMPUTED_BLUEPRINT)).toEqual([]);
  });
});

describe("computedPropertyIds", () => {
  test("is the set of every declared computed id", () => {
    expect(computedPropertyIds(BLUEPRINT)).toEqual(
      new Set(["domain_title", "stack", "risk", "score", "flagged", "replicas_list", "meta", "service_count"]),
    );
  });

  test("is empty for a blueprint with no computed properties", () => {
    expect(computedPropertyIds(NO_COMPUTED_BLUEPRINT)).toEqual(new Set());
  });
});

describe("computedValuesOf", () => {
  test("reads one entry per declared computed id, undefined when the entity carries none", () => {
    const entity = { properties: { domain_title: "Commerce", risk: "high", other: "unrelated" } } as unknown as Entity;
    expect(computedValuesOf(entity, BLUEPRINT)).toEqual({
      domain_title: "Commerce",
      stack: undefined,
      risk: "high",
      score: undefined,
      flagged: undefined,
      replicas_list: undefined,
      meta: undefined,
      service_count: undefined,
    });
  });

  test("an entity with no properties at all yields every id absent", () => {
    const entity = {} as unknown as Entity;
    expect(computedValuesOf(entity, NO_COMPUTED_BLUEPRINT)).toEqual({});
  });
});

describe("previewComputedColumns", () => {
  test("includes every mirror/aggregation property plus only string/number/boolean calculations", () => {
    const ids = previewComputedColumns(BLUEPRINT).map((d) => d.id);
    expect(ids).toEqual(["domain_title", "stack", "risk", "score", "flagged", "service_count"]);
  });

  test("an empty blueprint yields no preview columns", () => {
    expect(previewComputedColumns(NO_COMPUTED_BLUEPRINT)).toEqual([]);
  });
});
