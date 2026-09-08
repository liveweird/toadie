import { describe, expect, test } from "vitest";
import {
  aggregationBadge,
  calculationBadge,
  mirrorBadge,
  propertyBadge,
  relationBadge,
} from "./blueprintRowSummary";
import {
  emptyAggregationDraft,
  emptyCalculationDraft,
  emptyMirrorDraft,
  emptyPropertyDraft,
  emptyRelationDraft,
} from "./blueprintForm";

describe("propertyBadge", () => {
  test("a plain type with no distinguishing token is just the type", () => {
    expect(propertyBadge({ ...emptyPropertyDraft(), type: "boolean" })).toBe("boolean");
  });

  test("string + format", () => {
    expect(propertyBadge({ ...emptyPropertyDraft(), type: "string", format: "date-time" })).toBe(
      "string · date-time",
    );
  });

  test("array + items type", () => {
    expect(propertyBadge({ ...emptyPropertyDraft(), type: "array", itemsType: "number" })).toBe("array · number");
  });

  test("object + format", () => {
    expect(propertyBadge({ ...emptyPropertyDraft(), type: "object", objectFormat: "labeled-url" })).toBe(
      "object · labeled-url",
    );
  });
});

describe("relationBadge", () => {
  test("no target yet renders no badge", () => {
    expect(relationBadge(emptyRelationDraft())).toBe("");
  });

  test("a single target, not many", () => {
    expect(relationBadge({ ...emptyRelationDraft(), target: "team" })).toBe("team");
  });

  test("a many relation appends the multiplicity token", () => {
    expect(relationBadge({ ...emptyRelationDraft(), target: "team", many: true })).toBe("team · many");
  });
});

describe("mirrorBadge", () => {
  test("the trimmed path", () => {
    expect(mirrorBadge({ ...emptyMirrorDraft(), path: " team.$title " })).toBe("team.$title");
  });
});

describe("calculationBadge", () => {
  test("type only when no format is set", () => {
    expect(calculationBadge({ ...emptyCalculationDraft(), type: "number" })).toBe("number");
  });

  test("type and format", () => {
    expect(calculationBadge({ ...emptyCalculationDraft(), type: "string", format: "markdown" })).toBe(
      "string · markdown",
    );
  });
});

describe("aggregationBadge", () => {
  test("func alone when no target is picked yet", () => {
    expect(aggregationBadge(emptyAggregationDraft())).toBe("count");
  });

  test("target and function", () => {
    expect(aggregationBadge({ ...emptyAggregationDraft(), target: "service", func: "count" })).toBe(
      "service · count",
    );
  });
});
