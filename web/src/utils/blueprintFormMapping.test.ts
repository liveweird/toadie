import { describe, expect, test } from "vitest";
import type { Blueprint, BlueprintBody } from "../api/blueprints";
import {
  fromBlueprintResponse,
  safeJsonParse,
  toBlueprintRequest,
} from "./blueprintFormMapping";
import {
  emptyAggregationDraft,
  emptyBlueprintForm,
  emptyCalculationDraft,
  emptyPropertyDraft,
  emptyRelationDraft,
  type BlueprintFormValues,
} from "./blueprintFormModel";

function values(overrides: Partial<BlueprintFormValues> = {}): BlueprintFormValues {
  return { ...emptyBlueprintForm(), identifier: "svc", title: "Service", ...overrides };
}

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

describe("hierarchyRelations — derive, don't clear, per entry (v1.25.0, multi-hierarchy v1.32.0)", () => {
  function withRelation(relationId: string, many = false) {
    return values({
      relations: [{ ...emptyRelationDraft(), id: "owningTeam", title: "Owned by", target: "team", many }],
      hierarchyRelations: { composition: relationId },
    });
  }

  test("emits an entry while its value names a current single relation", () => {
    const r = toBlueprintRequest(withRelation("owningTeam"));
    expect(r.hierarchyRelations).toEqual({ composition: "owningTeam" });
  });

  test("omits the whole field once the named relation is removed — no effect needed", () => {
    const r = toBlueprintRequest(values({ relations: [], hierarchyRelations: { composition: "owningTeam" } }));
    expect(r.hierarchyRelations).toBeUndefined();
  });

  test("omits the entry once its relation flips to many — a single-value rule became stale", () => {
    const r = toBlueprintRequest(withRelation("owningTeam", true));
    expect(r.hierarchyRelations).toBeUndefined();
  });

  test("a blank entry value omits the field", () => {
    const r = toBlueprintRequest(withRelation(""));
    expect(r.hierarchyRelations).toBeUndefined();
  });

  test("prunes only the stale entry, keeping a sibling hierarchy's still-valid one", () => {
    const r = toBlueprintRequest(
      values({
        relations: [
          { ...emptyRelationDraft(), id: "owningTeam", title: "Owned by", target: "team", many: false },
          { ...emptyRelationDraft(), id: "peers", title: "Peers", target: "team", many: true },
        ],
        hierarchyRelations: { composition: "owningTeam", org: "peers" },
      }),
    );
    expect(r.hierarchyRelations).toEqual({ composition: "owningTeam" });
  });

  test("one relation may serve several hierarchies", () => {
    const r = toBlueprintRequest(
      values({
        relations: [{ ...emptyRelationDraft(), id: "owningTeam", title: "Owned by", target: "team", many: false }],
        hierarchyRelations: { composition: "owningTeam", org: "owningTeam" },
      }),
    );
    expect(r.hierarchyRelations).toEqual({ composition: "owningTeam", org: "owningTeam" });
  });

  test("an entry whose KEY left the hierarchies dictionary is still emitted (the server 400s it)", () => {
    // toBlueprintRequest has no visibility into the dictionary — the client never silently
    // drops evidence of a stale key, it just names one that is still a valid single relation.
    const r = toBlueprintRequest(withRelation("owningTeam"));
    expect(r.hierarchyRelations).toEqual({ composition: "owningTeam" });
  });

  test("fromBlueprintResponse round-trips a set hierarchyRelations map", () => {
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
      hierarchyRelations: { composition: "owningTeam" },
    } as unknown as Blueprint;

    const form = fromBlueprintResponse(response);
    expect(form.hierarchyRelations).toEqual({ composition: "owningTeam" });
    expect(toBlueprintRequest(form).hierarchyRelations).toEqual({ composition: "owningTeam" });
  });

  test("fromBlueprintResponse leaves it empty when the wire response carries none", () => {
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

    expect(fromBlueprintResponse(response).hierarchyRelations).toEqual({});
  });
});

describe("tiers (2.18.0) — a Toadie-only hint stored beside the Port document", () => {
  const prop = (id: string, tier: number | null) => ({ ...emptyPropertyDraft(), id, tier });
  const rel = (id: string, tier: number | null) => ({ ...emptyRelationDraft(), id, target: "team", tier });

  test("an untiered form emits no tiers field at all", () => {
    const r = toBlueprintRequest(values({ properties: [prop("language", null)], relations: [rel("owner", null)] }));
    expect(r.tiers).toBeUndefined();
  });

  test("emits the blueprint, property and relation tiers keyed by id", () => {
    const r = toBlueprintRequest(
      values({ tier: 1, properties: [prop("language", 2), prop("repo", null)], relations: [rel("owner", 3)] }),
    );
    expect(r.tiers).toEqual({ blueprint: 1, properties: { language: 2 }, relations: { owner: 3 } });
  });

  test("a blank-id row contributes nothing, and a lone blueprint tier is enough", () => {
    expect(toBlueprintRequest(values({ properties: [prop("  ", 4)] })).tiers).toBeUndefined();
    expect(toBlueprintRequest(values({ tier: 4 })).tiers).toEqual({ blueprint: 4, properties: {}, relations: {} });
  });

  test("the tier lives on the row: a renamed id carries it, a removed row drops it", () => {
    const response = {
      id: 1,
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
      identifier: "service",
      title: "Service",
      schema: { properties: { language: { type: "string" }, repo: { type: "string" } }, required: [] },
      relations: {
        owner: { title: "Owner", target: "team", required: false, many: false },
        peer: { title: "Peer", target: "service", required: false, many: false },
      },
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
      tiers: { blueprint: 1, properties: { language: 2, repo: 3 }, relations: { owner: 1, peer: 4 } },
    } as unknown as Blueprint;

    const form = fromBlueprintResponse(response);
    form.properties[0].id = "lang";
    form.relations.splice(0, 1);

    expect(toBlueprintRequest(form).tiers).toEqual({
      blueprint: 1,
      properties: { lang: 2, repo: 3 },
      relations: { peer: 4 },
    });
  });

  test("fromBlueprintResponse maps tiers onto the rows and toBlueprintRequest round-trips them", () => {
    const response = {
      id: 1,
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
      identifier: "service",
      title: "Service",
      schema: { properties: { language: { type: "string" }, repo: { type: "string" } }, required: [] },
      relations: { owner: { title: "Owner", target: "team", required: false, many: false } },
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
      tiers: { blueprint: 2, properties: { language: 1 }, relations: { owner: 4 } },
    } as unknown as Blueprint;

    const form = fromBlueprintResponse(response);
    expect(form.tier).toBe(2);
    expect(form.properties.map((p) => [p.id, p.tier])).toEqual([["language", 1], ["repo", null]]);
    expect(form.relations.map((r) => [r.id, r.tier])).toEqual([["owner", 4]]);
    expect(toBlueprintRequest(form).tiers).toEqual({ blueprint: 2, properties: { language: 1 }, relations: { owner: 4 } });
  });

  test("fromBlueprintResponse leaves every tier null when the response carries none", () => {
    const response = {
      id: 1,
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
      identifier: "service",
      title: "Service",
      schema: { properties: { language: { type: "string" } }, required: [] },
      relations: {},
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
    } as unknown as Blueprint;
    const form = fromBlueprintResponse(response);
    expect(form.tier).toBeNull();
    expect(form.properties[0].tier).toBeNull();
    expect(toBlueprintRequest(form).tiers).toBeUndefined();
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

  test("sourceUrl seeds from the response, absent reads as blank", () => {
    expect(fromBlueprintResponse(responseWith({ sourceUrl: "https://example.com/a.json" })).sourceUrl).toBe(
      "https://example.com/a.json",
    );
    expect(fromBlueprintResponse(responseWith({})).sourceUrl).toBe("");
  });
});

describe("toBlueprintRequest — embedded-url spec without authentication", () => {
  test("an embedded-url string property carrying no auth fields omits specAuthentication", () => {
    const draft = { ...emptyPropertyDraft(), id: "docs", type: "string" as const, spec: "embedded-url" };
    const request = toBlueprintRequest(values({ properties: [draft] }));
    expect(request.schema!.properties.docs.specAuthentication).toBeUndefined();
  });
});
