import { describe, expect, test } from "vitest";
import {
  emptyBlueprintForm,
  propertyFieldApplies,
  rowsWithErrors,
} from "./blueprintFormModel";

describe("propertyFieldApplies", () => {
  test("a field only applies to the types that carry it", () => {
    expect(propertyFieldApplies("string", "pattern")).toBe(true);
    expect(propertyFieldApplies("number", "pattern")).toBe(false);
    expect(propertyFieldApplies("number", "minimum")).toBe(true);
    expect(propertyFieldApplies("array", "itemsType")).toBe(true);
    expect(propertyFieldApplies("object", "objectSchemaJson")).toBe(true);
    expect(propertyFieldApplies("boolean", "defaultBool")).toBe(true);
    expect(propertyFieldApplies("boolean", "defaultText")).toBe(false);
  });
});

describe("emptyBlueprintForm", () => {
  test("sourceUrl starts blank", () => {
    expect(emptyBlueprintForm().sourceUrl).toBe("");
  });
});

describe("rowsWithErrors", () => {
  test("dedupes per row and orders family-then-index, ignoring non-row error keys", () => {
    const errors = {
      "aggregationProperties.0.func": "x",
      "relations.0.target": "x",
      "properties.2.title": "x",
      "properties.1.id": "x",
      "properties.1.title": "x",
      "mirrorProperties.3.path": "x",
      identifier: "x",
      ownershipPath: "x",
    };
    expect(rowsWithErrors(errors)).toEqual([
      { family: "properties", index: 1 },
      { family: "properties", index: 2 },
      { family: "relations", index: 0 },
      { family: "mirrorProperties", index: 3 },
      { family: "aggregationProperties", index: 0 },
    ]);
  });

  test("no row errors -> no rows", () => {
    expect(rowsWithErrors({ identifier: "x" })).toEqual([]);
    expect(rowsWithErrors({})).toEqual([]);
  });
});
