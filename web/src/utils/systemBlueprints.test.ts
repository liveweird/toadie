import { describe, expect, test } from "vitest";
import {
  lockedRowIds,
  OWNERSHIP_RELATION,
  referenceTargetOf,
  SYSTEM_BASE_ROWS,
  TEAM_BLUEPRINT,
  USER_BLUEPRINT,
} from "./systemBlueprints";

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

describe("identifiers", () => {
  test("TEAM_BLUEPRINT/USER_BLUEPRINT are the seeded system identifiers", () => {
    expect(TEAM_BLUEPRINT).toBe("_team");
    expect(USER_BLUEPRINT).toBe("_user");
  });

  test("OWNERSHIP_RELATION is the reserved `$team` wire relation id", () => {
    expect(OWNERSHIP_RELATION).toBe("$team");
  });
});

describe("referenceTargetOf", () => {
  test("team format resolves to the team blueprint", () => {
    expect(referenceTargetOf("team")).toBe(TEAM_BLUEPRINT);
  });

  test("user format resolves to the user blueprint", () => {
    expect(referenceTargetOf("user")).toBe(USER_BLUEPRINT);
  });

  test("every other format, including undefined, resolves to nothing", () => {
    expect(referenceTargetOf("url")).toBeUndefined();
    expect(referenceTargetOf(undefined)).toBeUndefined();
  });
});
