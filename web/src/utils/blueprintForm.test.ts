import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import type { Blueprint, BlueprintBody } from "../api/blueprints";
import { ApiError } from "../api/http";
import {
  blueprintDeleteErrorMessage,
  blueprintFormValidation,
  blueprintSaveErrorMessage,
  emptyAggregationDraft,
  emptyBlueprintForm,
  emptyCalculationDraft,
  emptyMirrorDraft,
  emptyPropertyDraft,
  emptyRelationDraft,
  fromBlueprintResponse,
  isValidBlueprintIdentifier,
  propertyFieldApplies,
  safeJsonParse,
  toBlueprintRequest,
  type BlueprintFormValues,
} from "./blueprintForm";

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

describe("safeJsonParse", () => {
  test("parses valid JSON, and returns undefined for blank or invalid input", () => {
    expect(safeJsonParse("{\"a\":1}")).toEqual({ a: 1 });
    expect(safeJsonParse("  ")).toBeUndefined();
    expect(safeJsonParse("{not json")).toBeUndefined();
  });
});

describe("toBlueprintRequest / fromBlueprintResponse round trip", () => {
  // Not typed as BlueprintBody: the wire `properties`/`patternProperties` sub-tree fields are
  // opaque JSON-Schema (Record<string, never> — the spec declares no shape for what it stores
  // and shape-checks only), so a literal fixture carrying real nested values can't satisfy the
  // strict type. The `response` cast below (`as unknown as Blueprint`) is the actual boundary;
  // this object is compared structurally via toEqual, which does not consult TS types.
  const FULL_REQUEST = {
    identifier: "microservice",
    title: "Microservice",
    description: "A deployable unit of software owned by a team.",
    icon: "Microservice",
    schema: {
      properties: {
        language: {
          type: "string",
          title: "Language",
          enum: ["kotlin", "typescript"],
          enumColors: { kotlin: "purple" },
          default: "kotlin",
        },
        docs: {
          type: "string",
          title: "Docs",
          spec: "embedded-url",
          specAuthentication: {
            authorizationUrl: "https://auth.example.com/authorize",
            tokenUrl: "https://auth.example.com/token",
            clientId: "client-1",
            authorizationScope: ["read"],
          },
        },
        port: {
          type: "number",
          title: "Port",
          minimum: 1,
          maximum: 65535,
          enum: [80, 443],
          enumColors: { "80": "blue" },
          default: 8080,
        },
        public: { type: "boolean", title: "Public", default: false },
        tags: {
          type: "array",
          title: "Tags",
          items: { type: "string" },
          minItems: 0,
          maxItems: 5,
          default: ["a", "b"],
        },
        ports: {
          type: "array",
          title: "Ports",
          items: { type: "number" },
          uniqueItems: true,
          default: [80, 443],
        },
        flags: {
          type: "array",
          title: "Flags",
          items: { type: "boolean" },
          default: [true, false],
        },
        config: {
          type: "object",
          title: "Config",
          properties: { retries: { type: "number" } },
          patternProperties: { "^S_": { type: "string" } },
          additionalProperties: true,
          default: { retries: 3 },
        },
      },
      required: ["language"],
    },
    relations: {
      owningTeam: { title: "Owned by", target: "team", required: false, many: false },
    },
    mirrorProperties: {
      teamName: { title: "Team", path: "owningTeam.$title" },
    },
    calculationProperties: {
      status: {
        title: "Status",
        type: "string",
        calculation: ".properties.rawStatus",
        colorized: true,
        colors: { OK: "green" },
      },
    },
    aggregationProperties: {
      openIssues: {
        title: "Open issues",
        target: "jiraIssue",
        calculationSpec: { calculationBy: "entities", func: "count" },
      },
    },
    ownership: { type: "Direct", title: "Owning team" },
  };

  test("fromBlueprintResponse then toBlueprintRequest reconstructs the original wire shape", () => {
    const response = {
      id: 1,
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 1000,
      updatedAt: 2000,
      ...FULL_REQUEST,
    } as unknown as Blueprint;

    const roundTripped = toBlueprintRequest(fromBlueprintResponse(response));

    expect(roundTripped).toEqual(FULL_REQUEST);
  });

  test("a minimal blueprint (no optional families) round-trips too", () => {
    const minimal: BlueprintBody = {
      identifier: "empty",
      title: "Empty",
      schema: { properties: {}, required: [] },
      relations: {},
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
    };
    const response = {
      id: 2,
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
      ...minimal,
    } as unknown as Blueprint;

    expect(toBlueprintRequest(fromBlueprintResponse(response))).toEqual(minimal);
  });
});

describe("toBlueprintRequest", () => {
  test("drops rows with a blank id", () => {
    const draft = { ...emptyPropertyDraft(), id: "  ", title: "x" };
    const request = toBlueprintRequest(values({ properties: [draft] }));
    expect(request.schema!.properties).toEqual({});
  });

  test("required rows land in schema.required", () => {
    const draft = { ...emptyPropertyDraft(), id: "lang", title: "Language", required: true };
    const request = toBlueprintRequest(values({ properties: [draft] }));
    expect(request.schema!.required).toEqual(["lang"]);
  });

  function requestFor(draft: Partial<ReturnType<typeof emptyPropertyDraft>> & { id: string }) {
    return toBlueprintRequest(values({ properties: [{ ...emptyPropertyDraft(), ...draft }] }));
  }

  test("a non-numeric numeric field resolves to undefined rather than NaN", () => {
    const r = requestFor({ id: "x", type: "string", minLength: "abc" });
    expect(r.schema!.properties.x.minLength).toBeUndefined();
  });

  test("a number property's enum drops entirely when every entry is non-numeric", () => {
    const r = requestFor({ id: "n", type: "number", enumValues: ["abc"] });
    expect(r.schema!.properties.n.enum).toBeUndefined();
  });

  test("a boolean property with no default omits the field", () => {
    const r = requestFor({ id: "b", type: "boolean", defaultBool: "" });
    expect(r.schema!.properties.b.default).toBeUndefined();
  });

  test("an array property with no default items omits default; no item type omits items", () => {
    const withoutDefault = requestFor({ id: "a", type: "array", itemsType: "string", defaultList: [] });
    expect(withoutDefault.schema!.properties.a.default).toBeUndefined();
    const withoutItems = requestFor({ id: "a2", type: "array", itemsType: "" });
    expect(withoutItems.schema!.properties.a2.items).toBeUndefined();
  });

  test("embedded-url spec auth with only a URL omits the empty scope array", () => {
    const r = requestFor({
      id: "s",
      type: "string",
      spec: "embedded-url",
      specAuthorizationUrl: "https://a",
      specAuthorizationScope: [],
    });
    expect(r.schema!.properties.s.specAuthentication).toEqual({ authorizationUrl: "https://a" });
  });

  test("an object property's blank JSON default omits the field", () => {
    const r = requestFor({ id: "o", type: "object", defaultText: "" });
    expect(r.schema!.properties.o.default).toBeUndefined();
  });

  test("a number property never carries string-only fields left over from a type switch", () => {
    const r = requestFor({ id: "n2", type: "number", format: "url", spec: "open-api", dateFormat: "relative" });
    expect(r.schema!.properties.n2.format).toBeUndefined();
    expect(r.schema!.properties.n2.spec).toBeUndefined();
    expect(r.schema!.properties.n2.date_format).toBeUndefined();
  });

  test("a relation description is included when set", () => {
    const r = toBlueprintRequest(
      values({
        relations: [{ ...emptyRelationDraft(), id: "r", title: "R", target: "t", description: "why" }],
      }),
    );
    expect(r.relations?.r.description).toBe("why");
  });

  test("a non-string calculation property omits format; a non-string/object one omits spec too", () => {
    const r = toBlueprintRequest(
      values({
        calculationProperties: [
          { ...emptyCalculationDraft(), id: "c", type: "number", format: "url", spec: "open-api", calculation: "x" },
        ],
      }),
    );
    expect(r.calculationProperties?.c.format).toBeUndefined();
    expect(r.calculationProperties?.c.spec).toBeUndefined();
  });

  test("calculationBy=property with func=average carries property/averageOf/measureTimeBy and JSON slots", () => {
    const r = toBlueprintRequest(
      values({
        aggregationProperties: [
          {
            ...emptyAggregationDraft(),
            id: "a",
            target: "t",
            calculationBy: "property",
            func: "average",
            property: "age",
            averageOf: "day",
            measureTimeBy: "$createdAt",
            queryJson: '{"combinator":"or","rules":[]}',
            pathFilterJson: "[]",
          },
        ],
      }),
    );
    expect(r.aggregationProperties?.a.calculationSpec).toEqual({
      calculationBy: "property",
      func: "average",
      property: "age",
      averageOf: "day",
      measureTimeBy: "$createdAt",
    });
    expect(r.aggregationProperties?.a.query).toEqual({ combinator: "or", rules: [] });
    expect(r.aggregationProperties?.a.pathFilter).toEqual([]);
  });

  test("Inherited ownership carries its path; a blank ownershipType omits ownership entirely", () => {
    const inherited = toBlueprintRequest(
      values({ ownershipType: "Inherited", ownershipTitle: "Team", ownershipPath: "svc.owningTeam" }),
    );
    expect(inherited.ownership).toEqual({ type: "Inherited", title: "Team", path: "svc.owningTeam" });

    const none = toBlueprintRequest(values({ ownershipType: "" }));
    expect(none.ownership).toBeUndefined();
  });
});

describe("hierarchyRelation — derive, don't clear (v1.25.0)", () => {
  function withRelation(hierarchyRelation: string, many = false) {
    return values({
      relations: [{ ...emptyRelationDraft(), id: "owningTeam", title: "Owned by", target: "team", many }],
      hierarchyRelation,
    });
  }

  test("emits the field while it names a current single relation", () => {
    const r = toBlueprintRequest(withRelation("owningTeam"));
    expect(r.hierarchyRelation).toBe("owningTeam");
  });

  test("omits the field once the named relation is removed — no effect needed", () => {
    const r = toBlueprintRequest(values({ relations: [], hierarchyRelation: "owningTeam" }));
    expect(r.hierarchyRelation).toBeUndefined();
  });

  test("omits the field once the named relation flips to many — a single-value rule became stale", () => {
    const r = toBlueprintRequest(withRelation("owningTeam", true));
    expect(r.hierarchyRelation).toBeUndefined();
  });

  test("a blank hierarchyRelation omits the field", () => {
    const r = toBlueprintRequest(withRelation(""));
    expect(r.hierarchyRelation).toBeUndefined();
  });

  test("fromBlueprintResponse round-trips a set hierarchyRelation", () => {
    const response = {
      id: 1,
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
      identifier: "service",
      title: "Service",
      schema: { properties: {}, required: [] },
      relations: { owningTeam: { title: "Owned by", target: "team", required: false, many: false } },
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
      hierarchyRelation: "owningTeam",
    } as unknown as Blueprint;

    const form = fromBlueprintResponse(response);
    expect(form.hierarchyRelation).toBe("owningTeam");
    expect(toBlueprintRequest(form).hierarchyRelation).toBe("owningTeam");
  });

  test("fromBlueprintResponse leaves it blank when the wire response carries none", () => {
    const response = {
      id: 1,
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
      identifier: "service",
      title: "Service",
      schema: { properties: {}, required: [] },
      relations: {},
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
    } as unknown as Blueprint;

    expect(fromBlueprintResponse(response).hierarchyRelation).toBe("");
  });
});

describe("fromBlueprintResponse edge branches", () => {
  function responseWith(overrides: Record<string, unknown>): Blueprint {
    return {
      id: 1,
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
      identifier: "x",
      title: "X",
      schema: { properties: {}, required: [] },
      relations: {},
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
      ...overrides,
    } as unknown as Blueprint;
  }

  test("an unrecognized property type falls back to string", () => {
    const form = fromBlueprintResponse(
      responseWith({ schema: { properties: { p: { type: "mystery" } }, required: [] } }),
    );
    expect(form.properties[0].type).toBe("string");
  });

  test("an object property with no default/sub-schema renders blank slots", () => {
    const form = fromBlueprintResponse(
      responseWith({ schema: { properties: { o: { type: "object" } }, required: [] } }),
    );
    expect(form.properties[0].objectSchemaJson).toBe("");
    expect(form.properties[0].defaultText).toBe("");
  });

  test("a number property with no default renders a blank default slot", () => {
    const form = fromBlueprintResponse(
      responseWith({ schema: { properties: { n: { type: "number" } }, required: [] } }),
    );
    expect(form.properties[0].defaultText).toBe("");
  });

  test("missing optional collections fall back to empty (a mid-deploy older/partial payload)", () => {
    const form = fromBlueprintResponse(
      responseWith({
        relations: undefined,
        mirrorProperties: undefined,
        calculationProperties: undefined,
        aggregationProperties: undefined,
      }),
    );
    expect(form.relations).toEqual([]);
    expect(form.mirrorProperties).toEqual([]);
    expect(form.calculationProperties).toEqual([]);
    expect(form.aggregationProperties).toEqual([]);
  });

  test("a calculation property with no colorized/colors defaults to unset", () => {
    const form = fromBlueprintResponse(
      responseWith({
        calculationProperties: { c: { title: "C", type: "string", calculation: "x" } },
      }),
    );
    expect(form.calculationProperties[0].colorized).toBe(false);
    expect(form.calculationProperties[0].colorsJson).toBe("");
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
