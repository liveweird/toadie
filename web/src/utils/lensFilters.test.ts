import { describe, expect, test } from "vitest";
import { ENTITY_KINDS } from "./catalogFileForm";
import { fromLensFilters, sameLensFilters, toLensFilters } from "./lensFilters";

describe("lens filter canonicalization", () => {
  test("toLensFilters normalizes empties and the all-kinds set to absent", () => {
    expect(
      toLensFilters({ name: "", namespace: "team-a", kind: [...ENTITY_KINDS], label: undefined }),
    ).toEqual({
      name: undefined,
      namespace: "team-a",
      kind: undefined,
      tag: undefined,
      type: undefined,
      lifecycle: undefined,
      owner: undefined,
      label: undefined,
      labelValue: undefined,
    });
  });

  test("kind subsets keep ENTITY_KINDS order and drop unknown entries", () => {
    expect(toLensFilters({ kind: ["API", "Bogus", "Component"] }).kind).toEqual(["Component", "API"]);
  });

  test("labelValue never survives without its label", () => {
    expect(toLensFilters({ labelValue: ["tier-1"] }).labelValue).toBeUndefined();
    expect(toLensFilters({ label: "example.com/tier", labelValue: ["tier-1"] }).labelValue).toEqual(["tier-1"]);
  });

  test("fromLensFilters maps wire nulls to absent values", () => {
    expect(fromLensFilters({ name: null, namespace: "team-a", kind: null })).toEqual(
      expect.objectContaining({ name: undefined, namespace: "team-a", kind: undefined }),
    );
  });

  test("sameLensFilters is order- and empty-insensitive", () => {
    expect(
      sameLensFilters(
        { namespace: "team-a", kind: ["Component", "API"], label: "k", labelValue: ["b", "a"] },
        { namespace: "team-a", kind: ["API", "Component"], label: "k", labelValue: ["a", "b"], name: "" },
      ),
    ).toBe(true);
    expect(sameLensFilters({ kind: [...ENTITY_KINDS] }, {})).toBe(true);
    expect(sameLensFilters({ namespace: "team-a" }, { namespace: "team-b" })).toBe(false);
    expect(sameLensFilters({ kind: ["Component"] }, {})).toBe(false);
  });
});
