import { describe, expect, test } from "vitest";
import { problemsFirst } from "./rowOrder";

describe("problemsFirst", () => {
  test("moves non-clean rows ahead of clean ones, keeping each group's order, without mutating the input", () => {
    const input = [
      { id: 1, clean: true },
      { id: 2, clean: false },
      { id: 3, clean: true },
      { id: 4, clean: false },
    ];
    const copy = [...input];
    expect(problemsFirst(input, (row) => row.clean).map((row) => row.id)).toEqual([2, 4, 1, 3]);
    expect(input).toEqual(copy);
  });

  test("an all-clean list keeps its order", () => {
    expect(problemsFirst([3, 1, 2], () => true)).toEqual([3, 1, 2]);
  });
});
