import { describe, expect, test } from "vitest";
import { entityErrorsReport } from "../test/fixtures";
import { classOfEntityCode, colorOfEntityClass, countByEntityClass, ENTITY_ERROR_CLASSES } from "./entityErrorClasses";

describe("classOfEntityCode", () => {
  test("every ownership code maps to ownership", () => {
    expect(classOfEntityCode("OWNERSHIP_UNRESOLVED")).toBe("ownership");
    expect(classOfEntityCode("OWNERSHIP_PATH_STALE")).toBe("ownership");
  });

  test("every computed-property code maps to computed", () => {
    for (const code of [
      "MIRROR_PATH_STALE",
      "AGGREGATION_PATH_STALE",
      "AGGREGATION_PROPERTY_STALE",
      "CALCULATION_COMPILE_FAILED",
      "CALCULATION_QUARANTINED",
    ] as const) {
      expect(classOfEntityCode(code)).toBe("computed");
    }
  });

  test("SOURCE_MISSING maps to source", () => {
    expect(classOfEntityCode("SOURCE_MISSING")).toBe("source");
  });

  test("every other EntityFindingCode (the 19 strict-save codes) maps to stale", () => {
    for (const code of [
      "UNKNOWN_PROPERTY",
      "COMPUTED_PROPERTY",
      "REQUIRED_MISSING",
      "TYPE_MISMATCH",
      "ENUM_MISMATCH",
      "FORMAT_INVALID",
      "LENGTH_OUT_OF_RANGE",
      "PATTERN_MISMATCH",
      "RANGE_OUT_OF_BOUNDS",
      "ARRAY_SIZE",
      "ARRAY_NOT_UNIQUE",
      "OBJECT_SHAPE",
      "UNKNOWN_RELATION",
      "RELATION_SHAPE",
      "RELATION_REQUIRED",
      "RELATION_TARGET_MISSING",
      "TEAM_TARGET_MISSING",
      "TEAM_NOT_ALLOWED",
      "USER_TARGET_MISSING",
    ] as const) {
      expect(classOfEntityCode(code)).toBe("stale");
    }
  });
});

describe("colorOfEntityClass", () => {
  test("stale is red (HARD on the entity's next save); every soft report-only class is orange", () => {
    expect(colorOfEntityClass("stale")).toBe("red");
    for (const entityClass of ENTITY_ERROR_CLASSES.filter((c) => c !== "stale" && c !== "source")) {
      expect(colorOfEntityClass(entityClass)).toBe("orange");
    }
  });

  test("source is gray (an optional reference's absence, not a defect)", () => {
    expect(colorOfEntityClass("source")).toBe("gray");
  });
});

describe("countByEntityClass", () => {
  test("classifies entity and blueprint findings, counts every saved-query diagnostic as queries and every mapping finding or drift as mappings", () => {
    const report = entityErrorsReport({
      entities: [
        {
          id: 1,
          blueprintId: 1,
          blueprint: "service",
          blueprintTitle: "Service",
          identifier: "checkout",
          title: "Checkout",
          team: [],
          findings: [
            { code: "REQUIRED_MISSING", field: "properties.name", message: "m" },
            { code: "OWNERSHIP_UNRESOLVED", field: "team", message: "m" },
            { code: "SOURCE_MISSING", field: "source", message: "m" },
          ],
        },
      ],
      blueprints: [
        {
          id: 2,
          identifier: "team",
          title: "Team",
          findings: [{ code: "CALCULATION_COMPILE_FAILED", field: "calculationProperties.cost", message: "m" }],
        },
      ],
      savedQueries: [
        {
          id: 3,
          name: "q1",
          visibility: "PRIVATE",
          createdBy: 1,
          query: "MATCH (n) RETURN n",
          diagnostics: [
            { code: "UNKNOWN_LABEL", message: "m" },
            { code: "SYNTAX", message: "m" },
          ],
        },
      ],
      mappings: [
        {
          id: 4,
          name: "m1",
          findings: [{ code: "UNKNOWN_BLUEPRINT", path: "/pairs/0/blueprint", message: "m" }],
          drift: [
            { code: "VOCABULARY_DRIFT", path: "/pairs/0/rules/1", message: "m" },
            { code: "REGISTRY_KEY_MISSING", path: "/pairs/0/rules/2/backstage/label", message: "m" },
          ],
        },
      ],
    });

    expect(countByEntityClass(report)).toEqual({
      stale: 1,
      ownership: 1,
      computed: 1,
      queries: 2,
      source: 1,
      mappings: 3,
    });
  });

  test("an all-clear report counts zero in every class", () => {
    expect(countByEntityClass(entityErrorsReport())).toEqual({
      stale: 0,
      ownership: 0,
      computed: 0,
      queries: 0,
      source: 0,
      mappings: 0,
    });
  });
});
