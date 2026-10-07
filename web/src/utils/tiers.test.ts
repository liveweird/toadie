import { describe, expect, test } from "vitest";
import { blueprintResponse } from "../test/fixtures";
import {
  asTier,
  blueprintTier,
  blueprintTierLookup,
  byTier,
  fillTier,
  isFilledProperty,
  isFilledRelation,
  isTier,
  propertyTier,
  relationTier,
  withinFocus,
  type Tier,
} from "./tiers";

const tiered = blueprintResponse({
  identifier: "service",
  tiers: { blueprint: 2, properties: { name: 1, owner: 3 }, relations: { system: 2 } },
});

describe("tier lookups", () => {
  test("blueprint, property and relation tiers read the stored map; anything unset is null", () => {
    expect(blueprintTier(tiered)).toBe(2);
    expect(propertyTier(tiered, "name")).toBe(1);
    expect(propertyTier(tiered, "owner")).toBe(3);
    expect(propertyTier(tiered, "other")).toBeNull();
    expect(relationTier(tiered, "system")).toBe(2);
    expect(relationTier(tiered, "other")).toBeNull();

    const untiered = blueprintResponse();
    expect(blueprintTier(untiered)).toBeNull();
    expect(propertyTier(untiered, "name")).toBeNull();
    expect(relationTier(untiered, "system")).toBeNull();
    expect(blueprintTier(undefined)).toBeNull();
    expect(propertyTier(undefined, "name")).toBeNull();
  });

  test("a value outside 1-4 reads as untiered", () => {
    expect(isTier(0)).toBe(false);
    expect(isTier(5)).toBe(false);
    expect(isTier("1")).toBe(false);
    expect(asTier(7)).toBeNull();
    expect(asTier(null)).toBeNull();
    expect(asTier(4)).toBe(4);
    expect(blueprintTier(blueprintResponse({ tiers: { blueprint: 9 } }))).toBeNull();
  });

  test("blueprintTierLookup resolves by identifier and answers null for unknown ones", () => {
    const tierOf = blueprintTierLookup([tiered, blueprintResponse({ identifier: "team" })]);
    expect(tierOf("service")).toBe(2);
    expect(tierOf("team")).toBeNull();
    expect(tierOf("nope")).toBeNull();
  });
});

describe("withinFocus", () => {
  test("no focus keeps everything, tiered or not", () => {
    expect(withinFocus(null, null)).toBe(true);
    expect(withinFocus(undefined, null)).toBe(true);
    expect(withinFocus(4, null)).toBe(true);
  });

  test("a focus keeps tier <= N and excludes untiered items", () => {
    expect(withinFocus(1, 2)).toBe(true);
    expect(withinFocus(2, 2)).toBe(true);
    expect(withinFocus(3, 2)).toBe(false);
    expect(withinFocus(null, 4)).toBe(false);
    expect(withinFocus(undefined, 4)).toBe(false);
  });
});

describe("byTier", () => {
  test("tiered ascending first, untiered last, ties keep their order", () => {
    const rows: { id: string; tier: Tier | null }[] = [
      { id: "a", tier: null },
      { id: "b", tier: 3 },
      { id: "c", tier: 1 },
      { id: "d", tier: null },
      { id: "e", tier: 3 },
      { id: "f", tier: 2 },
    ];
    const sorted = [...rows].sort((x, y) => byTier(x.tier, y.tier)).map((row) => row.id);
    expect(sorted).toEqual(["c", "f", "b", "e", "a", "d"]);
  });

  test("compares undefined like null", () => {
    expect(byTier(undefined, undefined)).toBe(0);
    expect(byTier(undefined, 1)).toBeGreaterThan(0);
    expect(byTier(1, undefined)).toBeLessThan(0);
  });
});

// The case tables below are copied from server/src/test/kotlin/QueryTiersTest.kt — Kotlin is
// canonical; a change there must be mirrored here.
describe("isFilledProperty (QueryTiersTest)", () => {
  test.each<[string, unknown, boolean]>([
    ["absent", undefined, false],
    ["null", null, false],
    ["empty string", "", false],
    ["blank string", "  \t", false],
    ["empty array", [], false],
    ["empty object", {}, false],
    ["text", "x", true],
    ["false", false, true],
    ["zero", 0, true],
    ["array holding an empty string", [""], true],
    ["object with a key", { k: 1 }, true],
  ])("%s", (_name, value, expected) => {
    expect(isFilledProperty(value)).toBe(expected);
  });
});

describe("isFilledRelation (QueryTiersTest)", () => {
  test.each<[string, unknown, boolean]>([
    ["absent", undefined, false],
    ["null", null, false],
    ["empty string", "", false],
    ["blank string", "   ", false],
    ["number", 3, false],
    ["empty array", [], false],
    ["array of blanks and null", ["", "  ", null], false],
    ["array of arrays", [["x"]], false],
    ["object", {}, false],
    ["target", "target", true],
    ["array holding one non-blank element", ["", "target"], true],
  ])("%s", (_name, value, expected) => {
    expect(isFilledRelation(value)).toBe(expected);
  });
});

describe("fillTier (QueryTiersTest)", () => {
  const bp = (tiers: NonNullable<typeof tiered.tiers>) => blueprintResponse({ tiers });

  test("no tiered property or relation is null, even with a blueprint tier", () => {
    expect(fillTier(bp({}), {}, {})).toBeNull();
    expect(fillTier(bp({ blueprint: 2 }), {}, {})).toBeNull();
    expect(fillTier(blueprintResponse(), { a: 1 }, { b: "x" })).toBeNull();
    expect(fillTier(undefined, undefined, undefined)).toBeNull();
  });

  test("an empty tier-1 field is 0, an empty tier-2 field is 1, and everything filled is 4", () => {
    const blueprint = bp({ properties: { name: 1, owner: 2 }, relations: { system: 3 } });
    const properties = { name: "n", owner: "o" };
    const relations = { system: "s" };

    expect(fillTier(blueprint, properties, relations)).toBe(4);
    expect(fillTier(blueprint, { owner: "o" }, relations)).toBe(0);
    expect(fillTier(blueprint, { name: "n" }, relations)).toBe(1);
    expect(fillTier(blueprint, properties, {})).toBe(2);
  });

  test("tiers without fields count as satisfied and the lowest unfilled tier decides", () => {
    // Only tier 3 and 4 fields exist: tiers 1-2 are vacuously satisfied.
    const blueprint = bp({ relations: { a: 3, b: 4 } });
    expect(fillTier(blueprint, {}, {})).toBe(2);
    expect(fillTier(blueprint, {}, { a: "x" })).toBe(3);
    expect(fillTier(blueprint, {}, { a: "x", b: ["y"] })).toBe(4);
  });

  test("tolerates missing property/relation maps", () => {
    expect(fillTier(bp({ properties: { p: 2 } }), undefined, undefined)).toBe(1);
  });
});
