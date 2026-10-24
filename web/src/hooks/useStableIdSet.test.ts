import { describe, expect, test } from "vitest";
import { renderHook } from "@testing-library/react";
import { useStableIdSet } from "./useStableIdSet";

describe("useStableIdSet", () => {
  test("keeps the same Set while the members are unchanged, whatever the array identity or order", () => {
    const { result, rerender } = renderHook(({ ids }) => useStableIdSet(ids), {
      initialProps: { ids: ["a", "b"] },
    });
    const first = result.current;
    rerender({ ids: ["b", "a"] });
    expect(result.current).toBe(first);
    rerender({ ids: ["a", "b", "a"] });
    expect(result.current).toBe(first);
  });

  test("an empty list replacing another empty list does not change identity", () => {
    const { result, rerender } = renderHook(({ ids }) => useStableIdSet(ids), {
      initialProps: { ids: [] as string[] },
    });
    const first = result.current;
    rerender({ ids: [] });
    expect(result.current).toBe(first);
  });

  test("a changed membership yields a new Set holding exactly the new ids", () => {
    const { result, rerender } = renderHook(({ ids }) => useStableIdSet(ids), {
      initialProps: { ids: ["a"] },
    });
    const first = result.current;
    rerender({ ids: ["a", "c"] });
    expect(result.current).not.toBe(first);
    expect([...result.current].sort()).toEqual(["a", "c"]);
    rerender({ ids: [] });
    expect(result.current.size).toBe(0);
  });
});
