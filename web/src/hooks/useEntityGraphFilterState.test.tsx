import { describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useEntityGraphFilterState } from "./useEntityGraphFilterState";

const VIEW_KEY = "entityGraphTestView";

describe("useEntityGraphFilterState", () => {
  test("starts with no filters and reads as an empty query", () => {
    const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY));
    expect(result.current.values).toEqual({ blueprints: undefined, q: undefined, team: undefined });
    expect(result.current.activeFilterCount).toBe(0);
    expect(result.current.controls.blueprints).toEqual([]);
    expect(result.current.controls.q).toBe("");
    expect(result.current.controls.team).toBe("");
  });

  test("setting team is discrete and feeds `values`/activeFilterCount immediately", () => {
    const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY));
    act(() => result.current.controls.setTeam("platform"));
    expect(result.current.controls.team).toBe("platform");
    expect(result.current.values.team).toBe("platform");
    expect(result.current.activeFilterCount).toBe(1);

    act(() => result.current.controls.setTeam(""));
    expect(result.current.values.team).toBeUndefined();
    expect(result.current.activeFilterCount).toBe(0);
  });

  test("persists team under toadie.viewSettings.<viewKey>.filter.team and restores on remount", () => {
    const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY));
    act(() => result.current.controls.setTeam("platform"));
    expect(localStorage.getItem(`toadie.viewSettings.${VIEW_KEY}.filter.team`)).toBe(
      JSON.stringify("platform"),
    );

    const { result: remounted } = renderHook(() => useEntityGraphFilterState(VIEW_KEY));
    expect(remounted.current.controls.team).toBe("platform");
  });

  test("setting blueprints is discrete (no debounce) and feeds `values`/activeFilterCount immediately", () => {
    const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY));
    act(() => result.current.controls.setBlueprints(["team", "service"]));
    expect(result.current.controls.blueprints).toEqual(["team", "service"]);
    expect(result.current.values.blueprints).toEqual(["team", "service"]);
    expect(result.current.activeFilterCount).toBe(1);
  });

  test("q debounces 300ms before it reaches `values`", () => {
    vi.useFakeTimers();
    try {
      const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY));
      act(() => result.current.controls.setQ("checkout"));
      // The control itself updates immediately — only the derived `values.q` is debounced.
      expect(result.current.controls.q).toBe("checkout");
      expect(result.current.values.q).toBeUndefined();

      act(() => vi.advanceTimersByTime(299));
      expect(result.current.values.q).toBeUndefined();

      act(() => vi.advanceTimersByTime(1));
      expect(result.current.values.q).toBe("checkout");
      expect(result.current.activeFilterCount).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });

  test("persists both slots under toadie.viewSettings.<viewKey>.filter.* and restores on remount", () => {
    const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY));
    act(() => result.current.controls.setBlueprints(["team"]));
    act(() => result.current.controls.setQ("acq"));
    expect(localStorage.getItem(`toadie.viewSettings.${VIEW_KEY}.filter.blueprints`)).toBe(
      JSON.stringify(["team"]),
    );
    expect(localStorage.getItem(`toadie.viewSettings.${VIEW_KEY}.filter.q`)).toBe(JSON.stringify("acq"));

    const { result: remounted } = renderHook(() => useEntityGraphFilterState(VIEW_KEY));
    expect(remounted.current.controls.blueprints).toEqual(["team"]);
    expect(remounted.current.controls.q).toBe("acq");
  });

  test("junk in storage falls back to the defaults for all three slots", () => {
    localStorage.setItem(`toadie.viewSettings.${VIEW_KEY}.filter.blueprints`, JSON.stringify("not-an-array"));
    localStorage.setItem(`toadie.viewSettings.${VIEW_KEY}.filter.q`, JSON.stringify(42));
    localStorage.setItem(`toadie.viewSettings.${VIEW_KEY}.filter.team`, JSON.stringify(42));
    const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY));
    expect(result.current.controls.blueprints).toEqual([]);
    expect(result.current.controls.q).toBe("");
    expect(result.current.controls.team).toBe("");
    expect(result.current.values).toEqual({ blueprints: undefined, q: undefined, team: undefined });
  });

  test("activeFilterCount counts a blank/whitespace-only q as inactive", () => {
    const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY));
    act(() => result.current.controls.setQ("   "));
    expect(result.current.activeFilterCount).toBe(0);
  });
});
