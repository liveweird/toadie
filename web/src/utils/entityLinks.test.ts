import { describe, expect, test } from "vitest";
import { entityBulkChangePath } from "./entityLinks";

describe("entityBulkChangePath", () => {
  test("is the bare page without a scope", () => {
    expect(entityBulkChangePath()).toBe("/entities/bulk-change");
    expect(entityBulkChangePath({ blueprint: " ", q: null, team: "" })).toBe("/entities/bulk-change");
  });
  test("carries blueprint, q and team as encoded params", () => {
    expect(entityBulkChangePath({ blueprint: "service", q: "pay ments", team: "t&1" })).toBe(
      "/entities/bulk-change?blueprint=service&q=pay+ments&team=t%261",
    );
  });
});
