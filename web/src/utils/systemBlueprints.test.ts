import { describe, expect, test } from "vitest";
import { lockedRowIds, SYSTEM_BASE_ROWS } from "./systemBlueprints";

describe("lockedRowIds", () => {
  test("_team locks its base relation but no properties", () => {
    expect(lockedRowIds("_team", "properties")).toEqual(new Set());
    expect(lockedRowIds("_team", "relations")).toEqual(new Set(["parent"]));
  });

  test("_user locks its base property and relation", () => {
    expect(lockedRowIds("_user", "properties")).toEqual(new Set(["email"]));
    expect(lockedRowIds("_user", "relations")).toEqual(new Set(["team"]));
  });

  test("a plain blueprint identifier locks nothing", () => {
    expect(lockedRowIds("microservice", "properties")).toEqual(new Set());
    expect(lockedRowIds("microservice", "relations")).toEqual(new Set());
  });

  test("SYSTEM_BASE_ROWS names exactly the two system blueprints", () => {
    expect(Object.keys(SYSTEM_BASE_ROWS).sort()).toEqual(["_team", "_user"]);
  });
});
