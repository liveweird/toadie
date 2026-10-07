import { describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useTierFocus } from "./useTierFocus";

describe("useTierFocus", () => {
  test("starts on all tiers (null)", () => {
    const { result } = renderHook(() => useTierFocus("tierFocusTest.default"));
    expect(result.current[0]).toBeNull();
  });

  test("persists the choice under toadie.viewSettings.<key> and restores it on remount", () => {
    const key = "tierFocusTest.persist";
    const { result } = renderHook(() => useTierFocus(key));
    act(() => result.current[1](2));
    expect(result.current[0]).toBe(2);
    expect(localStorage.getItem(`toadie.viewSettings.${key}`)).toBe("2");

    const { result: remounted } = renderHook(() => useTierFocus(key));
    expect(remounted.current[0]).toBe(2);

    act(() => remounted.current[1](null));
    expect(localStorage.getItem(`toadie.viewSettings.${key}`)).toBe("null");
  });

  test.each(["9", "\"2\"", "0", "{}"])("junk in storage (%s) falls back to all tiers", (junk) => {
    const key = `tierFocusTest.junk${junk.length}${junk.charCodeAt(0)}`;
    localStorage.setItem(`toadie.viewSettings.${key}`, junk);
    const { result } = renderHook(() => useTierFocus(key));
    expect(result.current[0]).toBeNull();
  });
});
