import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import type { Blueprint } from "../api/blueprints";
import type { Entity } from "../api/entities";
import {
  emptyEntityForm,
  entityDeleteErrorMessage,
  entityFormValidation,
  entitySaveErrorMessage,
  fromEntityResponse,
  isValidEntityIdentifier,
  teamValuesOf,
  toEntityRequest,
  type EntityFormValues,
  type PropertyValueDraft,
  type RelationValueDraft,
} from "./entityForm";
import { ApiError } from "../api/http";

// Key-echoing translator: assertions match on i18n keys, not rendered English (the
// blueprintForm.test.ts idiom).
const t = ((key: string) => key) as TFunction;

// A representative blueprint covering every widget the entity editor renders — cast at the
// boundary like blueprintForm.test.ts's FULL_REQUEST fixture, since the open JSON-Schema
// sub-trees make a literal fixture too narrow for the generated type.
const BLUEPRINT = {
  id: 1,
  identifier: "service",
  title: "Service",
  schema: {
    properties: {
      name: { type: "string", title: "Name", minLength: 2, maxLength: 10 },
      tier: { type: "number", title: "Tier", enum: [1, 2, 3] },
      active: { type: "boolean", title: "Active" },
      tags: { type: "array", title: "Tags", items: { type: "string" }, maxItems: 3, uniqueItems: true },
      homepage: { type: "string", title: "Homepage", format: "url" },
      link: { type: "object", title: "Link", format: "labeled-url" },
      notes: { type: "object", title: "Notes" },
    },
    required: ["name", "link"],
  },
  relations: {
    system: { title: "System", target: "system", required: true, many: false },
    dependsOn: { title: "Depends on", target: "service", required: false, many: true },
  },
  mirrorProperties: {},
  calculationProperties: {},
  aggregationProperties: {},
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 0,
  updatedAt: 0,
} as unknown as Blueprint;

function values(overrides: Partial<EntityFormValues> = {}): EntityFormValues {
  return { ...emptyEntityForm(BLUEPRINT), identifier: "checkout", title: "Checkout", ...overrides };
}

describe("isValidEntityIdentifier", () => {
  test("accepts Port's wider identifier charset, including unicode letters and dots", () => {
    expect(isValidEntityIdentifier("checkout")).toBe(true);
    expect(isValidEntityIdentifier("a.b.c")).toBe(true);
    expect(isValidEntityIdentifier("żółw+usługa'x\\y")).toBe(true);
  });

  test("rejects blank, oversized, and the bare . / .. forms", () => {
    expect(isValidEntityIdentifier("")).toBe(false);
    expect(isValidEntityIdentifier("a".repeat(201))).toBe(false);
    expect(isValidEntityIdentifier(".")).toBe(false);
    expect(isValidEntityIdentifier("..")).toBe(false);
    expect(isValidEntityIdentifier("has space")).toBe(false);
  });
});

describe("emptyEntityForm", () => {
  test("seeds one draft per schema property/relation, in schema order, defaults prefilled", () => {
    const form = emptyEntityForm(BLUEPRINT);
    expect(form.blueprint).toBe("service");
    expect(form.properties.map((p) => p.id)).toEqual(["name", "tier", "active", "tags", "homepage", "link", "notes"]);
    expect(form.relations.map((r) => r.id)).toEqual(["system", "dependsOn"]);
    expect(form.properties.every((p) => !p.unknown)).toBe(true);
  });
});

describe("toEntityRequest / fromEntityResponse round trip", () => {
  test("a fully populated entity survives the round trip", () => {
    const form = values({
      icon: "Microservice",
      team: ["payments"],
      properties: [
        { id: "name", text: "Checkout", bool: "", list: [], json: "", unknown: false },
        { id: "tier", text: "2", bool: "", list: [], json: "", unknown: false },
        { id: "active", text: "", bool: "true", list: [], json: "", unknown: false },
        { id: "tags", text: "", bool: "", list: ["a", "b"], json: "", unknown: false },
        { id: "homepage", text: "https://example.com", bool: "", list: [], json: "", unknown: false },
        { id: "link", text: "", bool: "", list: [], json: '{"url":"https://example.com","displayText":"Docs"}', unknown: false },
        { id: "notes", text: "", bool: "", list: [], json: '{"k":"v"}', unknown: false },
      ],
      relations: [
        { id: "system", single: "commerce", many: [] },
        { id: "dependsOn", single: "", many: ["catalog", "pricing"] },
      ],
    });

    const request = toEntityRequest(form, BLUEPRINT);
    expect(request).toEqual({
      blueprint: "service",
      identifier: "checkout",
      title: "Checkout",
      icon: "Microservice",
      team: ["payments"],
      properties: {
        name: "Checkout",
        tier: 2,
        active: true,
        tags: ["a", "b"],
        homepage: "https://example.com",
        link: { url: "https://example.com", displayText: "Docs" },
        notes: { k: "v" },
      },
      relations: {
        system: "commerce",
        dependsOn: ["catalog", "pricing"],
      },
    });

    const response: Entity = {
      id: 42,
      blueprint: "service",
      blueprintId: 1,
      identifier: "checkout",
      title: "Checkout",
      icon: "Microservice",
      team: ["payments"],
      properties: request.properties,
      relations: request.relations,
      findings: [],
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 0,
      updatedAt: 0,
    } as unknown as Entity;

    const restored = fromEntityResponse(response, BLUEPRINT);
    expect(toEntityRequest(restored, BLUEPRINT)).toEqual(request);
  });

  test("blank text / \"\" bool / empty list / blank json all mean absent — never an explicit null", () => {
    const form = values();
    const request = toEntityRequest(form, BLUEPRINT);
    expect(request.properties).toEqual({});
    expect(request.relations).toEqual({});
    expect(request.icon).toBeUndefined();
    expect(request.team).toBeUndefined();
  });

  test("a string team round-trips through the array-only draft as a one-entry array", () => {
    const entity = { blueprint: "service", identifier: "x", title: "X", team: "payments", properties: {}, relations: {} } as unknown as Entity;
    expect(fromEntityResponse(entity, BLUEPRINT).team).toEqual(["payments"]);
  });

  test("teamValuesOf normalizes the scalar/array/absent wire shape", () => {
    expect(teamValuesOf(undefined)).toEqual([]);
    expect(teamValuesOf("payments")).toEqual(["payments"]);
    expect(teamValuesOf(["payments", "platform"])).toEqual(["payments", "platform"]);
  });

  test("an Inherited-ownership blueprint omits team even when the form field holds values", () => {
    const inherited = { ...BLUEPRINT, ownership: { type: "Inherited", path: "system" } } as unknown as Blueprint;
    const form = values({ team: ["payments"] });
    expect(toEntityRequest(form, inherited).team).toBeUndefined();
  });

  test("a Direct-ownership blueprint still sends team normally", () => {
    const direct = { ...BLUEPRINT, ownership: { type: "Direct" } } as unknown as Blueprint;
    const form = values({ team: ["payments"] });
    expect(toEntityRequest(form, direct).team).toEqual(["payments"]);
  });

  test("a dotted property id round-trips (index-addressed, never id-keyed)", () => {
    const blueprint = {
      ...BLUEPRINT,
      schema: { properties: { "a.b.c": { type: "string", title: "Dotted" } }, required: [] },
      relations: {},
    } as unknown as Blueprint;
    const form = emptyEntityForm(blueprint);
    form.properties[0].text = "value";
    expect(toEntityRequest(form, blueprint).properties).toEqual({ "a.b.c": "value" });
  });

  test("a stored property key the blueprint no longer declares survives as an unknown JSON row", () => {
    const entity = {
      blueprint: "service",
      identifier: "checkout",
      title: "Checkout",
      properties: { name: "Checkout", gone: { still: "here" } },
      relations: {},
    } as unknown as Entity;
    const restored = fromEntityResponse(entity, BLUEPRINT);
    const goneDraft = restored.properties.find((p) => p.id === "gone");
    expect(goneDraft?.unknown).toBe(true);
    expect(JSON.parse(goneDraft!.json)).toEqual({ still: "here" });
    // It survives being saved again, unmodified.
    expect(toEntityRequest(restored, BLUEPRINT).properties.gone).toEqual({ still: "here" });
  });

  test("a computed (mirror) id present in the response never becomes an unknown draft", () => {
    const blueprintWithComputed = {
      ...BLUEPRINT,
      mirrorProperties: { domain_title: { title: "Domain title", path: "system.domain.$title" } },
    } as unknown as Blueprint;
    const entity = {
      blueprint: "service",
      identifier: "checkout",
      title: "Checkout",
      properties: { name: "Checkout", domain_title: "Commerce" },
      relations: {},
    } as unknown as Entity;

    const restored = fromEntityResponse(entity, blueprintWithComputed);
    expect(restored.properties.find((p) => p.id === "domain_title")).toBeUndefined();
  });

  test("toEntityRequest defensively skips a computed-id draft even if one exists", () => {
    const blueprintWithComputed = {
      ...BLUEPRINT,
      mirrorProperties: { domain_title: { title: "Domain title", path: "system.domain.$title" } },
    } as unknown as Blueprint;
    const form = values({
      properties: [
        { id: "name", text: "Checkout", bool: "", list: [], json: "", unknown: false },
        { id: "domain_title", text: "should-not-be-sent", bool: "", list: [], json: "", unknown: false },
      ],
    });

    expect(toEntityRequest(form, blueprintWithComputed).properties).toEqual({ name: "Checkout" });
  });
});

describe("entityFormValidation", () => {
  const validate = entityFormValidation(t, BLUEPRINT);

  test("identifier/title/icon/team grammar", () => {
    expect(validate.identifier("checkout")).toBeNull();
    expect(validate.identifier("..")).toBe("entities.validation.identifier");
    expect(validate.title("")).toBe("entities.validation.title");
    expect(validate.icon("a".repeat(101))).toBe("entities.validation.iconLength");
    expect(validate.team(Array.from({ length: 51 }, () => "x"))).toBe("entities.validation.teamCount");
    expect(validate.team(["a".repeat(101)])).toBe("entities.validation.teamEntryLength");
    expect(validate.team(["payments"])).toBeNull();
  });

  test("a required string property left blank is REQUIRED_MISSING", () => {
    const form = values();
    expect(validate.properties.text("", form, "properties.0.text")).toBe("entities.validation.required");
  });

  test("string minLength/maxLength/enum/url", () => {
    const form = values({
      properties: values().properties.map((p) => (p.id === "name" ? { ...p, text: "a" } : p)),
    });
    expect(validate.properties.text("a", form, "properties.0.text")).toBe("entities.validation.minLength");

    const tooLong = values({
      properties: values().properties.map((p) => (p.id === "name" ? { ...p, text: "way-too-long-name" } : p)),
    });
    expect(validate.properties.text("way-too-long-name", tooLong, "properties.0.text")).toBe("entities.validation.maxLength");

    const badEnum = values({
      properties: values().properties.map((p) => (p.id === "tier" ? { ...p, text: "9" } : p)),
    });
    expect(validate.properties.text("9", badEnum, "properties.1.text")).toBe("entities.validation.enum");

    const badUrl = values({
      properties: values().properties.map((p) => (p.id === "homepage" ? { ...p, text: "not-a-url" } : p)),
    });
    expect(validate.properties.text("not-a-url", badUrl, "properties.4.text")).toBe("entities.validation.url");
  });

  test("array maxItems and uniqueItems", () => {
    const tooMany = values({
      properties: values().properties.map((p) => (p.id === "tags" ? { ...p, list: ["a", "b", "c", "d"] } : p)),
    });
    expect(validate.properties.list(["a", "b", "c", "d"], tooMany, "properties.3.list")).toBe("entities.validation.maxItems");

    const dup = values({
      properties: values().properties.map((p) => (p.id === "tags" ? { ...p, list: ["a", "a"] } : p)),
    });
    expect(validate.properties.list(["a", "a"], dup, "properties.3.list")).toBe("entities.validation.uniqueItems");
  });

  test("a required labeled-url object must carry an absolute url", () => {
    const missing = values();
    expect(validate.properties.json("", missing, "properties.5.json")).toBe("entities.validation.required");

    const badShape = values({
      properties: values().properties.map((p) => (p.id === "link" ? { ...p, json: '{"url":"not-a-url"}' } : p)),
    });
    expect(validate.properties.json('{"url":"not-a-url"}', badShape, "properties.5.json")).toBe("entities.validation.url");

    const extraKey = values({
      properties: values().properties.map((p) => (p.id === "link" ? { ...p, json: '{"url":"https://x","other":1}' } : p)),
    });
    expect(validate.properties.json('{"url":"https://x","other":1}', extraKey, "properties.5.json")).toBe(
      "entities.validation.objectShape",
    );

    const ok = values({
      properties: values().properties.map((p) => (p.id === "link" ? { ...p, json: '{"url":"https://x"}' } : p)),
    });
    expect(validate.properties.json('{"url":"https://x"}', ok, "properties.5.json")).toBeNull();
  });

  test("invalid JSON in an object property", () => {
    const bad = values({
      properties: values().properties.map((p) => (p.id === "notes" ? { ...p, json: "{not json" } : p)),
    });
    expect(validate.properties.json("{not json", bad, "properties.6.json")).toBe("entities.validation.jsonInvalid");
  });

  test("a required single relation left blank is REQUIRED_MISSING; an optional many relation is not", () => {
    const form = values();
    expect(validate.relations.single("", form, "relations.0.single")).toBe("entities.validation.required");
    expect(validate.relations.many([], form, "relations.1.many")).toBeNull();
  });
});

// A second fixture, one property/relation per rule, so every branch of the rule table
// (both the triggering AND the passing side) gets exercised explicitly.
const RULES_BLUEPRINT = {
  id: 2,
  identifier: "rules",
  title: "Rules",
  schema: {
    properties: {
      padded: { type: "string", title: "Padded", minLength: 3, maxLength: 5 },
      coded: { type: "string", title: "Coded", pattern: "^[A-Z]+$" },
      grade: { type: "string", title: "Grade", enum: ["A", "B"] },
      mail: { type: "string", title: "Mail", format: "email" },
      ts: { type: "string", title: "Timestamp", format: "date-time" },
      tsTimer: { type: "string", title: "TimerTimestamp", format: "timer" },
      ip: { type: "string", title: "Ip", format: "ipv4" },
      score: { type: "number", title: "Score", minimum: 1, maximum: 10 },
      ratio: { type: "number", title: "Ratio", exclusiveMinimum: 0, exclusiveMaximum: 1 },
      picks: { type: "array", title: "Picks", items: { type: "string", enum: ["x", "y"] }, minItems: 1 },
      pair: { type: "array", title: "Pair", items: { type: "string" }, minItems: 2 },
      counts: { type: "array", title: "Counts", items: { type: "number" } },
      flags: { type: "array", title: "Flags", items: { type: "boolean" } },
      rows: { type: "array", title: "Rows", items: { type: "object" }, minItems: 1 },
      plain: { type: "object", title: "Plain" },
    },
    required: [],
  },
  relations: {
    reqMany: { title: "Req many", target: "x", required: true, many: true },
    optSingle: { title: "Opt single", target: "x", required: false, many: false },
  },
  mirrorProperties: {},
  calculationProperties: {},
  aggregationProperties: {},
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 0,
  updatedAt: 0,
} as unknown as Blueprint;

function rulesForm(overrides: Partial<EntityFormValues> = {}): EntityFormValues {
  return { ...emptyEntityForm(RULES_BLUEPRINT), identifier: "x", title: "X", ...overrides };
}

function withProp(form: EntityFormValues, id: string, patch: Partial<PropertyValueDraft>): EntityFormValues {
  return { ...form, properties: form.properties.map((p) => (p.id === id ? { ...p, ...patch } : p)) };
}

function withRelation(form: EntityFormValues, id: string, patch: Partial<RelationValueDraft>): EntityFormValues {
  return { ...form, relations: form.relations.map((r) => (r.id === id ? { ...r, ...patch } : r)) };
}

function propPath(form: EntityFormValues, id: string, field: "text" | "list" | "json"): [string, string] {
  const index = form.properties.findIndex((p) => p.id === id);
  return [`properties.${index}.${field}`, index.toString()];
}

describe("entityFormValidation — every rule table branch, pass and fail", () => {
  const validate = entityFormValidation(t, RULES_BLUEPRINT);

  test("string minLength/maxLength: fail short, fail long, pass in range", () => {
    let form = withProp(rulesForm(), "padded", { text: "ab" });
    const [path] = propPath(form, "padded", "text");
    expect(validate.properties.text("ab", form, path)).toBe("entities.validation.minLength");

    form = withProp(rulesForm(), "padded", { text: "abcdef" });
    expect(validate.properties.text("abcdef", form, path)).toBe("entities.validation.maxLength");

    form = withProp(rulesForm(), "padded", { text: "abcd" });
    expect(validate.properties.text("abcd", form, path)).toBeNull();
  });

  test("string pattern: fail no-match, pass match", () => {
    let form = withProp(rulesForm(), "coded", { text: "abc" });
    const [path] = propPath(form, "coded", "text");
    expect(validate.properties.text("abc", form, path)).toBe("entities.validation.pattern");

    form = withProp(rulesForm(), "coded", { text: "ABC" });
    expect(validate.properties.text("ABC", form, path)).toBeNull();
  });

  test("string enum: fail not-in-list, pass member", () => {
    let form = withProp(rulesForm(), "grade", { text: "Z" });
    const [path] = propPath(form, "grade", "text");
    expect(validate.properties.text("Z", form, path)).toBe("entities.validation.enum");

    form = withProp(rulesForm(), "grade", { text: "A" });
    expect(validate.properties.text("A", form, path)).toBeNull();
  });

  test("string email: fail malformed, pass valid", () => {
    let form = withProp(rulesForm(), "mail", { text: "not-an-email" });
    const [path] = propPath(form, "mail", "text");
    expect(validate.properties.text("not-an-email", form, path)).toBe("entities.validation.email");

    form = withProp(rulesForm(), "mail", { text: "a@b.co" });
    expect(validate.properties.text("a@b.co", form, path)).toBeNull();
  });

  test("string date-time: fail malformed, fail a missing offset, pass Z, pass an explicit offset", () => {
    let form = withProp(rulesForm(), "ts", { text: "not-a-date" });
    const [path] = propPath(form, "ts", "text");
    expect(validate.properties.text("not-a-date", form, path)).toBe("entities.validation.dateTimeOffset");

    // No offset at all — OffsetDateTime.parse (and the server) reject this.
    form = withProp(rulesForm(), "ts", { text: "2026-09-09T12:00:00" });
    expect(validate.properties.text("2026-09-09T12:00:00", form, path)).toBe("entities.validation.dateTimeOffset");

    form = withProp(rulesForm(), "ts", { text: "2026-01-01T00:00:00Z" });
    expect(validate.properties.text("2026-01-01T00:00:00Z", form, path)).toBeNull();

    form = withProp(rulesForm(), "ts", { text: "2026-01-01T00:00:00+02:00" });
    expect(validate.properties.text("2026-01-01T00:00:00+02:00", form, path)).toBeNull();
  });

  test("string timer: fail malformed, fail a missing offset, fail a non-Z offset, pass literal Z", () => {
    let form = withProp(rulesForm(), "tsTimer", { text: "not-a-date" });
    const [path] = propPath(form, "tsTimer", "text");
    expect(validate.properties.text("not-a-date", form, path)).toBe("entities.validation.timerZulu");

    form = withProp(rulesForm(), "tsTimer", { text: "2026-09-09T12:00:00" });
    expect(validate.properties.text("2026-09-09T12:00:00", form, path)).toBe("entities.validation.timerZulu");

    // Instant.parse accepts only the literal Z designator, not an arbitrary offset.
    form = withProp(rulesForm(), "tsTimer", { text: "2026-01-01T00:00:00+02:00" });
    expect(validate.properties.text("2026-01-01T00:00:00+02:00", form, path)).toBe("entities.validation.timerZulu");

    form = withProp(rulesForm(), "tsTimer", { text: "2026-01-01T00:00:00Z" });
    expect(validate.properties.text("2026-01-01T00:00:00Z", form, path)).toBeNull();
  });

  test("string ipv4: fail malformed, fail a leading zero octet, pass valid", () => {
    let form = withProp(rulesForm(), "ip", { text: "999.1.1.1" });
    const [path] = propPath(form, "ip", "text");
    expect(validate.properties.text("999.1.1.1", form, path)).toBe("entities.validation.ipv4");

    // Leading zeros are ambiguous (octal-looking) — the server rejects them via
    // `it.toString() == part`, mirrored here. RFC 5737 TEST-NET-2 — documentation-only
    // addresses, not real hosts (and outside sonarjs/no-hardcoded-ip's private-range match).
    form = withProp(rulesForm(), "ip", { text: "198.51.100.05" });
    expect(validate.properties.text("198.51.100.05", form, path)).toBe("entities.validation.ipv4");

    form = withProp(rulesForm(), "ip", { text: "198.51.100.5" });
    expect(validate.properties.text("198.51.100.5", form, path)).toBeNull();

    // RFC 5737 TEST-NET-1 — a documentation-only address, not a real host.
    form = withProp(rulesForm(), "ip", { text: "192.0.2.1" });
    expect(validate.properties.text("192.0.2.1", form, path)).toBeNull();
  });

  test("number bounds: fail below minimum, fail above maximum, pass in range", () => {
    let form = withProp(rulesForm(), "score", { text: "0" });
    const [path] = propPath(form, "score", "text");
    expect(validate.properties.text("0", form, path)).toBe("entities.validation.minimum");

    form = withProp(rulesForm(), "score", { text: "11" });
    expect(validate.properties.text("11", form, path)).toBe("entities.validation.maximum");

    form = withProp(rulesForm(), "score", { text: "5" });
    expect(validate.properties.text("5", form, path)).toBeNull();
  });

  test("number non-numeric text is a distinct error from out-of-range", () => {
    const form = withProp(rulesForm(), "score", { text: "abc" });
    const [path] = propPath(form, "score", "text");
    expect(validate.properties.text("abc", form, path)).toBe("entities.validation.numberInvalid");
  });

  test("number exclusive bounds: fail at the boundary, pass strictly inside", () => {
    let form = withProp(rulesForm(), "ratio", { text: "0" });
    const [path] = propPath(form, "ratio", "text");
    expect(validate.properties.text("0", form, path)).toBe("entities.validation.exclusiveMinimum");

    form = withProp(rulesForm(), "ratio", { text: "1" });
    expect(validate.properties.text("1", form, path)).toBe("entities.validation.exclusiveMaximum");

    form = withProp(rulesForm(), "ratio", { text: "0.5" });
    expect(validate.properties.text("0.5", form, path)).toBeNull();
  });

  test("array items.enum: fail an outside value, pass all-members", () => {
    let form = withProp(rulesForm(), "picks", { list: ["z"] });
    const [path] = propPath(form, "picks", "list");
    expect(validate.properties.list(["z"], form, path)).toBe("entities.validation.enum");

    form = withProp(rulesForm(), "picks", { list: ["x", "y"] });
    expect(validate.properties.list(["x", "y"], form, path)).toBeNull();
  });

  test("array minItems: a non-empty-but-short list fails, an empty list is simply unset, a full list passes", () => {
    let form = withProp(rulesForm(), "pair", { list: ["x"] });
    const [path] = propPath(form, "pair", "list");
    expect(validate.properties.list(["x"], form, path)).toBe("entities.validation.minItems");

    form = withProp(rulesForm(), "pair", { list: [] });
    expect(validate.properties.list([], form, path)).toBeNull();

    form = withProp(rulesForm(), "pair", { list: ["x", "y"] });
    expect(validate.properties.list(["x", "y"], form, path)).toBeNull();
  });

  test("array of numbers: a non-numeric entry is rejected, numeric entries pass", () => {
    let form = withProp(rulesForm(), "counts", { list: ["1", "a"] });
    const [path] = propPath(form, "counts", "list");
    expect(validate.properties.list(["1", "a"], form, path)).toBe("entities.validation.numberInvalid");

    form = withProp(rulesForm(), "counts", { list: ["1", "2"] });
    expect(validate.properties.list(["1", "2"], form, path)).toBeNull();
  });

  test("array of booleans: an entry that isn't exactly true/false is rejected, true/false entries pass", () => {
    let form = withProp(rulesForm(), "flags", { list: ["true", "maybe"] });
    const [path] = propPath(form, "flags", "list");
    expect(validate.properties.list(["true", "maybe"], form, path)).toBe("entities.validation.enum");

    form = withProp(rulesForm(), "flags", { list: ["true", "false"] });
    expect(validate.properties.list(["true", "false"], form, path)).toBeNull();
  });

  test("array of objects rides the json slot, not list — invalid/valid JSON, minItems", () => {
    let form = withProp(rulesForm(), "rows", { json: "{bad" });
    let [path] = propPath(form, "rows", "json");
    expect(validate.properties.json("{bad", form, path)).toBe("entities.validation.jsonInvalid");

    form = withProp(rulesForm(), "rows", { json: "[]" });
    [path] = propPath(form, "rows", "json");
    expect(validate.properties.json("[]", form, path)).toBe("entities.validation.minItems");

    form = withProp(rulesForm(), "rows", { json: '[{"a":1}]' });
    expect(validate.properties.json('[{"a":1}]', form, path)).toBeNull();

    // The list field never applies to an object-item array.
    expect(validate.properties.list([], form, propPath(form, "rows", "list")[0])).toBeNull();
  });

  test("a plain object property (no format) is verbatim — any valid JSON passes", () => {
    const form = withProp(rulesForm(), "plain", { json: '{"anything":true}' });
    const [path] = propPath(form, "plain", "json");
    expect(validate.properties.json('{"anything":true}', form, path)).toBeNull();
  });

  test("a required many relation: fail empty, pass non-empty", () => {
    let form = withRelation(rulesForm(), "reqMany", { many: [] });
    expect(validate.relations.many([], form, "relations.0.many")).toBe("entities.validation.required");

    form = withRelation(rulesForm(), "reqMany", { many: ["a"] });
    expect(validate.relations.many(["a"], form, "relations.0.many")).toBeNull();
  });

  test("an optional single relation never blocks, blank or filled", () => {
    const form = rulesForm();
    expect(validate.relations.single("", form, "relations.1.single")).toBeNull();
    const filled = withRelation(form, "optSingle", { single: "y" });
    expect(validate.relations.single("y", filled, "relations.1.single")).toBeNull();
  });

  test("a row index past the end of the array is a no-op (defensive)", () => {
    const form = rulesForm();
    expect(validate.properties.text("", form, "properties.999.text")).toBeNull();
    expect(validate.relations.single("", form, "relations.999.single")).toBeNull();
  });
});

describe("entitySaveErrorMessage / entityDeleteErrorMessage", () => {
  test("maps the fixed status vocabulary", () => {
    expect(entitySaveErrorMessage(new ApiError(409, null), t)).toBe("entities.saveConflict");
    expect(entitySaveErrorMessage(new ApiError(400, null), t)).toBe("entities.saveInvalid");
    expect(entitySaveErrorMessage(new ApiError(404, null), t)).toBe("entities.saveGone");
    expect(entityDeleteErrorMessage(new ApiError(409, null), t)).toBe("entities.deleteConflict");
  });
});
