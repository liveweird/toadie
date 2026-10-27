import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import { ApiError } from "../api/http";
import {
  emptyAggregationDraft,
  emptyBlueprintForm,
  emptyCalculationDraft,
  emptyMirrorDraft,
  emptyPropertyDraft,
  emptyRelationDraft,
  type BlueprintFormValues,
} from "./blueprintFormModel";
import {
  blueprintDeleteErrorMessage,
  blueprintFormValidation,
  blueprintSaveErrorMessage,
  isValidBlueprintIdentifier,
} from "./blueprintFormValidation";

// Key-echoing translator: assertions match on i18n keys, not rendered English.
const t = ((key: string) => key) as TFunction;

function values(overrides: Partial<BlueprintFormValues> = {}): BlueprintFormValues {
  return { ...emptyBlueprintForm(), identifier: "svc", title: "Service", ...overrides };
}

describe("isValidBlueprintIdentifier", () => {
  test("accepts Port's identifier charset", () => {
    expect(isValidBlueprintIdentifier("microservice")).toBe(true);
    expect(isValidBlueprintIdentifier("my-service_1.2:3/4=5@6")).toBe(true);
  });

  test("rejects blank, oversized, $-prefixed, and out-of-charset values", () => {
    expect(isValidBlueprintIdentifier("")).toBe(false);
    expect(isValidBlueprintIdentifier("a".repeat(101))).toBe(false);
    expect(isValidBlueprintIdentifier("$identifier")).toBe(false);
    expect(isValidBlueprintIdentifier("has space")).toBe(false);
  });
});

describe("blueprintFormValidation", () => {
  const validate = blueprintFormValidation(t);

  test("identifier grammar", () => {
    expect(validate.identifier("service-a")).toBeNull();
    expect(validate.identifier("has space")).toBe("blueprints.validation.identifier");
  });

  test("title is required, up to the length cap", () => {
    expect(validate.title("Service")).toBeNull();
    expect(validate.title("")).toBe("blueprints.validation.title");
  });

  test("description and icon length caps are enforced", () => {
    expect(validate.description("x".repeat(2001))).toBe("blueprints.validation.descriptionLength");
    expect(validate.description("short")).toBeNull();
    expect(validate.icon("x".repeat(101))).toBe("blueprints.validation.iconLength");
    expect(validate.icon("short")).toBeNull();
  });

  test("sourceUrl must be an absolute https URL without credentials, blank is fine", () => {
    expect(validate.sourceUrl("")).toBeNull();
    expect(validate.sourceUrl("https://example.com/a.json")).toBeNull();
    expect(validate.sourceUrl("http://example.com/a.json")).toBe("blueprints.validation.sourceUrl");
    expect(validate.sourceUrl("https://user:pass@example.com/a.json")).toBe("blueprints.validation.sourceUrl");
  });

  test("enum values on a non-string/non-number property type are ignored by the rule", () => {
    const v = values({ properties: [{ ...emptyPropertyDraft(), type: "boolean", enumValues: ["a"] }] });
    expect(validate.properties.enumValues(["a"], v, "properties.0.enumValues")).toBeNull();
  });

  test("exclusive bounds on a boolean property (not number) are a no-op", () => {
    const v = values({ properties: [{ ...emptyPropertyDraft(), type: "boolean" }] });
    expect(validate.properties.exclusiveMinimum("1", v, "properties.0.exclusiveMinimum")).toBeNull();
    expect(validate.properties.exclusiveMaximum("1", v, "properties.0.exclusiveMaximum")).toBeNull();
  });

  test("a default matching the closed enum list passes", () => {
    const v = values({
      properties: [{ ...emptyPropertyDraft(), type: "string", enumValues: ["a", "b"], defaultText: "a" }],
    });
    expect(validate.properties.defaultText("a", v, "properties.0.defaultText")).toBeNull();
  });

  test("a property id duplicated across the four families is flagged on the LATER row", () => {
    const v = values({
      properties: [{ ...emptyPropertyDraft(), id: "x" }],
      mirrorProperties: [{ ...emptyMirrorDraft(), id: "x" }],
    });
    expect(validate.properties.id("x", v, "properties.0.id")).toBeNull();
    expect(validate.mirrorProperties.id("x", v, "mirrorProperties.0.id")).toBe(
      "blueprints.validation.propertyIdDuplicate",
    );
  });

  test("relation identifiers are their OWN namespace — a property id doesn't collide", () => {
    const v = values({
      properties: [{ ...emptyPropertyDraft(), id: "owner" }],
      relations: [{ ...emptyRelationDraft(), id: "owner" }],
    });
    expect(validate.relations.id("owner", v, "relations.0.id")).toBeNull();
  });

  test("a relation cannot be both required and many", () => {
    const v = values({ relations: [{ ...emptyRelationDraft(), id: "r", required: true, many: true }] });
    expect(validate.relations.many(true, v, "relations.0.many")).toBe("blueprints.validation.relationRequiredMany");
  });

  test("min must not exceed max on a string property's length pair", () => {
    const draft = { ...emptyPropertyDraft(), type: "string" as const, minLength: "10", maxLength: "5" };
    const v = values({ properties: [draft] });
    expect(validate.properties.minLength("10", v, "properties.0.minLength")).toBe("blueprints.validation.minMax");
  });

  test("an invalid regex pattern is rejected", () => {
    const draft = { ...emptyPropertyDraft(), type: "string" as const, pattern: "(unterminated" };
    const v = values({ properties: [draft] });
    expect(validate.properties.pattern("(unterminated", v, "properties.0.pattern")).toBe(
      "blueprints.validation.patternInvalid",
    );
  });

  test("a non-numeric enum entry is rejected for a number property", () => {
    const draft = { ...emptyPropertyDraft(), type: "number" as const, enumValues: ["1", "two"] };
    const v = values({ properties: [draft] });
    expect(validate.properties.enumValues(["1", "two"], v, "properties.0.enumValues")).toBe(
      "blueprints.validation.enumNumber",
    );
  });

  test("a default outside the closed enum is rejected", () => {
    const draft = { ...emptyPropertyDraft(), type: "string" as const, enumValues: ["a", "b"], defaultText: "c" };
    const v = values({ properties: [draft] });
    expect(validate.properties.defaultText("c", v, "properties.0.defaultText")).toBe(
      "blueprints.validation.defaultNotInEnum",
    );
  });

  test("an invalid JSON textarea is rejected, blank is fine", () => {
    const draft = { ...emptyPropertyDraft(), type: "object" as const, objectSchemaJson: "{not json" };
    const v = values({ properties: [draft] });
    expect(validate.properties.objectSchemaJson("{not json", v, "properties.0.objectSchemaJson")).toBe(
      "blueprints.validation.jsonInvalid",
    );
    expect(
      validate.properties.objectSchemaJson("", values({ properties: [emptyPropertyDraft()] }), "properties.0.objectSchemaJson"),
    ).toBeNull();
  });

  test("a mirror path's first segment must name a relation of this blueprint", () => {
    const v = values({ relations: [{ ...emptyRelationDraft(), id: "system" }] });
    expect(validate.mirrorProperties.path("system.domain.$title", v)).toBeNull();
    expect(validate.mirrorProperties.path("other.domain", v)).toBe("blueprints.validation.pathFirstSegment");
  });

  test("the aggregation matrix: property required with calculationBy=property, forbidden with entities", () => {
    const byProperty = values({
      aggregationProperties: [{ ...emptyAggregationDraft(), calculationBy: "property", property: "" }],
    });
    expect(validate.aggregationProperties.property("", byProperty, "aggregationProperties.0.property")).toBe(
      "blueprints.validation.required",
    );
    const byEntities = values({
      aggregationProperties: [{ ...emptyAggregationDraft(), calculationBy: "entities", property: "count" }],
    });
    expect(
      validate.aggregationProperties.property("count", byEntities, "aggregationProperties.0.property"),
    ).toBe("blueprints.validation.aggregationPropertyForbidden");
  });

  test("func must belong to the allowed set for the chosen calculationBy", () => {
    const byEntities = values({
      aggregationProperties: [{ ...emptyAggregationDraft(), calculationBy: "entities", func: "sum" }],
    });
    expect(validate.aggregationProperties.func("sum", byEntities, "aggregationProperties.0.func")).toBe(
      "blueprints.validation.aggregationFunc",
    );
    const byProperty = values({
      aggregationProperties: [{ ...emptyAggregationDraft(), calculationBy: "property", func: "sum" }],
    });
    expect(validate.aggregationProperties.func("sum", byProperty, "aggregationProperties.0.func")).toBeNull();
  });

  test("an Inherited ownership requires a path naming a relation; Direct ignores it", () => {
    const inherited = values({
      ownershipType: "Inherited",
      relations: [{ ...emptyRelationDraft(), id: "service" }],
    });
    expect(validate.ownershipPath("", inherited)).toBe("blueprints.validation.required");
    expect(validate.ownershipPath("service.owningTeam", inherited)).toBeNull();
    expect(validate.ownershipPath("other.path", inherited)).toBe("blueprints.validation.pathFirstSegment");

    const direct = values({ ownershipType: "Direct" });
    expect(validate.ownershipPath("anything", direct)).toBeNull();
  });

  test("calculation text is required and capped", () => {
    expect(validate.calculationProperties.calculation("")).toBe("blueprints.validation.required");
    expect(validate.calculationProperties.calculation(".properties.x")).toBeNull();
    expect(validate.calculationProperties.calculation("x".repeat(10001))).toBe(
      "blueprints.validation.calculationLength",
    );
  });

  test("mirror/calculation/aggregation title and target are required", () => {
    expect(validate.mirrorProperties.title("Team")).toBeNull();
    expect(validate.mirrorProperties.title("")).toBe("blueprints.validation.required");
    expect(validate.calculationProperties.title("Status")).toBeNull();
    expect(validate.calculationProperties.title("")).toBe("blueprints.validation.required");
    expect(validate.aggregationProperties.title("Open issues")).toBeNull();
    expect(validate.aggregationProperties.title("")).toBe("blueprints.validation.required");
    expect(validate.aggregationProperties.target("jiraIssue")).toBeNull();
    expect(validate.aggregationProperties.target("")).toBe("blueprints.validation.targetRequired");
  });

  test("calculation/aggregation identifiers follow the shared grammar and duplicate rule", () => {
    const v = values({ calculationProperties: [{ ...emptyCalculationDraft(), id: "x" }] });
    expect(validate.calculationProperties.id("has space", v, "calculationProperties.0.id")).toBe(
      "blueprints.validation.propertyId",
    );
    const dup = values({
      calculationProperties: [
        { ...emptyCalculationDraft(), id: "x" },
        { ...emptyCalculationDraft(), id: "x" },
      ],
    });
    expect(validate.calculationProperties.id("x", dup, "calculationProperties.1.id")).toBe(
      "blueprints.validation.propertyIdDuplicate",
    );

    const agg = values({ aggregationProperties: [{ ...emptyAggregationDraft(), id: "y" }] });
    expect(validate.aggregationProperties.id("has space", agg, "aggregationProperties.0.id")).toBe(
      "blueprints.validation.propertyId",
    );
    const aggDup = values({
      aggregationProperties: [
        { ...emptyAggregationDraft(), id: "y" },
        { ...emptyAggregationDraft(), id: "y" },
      ],
    });
    expect(validate.aggregationProperties.id("y", aggDup, "aggregationProperties.1.id")).toBe(
      "blueprints.validation.propertyIdDuplicate",
    );
  });

  test("a relation id duplicated among relations is flagged on the later row", () => {
    const v = values({
      relations: [{ ...emptyRelationDraft(), id: "r" }, { ...emptyRelationDraft(), id: "r" }],
    });
    expect(validate.relations.id("r", v, "relations.0.id")).toBeNull();
    expect(validate.relations.id("r", v, "relations.1.id")).toBe("blueprints.validation.relationIdDuplicate");
    expect(validate.relations.id("has space", v, "relations.1.id")).toBe("blueprints.validation.relationId");
  });

  test("calculation colors and aggregation query/pathFilter accept blank, reject invalid JSON", () => {
    expect(validate.calculationProperties.colorsJson("")).toBeNull();
    expect(validate.calculationProperties.colorsJson("{not json")).toBe("blueprints.validation.jsonInvalid");
    expect(validate.calculationProperties.colorsJson('{"OK":"green"}')).toBeNull();
    expect(validate.aggregationProperties.queryJson("{not json")).toBe("blueprints.validation.jsonInvalid");
    expect(validate.aggregationProperties.queryJson('{"combinator":"and","rules":[]}')).toBeNull();
    expect(validate.aggregationProperties.pathFilterJson("{not json")).toBe("blueprints.validation.jsonInvalid");
    expect(validate.aggregationProperties.pathFilterJson("[]")).toBeNull();
  });

  test("minMaxRule and pathRule edge branches: missing row, non-numeric input, over-long path", () => {
    // The row at this index doesn't exist — every per-row rule must no-op rather than throw.
    const empty = values({});
    expect(validate.properties.minLength("5", empty, "properties.0.minLength")).toBeNull();
    expect(validate.properties.exclusiveMinimum("1", empty, "properties.0.exclusiveMinimum")).toBeNull();
    expect(validate.properties.exclusiveMaximum("1", empty, "properties.0.exclusiveMaximum")).toBeNull();
    expect(validate.aggregationProperties.property("x", empty, "aggregationProperties.0.property")).toBeNull();
    expect(validate.aggregationProperties.func("count", empty, "aggregationProperties.0.func")).toBeNull();

    const nonNumeric = { ...emptyPropertyDraft(), type: "string" as const, minLength: "abc", maxLength: "5" };
    expect(
      validate.properties.minLength("abc", values({ properties: [nonNumeric] }), "properties.0.minLength"),
    ).toBeNull();

    const deepPath = values({ relations: [{ ...emptyRelationDraft(), id: "r" }] });
    expect(validate.mirrorProperties.path(Array(11).fill("r").join("."), deepPath)).toBe(
      "blueprints.validation.pathLength",
    );
  });
});

describe("blueprintSaveErrorMessage / blueprintDeleteErrorMessage", () => {
  test("maps the fixed status vocabulary", () => {
    expect(blueprintSaveErrorMessage(new ApiError(409, null), t)).toBe("blueprints.saveConflict");
    expect(blueprintSaveErrorMessage(new ApiError(403, null), t)).toBe("blueprints.saveForbidden");
    expect(blueprintDeleteErrorMessage(new ApiError(409, null), t)).toBe("blueprints.deleteConflict");
    expect(blueprintDeleteErrorMessage(new Error("network"), t)).toBe("common.error.actionFailed");
  });
});

describe("blueprintFormValidation — remaining rule branches", () => {
  const validate = blueprintFormValidation(t);
  const forProps = (...properties: ReturnType<typeof emptyPropertyDraft>[]) => values({ properties });

  test("a property id with an illegal charset is rejected", () => {
    expect(validate.properties.id("has space", forProps(), "properties.0.id")).toBe("blueprints.validation.propertyId");
  });

  test("a property title is required", () => {
    expect(validate.properties.title("  ")).toBe("blueprints.validation.required");
    expect(validate.properties.title("Name")).toBeNull();
  });

  test("pattern: skipped for a non-string row or a blank value, rejected past the length cap", () => {
    const numberRow = { ...emptyPropertyDraft(), type: "number" as const };
    const stringRow = { ...emptyPropertyDraft(), type: "string" as const };
    expect(validate.properties.pattern("(", forProps(numberRow), "properties.0.pattern")).toBeNull();
    expect(validate.properties.pattern("  ", forProps(stringRow), "properties.0.pattern")).toBeNull();
    expect(validate.properties.pattern("(", values(), "properties.0.pattern")).toBeNull();
    expect(validate.properties.pattern("a".repeat(501), forProps(stringRow), "properties.0.pattern")).toBe(
      "blueprints.validation.patternLength",
    );
    expect(validate.properties.pattern("^ok$", forProps(stringRow), "properties.0.pattern")).toBeNull();
  });

  test("minimum/maximum and minItems/maxItems pairs: fail inverted, pass blank or ordered", () => {
    const number = { ...emptyPropertyDraft(), type: "number" as const, minimum: "5", maximum: "3" };
    expect(validate.properties.minimum("5", forProps(number), "properties.0.minimum")).toBe(
      "blueprints.validation.minMax",
    );
    const openEnded = { ...number, maximum: "" };
    expect(validate.properties.minimum("5", forProps(openEnded), "properties.0.minimum")).toBeNull();
    const ordered = { ...number, maximum: "9" };
    expect(validate.properties.minimum("5", forProps(ordered), "properties.0.minimum")).toBeNull();

    const array = { ...emptyPropertyDraft(), type: "array" as const, minItems: "4", maxItems: "2" };
    expect(validate.properties.minItems("4", forProps(array), "properties.0.minItems")).toBe(
      "blueprints.validation.minMax",
    );
    expect(validate.properties.minItems("4", values(), "properties.0.minItems")).toBeNull();
  });

  test("exclusiveMinimum conflicts with minimum and is ordered against exclusiveMaximum", () => {
    const both = { ...emptyPropertyDraft(), type: "number" as const, minimum: "1", exclusiveMinimum: "0" };
    expect(validate.properties.exclusiveMinimum("0", forProps(both), "properties.0.exclusiveMinimum")).toBe(
      "blueprints.validation.exclusiveConflict",
    );
    const inverted = {
      ...emptyPropertyDraft(),
      type: "number" as const,
      exclusiveMinimum: "9",
      exclusiveMaximum: "2",
    };
    expect(validate.properties.exclusiveMinimum("9", forProps(inverted), "properties.0.exclusiveMinimum")).toBe(
      "blueprints.validation.minMax",
    );
    const notNumber = { ...emptyPropertyDraft(), type: "string" as const, minimum: "1" };
    expect(validate.properties.exclusiveMinimum("0", forProps(notNumber), "properties.0.exclusiveMinimum")).toBeNull();
  });

  test("exclusiveMaximum conflicts with maximum only on a number row", () => {
    const both = { ...emptyPropertyDraft(), type: "number" as const, maximum: "10" };
    expect(validate.properties.exclusiveMaximum("5", forProps(both), "properties.0.exclusiveMaximum")).toBe(
      "blueprints.validation.exclusiveConflict",
    );
    expect(validate.properties.exclusiveMaximum("", forProps(both), "properties.0.exclusiveMaximum")).toBeNull();
    const notNumber = { ...emptyPropertyDraft(), type: "string" as const, maximum: "10" };
    expect(validate.properties.exclusiveMaximum("5", forProps(notNumber), "properties.0.exclusiveMaximum")).toBeNull();
  });

  test("enum values: capped in count, duplicates flagged after trimming, skipped for other types", () => {
    const stringRow = { ...emptyPropertyDraft(), type: "string" as const };
    const tooMany = Array.from({ length: 201 }, (_, i) => `v${i}`);
    expect(validate.properties.enumValues(tooMany, forProps(stringRow), "properties.0.enumValues")).toBe(
      "blueprints.validation.enumCount",
    );
    expect(validate.properties.enumValues(["a", " a "], forProps(stringRow), "properties.0.enumValues")).toBe(
      "blueprints.validation.enumDuplicate",
    );
    expect(validate.properties.enumValues(["a", "b"], forProps(stringRow), "properties.0.enumValues")).toBeNull();
    const boolRow = { ...emptyPropertyDraft(), type: "boolean" as const };
    expect(validate.properties.enumValues(["a", "a"], forProps(boolRow), "properties.0.enumValues")).toBeNull();
    expect(validate.properties.enumValues(["a"], values(), "properties.0.enumValues")).toBeNull();
  });

  test("default text: blank or rowless is fine, a number must be numeric, an object default must be JSON", () => {
    const numberRow = { ...emptyPropertyDraft(), type: "number" as const };
    const objectRow = { ...emptyPropertyDraft(), type: "object" as const };
    const stringRow = { ...emptyPropertyDraft(), type: "string" as const };
    expect(validate.properties.defaultText("x", values(), "properties.0.defaultText")).toBeNull();
    expect(validate.properties.defaultText("  ", forProps(numberRow), "properties.0.defaultText")).toBeNull();
    expect(validate.properties.defaultText("abc", forProps(numberRow), "properties.0.defaultText")).toBe(
      "blueprints.validation.defaultNumber",
    );
    expect(validate.properties.defaultText("12", forProps(numberRow), "properties.0.defaultText")).toBeNull();
    expect(validate.properties.defaultText("{nope", forProps(objectRow), "properties.0.defaultText")).toBe(
      "blueprints.validation.jsonInvalid",
    );
    expect(validate.properties.defaultText('{"a":1}', forProps(objectRow), "properties.0.defaultText")).toBeNull();
    expect(validate.properties.defaultText("free", forProps(stringRow), "properties.0.defaultText")).toBeNull();
  });

  test("a relation needs a title and a target", () => {
    expect(validate.relations.title(" ")).toBe("blueprints.validation.required");
    expect(validate.relations.title("Owner")).toBeNull();
    expect(validate.relations.target(" ")).toBe("blueprints.validation.targetRequired");
    expect(validate.relations.target("team")).toBeNull();
  });

  test("a mirror property id is grammar-checked", () => {
    expect(validate.mirrorProperties.id("has space", values(), "mirrorProperties.0.id")).toBe(
      "blueprints.validation.propertyId",
    );
  });

  test("an aggregation's property slot: set when counting entities and blank when measuring a property are both fine", () => {
    const byProperty = { ...emptyAggregationDraft(), calculationBy: "property" as const, func: "sum", property: "size" };
    const byEntities = { ...emptyAggregationDraft(), calculationBy: "entities" as const, property: "" };
    expect(
      validate.aggregationProperties.property("size", values({ aggregationProperties: [byProperty] }), "aggregationProperties.0.property"),
    ).toBeNull();
    expect(
      validate.aggregationProperties.property("", values({ aggregationProperties: [byEntities] }), "aggregationProperties.0.property"),
    ).toBeNull();
  });
});
