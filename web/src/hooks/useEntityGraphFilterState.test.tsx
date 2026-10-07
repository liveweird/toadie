import { describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useEntityGraphFilterState } from "./useEntityGraphFilterState";

const VIEW_KEY = "entityGraphTestView";

describe("useEntityGraphFilterState", () => {
  test("starts with no filters, every blueprint visible, and reads as an empty query", () => {
    const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY, ["team", "service"]));
    expect(result.current.values).toEqual({ blueprints: undefined, q: undefined, team: undefined });
    expect(result.current.activeFilterCount).toBe(0);
    expect(result.current.controls.q).toBe("");
    expect(result.current.controls.team).toBe("");
    expect(result.current.blueprintPills.active).toEqual(["team", "service"]);
    expect(result.current.blueprintPills.hidden).toEqual([]);
    expect(result.current.noBlueprints).toBe(false);
    expect(result.current.ready).toBe(true);
  });

  test("setting team is discrete and feeds `values`/activeFilterCount immediately", () => {
    const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY, ["team"]));
    act(() => result.current.controls.setTeam("platform"));
    expect(result.current.controls.team).toBe("platform");
    expect(result.current.values.team).toBe("platform");
    expect(result.current.activeFilterCount).toBe(1);

    act(() => result.current.controls.setTeam(""));
    expect(result.current.values.team).toBeUndefined();
    expect(result.current.activeFilterCount).toBe(0);
  });

  test("persists team under toadie.viewSettings.<viewKey>.filter.team and restores on remount", () => {
    const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY, ["team"]));
    act(() => result.current.controls.setTeam("platform"));
    expect(localStorage.getItem(`toadie.viewSettings.${VIEW_KEY}.filter.team`)).toBe(
      JSON.stringify("platform"),
    );

    const { result: remounted } = renderHook(() => useEntityGraphFilterState(VIEW_KEY, ["team"]));
    expect(remounted.current.controls.team).toBe("platform");
  });

  test("q debounces 300ms before it reaches `values`", () => {
    vi.useFakeTimers();
    try {
      const { result } = renderHook(() => useEntityGraphFilterState(VIEW_KEY, ["team"]));
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

  test("hiding a blueprint narrows `values.blueprints` to the visible set and persists the hidden list", () => {
    const viewKey = `${VIEW_KEY}-hide`;
    const { result } = renderHook(() => useEntityGraphFilterState(viewKey, ["team", "service"]));
    act(() => result.current.blueprintPills.setHidden(["service"]));
    expect(result.current.blueprintPills.hidden).toEqual(["service"]);
    expect(result.current.values.blueprints).toEqual(["team"]);
    expect(localStorage.getItem(`toadie.viewSettings.${viewKey}.filter.hiddenBlueprints`)).toBe(
      JSON.stringify(["service"]),
    );

    // Un-hiding everything drops the `blueprint` param entirely — the all-on-kind-pills idiom.
    act(() => result.current.blueprintPills.setHidden([]));
    expect(result.current.values.blueprints).toBeUndefined();
  });

  test("hiding every active blueprint reports noBlueprints", () => {
    const viewKey = `${VIEW_KEY}-noblueprints`;
    const { result } = renderHook(() => useEntityGraphFilterState(viewKey, ["team", "service"]));
    act(() => result.current.blueprintPills.setHidden(["team", "service"]));
    expect(result.current.noBlueprints).toBe(true);
  });

  test("an empty/unloaded registry never reports noBlueprints", () => {
    const viewKey = `${VIEW_KEY}-empty-registry`;
    const { result } = renderHook(() => useEntityGraphFilterState(viewKey, []));
    expect(result.current.noBlueprints).toBe(false);
  });

  test("setHidden prunes to active ids, in registry order, on write only", () => {
    const viewKey = `${VIEW_KEY}-prune`;
    const { result, rerender } = renderHook(
      ({ active }: { active: string[] }) => useEntityGraphFilterState(viewKey, active),
      { initialProps: { active: ["team", "service", "domain"] } },
    );
    act(() => result.current.blueprintPills.setHidden(["domain", "service", "gone"]));
    expect(localStorage.getItem(`toadie.viewSettings.${viewKey}.filter.hiddenBlueprints`)).toBe(
      JSON.stringify(["service", "domain"]),
    );

    // A blueprint later removed from the registry just lingers in storage until the next write.
    rerender({ active: ["team", "domain"] });
    expect(result.current.blueprintPills.hidden).toEqual(["domain"]);
  });

  test("ready waits for the registry only while a stored hidden list already exists", () => {
    const withHidden = `${VIEW_KEY}-ready-hidden`;
    localStorage.setItem(`toadie.viewSettings.${withHidden}.filter.hiddenBlueprints`, JSON.stringify(["service"]));
    const { result: loading } = renderHook(() => useEntityGraphFilterState(withHidden, [], true));
    expect(loading.current.ready).toBe(false);

    const noHidden = `${VIEW_KEY}-ready-empty`;
    const { result: ready } = renderHook(() => useEntityGraphFilterState(noHidden, [], true));
    expect(ready.current.ready).toBe(true);
  });

  test("junk in storage falls back to the defaults for all three slots", () => {
    const viewKey = `${VIEW_KEY}-junk`;
    localStorage.setItem(`toadie.viewSettings.${viewKey}.filter.hiddenBlueprints`, JSON.stringify("not-an-array"));
    localStorage.setItem(`toadie.viewSettings.${viewKey}.filter.q`, JSON.stringify(42));
    localStorage.setItem(`toadie.viewSettings.${viewKey}.filter.team`, JSON.stringify(42));
    const { result } = renderHook(() => useEntityGraphFilterState(viewKey, ["team"]));
    expect(result.current.controls.q).toBe("");
    expect(result.current.controls.team).toBe("");
    expect(result.current.blueprintPills.hidden).toEqual([]);
    expect(result.current.values).toEqual({ blueprints: undefined, q: undefined, team: undefined });
  });

  test("activeFilterCount counts a blank/whitespace-only q as inactive and never counts pills", () => {
    const viewKey = `${VIEW_KEY}-count`;
    const { result } = renderHook(() => useEntityGraphFilterState(viewKey, ["team", "service"]));
    act(() => result.current.controls.setQ("   "));
    act(() => result.current.blueprintPills.setHidden(["service"]));
    expect(result.current.activeFilterCount).toBe(0);
  });

  describe("tier Focus", () => {
    const TIERS: Record<string, 1 | 2 | 3 | 4 | null> = { team: 1, service: 2, domain: 4, scratch: null };
    const tierOf = (id: string) => TIERS[id] ?? null;
    const ACTIVE = ["team", "service", "domain", "scratch"];

    test("no focus leaves the blueprint param alone", () => {
      const { result } = renderHook(() => useEntityGraphFilterState(`${VIEW_KEY}-focus-none`, ACTIVE, false, tierOf));
      expect(result.current.controls.focus).toBeNull();
      expect(result.current.values.blueprints).toBeUndefined();
      expect(result.current.activeFilterCount).toBe(0);
      expect(result.current.blueprintPills.focus).toBeNull();
    });

    test("a focus narrows the visible blueprints to tier <= N, excluding untiered, and counts as a filter", () => {
      const viewKey = `${VIEW_KEY}-focus-narrow`;
      const { result } = renderHook(() => useEntityGraphFilterState(viewKey, ACTIVE, false, tierOf));
      act(() => result.current.controls.setFocus(2));
      expect(result.current.values.blueprints).toEqual(["team", "service"]);
      expect(result.current.activeFilterCount).toBe(1);
      expect(result.current.noBlueprints).toBe(false);
      expect(result.current.blueprintPills.focus).toBe(2);
      expect(localStorage.getItem(`toadie.viewSettings.${viewKey}.filter.focusTier`)).toBe("2");

      // Focus 4 still drops the untiered blueprint, so the param is sent.
      act(() => result.current.controls.setFocus(4));
      expect(result.current.values.blueprints).toEqual(["team", "service", "domain"]);

      act(() => result.current.controls.setFocus(null));
      expect(result.current.values.blueprints).toBeUndefined();
      expect(result.current.activeFilterCount).toBe(0);
    });

    test("a focus that excludes nothing sends no blueprint param but still counts as active", () => {
      const { result } = renderHook(() =>
        useEntityGraphFilterState(`${VIEW_KEY}-focus-all`, ["team", "service"], false, tierOf),
      );
      act(() => result.current.controls.setFocus(2));
      expect(result.current.values.blueprints).toBeUndefined();
      expect(result.current.activeFilterCount).toBe(1);
    });

    test("the focus intersects with hidden pills", () => {
      const { result } = renderHook(() =>
        useEntityGraphFilterState(`${VIEW_KEY}-focus-hidden`, ACTIVE, false, tierOf),
      );
      act(() => result.current.blueprintPills.setHidden(["team"]));
      act(() => result.current.controls.setFocus(2));
      expect(result.current.values.blueprints).toEqual(["service"]);
      // Pills keep their own hidden list, which the Focus never edits.
      expect(result.current.blueprintPills.hidden).toEqual(["team"]);
    });

    test("an empty intersection reports noBlueprints", () => {
      const { result } = renderHook(() =>
        useEntityGraphFilterState(`${VIEW_KEY}-focus-empty`, ["domain", "scratch"], false, tierOf),
      );
      act(() => result.current.controls.setFocus(1));
      expect(result.current.noBlueprints).toBe(true);
      expect(result.current.values.blueprints).toEqual([]);
    });

    test("without a tier lookup every blueprint is untiered, so any focus empties the set", () => {
      const { result } = renderHook(() => useEntityGraphFilterState(`${VIEW_KEY}-focus-nolookup`, ["team"]));
      act(() => result.current.controls.setFocus(4));
      expect(result.current.noBlueprints).toBe(true);
    });

    test("a stored focus makes ready wait for the registry", () => {
      const viewKey = `${VIEW_KEY}-focus-ready`;
      localStorage.setItem(`toadie.viewSettings.${viewKey}.filter.focusTier`, "3");
      const { result: loading } = renderHook(() => useEntityGraphFilterState(viewKey, [], true, tierOf));
      expect(loading.current.ready).toBe(false);
      const { result: loaded } = renderHook(() => useEntityGraphFilterState(viewKey, ACTIVE, false, tierOf));
      expect(loaded.current.ready).toBe(true);
    });

    test("junk in storage falls back to all tiers", () => {
      const viewKey = `${VIEW_KEY}-focus-junk`;
      localStorage.setItem(`toadie.viewSettings.${viewKey}.filter.focusTier`, JSON.stringify(9));
      const { result } = renderHook(() => useEntityGraphFilterState(viewKey, ACTIVE, false, tierOf));
      expect(result.current.controls.focus).toBeNull();
    });
  });
});
